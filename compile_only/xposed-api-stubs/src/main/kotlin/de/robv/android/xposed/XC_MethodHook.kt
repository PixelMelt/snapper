package de.robv.android.xposed

import java.lang.reflect.Member

abstract class XC_MethodHook protected constructor() {
    @Throws(Throwable::class)
    protected open fun beforeHookedMethod(param: MethodHookParam) {
    }

    @Throws(Throwable::class)
    protected open fun afterHookedMethod(param: MethodHookParam) {
    }

    class MethodHookParam {
        @JvmField var method: Member? = null
        @JvmField var thisObject: Any? = null
        @JvmField var args: Array<Any?> = emptyArray()

        fun setResult(result: Any?) {
            throw UnsupportedOperationException("compile-only stub")
        }
    }

    inner class Unhook
}
