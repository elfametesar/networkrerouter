package net.ip.rerouter.net

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.ip.rerouter.model.AppInfo

/** Lists installed apps via PackageManager, used for the per-rule exclusion picker. */
class AppRepository(private val context: Context) {

    suspend fun listInstalledApps(includeSystemApps: Boolean = false): List<AppInfo> =
        withContext(Dispatchers.Default) {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            apps.asSequence()
                .filter { includeSystemApps || (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
                .map { appInfo ->
                    AppInfo(
                        uid = appInfo.uid,
                        packageName = appInfo.packageName,
                        label = pm.getApplicationLabel(appInfo).toString(),
                        isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    )
                }
                .distinctBy { it.uid } // multiple packages can share a UID
                .sortedBy { it.label.lowercase() }
                .toList()
        }

    /**
     * Looks up the UID for a given package name.
     * Returns null if the package is not installed.
     */
    suspend fun getUidForPackage(packageName: String): Int? =
        withContext(Dispatchers.Default) {
            try {
                val pm = context.packageManager
                val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                apps.firstOrNull { it.packageName == packageName }?.uid
            } catch (e: Exception) {
                null
            }
        }
}
