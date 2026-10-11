# 注入脚本以字符串常量形式存在，不能被混淆改变（release 包要保证 JS 原样下发）
-keep class com.lichtcui.pokelauncher.server.InjectScripts { *; }
-keep class com.lichtcui.pokelauncher.cheat.CheatScripts { *; }
-keep class com.lichtcui.pokelauncher.cheat.Cheats { *; }
-keep class com.lichtcui.pokelauncher.web.SaveGuard { *; }
-keep class com.lichtcui.pokelauncher.core.Const { *; }

# ---------------------------------------------------------------------------
# JSON 字段名（Release 查询 + 下载状态文件）
#
# 本项目**不使用**反射式 JSON（Gson/Moshi/kotlinx.serialization 式的「属性名当 key」），
# 全部是 `optString("tag_name")` / `put("totalBytes", ...)` 这样的字符串字面量，
# R8 改不了字面量，因此不存在「release 包静默读不到值」的风险。
# 这里只保留模型类名，让线上堆栈/日志能直接看出类型（代价接近于零）。
# 新增字段时：只要仍用字符串字面量，就**不需要**加 -keep-property-name 这类规则。
# ---------------------------------------------------------------------------
-keepnames class com.lichtcui.pokelauncher.install.ReleaseInfo
-keepnames class com.lichtcui.pokelauncher.install.DownloadState
-keepnames class com.lichtcui.pokelauncher.install.PendingDownload

# ---------------------------------------------------------------------------
# 参数调整 / 存档快照
#
# 「参数调整」的开关与参数值存在 SharedPreferences，**key 名是字符串字面量**
# （`cheatMaster` / `cheatEnabledIds` / `cheatParams`），与 install 层同理：
# R8 改不了字面量，新增字段不需要额外的 -keep-property-name。
# 这里只保留类名，便于线上堆栈定位。
# ---------------------------------------------------------------------------
-keepnames class com.lichtcui.pokelauncher.cheat.CheatParam
-keepnames class com.lichtcui.pokelauncher.cheat.Cheat
-keepnames class com.lichtcui.pokelauncher.cheat.CheatState
-keepnames class com.lichtcui.pokelauncher.cheat.CheatFormat
-keepnames class com.lichtcui.pokelauncher.web.SaveGuardStore

# OkHttp / Okio 自带 consumer 规则（含 JVM 平台类的 dontwarn），无需重复声明

# JS 桥：@JavascriptInterface 标注的方法名必须保留，否则 WebView 侧调用静默失效
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# NanoHTTPD 内部用反射实例化 Response/Status
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**
