package com.pps.cardboardpoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlBarTest {

    private val bounds = ControlBar.bounds(WIDTH, HEIGHT)

    @Test
    fun `la pildora queda centrada abajo con su margen`() {
        assertEquals(WIDTH / 2f, bounds.centerX, 0.01f)
        assertEquals(HEIGHT - ControlBar.BOTTOM_MARGIN_PX, bounds.bottom, 0.01f)
        assertEquals(ControlBar.BUTTON_DIAMETER_PX + 2 * ControlBar.PADDING_PX, bounds.height, 0.01f)
    }

    @Test
    fun `el centro del boton de inicio lo devuelve`() {
        val x = ControlBar.buttonCenterX(0, WIDTH, HEIGHT)
        assertEquals(ControlBar.Button.HOME, ControlBar.buttonAt(x, bounds.centerY, WIDTH, HEIGHT))
    }

    @Test
    fun `la esquina de la pildora no es boton pero si es barra`() {
        val x = bounds.left + 1f
        val y = bounds.top + 1f
        assertTrue(ControlBar.contains(x, y, WIDTH, HEIGHT))
        assertNull(ControlBar.buttonAt(x, y, WIDTH, HEIGHT))
    }

    @Test
    fun `el borde de abajo de la pantalla no es barra`() {
        assertFalse(ControlBar.contains(WIDTH / 2f, HEIGHT.toFloat(), WIDTH, HEIGHT))
    }

    @Test
    fun `oculta no aparece al pasar por encima de la pildora`() {
        assertFalse(ControlBar.nextVisible(false, bounds.centerX, bounds.centerY, WIDTH, HEIGHT))
    }

    @Test
    fun `aparece en la zona del borde de abajo en cualquier lugar`() {
        val y = HEIGHT - ControlBar.REVEAL_ZONE_PX
        assertTrue(ControlBar.nextVisible(false, 0f, y, WIDTH, HEIGHT))
        assertTrue(ControlBar.nextVisible(false, bounds.centerX, HEIGHT.toFloat(), WIDTH, HEIGHT))
    }

    @Test
    fun `visible no se oculta al subir hasta los botones`() {
        assertTrue(ControlBar.nextVisible(true, bounds.centerX, bounds.centerY, WIDTH, HEIGHT))
        val justAbove = bounds.top - ControlBar.KEEP_MARGIN_PX
        assertTrue(ControlBar.nextVisible(true, bounds.centerX, justAbove, WIDTH, HEIGHT))
    }

    @Test
    fun `visible se oculta al alejarse de la pildora`() {
        val above = bounds.top - ControlBar.KEEP_MARGIN_PX - 1f
        assertFalse(ControlBar.nextVisible(true, bounds.centerX, above, WIDTH, HEIGHT))
        assertFalse(ControlBar.nextVisible(true, 0f, bounds.centerY, WIDTH, HEIGHT))
    }

    private companion object {
        const val WIDTH = 2340
        const val HEIGHT = 1080
    }
}
