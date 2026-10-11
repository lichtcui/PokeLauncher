# 注入脚本以字符串常量形式存在，不能被混淆改变（release 包要保证 JS 原样下发）
-keep class com.lichtcui.pokelauncher.server.InjectScripts { *; }
-keep class com.lichtcui.pokelauncher.core.Const { *; }

# JS 桥：@JavascriptInterface 标注的方法名必须保留，否则 WebView 侧调用静默失效
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# NanoHTTPD 内部用反射实例化 Response/Status
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**
