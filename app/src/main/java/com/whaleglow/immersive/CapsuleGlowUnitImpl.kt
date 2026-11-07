package com.whaleglow.immersive

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout

/**
 * 一个"胶囊光效单元"：给任意目标 View 套一层前景光。
 *
 * 对齐 OShin 协议语义：
 *   getBgView()                  背景层（光晕在外侧扩散，目标 View 之下）
 *   getFgView()                  前景层（描边高光，目标 View 之上）
 *   needsRelayout(left,top,vw,vh)  尺寸/位置变化时返回 true -> 需要重新布局
 *   updateFrame(aw, ah, cornerPx, alpha, glarePx)
 *        aw/ah       可用宽高（不含外扩）
 *        cornerPx    圆角半径
 *        alpha       整体透明度（呼吸）
 *        glarePx     高光斑直径
 */
class CapsuleGlowUnitImpl(
    context: Context,
    private val shape: LightShapeImpl = LightShapeImpl.RoundRectShape()
) {

    companion object {
        /** 光晕向外扩张的额外留白，给 halo 留绘制空间 */
        private const val OUTSET_DP = 10f
    }

    private val density = context.resources.displayMetrics.density
    private val outset = (OUTSET_DP * density).toInt()

    val lightLayer = LightLayerDrawable()
    val particleLayer = ParticleLayerDrawable()

    private val bg = object : FrameLayout(context) {
        init {
            setWillNotDraw(false)
            clipChildren = false
            clipToPadding = false
            background = null
            elevation = 0f
        }

        override fun onDraw(canvas: Canvas) {
            lightLayer.bounds = 0, 0, width, height
            lightLayer.draw(canvas)
        }

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            particleLayer.bounds = 0, 0, width, height
            particleLayer.draw(canvas)
        }
    }

    private val fg = View(context).apply {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
    }

    // 缓存上一次的布局参数，用于 needsRelayout 判断
    private var lastLeft = Int.MIN_VALUE
    private var lastTop = Int.MIN_VALUE
    private var lastW = -1
    private var lastH = -1

    // 当前帧参数
    private var curAlpha = 1f
    private var curCorner = 0f
    private var curGlare = 0f

    private val tmpRect = RectF()
    private val tmpPath = Path()

    init {
        lightLayer.lightColor = GlowPalette.DEFAULT_LIGHT
        particleLayer.lightColor = GlowPalette.DEFAULT_LIGHT
    }

    fun getBgView(): View = bg

    fun getFgView(): View = fg

    /** 尺寸/位置变了就需要重排（同时把 shader 缓存打脏） */
    fun needsRelayout(left: Int, top: Int, viewWidth: Int, viewHeight: Int): Boolean {
        val changed = left != lastLeft || top != lastTop ||
                viewWidth != lastW || viewHeight != lastH
        if (changed) {
            lastLeft = left
            lastTop = top
            lastW = viewWidth
            lastH = viewHeight
        }
        return changed
    }

    /** 每帧刷新：这里负责把五参翻译成光层和粒子层的内部状态 */
    fun updateFrame(aw: Int, ah: Int, cornerPx: Float, alpha: Float, glarePx: Float) {
        curAlpha = alpha.coerceIn(0f, 1f)
        curCorner = cornerPx
        curGlare = glarePx

        // 光心默认落在可用区域中心；真正跟随手指的偏移由 FrameTicker 通过 focus 传进来
        lightLayer.bounds = 0, 0, aw + outset * 2, ah + outset * 2
        lightLayer.update(
            focusX, focusY, depth,
            GlowPalette.CONTENT_BASE * curAlpha,
            GlowPalette.BORDER_BASE * curAlpha
        )
        lightLayer.frameAlpha = curAlpha

        particleLayer.bounds = 0, 0, aw + outset * 2, ah + outset * 2

        applyShape(aw, ah)
        bg.invalidate()
    }

    var focusX = 0f
    var focusY = 0f
    var depth = 0f

    /** 目标 View 的可用尺寸，供 FrameTicker 读取 */
    var targetWidth: Int = 0
    var targetHeight: Int = 0

    private fun applyShape(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        tmpRect.set(0f, 0f, w.toFloat(), h.toFloat())
        val r = shape.resolve(tmpRect)
        val clipPath = shape.outline() ?: tmpPath.apply {
            reset()
            addRoundRect(tmpRect, r, r, Path.Direction.CW)
        }
        bg.clipToOutline = true
        bg.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(
                    Rect(0, 0, w, h),
                    if (r > 0f) r else cornerOrFallback(h)
                )
            }
        }
        bg.invalidateOutline()
    }

    private fun cornerOrFallback(h: Int): Float =
        if (curCorner > 0f) curCorner else h / 2f

    fun setLightColor(color: Int) {
        lightLayer.lightColor = color
        particleLayer.lightColor = color
        bg.invalidate()
    }

    fun setHeadroom(h: Float) {
        lightLayer.headroom = h
        particleLayer.headroom = h
        bg.invalidate()
    }
}