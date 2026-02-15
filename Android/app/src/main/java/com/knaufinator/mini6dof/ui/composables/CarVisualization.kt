package com.knaufinator.mini6dof.ui.composables

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

private data class V3(val x: Float, val y: Float, val z: Float)
private data class Face(val idx: List<Int>, val color: Color, val outline: Color)

private const val DEG2RAD = 0.017453292f
private const val VIEW_RX = -0.50f   // look down ~29°
private const val VIEW_RY =  0.40f   // slight side view ~23°

/**
 * 3D car rendered on a Canvas, oriented by live roll/pitch/yaw.
 * The ground grid stays in the world frame; the car rotates relative to it.
 */
@Composable
fun CarVisualization(
    rollDeg: Float,
    pitchDeg: Float,
    yawDeg: Float,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(280.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0F1923))
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val scale = minOf(size.width, size.height) * 0.17f

        val roll  = rollDeg  * DEG2RAD
        val pitch = pitchDeg * DEG2RAD
        val yaw   = yawDeg   * DEG2RAD

        // ── Ground grid (world frame, view rotation only) ──────────────
        drawGroundGrid(cx, cy, scale)

        // ── Car model ──────────────────────────────────────────────────
        val (verts, faces) = carModel()

        val xformed = verts.map { v ->
            applyView(rotZYX(v, roll, pitch, yaw))
        }

        // Painter's algorithm — back-to-front
        val sorted = faces.sortedBy { f ->
            f.idx.sumOf { xformed[it].z.toDouble() } / f.idx.size
        }

        for (f in sorted) {
            val pts = f.idx.map { Offset(cx + xformed[it].x * scale, cy - xformed[it].y * scale) }
            val path = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
                close()
            }
            drawPath(path, f.color, style = Fill)
            drawPath(path, f.outline, style = Stroke(width = 1.2f))
        }

        // ── Axis gizmo (bottom-left) ──────────────────────────────────
        drawAxisGizmo(cx, cy, scale, roll, pitch, yaw)

        // ── Horizon reference line ────────────────────────────────────
        drawHorizonLabel(cx, cy, scale)
    }
}

// ── 3D math ────────────────────────────────────────────────────────────

private fun rotX(v: V3, a: Float): V3 {
    val c = cos(a); val s = sin(a)
    return V3(v.x, v.y * c - v.z * s, v.y * s + v.z * c)
}
private fun rotY(v: V3, a: Float): V3 {
    val c = cos(a); val s = sin(a)
    return V3(v.x * c + v.z * s, v.y, -v.x * s + v.z * c)
}
private fun rotZ(v: V3, a: Float): V3 {
    val c = cos(a); val s = sin(a)
    return V3(v.x * c - v.y * s, v.x * s + v.y * c, v.z)
}
private fun rotZYX(v: V3, rx: Float, ry: Float, rz: Float) = rotZ(rotY(rotX(v, rx), ry), rz)
private fun applyView(v: V3) = rotY(rotX(v, VIEW_RX), VIEW_RY)

// ── Car geometry ───────────────────────────────────────────────────────

private fun carModel(): Pair<List<V3>, List<Face>> {
    // Dimensions: x = forward, y = left, z = up.  Car centered at origin.
    val bl = -1.4f; val br = 1.4f    // body length
    val bw = 0.60f                    // body half-width
    val bh = 0.40f                    // body height

    val cl = -0.3f; val cr = 0.95f   // cabin x range
    val cw = 0.54f                    // cabin half-width
    val ch = 0.36f                    // cabin height above body

    val v = listOf(
        // Body bottom (0-3)
        V3(br, -bw, 0f), V3(bl, -bw, 0f), V3(bl,  bw, 0f), V3(br,  bw, 0f),
        // Body top (4-7)
        V3(br, -bw, bh), V3(bl, -bw, bh), V3(bl,  bw, bh), V3(br,  bw, bh),
        // Cabin bottom (8-11) = same z as body top
        V3(cr, -cw, bh), V3(cl, -cw, bh), V3(cl,  cw, bh), V3(cr,  cw, bh),
        // Cabin top (12-15)
        V3(cr, -cw, bh + ch), V3(cl, -cw, bh + ch), V3(cl,  cw, bh + ch), V3(cr,  cw, bh + ch),
        // Wheel hubs — front-left(16), front-right(17), rear-left(18), rear-right(19)
        V3(br - 0.25f, -bw - 0.05f, 0.12f), V3(br - 0.25f,  bw + 0.05f, 0.12f),
        V3(bl + 0.25f, -bw - 0.05f, 0.12f), V3(bl + 0.25f,  bw + 0.05f, 0.12f),
    )

    val body     = Color(0xFF1565C0)
    val bodyDk   = Color(0xFF0D47A1)
    val bodySd   = Color(0xFF1258A8)
    val hood     = Color(0xFF1976D2)
    val glass    = Color(0xFF64B5F6).copy(alpha = 0.65f)
    val glassDk  = Color(0xFF42A5F5).copy(alpha = 0.50f)
    val cabin    = Color(0xFF1565C0).copy(alpha = 0.85f)
    val bottom   = Color(0xFF263238)
    val ol       = Color(0xFF000000).copy(alpha = 0.35f)

    val faces = listOf(
        // Body
        Face(listOf(0, 1, 2, 3), bottom, ol),           // bottom
        Face(listOf(1, 0, 4, 5), bodyDk, ol),           // left side
        Face(listOf(3, 2, 6, 7), bodyDk, ol),           // right side
        Face(listOf(0, 3, 7, 4), body, ol),             // front
        Face(listOf(1, 5, 6, 2), bodySd, ol),           // rear
        // Hood & trunk (body top areas outside cabin)
        Face(listOf(4, 7, 11, 8), hood, ol),            // front hood
        Face(listOf(5, 9, 10, 6), hood, ol),            // rear trunk
        // Cabin
        Face(listOf(12, 13, 14, 15), cabin, ol),        // roof
        Face(listOf(8, 12, 15, 11), glass, ol),         // windshield (front)
        Face(listOf(9, 10, 14, 13), glassDk, ol),       // rear window
        Face(listOf(8, 9, 13, 12), cabin, ol),          // left cabin side
        Face(listOf(11, 15, 14, 10), cabin, ol),        // right cabin side
    )

    return Pair(v, faces)
}

// ── Ground grid ────────────────────────────────────────────────────────

private fun DrawScope.drawGroundGrid(cx: Float, cy: Float, scale: Float) {
    val gridColor = Color.White.copy(alpha = 0.06f)
    val axisColor = Color.White.copy(alpha = 0.12f)
    val gridSize = 3.5f
    val step = 0.5f

    var i = -gridSize
    while (i <= gridSize) {
        val isAxis = (i == 0f)
        val c = if (isAxis) axisColor else gridColor
        val sw = if (isAxis) 1f else 0.5f

        // Lines along X (varying y)
        val a1 = applyView(V3(-gridSize, i, -0.02f))
        val a2 = applyView(V3( gridSize, i, -0.02f))
        drawLine(c, Offset(cx + a1.x * scale, cy - a1.y * scale),
                    Offset(cx + a2.x * scale, cy - a2.y * scale), strokeWidth = sw)

        // Lines along Y (varying x)
        val b1 = applyView(V3(i, -gridSize, -0.02f))
        val b2 = applyView(V3(i,  gridSize, -0.02f))
        drawLine(c, Offset(cx + b1.x * scale, cy - b1.y * scale),
                    Offset(cx + b2.x * scale, cy - b2.y * scale), strokeWidth = sw)

        i += step
    }
}

// ── Axis gizmo ─────────────────────────────────────────────────────────

private fun DrawScope.drawAxisGizmo(cx: Float, cy: Float, scale: Float,
                                     roll: Float, pitch: Float, yaw: Float) {
    val origin = V3(-2.6f, -1.8f, 0.3f)
    val len = 0.55f

    val axes = listOf(
        V3(len, 0f, 0f) to Color(0xFFEF5350),  // X forward = red
        V3(0f, len, 0f) to Color(0xFF66BB6A),   // Y left    = green
        V3(0f, 0f, len) to Color(0xFF42A5F5),   // Z up      = blue
    )

    val o = applyView(rotZYX(origin, roll, pitch, yaw))
    val oS = Offset(cx + o.x * scale, cy - o.y * scale)

    for ((dir, color) in axes) {
        val tip = V3(origin.x + dir.x, origin.y + dir.y, origin.z + dir.z)
        val t = applyView(rotZYX(tip, roll, pitch, yaw))
        val tS = Offset(cx + t.x * scale, cy - t.y * scale)
        drawLine(color, oS, tS, strokeWidth = 2.5f, cap = StrokeCap.Round)
    }
}

// ── Horizon label ──────────────────────────────────────────────────────

private fun DrawScope.drawHorizonLabel(cx: Float, cy: Float, scale: Float) {
    // Thin horizontal reference line at z=0 in world frame
    val left  = applyView(V3(-3f, 0f, 0f))
    val right = applyView(V3( 3f, 0f, 0f))
    val c = Color.White.copy(alpha = 0.08f)
    drawLine(c, Offset(cx + left.x * scale, cy - left.y * scale),
                Offset(cx + right.x * scale, cy - right.y * scale), strokeWidth = 0.5f)
}
