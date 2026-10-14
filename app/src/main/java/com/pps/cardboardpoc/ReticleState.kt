package com.pps.cardboardpoc

import kotlin.math.hypot

/** Lo que muestra la retícula en un cuadro. */
sealed interface ReticleMode {

    /** Sin dedo apoyado ni borde: anillo y punto. */
    data object Rest : ReticleMode

    /** Dedo apoyado antes de que la app lo tome como pulsación sostenida; [fill] va de 0 a 1. */
    data class Pressing(val fill: Float) : ReticleMode

    /** Dedo apoyado que la app ya toma como pulsación sostenida o arrastre. */
    data object Holding : ReticleMode

    /** Cursor sobre un borde, esperando para avanzar; [fill] va de 0 a 1. */
    data class EdgeDwell(val dirX: Int, val dirY: Int, val fill: Float) : ReticleMode

    /** Cursor sobre un borde con la pantalla avanzando hacia él. */
    data class EdgeScrolling(val dirX: Int, val dirY: Int) : ReticleMode
}

object ReticleState {

    /**
     * Modo de la retícula a partir del estado del input. Un dedo apoyado pasa a [ReticleMode.Holding]
     * cuando cumple [longPressMs] o cuando el cursor se aleja más de [touchSlopPx] de donde se apoyó:
     * son los mismos límites con los que la app de destino decide entre tap, pulsación larga y
     * arrastre. El borde tiene prioridad porque con el dedo apoyado no hay avance por borde.
     */
    fun mode(
        press: JoystickManager.Press?,
        edge: JoystickManager.EdgeContact?,
        cursorX: Float,
        cursorY: Float,
        nowMs: Long,
        longPressMs: Long,
        touchSlopPx: Float
    ): ReticleMode {
        if (edge != null) {
            val fill = edge.fill(nowMs)
            return if (fill >= 1f) {
                ReticleMode.EdgeScrolling(edge.dirX, edge.dirY)
            } else {
                ReticleMode.EdgeDwell(edge.dirX, edge.dirY, fill)
            }
        }
        if (press == null) return ReticleMode.Rest

        val moved = hypot(cursorX - press.originX, cursorY - press.originY)
        val fill = if (longPressMs <= 0L) 1f else (nowMs - press.startMs) / longPressMs.toFloat()
        return if (moved > touchSlopPx || fill >= 1f) {
            ReticleMode.Holding
        } else {
            ReticleMode.Pressing(fill.coerceIn(0f, 1f))
        }
    }
}
