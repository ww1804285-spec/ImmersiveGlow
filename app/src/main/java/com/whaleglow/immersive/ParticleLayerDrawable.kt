package com.whaleglow.immersive

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * 自研粒子层：对齐 OShin 的 startStream / moveStream / stopStream / burst 生命周期。
 *
 * 语义还原：
 *  - startStream(localX, localY)   手指按下 -> 开始沿触摸点"拖拽"一条尾迹流
 *  - moveStream(x, y, vx, vy)      每帧更新流头位置与速度 -> 尾迹按速度方向散开
 *  - stopStream()                  手指抬起 -> 停止发射，剩余粒子自然衰减
 *  - burst()                       点击 -> 一次性向四周爆散一批粒子
 *  - clear()                       全部清空
 *
 * 粒子全部为轻量 struct，用对象池复用，避免每帧 GC。
 */
class ParticleLayerDrawable : Drawable() {

    var lightColor: Int = GlowPalette.DEFAULT_LIGHT
    var headroom: Float = 1f
        set(value) {
            field = value.coerceIn(1f, 8f)
        }

    private class P {
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
        var life = 0f      // 0..1，1 为满寿命
        var decay = 0.02f
        var size = 0f
        var alpha = 1f
    }

    private val pool = ArrayList<P>(128)
    private val active = ArrayList<P>(128)
    private val rnd = Random(System.nanoTime())

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private var shader: RadialGradient? = null
    private var shaderR = 0f
    private var shaderColor = 0

    // 流状态
    private var streaming = false
    private var streamX = 0f
    private var streamY = 0f
    private var streamVX = 0f
    private var streamVY = 0f
    private var streamEnergy = 0f   // 0..1，静止时衰减，移动时充能
    private var emitAcc = 0f

    fun startStream(localX: Float, localY: Float) {
        streaming = true
        streamX = localX
        streamY = localY
        streamVX = 0f
        streamVY = 0f
        streamEnergy = 0f
        emitAcc = 0f
        invalidateSelf()
    }

    fun moveStream(localX: Float, localY: Float, velocityX: Float, velocityY: Float) {
        streamX = localX
        streamY = localY
        streamVX = velocityX
        streamVY = velocityY
        val speed = hypot(velocityX, velocityY)
        // 速度越快 -> 充能越高（上限 1），静止时缓慢泄能
        streamEnergy = (streamEnergy + speed / (speed + 1800f) * 0.35f).coerceIn(0f, 1f)
        emitAcc += (6f + streamEnergy * 18f) * 0.016f
        invalidateSelf()
    }

    fun stopStream() {
        streaming = false
        invalidateSelf()
    }

    /** 点击爆散：16~26 颗，速度随机，寿命 0.5~0.9 秒 */
    fun burst() {
        val n = 16 + rnd.nextInt(10)
        for (i in 0 until n) {
            val ang = (Math.PI * 2 * i / n) + rnd.nextFloat() * 0.5f
            val sp = 220f + rnd.nextFloat() * 380f
            val p = obtain()
            p.x = streamX
            p.y = streamY
            p.vx = (cos(ang) * sp).toFloat()
            p.vy = (sin(ang) * sp).toFloat()
            p.life = 1f
            p.decay = 0.019f + rnd.nextFloat() * 0.012f
            p.size = 2.5f + rnd.nextFloat() * 3.5f
            p.alpha = 1f
            active.add(p)
        }
        invalidateSelf()
    }

    fun clear() {
        for (p in active) pool.add(p)
        active.clear()
        streaming = false
        streamEnergy = 0f
        emitAcc = 0f
        invalidateSelf()
    }

    private fun obtain(): P = if (pool.isNotEmpty()) pool.removeAt(pool.size - 1) else P()

    /** 每帧推进，由 FrameTicker 调用；dt 单位为秒 */
    fun step(dt: Float) {
        if (streaming) {
            // 惯性泄能
            streamEnergy = (streamEnergy - dt * 1.6f).coerceIn(0f, 1f)
            while (emitAcc >= 1f) {
                emitAcc -= 1f
                val p = obtain()
                val back = -0.06f           // 尾迹在流头后方
                p.x = streamX + streamVX * back + (rnd.nextFloat() - 0.5f) * 6f
                p.y = streamY + streamVY * back + (rnd.nextFloat() - 0.5f) * 6f
                p.vx = streamVX * 0.35f + (rnd.nextFloat() - 0.5f) * 90f
                p.vy = streamVY * 0.35f + (rnd.nextFloat() - 0.5f) * 90f
                p.life = 1f
                p.decay = 0.022f
                p.size = 1.8f + rnd.nextFloat() * 2.6f
                p.alpha = 0.55f + streamEnergy * 0.45f
                active.add(p)
            }
        }

        var i = active.size - 1
        while (i >= 0) {
            val p = active[i]
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.vx *= (1f - 2.4f * dt)   // 阻尼
            p.vy *= (1f - 2.4f * dt)
            p.vy += 60f * dt           // 轻微上浮感（是的，鲸鱼娘的光是往上飘的）
            p.life -= p.decay
            if (p.life <= 0f) {
                active.removeAt(i)
                pool.add(p)
            }
            i--
        }
        if (active.isNotEmpty() || streaming) invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (active.isEmpty()) return
        val b = bounds
        val boost = GlowPalette.applyHeadroom(1f, headroom)
        val baseR = 4f * boost
        for (p in active) {
            val a = (p.life * p.alpha * 255f).toInt().coerceIn(0, 255)
            if (a == 0) continue
            val r = p.size * boost * (0.6f + p.life * 0.8f)
            paint.shader = ensureShader(r)
            paint.alpha = a
            canvas.drawCircle(b.left + p.x, b.top + p.y, r, paint)
        }
        paint.shader = null
        if (baseR > 0f) { /* 保留接口，后续可加发光核心 */ }
    }

    private fun ensureShader(r: Float): RadialGradient {
        val c = GlowPalette.withAlpha(lightColor, 0.90f)
        if (shader == null || kotlin.math.abs(shaderR - r) > 0.75f || shaderColor != c) {
            shaderR = r
            shaderColor = c
            shader = RadialGradient(
                0f, 0f, r.coerceAtLeast(0.6f),
                intArrayOf(
                    c,
                    GlowPalette.withAlpha(lightColor, 0.35f),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        return shader!!
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}