package com.whaleglow.immersive

import android.app.Application
import android.content.Context
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * 自研沉浸光感模块入口。
 *
 * 设计原则（完全独立实现，不含任何 OShin 代码与付费逻辑）：
 *   1. 不 hook 具体 SystemUI 类 —— 只 hook 通用 View 层的触摸分发，
 *      按 View 实例挂光效，任何 App、任何 ROM 的按钮/卡片都能吃到光。
 *   2. 用 WeakHashMap 做 View ↔ 单元的映射，杜绝泄漏。
 *   3. 触发条件：带 android:clickable 的 View 收到 ACTION_DOWN。
 *
 * hook 点选择理由：
 *   - View.onTouchEvent(MotionEvent) 是点击态的最上游，几乎所有可点控件都会经过
 *   - 不走 ViewGroup.dispatchTouchEvent 是因为会被大批 View 重复进入，开销大
 */
class GlowHookEntry : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "ImmersiveGlow"
        private const val MIN_EDGE_DP = 18f       // 太小的控件不挂，避免视觉噪音
        private const val MAX_UNITS = 512         // 全局单元上限，防失控
    }

    /** View -> 单元映射，弱引用，View 回收自动释放 */
    private val units = WeakHashMap<View, CapsuleGlowUnitImpl>()

    /** 单元 -> 光晕宿主容器（确保它被添加到父 ViewGroup 里） */
    private val hosts = WeakHashMap<CapsuleGlowUnitImpl, WeakReference<ViewGroup>>()

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "android") return
        try {
            hookTouchEvent(lpparam)
            hookLayout(lpparam)
        } catch (t: Throwable) {
            log("init failed: ${t.message}")
        }
    }

    private fun hookTouchEvent(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedHelpers.findAndHookMethod(
            View::class.java,
            "onTouchEvent",
            MotionEvent::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    val ev = param.args[0] as? MotionEvent ?: return
                    if (!view.isClickable && !view.isLongClickable) return
                    if (view.width < 1 || view.height < 1) return
                    val density = view.resources.displayMetrics.density
                    if (minOf(view.width, view.height) < MIN_EDGE_DP * density) return

                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN ->
                            onDown(view, ev.x, ev.y, density)
                        MotionEvent.ACTION_MOVE ->
                            onMove(view, ev.x, ev.y)
                        MotionEvent.ACTION_UP ->
                            onUp(view, ev, density)
                        MotionEvent.ACTION_CANCEL ->
                            onCancel(view)
                    }
                }
            }
        )
    }

    /** 布局变化时同步光层尺寸；挂在 View.onLayout 之后 */
    private fun hookLayout(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedHelpers.findAndHookMethod(
            View::class.java,
            "onSizeChanged",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    val unit = units[view] ?: return
                    unit.targetWidth = view.width
                    unit.targetHeight = view.height
                    val bg = unit.getBgView() as? FrameLayout ?: return
                    if (bg.layoutParams != null &&
                        (bg.layoutParams.width != view.width || bg.layoutParams.height != view.height)
                    ) {
                        // 不能假设父容器类型：只改宽高，不碰类型
                        val lp = bg.layoutParams
                        lp.width = view.width
                        lp.height = view.height
                        bg.layoutParams = lp
                        bg.requestLayout()
                    }
                    unit.lightLayer.invalidateSelf()
                }
            }
        )
    }

    private fun onDown(view: View, x: Float, y: Float, density: Float) {
        val unit = ensureUnit(view, density) ?: return
        unit.setLightColor(GlowPalette.deriveFromAccent(pickAccent(view)))
        unit.setHeadroom(1.0f)
        tickerOf(unit).onDown(x, y)
    }

    private fun onMove(view: View, x: Float, y: Float) {
        val unit = units[view] ?: return
        tickerOf(unit).onMove(x, y)
    }

    private fun onUp(view: View, ev: MotionEvent, density: Float) {
        val unit = units[view] ?: return
        val isTap = true // 轻点即爆散；长按/拖动在 Energy 衰减里自然收束
        tickerOf(unit).onUp(isTap)
    }

    private fun onCancel(view: View) {
        val unit = units[view] ?: return
        tickerOf(unit).onCancel()
    }

    /** 每个单元挂一个 FrameTicker（弱表里顺手缓存） */
    private val tickers = WeakHashMap<CapsuleGlowUnitImpl, FrameTicker>()

    private fun tickerOf(unit: CapsuleGlowUnitImpl): FrameTicker =
        tickers.getOrPut(unit) {
            FrameTicker(
                unit,
                widthProvider = { unit.targetWidth },
                heightProvider = { unit.targetHeight }
            )
        }

    /** 取宿主 View 的强调色：优先 background 的主色，退化到默认光色 */
    private fun pickAccent(view: View): Int {
        return try {
            val bgd = view.background
            if (bgd == null) {
                GlowPalette.DEFAULT_LIGHT
            } else {
                val w = bgd.intrinsicWidth.coerceAtLeast(1)
                val h = bgd.intrinsicHeight.coerceAtLeast(1)
                val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                val cvs = android.graphics.Canvas(bmp)
                bgd.setBounds(0, 0, w, h)
                bgd.draw(cvs)
                val px = bmp.getPixel(w / 2, h / 2)
                bmp.recycle()
                if (android.graphics.Color.alpha(px) < 8) GlowPalette.DEFAULT_LIGHT
                else GlowPalette.deriveFromAccent(px)
            }
        } catch (t: Throwable) {
            GlowPalette.DEFAULT_LIGHT
        }
    }

    private fun ensureUnit(view: View, density: Float): CapsuleGlowUnitImpl? {
        val existing = units[view]
        if (existing != null) return existing
        if (units.size >= MAX_UNITS) return null
        val ctx: Context = view.context.applicationContext ?: return null
        val unit = CapsuleGlowUnitImpl(ctx)
        unit.targetWidth = view.width
        unit.targetHeight = view.height
        units[view] = unit
        attachHost(view, unit)
        return unit
    }

    /** 把光晕宿主挂到 View 的父容器里，且位于 View 下方不被裁剪 */
    private fun attachHost(view: View, unit: CapsuleGlowUnitImpl) {
        val parent = view.parent as? ViewGroup ?: return
        val bg = unit.getBgView() as? FrameLayout ?: return
        if (bg.parent != null) return
        val lp = FrameLayout.LayoutParams(view.width, view.height)
        lp.leftMargin = view.left
        lp.topMargin = view.top
        // 插到 View 前面，让它绘制在 View 之下
        parent.addView(bg, parent.indexOfChild(view).coerceAtLeast(0), lp)
        bg.elevation = 0f
        hosts[unit] = WeakReference(parent)
    }

    private fun log(msg: String) {
        XposedBridge.log("[$TAG] $msg")
    }
}