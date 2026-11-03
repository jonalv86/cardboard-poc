package com.pps.cardboardpoc.adb

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log

/** Paso del setup del modo fluido en el que está el teléfono, según sus ajustes reales. */
enum class FluidSetupStep {
    /** Opciones de desarrollador desactivadas. */
    DEVELOPER_OPTIONS,

    /** Opciones de desarrollador activas, Depuración inalámbrica desactivada. */
    WIRELESS_DEBUGGING,

    /** Las dos activas: falta vincular con el código. */
    PAIR;

    companion object {
        private const val TAG = "CardboardCapture"

        /** Clave de la preferencia "Depuración inalámbrica" dentro de Opciones de desarrollador. */
        private const val WIRELESS_DEBUGGING_KEY = "toggle_adb_wireless"

        fun current(context: Context): FluidSetupStep = when {
            !isDeveloperOptionsEnabled(context) -> DEVELOPER_OPTIONS
            !WirelessDebugging.isEnabled(context) -> WIRELESS_DEBUGGING
            else -> PAIR
        }

        fun isDeveloperOptionsEnabled(context: Context): Boolean =
            Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

        /**
         * Abre el ajuste del sistema donde se resuelve [step]: "Acerca del teléfono" para activar las
         * Opciones de desarrollador, o las Opciones de desarrollador con "Depuración inalámbrica"
         * resaltada, que sirve tanto para activarla como para entrar a vincular (la pantalla de la
         * depuración inalámbrica no tiene intent propio). Si el fabricante no abre esa pantalla, abre
         * Ajustes.
         */
        fun openSettings(context: Context, step: FluidSetupStep) {
            val intent = if (step == DEVELOPER_OPTIONS) {
                Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
            } else {
                // El resaltado no es API pública: Ajustes lo toma de estos extras cuando los soporta y,
                // si no, los ignora.
                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    .putExtra(":settings:fragment_args_key", WIRELESS_DEBUGGING_KEY)
                    .putExtra(
                        ":settings:show_fragment_args",
                        Bundle().apply { putString(":settings:fragment_args_key", WIRELESS_DEBUGGING_KEY) }
                    )
            }
            try {
                context.startActivity(intent.forContext(context))
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "setup fluido: no se pudo abrir ${intent.action}, se abre Ajustes", e)
                context.startActivity(Intent(Settings.ACTION_SETTINGS).forContext(context))
            }
        }

        private fun Intent.forContext(context: Context): Intent =
            if (context is Activity) this else addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        const val SETTINGS_PACKAGE = "com.android.settings"

        /**
         * Textos de las opciones de Ajustes que hay que tocar en [step], en orden: el primero que esté a la
         * vista es el más cercano a la meta. Se leen de los recursos de la app Ajustes, así salen en el
         * idioma y con la redacción del fabricante; los escritos acá cubren un Ajustes sin esos recursos.
         */
        fun targetLabels(context: Context, step: FluidSetupStep): List<String> {
            val resources = when (step) {
                DEVELOPER_OPTIONS -> listOf(RES_BUILD_NUMBER, RES_SOFTWARE_INFO)
                WIRELESS_DEBUGGING -> listOf(RES_WIRELESS_DEBUGGING)
                PAIR -> listOf(RES_PAIR_WITH_CODE, RES_WIRELESS_DEBUGGING)
            }
            val fallback = when (step) {
                DEVELOPER_OPTIONS -> listOf("Número de compilación", "Build number", "Información de software", "Software information")
                WIRELESS_DEBUGGING -> listOf("Depuración inalámbrica", "Wireless debugging")
                PAIR -> listOf("Vincular dispositivo con", "Pair device with pairing code", "Depuración inalámbrica", "Wireless debugging")
            }
            return (resources.mapNotNull { settingsLabel(context, it) } + fallback).distinct()
        }

        /** Nombre que Ajustes le da en este celular a la opción de vincular con código. */
        fun pairWithCodeLabel(context: Context): String =
            settingsLabel(context, RES_PAIR_WITH_CODE) ?: "Vincular dispositivo con código"

        private fun settingsLabel(context: Context, name: String): String? = runCatching {
            val resources = context.packageManager.getResourcesForApplication(SETTINGS_PACKAGE)
            resources.getIdentifier(name, "string", SETTINGS_PACKAGE).takeIf { it != 0 }?.let(resources::getString)
        }.getOrNull()

        private const val RES_BUILD_NUMBER = "build_number"

        /** One UI: el número de compilación está dentro de "Información de software". */
        private const val RES_SOFTWARE_INFO = "device_info_software_title"
        private const val RES_WIRELESS_DEBUGGING = "adb_wireless_settings"
        private const val RES_PAIR_WITH_CODE = "adb_pair_method_code_title"
    }
}
