package com.example.homeezch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Small deterministic line icons; no font glyph dependence on OEM television fonts. */
@Composable
internal fun GlassIcon(kind: String, modifier: Modifier = Modifier, color: Color = Color(0xFFBCDFFF)) {
    Canvas(modifier.size(26.dp)) {
        val w = size.width; val h = size.height; val stroke = 1.8.dp.toPx()
        fun line(x: Float, y: Float, x2: Float, y2: Float) =
            drawLine(color, Offset(w*x, h*y), Offset(w*x2, h*y2), stroke)
        fun box(x: Float, y: Float, width: Float, height: Float) =
            drawRect(color, Offset(w*x, h*y), Size(w*width, h*height), style = Stroke(stroke))
        when (kind) {
            "Домой" -> {
                line(.1f,.45f,.5f,.12f); line(.5f,.12f,.9f,.45f)
                line(.22f,.36f,.22f,.88f); line(.78f,.36f,.78f,.88f); line(.22f,.88f,.78f,.88f)
                box(.41f,.6f,.18f,.28f)
            }
            "Папка" -> { box(.1f,.32f,.8f,.54f); line(.1f,.32f,.1f,.16f); line(.1f,.16f,.4f,.16f); line(.4f,.16f,.53f,.32f) }
            "Строки" -> { for(y in listOf(.22f,.5f,.78f)) { box(.1f,y-.08f,.14f,.16f); line(.36f,y,.9f,y) } }
            "Обои" -> {
                box(.08f,.12f,.84f,.76f); drawCircle(color,w*.08f,Offset(w*.7f,h*.32f),style=Stroke(stroke))
                line(.1f,.75f,.37f,.46f); line(.37f,.46f,.58f,.7f); line(.58f,.7f,.75f,.53f); line(.75f,.53f,.9f,.75f)
            }
            "Bluetooth" -> {
                line(.46f,.12f,.46f,.88f); line(.46f,.12f,.72f,.34f)
                line(.72f,.34f,.23f,.72f); line(.23f,.28f,.72f,.67f); line(.72f,.67f,.46f,.88f)
            }
            "Сеть" -> {
                listOf(.78f, .55f, .32f).forEach { size ->
                    drawArc(color, 218f, 104f, false, Offset(w*(1-size)/2, h*(.43f-size/2)),
                        Size(w*size,h*size), style=Stroke(stroke))
                }
                drawCircle(color, stroke, Offset(w*.5f,h*.78f))
            }
            "Экран", "TV" -> { box(.12f,.16f,.76f,.55f); line(.5f,.71f,.5f,.86f); line(.28f,.86f,.72f,.86f) }
            "Звук" -> {
                val p=Path().apply { moveTo(w*.14f,h*.38f); lineTo(w*.3f,h*.38f); lineTo(w*.5f,h*.2f)
                    lineTo(w*.5f,h*.8f); lineTo(w*.3f,h*.62f); lineTo(w*.14f,h*.62f); close() }
                drawPath(p,color,style=Stroke(stroke))
                drawArc(color,-60f,120f,false,Offset(w*.35f,h*.16f),Size(w*.52f,h*.68f),style=Stroke(stroke))
            }
            "Хранилище" -> { box(.2f,.3f,.6f,.55f); line(.27f,.16f,.73f,.16f); line(.28f,.68f,.55f,.68f) }
            "HDMI" -> {
                val p=Path().apply { moveTo(w*.12f,h*.3f); lineTo(w*.88f,h*.3f); lineTo(w*.88f,h*.54f)
                    lineTo(w*.72f,h*.72f); lineTo(w*.28f,h*.72f); lineTo(w*.12f,h*.54f); close() }
                drawPath(p,color,style=Stroke(stroke)); line(.27f,.46f,.73f,.46f)
            }
            "AV" -> listOf(.22f,.5f,.78f).forEachIndexed { i,x ->
                drawCircle(listOf(Color(0xFFD7B477),Color.White,Color(0xFFE96172))[i],w*.095f,Offset(w*x,h*.5f),style=Stroke(stroke))
            }
            "USB" -> { box(.33f,.12f,.34f,.28f); box(.25f,.4f,.5f,.47f); line(.46f,.2f,.46f,.3f); line(.55f,.2f,.55f,.3f) }
            else -> {
                drawCircle(color,w*.24f,Offset(w*.5f,h*.5f),style=Stroke(stroke))
                drawCircle(color,w*.075f,Offset(w*.5f,h*.5f),style=Stroke(stroke))
                for(i in 0..7) {
                    val a=i*Math.PI/4
                    line((.5+ .3*kotlin.math.cos(a)).toFloat(),(.5+ .3*kotlin.math.sin(a)).toFloat(),
                        (.5+ .43*kotlin.math.cos(a)).toFloat(),(.5+ .43*kotlin.math.sin(a)).toFloat())
                }
            }
        }
    }
}
