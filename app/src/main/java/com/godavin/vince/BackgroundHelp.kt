package com.godavin.vince

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** Opens the phone maker's own "autostart / background" page when it exists. */
object BackgroundHelp {
    private val CANDIDATES = listOf(
        // Xiaomi / Redmi / Poco
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutostartManagementActivity",
        // Tecno / Infinix / Itel (Transsion)
        "com.transsion.phonemaster" to "com.cyin.himgr.autostart.AutoStartActivity",
        "com.transsion.phonemanager" to "com.cyin.himgr.autostart.AutoStartActivity",
        "com.itel.autostart" to "com.itel.autostart.AutoStartActivity",
        // Oppo / Realme / OnePlus
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        // Vivo
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        // Huawei / Honor
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        // Samsung
        "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity"
    )

    /** Returns true if a maker-specific page opened; otherwise opens VINCE's app info page. */
    fun openAutostart(context: Context): Boolean {
        for ((pkg, cls) in CANDIDATES) {
            try {
                val i = Intent().apply {
                    component = ComponentName(pkg, cls)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(i)
                return true
            } catch (e: Exception) {
                // not this maker - try the next
            }
        }
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) { }
        return false
    }
}
