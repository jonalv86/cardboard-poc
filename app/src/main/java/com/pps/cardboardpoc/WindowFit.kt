package com.pps.cardboardpoc

import kotlin.math.abs

/**
 * Cómo encaja la ventana de una app en la pantalla. Una app que solo funciona en una orientación, en
 * la contraria, el sistema la encaja con bandas: una vertical, en horizontal, ocupa todo el alto pero
 * es más alta que ancha; una horizontal, en vertical, ocupa todo el ancho, centrada, pero es más ancha
 * que alta.
 */
enum class WindowFit {
    /** Ocupa toda la pantalla. */
    FULL,

    /** Vertical encajada con bandas en una pantalla horizontal: la app solo funciona en vertical. */
    PORTRAIT_ONLY,

    /** Horizontal encajada con bandas en una pantalla vertical: la app solo funciona en horizontal. */
    LANDSCAPE_ONLY,

    /** No es la ventana principal de la app: un diálogo, una hoja que sube desde abajo. */
    PARTIAL;

    /** Si es la ventana principal de una app y no algo que se muestra encima. */
    val isMain: Boolean get() = this != PARTIAL

    companion object {
        /** Fracción del lado de la pantalla a partir de la cual una ventana lo ocupa entero. */
        private const val FULL_SIZE_FRACTION = 0.95f

        /** Diferencia tolerada entre las bandas de arriba y de abajo, como fracción del alto. */
        private const val CENTERED_TOLERANCE = 0.02f

        /** Cómo encaja una ventana con esos bordes, en píxeles, en una pantalla de [width] x [height]. */
        fun of(left: Int, top: Int, right: Int, bottom: Int, width: Int, height: Int): WindowFit {
            val windowWidth = right - left
            val windowHeight = bottom - top
            val fullWidth = windowWidth >= width * FULL_SIZE_FRACTION
            val fullHeight = windowHeight >= height * FULL_SIZE_FRACTION
            return when {
                fullWidth && fullHeight -> FULL

                // Un diálogo también puede ser más alto que ancho; la ventana encajada ocupa todo
                // el alto.
                width > height && fullHeight && windowHeight > windowWidth -> PORTRAIT_ONLY

                // Una hoja que sube desde abajo también ocupa todo el ancho; la ventana encajada
                // queda centrada.
                height > width && fullWidth && windowWidth > windowHeight &&
                    abs(top - (height - bottom)) <= height * CENTERED_TOLERANCE -> LANDSCAPE_ONLY

                else -> PARTIAL
            }
        }
    }
}
