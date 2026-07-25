package com.ggsapple.remotear.ui.session

import com.ggsapple.remotear.annotation.AnnotationTool

enum class SessionPanel {
    NONE,
    MENU,
    CHAT,
    FILES,
}

enum class SidebarTool {
    POINTER,
    ARROW,
    DRAW,
    CIRCLE,
    UNDO,
    DELETE,
}

/** Drawing tools that use the touch layer (not pointer / action buttons). */
fun SidebarTool.isDrawingTool(): Boolean =
    this == SidebarTool.DRAW || this == SidebarTool.ARROW || this == SidebarTool.CIRCLE

fun SidebarTool.toAnnotationTool(): AnnotationTool? = when (this) {
    SidebarTool.DRAW -> AnnotationTool.FREEHAND
    SidebarTool.ARROW -> AnnotationTool.ARROW
    SidebarTool.CIRCLE -> AnnotationTool.CIRCLE
    else -> null
}
