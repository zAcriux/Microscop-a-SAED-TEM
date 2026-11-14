/*
 * TEM Lab
 * Copyright © 2026 F. Mares-Briones. All rights reserved.
 */
package com.temlab.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.temlab.app.render.RenderedTile
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** View transform: screen = C + R(θ)·(img·s + offset − C). Rotation is display-only. */
@Stable
class ViewTransform {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    var rotation by mutableFloatStateOf(0f)
    var viewSize by mutableStateOf(Size.Zero)
    var fittedFor by mutableStateOf<IntSize?>(null)
    var fittedView by mutableStateOf(Size.Zero)

    private fun rot(p: Offset, deg: Float): Offset {
        val a = Math.toRadians(deg.toDouble()); val c = cos(a).toFloat(); val s = sin(a).toFloat()
        return Offset(p.x * c - p.y * s, p.x * s + p.y * c)
    }
    private val center get() = Offset(viewSize.width / 2, viewSize.height / 2)

    fun imageToScreen(p: Offset): Offset = center + rot(p * scale + offset - center, rotation)
    fun screenToImage(p: Offset): Offset = (rot(p - center, -rotation) + center - offset) / scale

    fun fit(w: Int, h: Int) {
        if (viewSize.width <= 0f || viewSize.height <= 0f) return
        scale = min(viewSize.width / w, viewSize.height / h) * 0.98f
        offset = Offset((viewSize.width - w * scale) / 2, (viewSize.height - h * scale) / 2)
        fittedFor = IntSize(w, h); fittedView = viewSize
    }

    fun zoomAbout(centroid: Offset, zoom: Float) {
        val q = rot(centroid - center, -rotation) + center
        val img = (q - offset) / scale
        scale = (scale * zoom).coerceIn(0.01f, 200f)
        offset = q - img * scale
    }

    fun pan(delta: Offset) { offset += rot(delta, -rotation) }

    fun copyFrom(o: ViewTransform) { scale = o.scale; offset = o.offset; rotation = o.rotation }

    /** Visible image-space bounding rectangle. */
    fun visibleRect(w: Int, h: Int): IntArray {
        val pts = listOf(Offset.Zero, Offset(viewSize.width, 0f), Offset(0f, viewSize.height), Offset(viewSize.width, viewSize.height)).map { screenToImage(it) }
        val x0 = pts.minOf { it.x }.toInt().coerceIn(0, w); val y0 = pts.minOf { it.y }.toInt().coerceIn(0, h)
        val x1 = (pts.maxOf { it.x }.toInt() + 2).coerceIn(0, w); val y1 = (pts.maxOf { it.y }.toInt() + 2).coerceIn(0, h)
        return intArrayOf(x0, y0, x1 - x0, y1 - y0)
    }
}

/** Gesture interaction of the active tool. */
class ToolHandler(
    val wantsDrag: Boolean = false,
    val magnifier: Boolean = false,
    val onTap: ((Offset) -> Unit)? = null,
    val onDragStart: ((Offset) -> Unit)? = null,
    val onDrag: ((Offset) -> Unit)? = null,
    val onDragEnd: (() -> Unit)? = null,
)

/**
 * Pan/zoom image viewer. One finger pans (or is forwarded to the active tool), two fingers zoom/pan.
 * [overlay] draws in IMAGE coordinates; its argument is the current scale (divide stroke widths by it).
 * While a tool drags, a 3× magnifier ("lupa") is shown in the upper-left corner.
 */
@Composable
fun ZoomableImage(
    tile: RenderedTile?,
    imageW: Int,
    imageH: Int,
    modifier: Modifier = Modifier,
    transform: ViewTransform = remember { ViewTransform() },
    tool: ToolHandler = ToolHandler(),
    detailRenderer: (suspend (Int, Int, Int, Int, Int) -> RenderedTile?)? = null,
    background: Color = Color(0xFF0B0E11),
    onTransformChanged: (() -> Unit)? = null,
    overlay: DrawScope.(Float) -> Unit = {},
) {
    var detail by remember(tile) { mutableStateOf<RenderedTile?>(null) }
    var dragScreen by remember { mutableStateOf<Offset?>(null) }
    val toolState by rememberUpdatedState(tool)
    val onChanged by rememberUpdatedState(onTransformChanged)

    LaunchedEffect(imageW, imageH, transform.viewSize) {
        if ((transform.fittedFor != IntSize(imageW, imageH) || transform.fittedView != transform.viewSize) && transform.viewSize.width > 0) transform.fit(imageW, imageH)
    }
    if (detailRenderer != null && tile != null) {
        LaunchedEffect(tile, transform.scale, transform.offset, transform.rotation, transform.viewSize) {
            delay(180)
            val previewScale = tile.bitmap.width.toFloat() / tile.w
            if (transform.scale <= previewScale * 1.15f) { detail = null; return@LaunchedEffect }
            val r = transform.visibleRect(imageW, imageH)
            if (r[2] <= 0 || r[3] <= 0) return@LaunchedEffect
            val maxDim = max(256, (max(transform.viewSize.width, transform.viewSize.height) * 1.2f).toInt()).coerceAtMost(2048)
            detail = detailRenderer(r[0], r[1], r[2], r[3], maxDim)
        }
    }

    Box(modifier.clipToBounds().background(background).onSizeChanged { transform.viewSize = Size(it.width.toFloat(), it.height.toFloat()) }) {
        Canvas(
            Modifier.matchParentSize().pointerInput(imageW, imageH) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var multi = false; var moved = false; var toolDragging = false
                    var last = down.position
                    val slop = viewConfiguration.touchSlop
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            if (toolDragging) { toolState.onDragEnd?.invoke(); toolDragging = false; dragScreen = null }
                            multi = true
                            val z = event.calculateZoom(); val p = event.calculatePan(); val c = event.calculateCentroid()
                            if (z != 1f) transform.zoomAbout(c, z)
                            if (p != Offset.Zero) transform.pan(p)
                            onChanged?.invoke()
                            event.changes.forEach { it.consume() }
                        } else if (!multi) {
                            val c = pressed[0]
                            if (!moved && (c.position - down.position).getDistance() > slop) {
                                moved = true
                                if (toolState.wantsDrag) {
                                    toolDragging = true; dragScreen = c.position
                                    toolState.onDragStart?.invoke(transform.screenToImage(down.position))
                                }
                            }
                            if (moved) {
                                if (toolDragging) { dragScreen = c.position; toolState.onDrag?.invoke(transform.screenToImage(c.position)) }
                                else { transform.pan(c.position - last); onChanged?.invoke() }
                            }
                            last = c.position
                            c.consume()
                        }
                    }
                    if (toolDragging) { toolState.onDragEnd?.invoke(); dragScreen = null }
                    else if (!moved && !multi) toolState.onTap?.invoke(transform.screenToImage(down.position))
                }
            }
        ) {
            fun DrawScope.content(extraScale: Float) {
                withTransform({
                    rotate(transform.rotation, pivot = center)
                    translate(transform.offset.x, transform.offset.y)
                    scale(transform.scale, transform.scale, pivot = Offset.Zero)
                }) {
                    val fq = if (transform.scale * extraScale > 2f) FilterQuality.None else FilterQuality.Low
                    tile?.let {
                        drawImage(it.bitmap, IntOffset.Zero, IntSize(it.bitmap.width, it.bitmap.height),
                            IntOffset(it.x, it.y), IntSize(it.w, it.h), filterQuality = fq)
                    }
                    detail?.let {
                        drawImage(it.bitmap, IntOffset.Zero, IntSize(it.bitmap.width, it.bitmap.height),
                            IntOffset(it.x, it.y), IntSize(it.w, it.h), filterQuality = fq)
                    }
                    overlay(transform.scale * extraScale)
                }
            }
            content(1f)
            val ds = dragScreen
            if (ds != null && toolState.magnifier) {
                val r = min(size.minDimension * 0.22f, 260f)
                val m = Offset(r + 16f, r + 16f)
                val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(m, r)) }
                clipPath(circle) {
                    drawRect(background)
                    withTransform({ translate(m.x, m.y); scale(3f, 3f, pivot = Offset.Zero); translate(-ds.x, -ds.y) }) { content(3f) }
                }
                drawCircle(Color.White, r, m, style = Stroke(3f))
                drawLine(Color.Red, m - Offset(14f, 0f), m + Offset(14f, 0f), 2f)
                drawLine(Color.Red, m - Offset(0f, 14f), m + Offset(0f, 14f), 2f)
            }
        }
    }
}
