package dev.nanodash.logger

import android.content.Context

internal object BuildConfigInfo {
    fun versionName(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "unknown"

    /** True when an F-Droid client installed the app: that client updates it, so the GitHub update link is not needed. */
    @Suppress("DEPRECATION") // getInstallSourceInfo needs API 30, minSdk is 26
    fun fromFDroid(context: Context): Boolean {
        val installer = runCatching { context.packageManager.getInstallerPackageName(context.packageName) }.getOrNull() ?: return false
        return installer.startsWith("org.fdroid.") || installer == "com.looker.droidify" || installer == "com.machiav3lli.fdroid"
    }

    /** The `versionCode`, which CI sets to the run number that is also the end of the release tag. */
    fun build(context: Context): Long =
        runCatching { androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0)) }.getOrDefault(0L)
}
