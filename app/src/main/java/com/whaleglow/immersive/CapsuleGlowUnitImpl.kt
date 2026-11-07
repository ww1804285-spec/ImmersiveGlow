package com.whaleglow.immersive

import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.view.View

/**
 * 一个"胶囊光效单元"：给任意目标 View 套一层动态光。
 *
 * v2 架构（重写）：不再创建任何子 View，也不往父容器 addView。
 * 光层直接寄生在目标 View 的 draw(Canvas) 里，坐标系天然跟随 View 自身，
 * 彻底消除「大圆角矩形糊在 SystemUI 上」「边框没对齐、大小不同」两类错位。
 *
 * 对齐 OShin 协议语义（但实现方式不同）：
 *   updateFrame(aw, ah, cornerPx, alpha, glarePx)
 *        aw/ah       目标 View 实际宽高（= View.width / View.height）
 *        cornerPx    圆角半径
 *        alpha       整体透明度（呼吸）
 *        glarePx     高光斑直径
 */
class CapsuleGlowUnitImpl(val hostView: View) {

    companion object {
        /** 光晕外扩的额外留白比例（相对 View 短边），用于给 halo 溢出留空间 */
        private const val OUTSET_RATIO = 0.16f
    }

    val lightLayer = LightLayerDrawable()
    val particleLayer = ParticleLayerDrawable()

    // 当前帧参数
    private var curAlpha = 1f
    private var curCorner = 0f
    private var curGlare = 0f

    /** 光心偏移（相对 View 中心，px），由 FrameTicker 跟随手指写入 */
    var focusX = 0f
    var focusY = 0f
    var depth = 0f

    /** 目标 View 的可用尺寸，供 FrameTicker 读取 */
    var targetWidth: Int = 0
    var targetHeight: Int = 0

    private val tmpRect = RectF()

    init {
        lightLayer.lightColor = GlowPalette.DEFAULT_LIGHT
        particleLayer.lightColor = GlowPalette.DEFAULT_LIGHT
    }

    /**
     * 每帧刷新：把五参翻译成光层和粒子层的内部状态。
     * 注意：这里不碰 bounds，bounds 由 drawInView() 每帧现场写入。
     */
    fun updateFrame(aw: Int, ah: Int, cornerPx: Float, alpha: Float, glarePx: Float) {
        curAlpha = alpha.coerceIn(0f, 1f)
        curCorner = cornerPx
        curGlare = glarePx
        targetWidth = aw
        targetHeight = ah

        lightLayer.update(
            focusX, focusY, depth,
            GlowPalette.CONTENT_BASE * curAlpha,
            GlowPalette.BORDER_BASE * curAlpha
        )
        lightLayer.frameAlpha = curAlpha
    }

    /**
     * 在目标 View 自己的 canvas 上绘制光效。
     *
     * 调用时机：View.draw() hook 内，canvas 坐标系已经是「View 本地坐标」，
     * 即 (0,0) = View 左上角。因此：
     *   · bounds 直接用 Rect(0, 0, w, h) —— 永远不错位
     *   · 光晕外扩靠 translate(-outset) + 放大 bounds 实现溢出，不改 View 布局
     */
    fun drawInView(canvas: Canvas, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        if (curAlpha <= 0.001f) return

        val outset = (minOf(w, h) * OUTSET_RATIO).toInt().coerceIn(2, 40)

        // ① 光晕层：外扩画，允许溢出
        val sc = canvas.save()
        canvas.translate(-outset.toFloat(), -outset.toFloat())
        lightLayer.bounds = Rect(0, 0, w + outset * 2, h + outset * 2)
        lightLayer.draw(canvas)
        canvas.restoreToCount(sc)

        // ② 描边层：严格贴合 View 真实边界，绝不偏移
        val sc2 = canvas.save()
        lightLayer.bounds = Rect(0, 0, w, h)
        lightLayer.drawBorderOnly(canvas)
        canvas.restoreToCount(sc2)

        // ③ 粒子层：贴合 View
        val sc3 = canvas.save()
        particleLayer.bounds = Rect(0, 0, w, h)
        particleLayer.draw(canvas)
        canvas.restoreToCount(sc3)
    }

    fun setLightColor(color: Int) {
        lightLayer.lightColor = color
        particleLayer.lightColor = color
    }

    fun setHeadroom(h: Float) {
        lightLayer.headroom = h
        particleLayer.headroom = h
    }

    /** 供 FrameTicker 判断是否需要继续 tick */
    fun isActive(): Boolean = curAlpha > 0.001f
}