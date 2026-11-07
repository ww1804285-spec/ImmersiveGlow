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
        // 光晕层：只画内部径向光场。描边由 drawBorderOnly() 独立负责，
        // 二者使用不同 bounds，避免外扩把描边带偏。
        if (contentIntensity <= 0.001f) return
        if (frameAlpha <= 0.001f) return

        val b = bounds ?: return
        if (b.isEmpty) return

        boundsRect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
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

        dirty = false
    }

    /**
     * 只画描边层（不画内部光晕）。
     *
     * 单独拆出来是为了让描边严格使用「View 真实 bounds」，
     * 而光晕层使用「外扩后的 bounds」，两者坐标系互不干扰。
     */
    fun drawBorderOnly(canvas: Canvas) {
        if (borderIntensity <= 0.001f) return
        if (frameAlpha <= 0.001f) return

        val b = bounds ?: return
        if (b.isEmpty) return

        boundsRect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        tmpRect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        val radius = shape.resolve(tmpRect)

        val h = headroom.coerceIn(1f, 4f)
        val strength = GlowPalette.applyHeadroom(
            GlowPalette.BORDER_BASE * borderIntensity * frameAlpha, h
        )
        if (strength <= 0.001f) return

        val bh = b.height().toFloat()
        borderPaint.color = GlowPalette.withAlpha(lightColor, strength)
        borderPaint.strokeWidth = maxOf(1.5f, bh * 0.035f)

        val path: Path? = shape.outline()
        if (path != null) {
            // shape.outline() 返回的是 0..100 的百分比坐标系，需缩放贴合实际 bounds
            val m = canvas.save()
            val sx = boundsRect.width() / 100f
            val sy = boundsRect.height() / 100f
            canvas.translate(boundsRect.left, boundsRect.top)
            canvas.scale(sx, sy)
            canvas.drawPath(path, borderPaint)
            canvas.restoreToCount(m)
        } else {
            val r = if (radius > 0f) radius else bh / 2f
            canvas.drawRoundRect(boundsRect, r, r, borderPaint)
        }
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