package com.pps.cardboardpoc

import android.content.Context

/** Ajustes del zoom que se eligen en la pantalla de ajustes y se aplican al iniciar cada sesión. */
object ZoomSettings {

    private const val PREFS = "celuvr_prefs"
    private const val KEY_START_LEVEL = "zoom_start_level"
    private const val KEY_HEAD_SMOOTHING_MS = "head_smoothing_ms"

    /** Zoom con que arranca la sesión si no se eligió otro. */
    const val DEFAULT_START_LEVEL = 1f

    /** Suavizado del movimiento de la cabeza, en ms, si no se eligió otro. */
    const val DEFAULT_HEAD_SMOOTHING_MS = 60f

    /** Zoom con que arranca cada sesión, uno de [ZoomLevels.STEPS]. */
    fun startLevel(context: Context): Float =
        prefs(context).getFloat(KEY_START_LEVEL, DEFAULT_START_LEVEL)

    /** Guarda el zoom con que arranca cada sesión. */
    fun setStartLevel(context: Context, level: Float) {
        prefs(context).edit().putFloat(KEY_START_LEVEL, level).apply()
    }

    /**
     * Constante de tiempo del filtro que suaviza el movimiento de la cabeza, en ms; 0 lo apaga. Más
     * alta deja el puntero y la imagen con zoom más quietos, pero siguen a la cabeza con más retraso.
     */
    fun headSmoothingMs(context: Context): Float =
        prefs(context).getFloat(KEY_HEAD_SMOOTHING_MS, DEFAULT_HEAD_SMOOTHING_MS)

    /** Guarda el suavizado del movimiento de la cabeza, en ms. */
    fun setHeadSmoothingMs(context: Context, smoothingMs: Float) {
        prefs(context).edit().putFloat(KEY_HEAD_SMOOTHING_MS, smoothingMs).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
