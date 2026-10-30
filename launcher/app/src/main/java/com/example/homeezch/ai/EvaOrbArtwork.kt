package com.example.homeezch.ai

import android.graphics.*

/** Cached, software-rendered glass artwork; no JPEG/GIF and no per-frame blur work. */
internal object EvaOrbArtwork {
    fun create(size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        c.scale(size / 400f, size / 400f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val cx = 200f; val cy = 200f; val r = 150f
        fun radial(x: Float, y: Float, radius: Float, colors: IntArray, stops: FloatArray? = null) {
            p.shader = RadialGradient(x, y, radius, colors, stops, Shader.TileMode.CLAMP)
        }
        radial(cx,cy,185f,intArrayOf(0x00000000,0x00000000,0x304745DD,0x163B4CC4,0x00000000),floatArrayOf(0f,.69f,.81f,.9f,1f))
        c.drawCircle(cx,cy,185f,p)
        radial(cx,cy,151f,intArrayOf(0xFF02030C.toInt(),0xFF070416.toInt(),0xFF14102C.toInt(),0xFF4C3A88.toInt(),0xFF9586ED.toInt()),floatArrayOf(0f,.67f,.85f,.96f,1f))
        c.drawCircle(cx,cy,151f,p)
        c.save(); c.clipPath(Path().apply { addCircle(cx,cy,149f,Path.Direction.CW) })
        radial(238f,305f,115f,intArrayOf(0xC0473295.toInt(),0x70362879,0x00000000),floatArrayOf(0f,.48f,1f))
        c.drawCircle(238f,305f,115f,p)
        radial(108f,176f,71f,intArrayOf(0x9046DCFF.toInt(),0x304072E1,0x00000000),null)
        c.drawCircle(108f,176f,71f,p)
        radial(206f,80f,115f,intArrayOf(0x70433DC3,0x15252C80,0x00000000),null)
        c.drawCircle(206f,80f,115f,p)
        c.restore()
        val rim = SweepGradient(cx,cy,intArrayOf(0xFF947CF1.toInt(),0xFFB793FA.toInt(),0xFF83DAFF.toInt(),0xFF6CA4E7.toInt(),0xFF9CA1FF.toInt(),0xFFD4A3FF.toInt(),0xFF947CF1.toInt()),null)
        p.shader = rim; p.style = Paint.Style.STROKE
        p.strokeWidth=11f; p.alpha=100; p.maskFilter=BlurMaskFilter(6f,BlurMaskFilter.Blur.NORMAL); c.drawCircle(cx,cy,148.5f,p)
        p.maskFilter=null; p.alpha=220; p.strokeWidth=2.4f; c.drawCircle(cx,cy,149.7f,p)
        p.alpha=85;p.strokeWidth=6f;c.drawCircle(cx,cy,146.5f,p)
        p.alpha=255;p.style=Paint.Style.FILL
        // Curved glass highlights, deliberately asymmetric as in the reference.
        var path=Path().apply {
            moveTo(115f,91f); cubicTo(153f,43f,238f,38f,275f,76f)
            cubicTo(236f,49f,161f,49f,115f,91f); close()
        }
        p.shader=LinearGradient(135f,60f,270f,100f,intArrayOf(0xD0D6D2FF.toInt(),0x805F93FF.toInt(),0x003B5AF0),null,Shader.TileMode.CLAMP)
        p.maskFilter=BlurMaskFilter(1.4f,BlurMaskFilter.Blur.NORMAL);c.drawPath(path,p);p.maskFilter=null
        path=Path().apply { moveTo(277f,82f);cubicTo(319f,113f,339f,175f,338f,208f);cubicTo(347f,157f,322f,107f,277f,82f);close() }
        p.shader=LinearGradient(280f,95f,344f,190f,0x80DCAEFF.toInt(),0x00355CEC,Shader.TileMode.CLAMP);c.drawPath(path,p)
        p.style=Paint.Style.STROKE;p.strokeWidth=1.1f;p.shader=LinearGradient(80f,330f,300f,320f,intArrayOf(0x002A77DF,0xC0A7EEFF.toInt(),0x605E83E4),null,Shader.TileMode.CLAMP)
        c.drawArc(RectF(51f,51f,349f,349f),35f,128f,false,p)
        p.style=Paint.Style.FILL
        val petals=listOf(
            Path().apply { moveTo(124f,174f);cubicTo(116f,211f,147f,242f,190f,232f);cubicTo(181f,198f,155f,169f,124f,174f);close() },
            Path().apply { moveTo(276f,174f);cubicTo(284f,211f,253f,242f,210f,232f);cubicTo(219f,198f,245f,169f,276f,174f);close() },
            Path().apply { moveTo(200f,116f);cubicTo(166f,133f,161f,185f,200f,225f);cubicTo(239f,185f,234f,133f,200f,116f);close() }
        )
        for ((index,leaf) in petals.withIndex()) {
            p.shader=when(index) {
                0 -> LinearGradient(123f,175f,187f,237f,intArrayOf(0xFF75E8FF.toInt(),0xFF8EB4FF.toInt(),0xFFD3A5FF.toInt()),null,Shader.TileMode.CLAMP)
                1 -> LinearGradient(271f,170f,218f,235f,intArrayOf(0xFF9674F5.toInt(),0xFFBA8FF8.toInt(),0xFFF3B8FF.toInt()),null,Shader.TileMode.CLAMP)
                else -> LinearGradient(177f,126f,220f,222f,intArrayOf(0xFF57DFFF.toInt(),0xFF7384F2.toInt(),0xFFE8B0F4.toInt()),null,Shader.TileMode.CLAMP)
            }
            p.alpha=110;p.maskFilter=BlurMaskFilter(8f,BlurMaskFilter.Blur.NORMAL);c.drawPath(leaf,p)
            p.alpha=255;p.maskFilter=null;c.drawPath(leaf,p)
        }
        p.shader=null;p.color=0xFFD0BAF0.toInt();p.textSize=36f;p.textAlign=Paint.Align.CENTER;p.typeface=Typeface.create("sans-serif-light",Typeface.NORMAL)
        p.setShadowLayer(3f,0f,0f,0x707156C5);c.drawText("Ева",200f,289f,p);p.clearShadowLayer()
        return bitmap
    }
}
