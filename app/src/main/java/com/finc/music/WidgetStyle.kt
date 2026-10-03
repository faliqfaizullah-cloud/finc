package com.finc.music

import android.content.Context

object WidgetTheme {
    const val ORIGINAL = 0   // solid white or black
    const val IOS26 = 1      // liquid-glass look
    const val GRADIENT = 2   // blur that fades into a colour
}

data class WidgetStyle(
    val theme: Int = WidgetTheme.IOS26,
    val solidWhite: Boolean = true,
    val tint: Int = 0,          // 0 = automatic colour taken from the album art
    val opacity: Float = 0.7f   // blur opacity, 0.2 .. 1.0
) {
    fun save(ctx: Context) {
        ctx.getSharedPreferences("finc_widget_style", Context.MODE_PRIVATE).edit()
            .putInt("theme", theme).putBoolean("white", solidWhite).putInt("tint", tint).putFloat("opacity", opacity).apply()
    }

    companion object {
        fun load(ctx: Context): WidgetStyle {
            val p = ctx.getSharedPreferences("finc_widget_style", Context.MODE_PRIVATE)
            return WidgetStyle(p.getInt("theme", WidgetTheme.IOS26), p.getBoolean("white", true),
                p.getInt("tint", 0), p.getFloat("opacity", 0.7f))
        }
    }
}
