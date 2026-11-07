package com.whaleglow.immersive

import android.graphics.Path
import android.graphics.RectF

/**
 * 形状描述器：决定"光沿什么轮廓画"。
 *
 * 对应 OShin 协议里 LightShape 的语义：
 *  - resolve(out: RectF): Float  —— 把外接矩形写入 out，返回特征圆角半径
 *  - outline(): Path?            —— 返回可选的自定义描边路径
 *
 * 这里自研实现了三种常用形状，覆盖通知胶囊的绝大多数场景。
 */
interface LightShapeImpl {
    fun resolve(out: RectF): Float
    fun outline(): Path?
}

/** 圆角矩形 / 胶囊（默认） */
class RoundRectShape : LightShapeImpl {
    override fun resolve(out: RectF): Float {
        val r = minOf(out.width(), out.height()) / 2f
        return r
    }

    override fun outline(): Path? = null
}

/** 正圆形（用于圆形通知/头像光效） */
class CircleShape : LightShapeImpl {
    override fun resolve(out: RectF): Float {
        val r = minOf(out.width(), out.height()) / 2f
        // 收成正方形外接框
        val cx = out.centerX()
        val cy = out.centerY()
        out.set(cx - r, cy - r, cx + r, cy + r)
        return r
    }

    override fun outline(): Path? = null
}

/** 有机形变圆角（上圆下方之类，用于卡片式胶囊） */
class OrganicShape(private val topRatio: Float = 1f, private val bottomRatio: Float = 1f) : LightShapeImpl {
    override fun resolve(out: RectF): Float = minOf(out.width(), out.height()) / 2f

    override fun outline(): Path {
        // 返回 null 以外的 Path 时，绘制层会优先用它作裁剪路径
        return Path().apply {
            val r = 24f
            addRoundRect(RectF(0f, 0f, 100f, 100f), r * topRatio, r * topRatio, Path.Direction.CW)
        }
    }
}
