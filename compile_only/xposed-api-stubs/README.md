# Xposed API compile stubs

Minimal Kotlin declarations of the legacy Xposed API symbols used by Snapper's optional LSPosed
entry point. The `:xposed-stubs` module is a `compileOnly` dependency of `:app`, so nothing from it
is packaged; LSPosed supplies the real API inside `system_server`. Field and method shapes must
match the real `de.robv.android.xposed` classes exactly.
