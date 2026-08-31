package de.robv.android.xposed

object XposedHelpers {
    @JvmStatic
    fun findAndHookMethod(
        className: String,
        classLoader: ClassLoader,
        methodName: String,
        vararg parameterTypesAndCallback: Any?,
    ): XC_MethodHook.Unhook {
        throw UnsupportedOperationException("compile-only stub")
    }

    @JvmStatic
    fun getObjectField(instance: Any, fieldName: String): Any? {
        throw UnsupportedOperationException("compile-only stub")
    }
}
