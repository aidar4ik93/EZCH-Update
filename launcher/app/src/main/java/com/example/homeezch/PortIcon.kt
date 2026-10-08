package com.example.homeezch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Port-row artwork only; the top controls retain their original GlassIcon. */
@Composable
internal fun PortIcon(kind: TvSourceKind, modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier.size(32.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.6.dp.toPx()
        fun line(x: Float, y: Float, x2: Float, y2: Float) =
            drawLine(color, Offset(w*x, h*y), Offset(w*x2, h*y2), stroke, StrokeCap.Round)
        fun panel(x: Float, y: Float, width: Float, height: Float) {
            val origin = Offset(w*x, h*y)
            val bounds = Size(w*width, h*height)
            val radius = CornerRadius(w*.07f, h*.07f)
            drawRoundRect(color.copy(alpha=.13f), origin, bounds, radius)
            drawRoundRect(color, origin, bounds, radius, style=Stroke(stroke))
        }
        when (kind) {
            TvSourceKind.HDMI -> {
                val port = Path().apply {
                    moveTo(w*.13f,h*.29f); lineTo(w*.87f,h*.29f)
                    lineTo(w*.87f,h*.53f); lineTo(w*.72f,h*.73f)
                    lineTo(w*.28f,h*.73f); lineTo(w*.13f,h*.53f); close()
                }
                drawPath(port,color.copy(alpha=.13f))
                drawPath(port,color,style=Stroke(stroke))
                line(.27f,.44f,.73f,.44f)
                for (i in 0..4) { val x=.30f+i*.10f; line(x,.45f,x,.54f) }
            }
            TvSourceKind.USB -> {
                panel(.22f,.30f,.56f,.54f)
                panel(.33f,.10f,.34f,.20f)
                line(.43f,.16f,.43f,.22f); line(.57f,.16f,.57f,.22f)
                line(.5f,.70f,.5f,.43f)
                line(.5f,.43f,.43f,.50f); line(.5f,.43f,.57f,.50f)
                line(.5f,.61f,.64f,.53f)
            }
            TvSourceKind.AV -> {
                listOf(Color(0xFFFFD379),Color(0xFFEDF5FF),Color(0xFFFF8392)).forEachIndexed { i, tint ->
                    val center=Offset(w*(.21f+i*.29f),h*.48f)
                    drawCircle(tint.copy(alpha=.16f),w*.12f,center)
                    drawCircle(tint,w*.12f,center,style=Stroke(stroke))
                    drawCircle(tint,w*.035f,center)
                }
                line(.21f,.68f,.21f,.80f); line(.5f,.68f,.5f,.80f); line(.79f,.68f,.79f,.80f)
            }
            TvSourceKind.TV -> {
                panel(.13f,.26f,.74f,.49f)
                line(.34f,.10f,.5f,.26f); line(.66f,.10f,.5f,.26f)
                line(.34f,.87f,.66f,.87f); line(.5f,.75f,.5f,.87f)
                line(.72f,.40f,.72f,.59f)
            }
        }
    }
}
