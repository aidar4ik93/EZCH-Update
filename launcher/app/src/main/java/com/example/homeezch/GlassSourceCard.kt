package com.example.homeezch

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun GlassSourceCard(source: TvSourceEntry, recent: Boolean, onClick: () -> Unit) {
    val warm = source.kind == TvSourceKind.HDMI && Regex("(?:^|\\D)2(?:\\D|$)").containsMatchIn(source.label)
    val accent = if (warm) Gold else Ice
    var focus by remember { mutableStateOf(false) }
    val glow by animateFloatAsState(if (source.connected == true) if (recent) 1f else .46f else 0f,
        tween(if (recent) 550 else 1100), label = "connection")
    val shape = RoundedCornerShape(15.dp)
    Box(Modifier.width(136.dp).height(108.dp)
        .semantics { contentDescription = "${source.label}: ${source.hint}" }
        .clip(shape)
        .background(Brush.linearGradient(listOf(Color(0xA9557694), Color(0xB3192D4B), Color(0xD2061124))))
        .border(if (focus) 2.dp else 1.dp, if (focus) Color.White.copy(alpha = .8f) else accent.copy(alpha = .23f + glow * .48f), shape)
        .onFocusChanged { focus = it.isFocused }
        .clickable(onClick = onClick)) {
        Canvas(Modifier.fillMaxSize()) {
            // Diffuse light stays inside the translucent glass; focus uses its own rim.
            drawRect(Brush.radialGradient(listOf(accent.copy(alpha = .12f + glow * .27f), Color.Transparent),
                Offset(size.width * .73f, size.height * .3f), size.width * .95f))
            drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .27f), Color.White.copy(alpha = .025f), Color.Transparent)),
                Offset(1.dp.toPx(), 1.dp.toPx()), Size(size.width - 2.dp.toPx(), size.height * .46f), CornerRadius(15.dp.toPx()))
            drawLine(accent.copy(alpha = glow * .65f), Offset(size.width * .16f, size.height - 1.dp.toPx()),
                Offset(size.width * .84f, size.height - 1.dp.toPx()), 3.dp.toPx())
        }
        Column(Modifier.fillMaxSize().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            GlassPortGlyph(source.kind, accent, glow, Modifier.width(76.dp).height(44.dp))
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(source.label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (source.kind == TvSourceKind.USB) Text("  ⌄", color = Ice, fontSize = 17.sp)
            }
            Text(if (recent) "Только что подключено" else source.hint,
                color = if (source.connected == true) accent else Muted, fontSize = 9.sp, maxLines = 1)
        }
    }
}

@Composable internal fun GlassPortGlyph(kind: TvSourceKind, accent: Color, glow: Float, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val light = accent.copy(alpha = .72f + glow * .28f)
        when (kind) {
            TvSourceKind.HDMI -> {
                val socket = Path().apply {
                    moveTo(w * .12f, h * .17f); lineTo(w * .88f, h * .17f)
                    lineTo(w * .88f, h * .54f); lineTo(w * .77f, h * .78f)
                    lineTo(w * .23f, h * .78f); lineTo(w * .12f, h * .54f); close()
                }
                drawPath(socket, Brush.linearGradient(listOf(Color(0xFF163457), Color(0xFF020914))))
                drawPath(socket, light.copy(alpha = .12f + glow * .12f), style = Stroke(10.dp.toPx()))
                drawPath(socket, Color.White.copy(alpha = .78f), style = Stroke(3.dp.toPx()))
                drawPath(socket, light, style = Stroke(1.6.dp.toPx()))
                drawLine(light, Offset(w * .25f, h * .35f), Offset(w * .75f, h * .35f), 2.dp.toPx())
                repeat(6) { i -> drawRoundRect(light, Offset(w * (.28f + i * .077f), h * .52f), Size(w * .035f, h * .08f), CornerRadius(1.dp.toPx())) }
            }
            TvSourceKind.AV -> {
                listOf(Color(0xFFFFD354), Color(0xFFFF4B54), Color(0xFFD9E6FF)).forEachIndexed { i, c ->
                    val center = Offset(w * (.18f + i * .32f), h * .46f)
                    drawCircle(c.copy(alpha = .08f + glow * .18f), h * .33f, center)
                    drawCircle(Color(0xFF070D18), h * .19f, center)
                    drawCircle(Color.White.copy(alpha = .65f), h * .2f, center, style = Stroke(3.dp.toPx()))
                    drawCircle(c, h * .2f, center, style = Stroke(2.dp.toPx()))
                    drawCircle(c.copy(alpha = .6f), h * .085f, center, style = Stroke(1.dp.toPx()))
                }
            }
            TvSourceKind.TV -> {
                drawRoundRect(Color(0xFF020B1D), Offset(w * .12f, h * .05f), Size(w * .76f, h * .68f), CornerRadius(3.dp.toPx()))
                drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF37AFFF), Color(0xFF113C81))), Offset(w * .15f, h * .09f), Size(w * .7f, h * .6f), CornerRadius(2.dp.toPx()))
                val mountains = Path().apply { moveTo(w * .15f, h * .58f); lineTo(w * .37f, h * .24f); lineTo(w * .53f, h * .47f); lineTo(w * .68f, h * .33f); lineTo(w * .85f, h * .6f); lineTo(w * .85f, h * .69f); lineTo(w * .15f, h * .69f); close() }
                drawPath(mountains, Brush.verticalGradient(listOf(Color(0xFF8EDDFF), Color(0xFF071B43))))
                drawRoundRect(light, Offset(w * .12f, h * .05f), Size(w * .76f, h * .68f), CornerRadius(3.dp.toPx()), style = Stroke(1.5.dp.toPx()))
                drawLine(light, Offset(w * .5f, h * .73f), Offset(w * .5f, h * .84f), 2.dp.toPx())
                drawLine(light, Offset(w * .35f, h * .87f), Offset(w * .65f, h * .87f), 2.dp.toPx())
            }
            TvSourceKind.USB -> {
                drawRoundRect(Brush.linearGradient(listOf(Color(0xFFC3D8F5), Color(0xFF426589))), Offset(w * .39f, 0f), Size(w * .22f, h * .29f), CornerRadius(2.dp.toPx()))
                repeat(2) { i -> drawRect(Color(0xFF16233C), Offset(w * (.43f + i * .095f), h * .08f), Size(w * .04f, h * .07f)) }
                drawRoundRect(Brush.linearGradient(listOf(Color(0xADAFD0FF), Color(0xAD386EB0))), Offset(w * .32f, h * .26f), Size(w * .36f, h * .64f), CornerRadius(4.dp.toPx()))
                drawRoundRect(light.copy(alpha = .75f), Offset(w * .32f, h * .26f), Size(w * .36f, h * .64f), CornerRadius(4.dp.toPx()), style = Stroke(1.dp.toPx()))
                drawLine(Color.White, Offset(w * .5f, h * .78f), Offset(w * .5f, h * .4f), 1.dp.toPx())
                drawLine(Color.White, Offset(w * .5f, h * .58f), Offset(w * .4f, h * .48f), 1.dp.toPx())
                drawLine(Color.White, Offset(w * .5f, h * .66f), Offset(w * .6f, h * .55f), 1.dp.toPx())
                drawCircle(Color.White, h * .02f, Offset(w * .4f, h * .48f))
                drawCircle(Color.White, h * .03f, Offset(w * .5f, h * .78f))
            }
        }
    }
}
