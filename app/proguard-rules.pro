# SoundWave R8 rules (minimal set — expand incrementally as needed)

# WebView JS bridge：@JavascriptInterface 方法不可被 R8 重命名/移除，否则脚本取链/回调完全失效。
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# NativeBridge 通过 addJavascriptInterface("NativeBridge") 按名称注入，保持桥类与其成员名。
-keep class xyz.cncnlp.soundwave.ScriptRuntime$Bridge { *; }

# r657 P0-5：正式包剥离 verbose/debug/info 日志（调用与参数构造一起被 R8 视为无副作用删除）。
# Log.w/e 保留；带歌曲 key 等标识的 Log.w 已在源码中用 BuildConfig.DEBUG 守卫。
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# r668 emergency: ART rejects R8-optimized high-arity Compose methods in 667.
# Keep their original method bodies and class boundaries; obfuscation is still allowed.
# Prevents parameter-strength reduction/inlining/static class merging for these two facades.
-keep,allowobfuscation class xyz.cncnlp.soundwave.LibraryPageKt { *; }
-keep,allowobfuscation class xyz.cncnlp.soundwave.FullPlayerKt { *; }
