package de.robv.android.xposed

import java.lang.reflect.Member

object XposedBridge {
    @JvmStatic
    fun log(message: String) {
    }

    @JvmStatic
    @Throws(Throwable::class)
    fun invokeOriginalMethod(method: Member, thisObject: Any?, args: Array<Any?>?): Any? {
        throw UnsupportedOperationException("compile-only stub")
    }
}
