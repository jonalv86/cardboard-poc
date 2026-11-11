package com.pps.cardboardpoc

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object RecentAppsProvider {

    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        } else {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun getRecentApps(context: Context, packageManager: PackageManager): List<ComponentName> {
        val sessionRecent = SessionApps.getRecent()
        if (!hasUsageStatsPermission(context)) {
            return sessionRecent
        }

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val currentTime = System.currentTimeMillis()
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            currentTime - 7 * 24 * 3600 * 1000L,
            currentTime
        ) ?: return sessionRecent

        val systemRecent = stats
            .filter { it.lastTimeUsed > 0 }
            .sortedByDescending { it.lastTimeUsed }
            .mapNotNull { stat ->
                val pkg = stat.packageName
                if (pkg == context.packageName) return@mapNotNull null
                packageManager.getLaunchIntentForPackage(pkg)?.component
            }
            .distinct()
            .take(12)

        val combined = (sessionRecent + systemRecent).distinct()
        return combined
    }
}
