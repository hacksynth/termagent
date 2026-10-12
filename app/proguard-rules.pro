# termagent ProGuard rules
# The PTY bridge is reached from native code through JNI, keep its symbols.
-keep class com.termux.terminal.** { *; }
-keep class com.termux.view.** { *; }

# JNI entry points (belt and braces — the class above is already kept).
-keepclasseswithmembernames class * {
    native <methods>;
}
