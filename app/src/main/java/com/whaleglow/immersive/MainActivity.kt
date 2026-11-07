package com.whaleglow.immersive

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 模块说明页：只是个"我在这儿"的落地页。
 * LSPosed 里的勾选才是真正的开关。
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_ImmersiveGlow)

        val dp = resources.displayMetrics.density
        fun d(v: Int) = (v * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(d(24), d(72), d(24), d(24))
            setBackgroundColor(0xFF0B1220.toInt())
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.module_name)
            textSize = 22f
            setTextColor(0xFFF2F6FF.toInt())
        })

        root.addView(TextView(this).apply {
            text = getString(R.string.module_desc)
            textSize = 13f
            setTextColor(0xFF9FB3C8.toInt())
            setPadding(0, d(16), 0, 0)
        })

        root.addView(TextView(this).apply {
            text = getString(R.string.state_hint)
            textSize = 13f
            setTextColor(0xFF6EA8FF.toInt())
            setPadding(0, d(24), 0, 0)
        })

        setContentView(root)
    }
}