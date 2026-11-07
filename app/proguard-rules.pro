# Xposed 模块必须保留入口类与 hook 相关类名，不做混淆
-keep class com.whaleglow.immersive.** { *; }
-keep class de.robv.android.xposed.** { *; }
-dontwarn de.robv.android.xposed.**