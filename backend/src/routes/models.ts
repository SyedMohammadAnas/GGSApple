import fs from 'fs/promises';
import path from 'path';
import { randomUUID } from 'crypto';
import type { NextFunction, Request, Response } from 'express';
import { Router } from 'express';
import multer from 'multer';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const assetsRoot = path.join(__dirname, '../../assets');
const modelsDir = path.join(assetsRoot, 'models');
const thumbnailsDir = path.join(assetsRoot, 'thumbnails');
const manifestPath = path.join(modelsDir, 'manifest.json');

interface ManifestModel {
  id: string;
  name: string;
  fileName: string;
  thumbnailFileName?: string;
  description?: string;
  fileSizeBytes?: number;
  createdAt: string;
}

interface ManifestFile {
  models: ManifestModel[];
}

export const upload = multer({
  storage: multer.diskStorage({
    destination: async (_req, _file, cb) => {
      await fs.mkdir(modelsDir, { recursive: true });
      cb(null, modelsDir);
    },
    filename: (_req, file, cb) => {
      const safe = file.originalname.replace(/[^a-zA-Z0-9._-]/g, '_');
      cb(null, `${Date.now()}-${safe}`);
    },
  }),
  limits: { fileSize: 50 * 1024 * 1024 },
});

async function readManifest(): Promise<ManifestFile> {
  try {
    const raw = await fs.readFile(manifestPath, 'utf8');
    return JSON.parse(raw) as ManifestFile;
  } catch {
    return { models: [] };
  }
}

async function writeManifest(manifest: ManifestFile): Promise<void> {
  await fs.mkdir(modelsDir, { recursive: true });
  await fs.writeFile(manifestPath, JSON.stringify(manifest, null, 2));
}

function toPublicModel(model: ManifestModel, baseUrl: string) {
  return {
    id: model.id,
    name: model.name,
    url: `${baseUrl}/assets/models/${model.fileName}`,
    thumbnailUrl: model.thumbnailFileName
      ? `${baseUrl}/assets/thumbnails/${model.thumbnailFileName}`
      : null,
    fileSizeBytes: model.fileSizeBytes ?? null,
    description: model.description ?? null,
    createdAt: model.createdAt,
  };
}

export async function listModels(baseUrl: string) {
  const manifest = await readManifest();
  return manifest.models.map((model) => toPublicModel(model, baseUrl));
}

export function requireDashboardKey(req: Request, res: Response, next: NextFunction): void {
  const expected = process.env.DASHBOARD_KEY ?? 'dev-dashboard';
  const provided = req.header('x-dashboard-key');
  if (!provided || provided !== expected) {
    res.status(401).json({ error: 'Invalid dashboard key' });
    return;
  }
  next();
}

export async function deleteModelById(id: string): Promise<boolean> {
  const manifest = await readManifest();
  const index = manifest.models.findIndex((m) => m.id === id);
  if (index < 0) return false;

  const [removed] = manifest.models.splice(index, 1);
  await writeManifest(manifest);

  await fs.unlink(path.join(modelsDir, removed.fileName)).catch(() => undefined);
  if (removed.thumbnailFileName) {
    await fs.unlink(path.join(thumbnailsDir, removed.thumbnailFileName)).catch(() => undefined);
  }
  return true;
}

export const uploadModel = [
  upload.fields([
    { name: 'model', maxCount: 1 },
    { name: 'thumbnail', maxCount: 1 },
  ]),
  async (req: Request, res: Response) => {
    const modelFile = req.files && 'model' in req.files ? req.files.model?.[0] : undefined;
    if (!modelFile) {
      res.status(400).json({ error: 'model file is required (.glb)' });
      return;
    }

    const name = String(req.body?.name ?? modelFile.originalname.replace(/\.glb$/i, ''));
    const description = req.body?.description ? String(req.body.description) : undefined;
    const thumbnailFile =
      req.files && 'thumbnail' in req.files ? req.files.thumbnail?.[0] : undefined;

    let thumbnailFileName: string | undefined;
    if (thumbnailFile) {
      await fs.mkdir(thumbnailsDir, { recursive: true });
      thumbnailFileName = path.basename(thumbnailFile.path);
      await fs.rename(thumbnailFile.path, path.join(thumbnailsDir, thumbnailFileName));
    }

    const entry: ManifestModel = {
      id: randomUUID(),
      name,
      fileName: path.basename(modelFile.path),
      thumbnailFileName,
      description,
      fileSizeBytes: modelFile.size,
      createdAt: new Date().toISOString(),
    };

    const manifest = await readManifest();
    manifest.models.unshift(entry);
    await writeManifest(manifest);

    const baseUrl = `${req.protocol}://${req.get('host')}`;
    res.status(201).json({ model: toPublicModel(entry, baseUrl) });
  },
] as const;

const router = Router();

// Catalog is public — Instant ModelCatalogService and expert UIs fetch without a token.
router.get('/', async (req, res) => {
  const baseUrl = `${req.protocol}://${req.get('host')}`;
  const models = await listModels(baseUrl);
  res.json({ models });
});

export default router;
