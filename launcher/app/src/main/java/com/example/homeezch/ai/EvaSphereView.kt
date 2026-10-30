package com.example.homeezch.ai

import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.KeyEvent
import android.view.View
import kotlin.math.*

/** Programmatic reference composition. Blurred glass is cached, live waves use actual audio levels. */
open class EvaSphereView(context: Context) : View(context) {
    var message = "Нажмите OK, чтобы говорить"; set(value) { field = value; invalidate() }
    var transcript = ""; set(value) { field = value; invalidate() }
    var amplitude = 0f; set(value) { field = value.coerceIn(0f, 1f); invalidate() }
    var busy = false; set(value) { field = value; invalidate() }
    var compact = false; set(value) { field = value; invalidate() }
    var onListen: () -> Unit = {}
    var onText: () -> Unit = {}
    var onSettings: () -> Unit = {}
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var orb: Bitmap? = null
    private val cyan = 0xFF6BCFEF.toInt()
    private val violet = 0xFFAB7CE1.toInt()
    init {
        isFocusable = true; isFocusableInTouchMode = true
        contentDescription = "Ai EvA. OK — говорить. Вниз — текстовая команда. Меню — настройки. Back — закрыть."
        setOnClickListener { onListen() }
    }
    override fun onKeyDown(code: Int, event: KeyEvent): Boolean = when (code) {
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_ASSIST -> { if (event.repeatCount == 0) onListen(); true }
        KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.repeatCount == 0) onText(); true }
        KeyEvent.KEYCODE_MENU -> { if (event.repeatCount == 0) onSettings(); true }
        else -> super.onKeyDown(code, event)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (!compact) canvas.drawColor(Color.BLACK)
        val unit = if (compact) min(w / 900f, h / 440f) else min(w / 1536f, h / 864f)
        val cx = w / 2; val cy = h * if (compact) .35f else .435f; val r = (if (compact) 80 else 150) * unit
        // Small movement during action; speech height remains driven by the input/output PCM level.
        val phase = if (busy) sin(android.os.SystemClock.uptimeMillis() / 1500.0).toFloat() * .12f else 0f
        val heightFactor = .86f + amplitude * .65f
        val available = cx - r - 48 * unit
        fun wave(t: Float, layer: Int): Float {
            val envelope = sin(t * PI).toFloat().pow(.8f) * (1 - t * .43f)
            return sin(t * 3.8f * PI - .3f + phase + layer * .45f).toFloat() * envelope * (105 - layer * 22) * heightFactor * unit
        }
        for (side in listOf(-1,1)) {
            for (layer in 0..2) {
                val ribbon = Path()
                for (i in 0..120) {
                    val t = i / 120f; val x = cx + side * (r - 7 * unit + available * t)
                    val y = cy + wave(t,layer)
                    if (i==0) ribbon.moveTo(x,y) else ribbon.lineTo(x,y)
                }
                for (i in 120 downTo 0) {
                    val t = i / 120f; val x = cx + side * (r - 7 * unit + available * t)
                    ribbon.lineTo(x, cy - wave(t,layer) * .66f)
                }
                ribbon.close()
                paint.shader=LinearGradient(cx+side*r,cy,cx+side*(r+available),cy,
                    intArrayOf(0x30709BE8,0x506F59B8,0x302B8BB9,0x001D7199),null,Shader.TileMode.CLAMP)
                paint.style=Paint.Style.FILL;paint.alpha=200-layer*35;canvas.drawPath(ribbon,paint)
                paint.shader=LinearGradient(cx+side*r,cy,cx+side*(r+available),cy,intArrayOf(violet,cyan,0x00156B96),null,Shader.TileMode.CLAMP)
                paint.style=Paint.Style.STROKE;paint.strokeWidth=1.2f*unit;paint.alpha=130-layer*25;canvas.drawPath(ribbon,paint)
            }
            for (i in 0..35) {
                val t = i/35f;val x=cx+side*(r+7*unit+available*t)
                val envelope=(sin(t*PI).toFloat().pow(1.4f)*(1-t*.6f)+.025f)
                val length=(3+67*envelope*(.75f+amplitude))*unit
                paint.shader=null;paint.color=if (t<.5f) violet else cyan
                paint.alpha=(205*(1-t*.6f)).toInt();paint.strokeWidth=2.6f*unit
                canvas.drawLine(x,cy-length,x,cy+length,paint)
            }
        }
        val artSize=(r*400/150).toInt().coerceAtLeast(1)
        if (orb?.width != artSize) { orb?.recycle();orb=EvaOrbArtwork.create(artSize) }
        paint.shader=null;paint.alpha=255;paint.style=Paint.Style.FILL
        orb?.let { canvas.drawBitmap(it,cx-it.width/2f,cy-it.height/2f,paint) }
        val statusY=cy+r+38*unit
        textPaint.color=0xFFC7C3CC.toInt();textPaint.textSize=(if (compact) 22 else 25)*unit;textPaint.textAlign=Paint.Align.LEFT
        textPaint.typeface=Typeface.create("sans-serif-light",Typeface.NORMAL)
        val textWidth=(min(w*.8f,1000*unit)).toInt().coerceAtLeast(1)
        val shown=if(transcript.isBlank())message else transcript+"\n"+message
        val layout=StaticLayout.Builder.obtain(shown,0,shown.length,textPaint,textWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(2).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
        canvas.save();canvas.translate(cx-textWidth/2f,statusY);layout.draw(canvas);canvas.restore()
        val dotsY=statusY+70*unit
        for(i in 0..4) {
            val x=cx+(i-2)*24*unit;val radius=(if(i==2)5.4f else 3.5f)*unit
            paint.shader=RadialGradient(x,dotsY,radius*3.4f,intArrayOf(if(i==2)0xA0B9A5FF.toInt() else 0x608D6EEC,Color.TRANSPARENT),null,Shader.TileMode.CLAMP)
            canvas.drawCircle(x,dotsY,radius*3.4f,paint)
            paint.shader=null;paint.color=if(i==2)0xFFD2C6FF.toInt() else 0xFF9D8AE0.toInt();paint.alpha=if(i==2)255 else 185
            canvas.drawCircle(x,dotsY,radius,paint)
        }
        if(!compact) {
            canvas.save();canvas.translate(cx,h*.83f);canvas.scale(1f,.17f)
            paint.alpha=255;paint.shader=RadialGradient(0f,0f,340*unit,intArrayOf(0x553C389A,0x304A248F,Color.TRANSPARENT),null,Shader.TileMode.CLAMP)
            canvas.drawCircle(0f,0f,340*unit,paint);canvas.restore();paint.shader=null
        }
        if(busy&&isShown&&windowVisibility==VISIBLE)postInvalidateDelayed(50)
    }
    override fun onDetachedFromWindow() { orb?.recycle();orb=null;super.onDetachedFromWindow() }
}
