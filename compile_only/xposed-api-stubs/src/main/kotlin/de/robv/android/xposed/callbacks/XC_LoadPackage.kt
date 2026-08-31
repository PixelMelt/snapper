package de.robv.android.xposed.callbacks

abstract class XC_LoadPackage private constructor() {
    class LoadPackageParam {
        @JvmField var packageName: String = ""
        @JvmField var processName: String = ""
        @JvmField var classLoader: ClassLoader = ClassLoader.getSystemClassLoader()
    }
}
