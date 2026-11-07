package com.whaleglow.immersive

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * 主光层：跟随目标视图画一个动态径向光场。
 *
 * 自研实现，语义对齐 OShin 的 ImmersiveLightLayer：
 *   update(x, y, z, content, border)
 *
 * 参数含义（我们自己的解释，非抄配方）：
 *   x, y    —— 光心偏移（相对视图中心，单位 px）
 *   z       —— 景深/扩散系数，0=贴合，1=散开
 *   content —— 内容区光强 0..1
 *   border  —— 边缘光强 0..1
 */
class LightLayerDrawable(
    private val shape: LightShapeImpl = RoundRectShape()
) : Drawable() {
    var lightColor: Int = GlowPalette.DEFAULT_LIGHT
    var headroom: Float = 1f

    /** 帧级别整体透明度（0..1），由 CapsuleGlowUnit 每帧写入 */
    var frameAlpha: Float = 1f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (v != field) {
                field = v
                invalidateSelf()
            }
        }


    private var cx = 0f
    private var cy = 0f
    private var depth = 0f
    private var contentIntensity = 0f
    private var borderIntensity = 0f
    private var dirty = true

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val boundsRect = RectF()
    private val tmpRect = RectF()
    private var haloShader: RadialGradient? = null
    private var lastShaderKey = 0

    fun update(x: Float, y: Float, z: Float, content: Float, border: Float) {
        if (
            x == cx && y == cy && z == depth &&
            content == contentIntensity && border == borderIntensity
        ) return
        cx = x
        cy = y
        depth = z.coerceIn(0f, 1f)
        contentIntensity = content.coerceIn(0f, 1f)
        borderIntensity = border.coerceIn(0f, 1f)
        dirty = true
        invalidateSelf()
    }

    fun clear() {
        cx = 0f; cy = 0f; depth = 0f
        contentIntensity = 0f; borderIntensity = 0f
        dirty = true
        invalidateSelf()
    }

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        dirty = true
    }

    override fun draw(canvas: Canvas) {
        if (contentIntensity <= 0.001f && borderIntensity <= 0.001f) return

        val b = bounds ?: return
        if (b.isEmpty) return

        boundsRect.set(b)
        // 先把外接矩形写进 tmpRect，再让 shape 决定圆角——否则读到的是上一次的脏值
        tmpRect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        val radius = shape.resolve(tmpRect)

        val c = GlowPalette.withAlpha(lightColor, 1f)
        val h = headroom.coerceIn(1f, 4f)

        // ---- ① 内部光晕：径向渐变 ----
        val bw = b.width().toFloat()
        val bh = b.height().toFloat()
        val haloW = bw * (1f + depth * 0.5f) * GlowPalette.HALO_SCALE
        val haloH = bh * (1f + depth * 0.5f) * GlowPalette.HALO_SCALE
        val haloR = maxOf(haloW, haloH) / 2f
        val centerX = b.exactCenterX() + cx
        val centerY = b.exactCenterY() + cy

        if (haloR > 0f) {
            val key = (haloR.toInt() shl 8) or (c and 0xFFFFFF)
            if (dirty || haloShader == null || key != lastShaderKey) {
                val strength = GlowPalette.applyHeadroom(
                    GlowPalette.CONTENT_BASE * contentIntensity * frameAlpha, h
                )
                val inner = GlowPalette.withAlpha(lightColor, strength * 0.55f)
                val mid = GlowPalette.withAlpha(lightColor, strength * 0.22f)
                val outer = Color.TRANSPARENT
                haloShader = RadialGradient(
                    centerX, centerY, haloR,
                    intArrayOf(inner, mid, outer),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP
                )
                lastShaderKey = key
            }
            paint.shader = haloShader
            canvas.drawCircle(centerX, centerY, haloR, paint)
            paint.shader = null
        }

        // ---- ② 边缘描边光：沿轮廓画一圈 ----
        if (borderIntensity > 0.001f) {
            val strength = GlowPalette.applyHeadroom(
                GlowPalette.BORDER_BASE * borderIntensity * frameAlpha, h
            )
            borderPaint.color = GlowPalette.withAlpha(lightColor, strength)
            borderPaint.strokeWidth = maxOf(1.5f, bh * 0.035f)

            val path: Path? = shape.outline()
            if (path != null) {
                val m = canvas.save()
                canvas.translate(b.left.toFloat(), b.top.toFloat())
                val sx = b.width() / 100f
                val sy = b.height() / 100f
                canvas.scale(sx, sy)
                canvas.drawPath(path, borderPaint)
                canvas.restoreToCount(m)
            } else {
                canvas.drawRoundRect(boundsRect, radius, radius, borderPaint)
            }
        }

        dirty = false
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        borderPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        paint.colorFilter = colorFilter
        borderPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}