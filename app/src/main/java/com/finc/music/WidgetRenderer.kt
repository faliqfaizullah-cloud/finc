package com.finc.music

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader

/** Draws the widget background (RemoteViews cannot blur, so the blur is baked into a bitmap). */
object WidgetRenderer {
    class Look(val bg: Bitmap, val text: Int, val sub: Int, val lightText: Boolean)

    private fun argb(a: Int, rgb: Int) = (a.coerceIn(0, 255) shl 24) or (rgb and 0xFFFFFF)
    private fun withAlpha(c: Int, a: Float) = argb((a * 255).toInt(), c)
    private fun lum(c: Int) = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f
    private fun blend(a: Int, b: Int, t: Float): Int {
        fun m(x: Int, y: Int) = (x + (y - x) * t).toInt()
        return Color.rgb(m(Color.red(a), Color.red(b)), m(Color.green(a), Color.green(b)), m(Color.blue(a), Color.blue(b)))
    }
    private fun darken(c: Int, f: Float) = Color.rgb((Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())
    private fun boost(c: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[1] = (hsv[1] * 1.15f).coerceAtMost(1f)
        hsv[2] = hsv[2].coerceIn(0.4f, 0.95f)
        return Color.HSVToColor(hsv)
    }

    private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(px.size)
        val d = 2 * r + 1
        for (y in 0 until h) for (x in 0 until w) {
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (k in -r..r) {
                val c = px[y * w + (x + k).coerceIn(0, w - 1)]
                a += c ushr 24; rr += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255
            }
            tmp[y * w + x] = ((a / d) shl 24) or ((rr / d) shl 16) or ((g / d) shl 8) or (b / d)
        }
        for (y in 0 until h) for (x in 0 until w) {
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (k in -r..r) {
                val c = tmp[(y + k).coerceIn(0, h - 1) * w + x]
                a += c ushr 24; rr += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255
            }
            px[y * w + x] = ((a / d) shl 24) or ((rr / d) shl 16) or ((g / d) shl 8) or (b / d)
        }
    }

    /** Shrink the art, blur it, and stretch it back to w x h: a cheap, smooth blur on every Android version. */
    private fun blurSmall(src: Bitmap, w: Int, h: Int, sw: Int, passes: Int): Bitmap {
        val soft = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
        val aspect = w.toFloat() / h
        var cw = soft.width
        var ch = (cw / aspect).toInt()
        if (ch > soft.height) { ch = soft.height; cw = (ch * aspect).toInt() }
        cw = cw.coerceAtLeast(1); ch = ch.coerceAtLeast(1)
        var cur = Bitmap.createBitmap(soft, (soft.width - cw) / 2, (soft.height - ch) / 2, cw, ch)
        val sh = (sw / aspect).toInt().coerceAtLeast(8)
        while (cur.width > sw * 2) cur = Bitmap.createScaledBitmap(cur, cur.width / 2, (cur.height / 2).coerceAtLeast(1), true)
        val small = Bitmap.createScaledBitmap(cur, sw, sh, true).copy(Bitmap.Config.ARGB_8888, true)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        repeat(passes) { boxBlur(px, sw, sh, 2) }
        small.setPixels(px, 0, sw, 0, 0, sw, sh)
        return Bitmap.createScaledBitmap(small, w, h, true)
    }

    private fun meanColor(b: Bitmap): Int {
        val s = Bitmap.createScaledBitmap(b, 8, 8, true)
        var r = 0; var g = 0; var bl = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            val c = s.getPixel(x, y); r += Color.red(c); g += Color.green(c); bl += Color.blue(c)
        }
        return Color.rgb(r / 64, g / 64, bl / 64)
    }

    fun render(art: Bitmap?, s: WidgetStyle, w: Int, h: Int, radius: Float): Look {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val wf = w.toFloat(); val hf = h.toFloat()
        val rect = RectF(0f, 0f, wf, hf)
        val sw = maxOf(2f, w * 0.009f)

        if (s.theme == WidgetTheme.ORIGINAL) {
            val bg = if (s.solidWhite) Color.WHITE else Color.BLACK
            canvas.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg })
            canvas.drawRoundRect(RectF(sw / 2, sw / 2, wf - sw / 2, hf - sw / 2), radius - sw / 2, radius - sw / 2,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = sw
                    color = if (s.solidWhite) 0x22000000 else 0x33FFFFFF
                })
            val text = if (s.solidWhite) 0xFF111111.toInt() else Color.WHITE
            return Look(out, text, withAlpha(text, 0.65f), !s.solidWhite)
        }

        val src = art ?: placeholderBitmap()
        val layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val lc = Canvas(layer)
        val heavy = blurSmall(src, w, h, 40, 3)
        val mean = meanColor(heavy)
        val custom = s.tint != 0
        val tint = if (custom) s.tint else boost(mean)
        val sat = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.25f) }) }
        val overall: Float
        val textBg: Int

        if (s.theme == WidgetTheme.IOS26) {
            lc.drawBitmap(heavy, 0f, 0f, sat)
            val wash = if (custom) 0.5f else 0.18f
            lc.drawColor(withAlpha(tint, wash))
            // liquid-glass specular light from the top-left, soft shade at the bottom-right
            lc.drawRect(rect, Paint().apply {
                shader = LinearGradient(0f, 0f, wf * 0.7f, hf * 0.7f, intArrayOf(argb(100, 0xFFFFFF), argb(0, 0xFFFFFF)), null, Shader.TileMode.CLAMP)
            })
            lc.drawRect(rect, Paint().apply {
                shader = LinearGradient(wf * 0.4f, hf * 0.4f, wf, hf, intArrayOf(argb(0, 0), argb(45, 0)), null, Shader.TileMode.CLAMP)
            })
            overall = 0.35f + 0.65f * s.opacity
            textBg = blend(mean, tint, wash)
        } else {
            // gradient blur: lightly blurred on top, progressively blurrier, fading into a colour at the bottom
            lc.drawBitmap(blurSmall(src, w, h, 120, 1), 0f, 0f, sat)
            val hl = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val hc = Canvas(hl)
            hc.drawBitmap(heavy, 0f, 0f, sat)
            hc.drawRect(rect, Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, hf, intArrayOf(argb(0, 0), argb(255, 0)), floatArrayOf(0.1f, 0.75f), Shader.TileMode.CLAMP)
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            })
            lc.drawBitmap(hl, 0f, 0f, null)
            val fade = if (custom) tint else darken(tint, 0.55f)
            val fa = 0.45f + 0.5f * s.opacity
            lc.drawRect(rect, Paint().apply {
                shader = LinearGradient(0f, hf * 0.3f, 0f, hf, intArrayOf(argb(0, fade), withAlpha(fade, fa)), null, Shader.TileMode.CLAMP)
            })
            lc.drawRect(rect, Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, hf * 0.35f, intArrayOf(argb(70, 0xFFFFFF), argb(0, 0xFFFFFF)), null, Shader.TileMode.CLAMP)
            })
            overall = 0.5f + 0.5f * s.opacity
            textBg = blend(mean, fade, fa)
        }

        canvas.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(layer, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            alpha = (overall.coerceIn(0f, 1f) * 255).toInt()
        })
        // glass rim light
        canvas.drawRoundRect(RectF(sw / 2, sw / 2, wf - sw / 2, hf - sw / 2), radius - sw / 2, radius - sw / 2,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = sw
                shader = LinearGradient(0f, 0f, wf, hf, intArrayOf(argb(235, 0xFFFFFF), argb(25, 0xFFFFFF), argb(140, 0xFFFFFF)),
                    floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            })

        val a = overall.coerceIn(0f, 1f)
        val dark = lum(textBg) * a + 0.5f * (1f - a) > 0.62f
        val text = if (dark) 0xFF111111.toInt() else Color.WHITE
        return Look(out, text, withAlpha(text, 0.7f), !dark)
    }
}
