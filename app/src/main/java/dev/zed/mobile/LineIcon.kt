package dev.zed.mobile

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.dp

/**
 * How thick icon lines look on screen, whatever size the icon is drawn at: 1.5 units of the
 * 24-unit icon grid at 18dp.
 */
private val LineWidth = 1.125.dp

/**
 * Draws [imageVector] like Material's `Icon`, but rescales the stroke of line icons to its
 * displayed size. A vector's stroke scales with the icon, so without this a 22dp icon draws
 * visibly heavier lines than a 16dp one.
 */
@Composable
internal fun Icon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    // The trailing 24dp only applies when the caller set no size, matching Material's default.
    BoxWithConstraints(modifier.size(24.dp)) {
        val strokeWidth = LineWidth.value * imageVector.viewportWidth / maxWidth.value.coerceAtLeast(1f)
        val vector = remember(imageVector, strokeWidth) { imageVector.withStrokeWidth(strokeWidth) }
        androidx.compose.material3.Icon(vector, contentDescription, Modifier.fillMaxSize(), tint)
    }
}

private fun ImageVector.withStrokeWidth(strokeWidth: Float): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = defaultWidth,
        defaultHeight = defaultHeight,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
        tintColor = tintColor,
        tintBlendMode = tintBlendMode,
        autoMirror = autoMirror,
    ).apply { addChildren(root, strokeWidth) }.build()

private fun ImageVector.Builder.addChildren(group: VectorGroup, strokeWidth: Float) {
    for (node in group) {
        when (node) {
            is VectorPath -> addPath(
                pathData = node.pathData,
                pathFillType = node.pathFillType,
                name = node.name,
                fill = node.fill,
                fillAlpha = node.fillAlpha,
                stroke = node.stroke,
                strokeAlpha = node.strokeAlpha,
                strokeLineWidth = if (node.stroke != null) strokeWidth else node.strokeLineWidth,
                strokeLineCap = node.strokeLineCap,
                strokeLineJoin = node.strokeLineJoin,
                strokeLineMiter = node.strokeLineMiter,
                trimPathStart = node.trimPathStart,
                trimPathEnd = node.trimPathEnd,
                trimPathOffset = node.trimPathOffset,
            )
            is VectorGroup -> {
                addGroup(
                    name = node.name,
                    rotate = node.rotation,
                    pivotX = node.pivotX,
                    pivotY = node.pivotY,
                    scaleX = node.scaleX,
                    scaleY = node.scaleY,
                    translationX = node.translationX,
                    translationY = node.translationY,
                    clipPathData = node.clipPathData,
                )
                addChildren(node, strokeWidth)
                clearGroup()
            }
        }
    }
}
