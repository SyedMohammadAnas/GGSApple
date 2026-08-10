#!/usr/bin/env python3
"""Minimal GLB → USDZ converter for simple textured meshes (RealityKit / AR Quick Look)."""

from __future__ import annotations

import argparse
import struct
import sys
import tempfile
from pathlib import Path

from pygltflib import GLTF2
from pxr import Gf, Sdf, Usd, UsdGeom, UsdShade, UsdUtils, Vt


def _read_glb(path: Path) -> GLTF2:
    data = path.read_bytes()
    if data[:4] != b"glTF":
        raise ValueError(f"Not a GLB file: {path}")
    return GLTF2().load(str(path))


def _buffer_bytes(gltf: GLTF2, buffer_view_index: int) -> bytes:
    view = gltf.bufferViews[buffer_view_index]
    blob = gltf.binary_blob() if gltf.binary_blob() else b""
    start = view.byteOffset or 0
    end = start + view.byteLength
    return blob[start:end]


def _accessor_array(gltf: GLTF2, accessor_index: int):
    acc = gltf.accessors[accessor_index]
    view = gltf.bufferViews[acc.bufferView]
    raw = _buffer_bytes(gltf, acc.bufferView)
    off = acc.byteOffset or 0
    count = acc.count
    ctype = acc.componentType
    atype = acc.type

    fmt_map = {
        5120: "b",
        5121: "B",
        5122: "h",
        5123: "H",
        5125: "I",
        5126: "f",
    }
    comp = fmt_map[ctype]
    comps = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4}[atype]
    stride = view.byteStride or struct.calcsize(comp) * comps
    values = []
    for i in range(count):
        chunk = raw[off + i * stride : off + i * stride + struct.calcsize(comp) * comps]
        values.append(struct.unpack("<" + comp * comps, chunk))
    return values


def _identity_mat4() -> list[float]:
    return [
        1.0, 0.0, 0.0, 0.0,
        0.0, 1.0, 0.0, 0.0,
        0.0, 0.0, 1.0, 0.0,
        0.0, 0.0, 0.0, 1.0,
    ]


def _mul_mat4(a: list[float], b: list[float]) -> list[float]:
    out = [0.0] * 16
    for col in range(4):
        for row in range(4):
            out[col * 4 + row] = sum(
                a[k * 4 + row] * b[col * 4 + k] for k in range(4)
            )
    return out


def _local_mat4(node) -> list[float]:
    if node.matrix:
        return list(node.matrix)
    m = _identity_mat4()
    if node.translation:
        m[12], m[13], m[14] = node.translation
    if node.scale:
        sx, sy, sz = node.scale
        sm = [sx, 0, 0, 0, 0, sy, 0, 0, 0, 0, sz, 0, 0, 0, 0, 1]
        m = _mul_mat4(m, sm)
    if node.rotation:
        x, y, z, w = node.rotation
        xx, yy, zz = x * x, y * y, z * z
        xy, xz, yz = x * y, x * z, y * z
        wx, wy, wz = w * x, w * y, w * z
        rm = [
            1 - 2 * (yy + zz), 2 * (xy + wz), 2 * (xz - wy), 0,
            2 * (xy - wz), 1 - 2 * (xx + zz), 2 * (yz + wx), 0,
            2 * (xz + wy), 2 * (yz - wx), 1 - 2 * (xx + yy), 0,
            0, 0, 0, 1,
        ]
        m = _mul_mat4(m, rm)
    return m


def _world_mat4(gltf: GLTF2, node_index: int, parents: dict[int, int]) -> list[float]:
    chain = [node_index]
    while chain[-1] in parents:
        chain.append(parents[chain[-1]])
    world = _identity_mat4()
    for idx in reversed(chain):
        world = _mul_mat4(world, _local_mat4(gltf.nodes[idx]))
    return world


def _transform_point(m: list[float], p: tuple[float, float, float]) -> tuple[float, float, float]:
    x, y, z = p
    ox = m[0] * x + m[4] * y + m[8] * z + m[12]
    oy = m[1] * x + m[5] * y + m[9] * z + m[13]
    oz = m[2] * x + m[6] * y + m[10] * z + m[14]
    return ox, oy, oz


def _parent_map(gltf: GLTF2) -> dict[int, int]:
    parents: dict[int, int] = {}
    for idx, node in enumerate(gltf.nodes):
        for child in node.children or []:
            parents[child] = idx
    return parents


def _mesh_node_index(gltf: GLTF2, mesh_index: int) -> int:
    for idx, node in enumerate(gltf.nodes):
        if node.mesh == mesh_index:
            return idx
    raise ValueError(f"No node references mesh {mesh_index}")


def _center_on_ground(points: list[tuple[float, float, float]]) -> list[Gf.Vec3f]:
    xs = [p[0] for p in points]
    ys = [p[1] for p in points]
    zs = [p[2] for p in points]
    cx = (min(xs) + max(xs)) * 0.5
    cz = (min(zs) + max(zs)) * 0.5
    min_y = min(ys)
    return [Gf.Vec3f(p[0] - cx, p[1] - min_y, p[2] - cz) for p in points]


def _write_texture(gltf: GLTF2, image_index: int, out_dir: Path) -> Path | None:
    image = gltf.images[image_index]
    if image.bufferView is not None:
        data = _buffer_bytes(gltf, image.bufferView)
        ext = ".png"
        if image.mimeType == "image/jpeg":
            ext = ".jpg"
        tex_path = out_dir / f"texture{ext}"
        tex_path.write_bytes(data)
        return tex_path
    if image.uri:
        src = Path(image.uri)
        if src.exists():
            dest = out_dir / src.name
            import shutil

            shutil.copy2(src, dest)
            return dest
    return None


def convert(glb_path: Path, usdz_path: Path) -> None:
    gltf = _read_glb(glb_path)
    mesh = gltf.meshes[0]
    prim = mesh.primitives[0]
    parents = _parent_map(gltf)
    mesh_node = _mesh_node_index(gltf, 0)
    world = _world_mat4(gltf, mesh_node, parents)

    positions = _accessor_array(gltf, prim.attributes.POSITION)
    indices = _accessor_array(gltf, prim.indices)
    uvs = (
        _accessor_array(gltf, prim.attributes.TEXCOORD_0)
        if prim.attributes.TEXCOORD_0 is not None
        else None
    )

    transformed = [_transform_point(world, p) for p in positions]
    centered = _center_on_ground(transformed)

    xs = [p[0] for p in transformed]
    ys = [p[1] for p in transformed]
    zs = [p[2] for p in transformed]
    print(
        f"[glb_to_usdz] world bbox "
        f"X {min(xs):.3f}-{max(xs):.3f} "
        f"Y {min(ys):.3f}-{max(ys):.3f} "
        f"Z {min(zs):.3f}-{max(zs):.3f} (meters)"
    )

    with tempfile.TemporaryDirectory() as tmp:
        tmp_dir = Path(tmp)
        usdc_path = tmp_dir / "model.usdc"
        stage = Usd.Stage.CreateNew(str(usdc_path))
        UsdGeom.SetStageUpAxis(stage, UsdGeom.Tokens.y)
        UsdGeom.SetStageMetersPerUnit(stage, 1.0)

        root = UsdGeom.Xform.Define(stage, "/Root")
        stage.SetDefaultPrim(root.GetPrim())
        mesh_prim = UsdGeom.Mesh.Define(stage, "/Root/Mesh")

        mesh_prim.CreatePointsAttr(Vt.Vec3fArray(centered))
        mesh_prim.CreateFaceVertexCountsAttr(Vt.IntArray([3] * (len(indices) // 3)))
        mesh_prim.CreateFaceVertexIndicesAttr(
            Vt.IntArray([i[0] for i in indices])
        )

        if uvs:
            pv = UsdGeom.PrimvarsAPI(mesh_prim).CreatePrimvar(
                "st", Sdf.ValueTypeNames.TexCoord2fArray, UsdGeom.Tokens.vertex
            )
            pv.Set(Vt.Vec2fArray([Gf.Vec2f(u[0], 1.0 - u[1]) for u in uvs]))

        mat_path = "/Root/Material"
        material = UsdShade.Material.Define(stage, mat_path)
        shader = UsdShade.Shader.Define(stage, f"{mat_path}/PreviewSurface")
        shader.CreateIdAttr("UsdPreviewSurface")
        material.CreateSurfaceOutput().ConnectToSource(shader.ConnectableAPI(), "surface")

        if prim.material is not None:
            gmat = gltf.materials[prim.material]
            pbr = gmat.pbrMetallicRoughness
            if pbr and pbr.baseColorTexture is not None:
                tex_file = _write_texture(gltf, gltf.textures[pbr.baseColorTexture.index].source, tmp_dir)
                if tex_file:
                    st = UsdShade.Shader.Define(stage, f"{mat_path}/DiffuseTexture")
                    st.CreateIdAttr("UsdUVTexture")
                    st.CreateInput("file", Sdf.ValueTypeNames.Asset).Set(
                        Sdf.AssetPath(tex_file.name)
                    )
                    st.CreateInput("sourceColorSpace", Sdf.ValueTypeNames.Token).Set(
                        "sRGB"
                    )
                    st.CreateOutput("rgb", Sdf.ValueTypeNames.Float3)
                    shader.CreateInput("diffuseColor", Sdf.ValueTypeNames.Color3f).ConnectToSource(
                        st.ConnectableAPI(), "rgb"
                    )

        UsdShade.MaterialBindingAPI(mesh_prim.GetPrim()).Bind(material)
        stage.GetRootLayer().Save()

        usdz_path.parent.mkdir(parents=True, exist_ok=True)
        ok = UsdUtils.CreateNewARKitUsdzPackage(
            Sdf.AssetPath(str(usdc_path)), str(usdz_path)
        )
        if not ok:
            raise RuntimeError("CreateNewARKitUsdzPackage failed")


def main() -> int:
    parser = argparse.ArgumentParser(description="Convert a simple GLB to USDZ")
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    convert(args.input, args.output)
    print(f"Wrote {args.output} ({args.output.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
