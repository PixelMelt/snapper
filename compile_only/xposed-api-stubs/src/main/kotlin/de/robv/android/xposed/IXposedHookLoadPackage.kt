package de.robv.android.xposed

import de.robv.android.xposed.callbacks.XC_LoadPackage

interface IXposedHookLoadPackage {
    @Throws(Throwable::class)
    fun handleLoadPackage(loadPackage: XC_LoadPackage.LoadPackageParam)
}
