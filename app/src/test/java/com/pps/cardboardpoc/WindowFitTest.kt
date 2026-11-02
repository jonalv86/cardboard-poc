package com.pps.cardboardpoc

import org.junit.Assert.assertEquals
import org.junit.Test

class WindowFitTest {

    @Test
    fun `la ventana que ocupa toda la pantalla horizontal la llena`() {
        assertEquals(WindowFit.FULL, WindowFit.of(0, 0, LONG, SHORT, LONG, SHORT))
    }

    @Test
    fun `la ventana que ocupa toda la pantalla vertical la llena`() {
        assertEquals(WindowFit.FULL, WindowFit.of(0, 0, SHORT, LONG, SHORT, LONG))
    }

    @Test
    fun `una vertical de todo el alto en horizontal solo funciona en vertical`() {
        assertEquals(WindowFit.PORTRAIT_ONLY, WindowFit.of(920, 0, 1420, SHORT, LONG, SHORT))
    }

    @Test
    fun `una horizontal centrada de todo el ancho en vertical solo funciona en horizontal`() {
        assertEquals(WindowFit.LANDSCAPE_ONLY, WindowFit.of(0, 920, SHORT, 1420, SHORT, LONG))
    }

    @Test
    fun `una hoja de todo el ancho pegada abajo no es la ventana principal`() {
        assertEquals(WindowFit.PARTIAL, WindowFit.of(0, 1840, SHORT, LONG, SHORT, LONG))
    }

    @Test
    fun `un dialogo centrado no es la ventana principal`() {
        assertEquals(WindowFit.PARTIAL, WindowFit.of(870, 240, 1470, 840, LONG, SHORT))
    }

    private companion object {
        const val LONG = 2340
        const val SHORT = 1080
    }
}
