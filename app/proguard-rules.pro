# SoundWave R8 rules (minimal set — expand incrementally as needed)

# WebView JS bridge：@JavascriptInterface 方法不可被 R8 重命名/移除，否则脚本取链/回调完全失效。
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# NativeBridge 通过 addJavascriptInterface("NativeBridge") 按名称注入，保持桥类与其成员名。
-keep class xyz.cncnlp.soundwave.ScriptRuntime$Bridge { *; }

# 正式包剥离 verbose/debug/info 日志（assumenosideeffects 使 R8 连同调用与参数构造一并删除）；Log.w/e 保留。
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# 高参 Compose 方法经 R8 优化后被 ART 拒收：保留这两个门面的方法体与类边界（仍可混淆），禁止参数强度削减、内联与静态类合并。
-keep,allowobfuscation class xyz.cncnlp.soundwave.LibraryPageKt { *; }
-keep,allowobfuscation class xyz.cncnlp.soundwave.FullPlayerKt { *; }
