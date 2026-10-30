package com.example.ezchupdate

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/** Small native vectors keep TV focus icons crisp at any display density. */
@Composable internal fun CinemaSymbol(name: String, color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val unit = size.width / 24f
        val stroke = Stroke(1.8f * unit, cap = StrokeCap.Round)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color,
            Offset(x1 * unit, y1 * unit), Offset(x2 * unit, y2 * unit), strokeWidth = stroke.width, cap = StrokeCap.Round)
        fun circle(x: Float, y: Float, radius: Float) = drawCircle(color, radius * unit, Offset(x * unit, y * unit), style = stroke)
        when (name) {
            "Поиск" -> { circle(10f, 10f, 7f); line(15f,15f,22f,22f) }
            "Главная" -> {
                val house = Path().apply {
                    moveTo(2*unit,11*unit); lineTo(12*unit,3*unit); lineTo(22*unit,11*unit)
                    moveTo(5*unit,9*unit); lineTo(5*unit,21*unit); lineTo(10*unit,21*unit)
                    lineTo(10*unit,15*unit); lineTo(14*unit,15*unit); lineTo(14*unit,21*unit)
                    lineTo(19*unit,21*unit); lineTo(19*unit,9*unit)
                }; drawPath(house, color, style = stroke)
            }
            "Фильмы и ТВ" -> { circle(12f,12f,10f); circle(8f,7f,1.5f); circle(16f,7f,1.5f); circle(8f,17f,1.5f); circle(16f,17f,1.5f); circle(12f,12f,1.5f) }
            "Видеосервисы" -> {
                drawRoundRect(color, Offset(1*unit,4*unit), Size(22*unit,16*unit), androidx.compose.ui.geometry.CornerRadius(4*unit), style = stroke)
                val play = Path().apply { moveTo(10*unit,8*unit); lineTo(16*unit,12*unit); lineTo(10*unit,16*unit); close() }
                drawPath(play,color)
            }
            "Настройки" -> {
                circle(12f,12f,6.5f); circle(12f,12f,2.3f)
                repeat(8) { val angle=it*Math.PI/4; line((12+7*cos(angle)).toFloat(),(12+7*sin(angle)).toFloat(),(12+10*cos(angle)).toFloat(),(12+10*sin(angle)).toFloat()) }
            }
            "Инструменты" -> {
                val tool = Path().apply {
                    moveTo(14*unit,3*unit); cubicTo(9*unit,2*unit,7*unit,7*unit,10*unit,10*unit)
                    lineTo(3*unit,18*unit); cubicTo(1*unit,21*unit,4*unit,23*unit,6*unit,20*unit)
                    lineTo(14*unit,12*unit); cubicTo(19*unit,14*unit,23*unit,10*unit,21*unit,6*unit)
                    lineTo(18*unit,9*unit); lineTo(14*unit,7*unit); close()
                }; drawPath(tool,color,style=stroke)
            }
            "Обновления" -> {
                drawArc(color, 35f, 275f, false, Offset(3*unit,3*unit), Size(18*unit,18*unit), style = stroke)
                line(20f,3f,20f,9f); line(20f,9f,14f,9f)
            }
            else -> repeat(4) { index ->
                drawRoundRect(color, Offset((3+(index%2)*11)*unit,(3+(index/2)*11)*unit), Size(7*unit,7*unit), androidx.compose.ui.geometry.CornerRadius(unit),style=stroke)
            }
        }
    }
}
