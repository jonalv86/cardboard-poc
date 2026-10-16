package com.pps.cardboardpoc

/**
 * Borde de la pantalla sobre el que está el cursor. Para tomar un borde el cursor tiene que llegar hasta
 * él, pero para dejarlo tiene que alejarse [EXIT_MARGIN_PX]: el temblor de la cabeza mueve el cursor
 * fracciones de píxel y, sin ese margen, lo haría entrar y salir del borde varias veces por segundo. En
 * una esquina gana el borde vertical.
 */
object EdgeZone {

    /** Cuánto tiene que alejarse el cursor del borde que tiene tomado para dejarlo, en píxeles. */
    const val EXIT_MARGIN_PX = 24f

    /** Borde, como dirección hacia él: -1, 0 o 1 por eje; los dos en 0 es ningún borde. */
    data class Edge(val dirX: Int, val dirY: Int) {
        val isNone: Boolean get() = dirX == 0 && dirY == 0
    }

    val NONE = Edge(0, 0)

    /**
     * Borde que corresponde al cursor en ([x], [y]) en una pantalla de [width] x [height], sabiendo que
     * tenía tomado [current].
     */
    fun edgeAt(x: Float, y: Float, width: Int, height: Int, current: Edge): Edge {
        val touchedY = touched(y, height.toFloat())
        if (touchedY != 0) return Edge(0, touchedY)
        if (current.dirY != 0 && isNear(y, height.toFloat(), current.dirY)) return current
        val touchedX = touched(x, width.toFloat())
        if (touchedX != 0) return Edge(touchedX, 0)
        if (current.dirX != 0 && isNear(x, width.toFloat(), current.dirX)) return current
        return NONE
    }

    private fun touched(value: Float, size: Float): Int = when {
        value <= 0f -> -1
        value >= size -> 1
        else -> 0
    }

    private fun isNear(value: Float, size: Float, dir: Int): Boolean =
        if (dir > 0) value >= size - EXIT_MARGIN_PX else value <= EXIT_MARGIN_PX
}
