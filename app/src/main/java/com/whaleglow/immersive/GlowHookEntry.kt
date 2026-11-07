package com.whaleglow.immersive

import android.app.Application
import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
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

    /** 防重入标记：自己的绘制流程里若再次触发 draw() 就直接跳过，杜绝死循环 */
    private val inGlowDraw = ThreadLocal<Boolean>()

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "android") return
        try {
            hookTouchEvent(lpparam)
            hookLayout(lpparam)
            hookDraw(lpparam)
        } catch (t: Throwable) {
            log("init failed: ${t.message}")
        }
    }

    /**
     * 核心 hook：View.draw(Canvas)
     *
     * 为什么选这里：canvas 坐标系已是 View 本地坐标（0,0 = View 左上角），
     * 光层 bounds 直接用 (0,0,width,height)，天然与 View 严丝合缝，不可能错位。
     *
     * 绘制顺序：
     *   afterHookedMethod -> 在 View 自身内容画完之后叠加光层
     *   （光晕向外溢出由 drawInView 里的 translate + 外扩 bounds 完成）
     */
    private fun hookDraw(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedHelpers.findAndHookMethod(
            View::class.java,
            "draw",
            Canvas::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    val canvas = param.args[0] as? Canvas ?: return
                    if (inGlowDraw.get() == true) return

                    val unit = units[view] ?: return
                    if (!unit.isActive()) return
                    val w = view.width
                    val h = view.height
                    if (w <= 0 || h <= 0) return

                    inGlowDraw.set(true)
                    try {
                        unit.drawInView(canvas, w, h)
                    } catch (t: Throwable) {
                        log("draw error: ${t.message}")
                    } finally {
                        inGlowDraw.set(false)
                    }
                }
            }
        )
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
        val unit = CapsuleGlowUnitImpl(view)
        unit.targetWidth = view.width
        unit.targetHeight = view.height
        units[view] = unit
        return unit
    }

    private fun log(msg: String) {
        XposedBridge.log("[$TAG] $msg")
    }
}