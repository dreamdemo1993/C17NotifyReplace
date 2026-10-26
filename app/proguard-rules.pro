# YukiHook's libxposed runtime ships the consumer keep rules for the generated entry.

# Debug diagnostics are useful during development but must not enter Release artifacts.
-assumenosideeffects class com.c17.notify.replace.diagnostics.Diagnostics {
    public final void debug(...);
}
