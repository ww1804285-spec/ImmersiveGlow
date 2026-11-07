package com.whaleglow.immersive

import android.os.SystemClock
import android.view.Choreographer
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 帧驱动：把 View 的触摸/布局状态翻译成 CapsuleGlowUnit.updateFrame 的五个参数。
 *
 * 五参来源：
 *   aw/ah       = 单元当前可用宽高
 *   cornerPx    = 形状解析出的圆角半径
 *   alpha       = 呼吸正弦 + 触摸增益，0..1
 *   glarePx     = 高光斑直径，随景深 z 与速度变化
 *   focus/depth = 光心偏移与扩散，由帧内持续追手（带惯性插值）
 *
 * 只在有活动（呼吸 + 触摸惯性未衰减）时持续调度帧回调，
 * 静止后自动停机，避免耗电。
 */
class FrameTicker(
    private val unit: CapsuleGlowUnitImpl,
    private val widthProvider: () -> Int,
    private val heightProvider: () -> Int
) : Choreographer.FrameCallback {

    companion object {
        /** 触摸目标相对于单元中心的最大偏移比例 */
        private const val FOCUS_WARP = 0.32f
        /** 惯性追手插值系数（每帧） */
        private const val FOLLOW = 0.22f
        private const val IDLE_DEPTH = 0.0f
        private const val PEAK_DEPTH = 1.0f
    }

    private var running = false
    private var lastFrameNs = 0L
    private var startNs = SystemClock.uptimeMillis() * 1_000_000L

    // 触摸目标（归一化 -1..1）
    private var targetNx = 0f
    private var targetNy = 0f
    private var curNx = 0f
    private var curNy = 0f

    // 能量：手指在按着 -> 1，抬起后自然回落到 0
    private var energy = 0f
    private var touching = false

    // 速度估计，用于 glare 与粒子层
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastTouchNs = 0L
    private var curVelX = 0f
    private var curVelY = 0f

    fun start() {
        if (running) return
        running = true
        lastFrameNs = 0L
        startNs = SystemClock.uptimeMillis() * 1_000_000L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    /** 手指按下 */
    fun onDown(localX: Float, localY: Float) {
        touching = true
        energy = 1f
        lastTouchX = localX
        lastTouchY = localY
        lastTouchNs = SystemClock.uptimeMillis()
        updateTarget(localX, localY)
        unit.particleLayer.startStream(localX, localY)
        start()
    }

    /** 手指移动 */
    fun onMove(localX: Float, localY: Float) {
        if (!touching) onDown(localX, localY)
        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastTouchNs).coerceIn(1, 64)).toFloat() / 1000f
        val vx = (localX - lastTouchX) / dt
        val vy = (localY - lastTouchY) / dt
        // 速度低通，避免抖动导致粒子炸毛
        curVelX = curVelX * 0.45f + vx * 0.55f
        curVelY = curVelY * 0.45f + vy * 0.55f
        lastTouchX = localX
        lastTouchY = localY
        lastTouchNs = now
        energy = 1f
        updateTarget(localX, localY)
        unit.particleLayer.moveStream(localX, localY, curVelX, curVelY)
        start()
    }

    /** 手指抬起 */
    fun onUp(isTap: Boolean) {
        touching = false
        unit.particleLayer.stopStream()
        if (isTap) unit.particleLayer.burst()
        start()
    }

    /** 手指取消 / View 回收 */
    fun onCancel() {
        touching = false
        targetNx = 0f
        targetNy = 0f
        unit.particleLayer.stopStream()
        start()
    }

    private fun updateTarget(localX: Float, localY: Float) {
        val w = widthProvider().coerceAtLeast(1)
        val h = heightProvider().coerceAtLeast(1)
        val nx = ((localX / w) * 2f - 1f).coerceIn(-1f, 1f)
        val ny = ((localY / h) * 2f - 1f).coerceIn(-1f, 1f)
        targetNx = nx
        targetNy = ny
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return

        val dt: Float
        if (lastFrameNs == 0L) {
            dt = 1f / 60f
        } else {
            dt = ((frameTimeNanos - lastFrameNs) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.05f)
        }
        lastFrameNs = frameTimeNanos

        // ① 惯性追手
        curNx += (targetNx - curNx) * FOLLOW
        curNy += (targetNy - curNy) * FOLLOW

        // ② 能量衰减
        if (!touching) {
            energy = (energy - dt * 2.2f).coerceIn(0f, 1f)
        }

        val w = widthProvider().coerceAtLeast(1)
        val h = heightProvider().coerceAtLeast(1)

        // ③ 直径（取短边） + 呼吸
        val minSide = minOf(w, h).toFloat()
        val bg = GlowPalette.BREATH_AMPLITUDE
        val period = GlowPalette.BREATH_PERIOD_MS / 1000f
        val breathe = 1f + bg * sin((frameTimeNanos - startNs) / 1e9 / period * (2.0 * Math.PI)).toFloat()

        // ④ 触摸增益：按下时整体更亮更"厚"
        val touchBoost = 1f + energy * 0.35f
        val alpha = (breathe * touchBoost * (0.75f + energy * 0.25f)).coerceIn(0f, 1f)

        // ⑤ 景深：静止 IDLE，按下 PEAK，随能量衰减回落
        val depth = IDLE_DEPTH + (PEAK_DEPTH - IDLE_DEPTH) * energy

        // ⑥ 光心偏移（相对可用区域中心）
        val focusX = curNx * minSide * FOCUS_WARP
        val focusY = curNy * minSide * FOCUS_WARP
        unit.focusX = focusX
        unit.focusY = focusY
        unit.depth = depth

        // ⑦ 高光斑直径：基础比例 × 景深 × 速度
        val speed = hypot(curVelX, curVelY)
        val speedGain = (speed / (speed + 2400f)).coerceIn(0f, 1f)
        val glare = minSide * GlowPalette.GLARE_RATIO * (0.6f + depth * 0.5f + speedGain * 0.4f)

        // ⑧ 圆角：胶囊取短边一半，否则 12dp
        val cornerPx = minSide / 2f

        // ⑨ 推给单元
        unit.updateFrame(w, h, cornerPx, alpha, glare)

        // ⑩ 粒子步进
        unit.particleLayer.step(dt)

        // ⑪ 判断是否继续
        val active = touching || energy > 0.02f ||
                curVelX * curVelX + curVelY * curVelY > 4f ||
                kotlin.math.abs(targetNx - curNx) > 0.002f ||
                kotlin.math.abs(targetNy - curNy) > 0.002f

        if (active) {
            Choreographer.getInstance().postFrameCallback(this)
        } else {
            // 静止后减速停帧，但呼吸仍然需要（低频）——只在有触摸痕迹后彻底停
            curVelX = 0f
            curVelY = 0f
            running = false
        }
    }

    /** 供外部读取当前速度（调试用） */
    fun velocity(): FloatArray = floatArrayOf(curVelX, curVelY)

    @Suppress("unused")
    private fun dirOf(x: Float, y: Float): Float = Math.atan2(y.toDouble(), x.toDouble()).toFloat()
}