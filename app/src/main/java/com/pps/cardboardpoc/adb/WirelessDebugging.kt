package com.pps.cardboardpoc.adb

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * Enciende la depuración inalámbrica del propio celular sin computadora. Escribir ese ajuste exige
 * WRITE_SECURE_SETTINGS, un permiso que no se concede con un diálogo; CeluVR se lo concede a sí misma
 * por adb ([AdbServerLauncher] corre `pm grant` la primera vez que conecta). Con el permiso, encender
 * la depuración inalámbrica al tocar INICIAR evita el único paso manual que quedaba después del
 * emparejamiento (activarla de nuevo tras cada reinicio).
 */
object WirelessDebugging {

    private const val TAG = "CardboardCapture"
    private const val SETTING = "adb_wifi_enabled"

    const val PERMISSION = "android.permission.WRITE_SECURE_SETTINGS"

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun isEnabled(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, SETTING, 0) == 1

    /** Enciende la depuración inalámbrica si CeluVR tiene el permiso. Devuelve si quedó encendida. */
    fun enable(context: Context): Boolean {
        if (!hasPermission(context)) return false
        return try {
            Settings.Global.putInt(context.contentResolver, SETTING, 1)
            Log.d(TAG, "depuración inalámbrica encendida por la app")
            true
        } catch (e: Exception) {
            Log.w(TAG, "no se pudo encender la depuración inalámbrica", e)
            false
        }
    }

    /** Apaga la depuración inalámbrica si CeluVR tiene el permiso. */
    fun disable(context: Context): Boolean {
        if (!hasPermission(context)) return false
        return try {
            Settings.Global.putInt(context.contentResolver, SETTING, 0)
            Log.d(TAG, "depuración inalámbrica apagada por la app")
            true
        } catch (e: Exception) {
            Log.w(TAG, "no se pudo apagar la depuración inalámbrica", e)
            false
        }
    }
}
