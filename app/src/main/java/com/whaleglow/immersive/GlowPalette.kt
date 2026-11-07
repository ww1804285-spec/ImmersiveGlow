package com.whaleglow.immersive

import android.graphics.Color

/**
 * 调色板与视觉常量。
 * 全部为自研数值，按"呼吸光场"的视觉直觉调的，不是抄来的配方。
 */
object GlowPalette {

    // ---------- 光色 ----------
    /** 默认主光色（柔白偏冷，接近系统高光的色温） */
    const val DEFAULT_LIGHT = 0xFFF2F6FF.toInt()

    // ---------- 尺寸 ----------
    /** 光圈相对胶囊高度的外扩比（1.0 = 不外扩） */
    const val HALO_SCALE = 1.35f

    /** 高光斑尺寸相对胶囊高度的比例 */
    const val GLARE_RATIO = 0.45f

    // ---------- 强度 ----------
    /** 内容区基础光强 */
    const val CONTENT_BASE = 0.55f

    /** 边框光强（描边那一圈的亮度） */
    const val BORDER_BASE = 0.85f

    /** 呼吸动画的振幅 */
    const val BREATH_AMPLITUDE = 0.08f

    /** 呼吸动画周期（毫秒） */
    const val BREATH_PERIOD_MS = 2600f

    // ---------- 颜色合成辅助 ----------

    /** 按 alpha 缩放颜色透明度 */
    fun withAlpha(color: Int, alpha: Float): Int {
        val a = (Color.alpha(color) * alpha.coerceIn(0f, 1f)).toInt()
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    /**
     * HDR headroom 映射：headroom 越大说明屏幕能出的亮度越高，
     * 我们据此把光强往上抬一点，避免 HDR 屏上看着"发灰"。
     */
    fun applyHeadroom(base: Float, headroom: Float): Float {
        val h = headroom.coerceIn(1f, 4f)
        // 1.0 -> 1.0x, 4.0 -> 1.45x，平缓抬升
        return base * (1f + (h - 1f) * 0.15f)
    }

    /** 从通知图标的 accent 色推导出协调的光色（简单 HSL 提亮） */
    fun deriveFromAccent(accent: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(accent, hsv)
        // 压低饱和度、拉高亮度，让它变成"光"而不是"色块"
        hsv[1] = (hsv[1] * 0.45f).coerceAtMost(0.6f)
        hsv[2] = (hsv[2] * 0.25f + 0.85f).coerceAtMost(1f)
        return Color.HSVToColor(hsv)
    }
}
