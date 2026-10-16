package com.pps.cardboardpoc

import com.pps.cardboardpoc.ControlBar.Button
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlBarTest {

    private val bounds = ControlBar.bounds(WIDTH, HEIGHT, HOME_ONLY)

    @Test
    fun `la pildora queda centrada abajo con su margen`() {
        assertEquals(WIDTH / 2f, bounds.centerX, 0.01f)
        assertEquals(HEIGHT - ControlBar.BOTTOM_MARGIN_PX, bounds.bottom, 0.01f)
        assertEquals(ControlBar.BUTTON_DIAMETER_PX + 2 * ControlBar.PADDING_PX, bounds.height, 0.01f)
    }

    @Test
    fun `el centro del boton de inicio lo devuelve`() {
        val x = ControlBar.buttonCenterX(0, WIDTH, HEIGHT, HOME_ONLY)
        assertEquals(Button.HOME, ControlBar.buttonAt(x, bounds.centerY, WIDTH, HEIGHT, HOME_ONLY))
    }

    @Test
    fun `con dos botones cada centro devuelve el suyo`() {
        val buttons = listOf(Button.HOME, Button.PORTRAIT)
        val centerY = ControlBar.bounds(WIDTH, HEIGHT, buttons).centerY
        val homeX = ControlBar.buttonCenterX(0, WIDTH, HEIGHT, buttons)
        val portraitX = ControlBar.buttonCenterX(1, WIDTH, HEIGHT, buttons)
        assertEquals(Button.HOME, ControlBar.buttonAt(homeX, centerY, WIDTH, HEIGHT, buttons))
        assertEquals(Button.PORTRAIT, ControlBar.buttonAt(portraitX, centerY, WIDTH, HEIGHT, buttons))
    }

    @Test
    fun `la esquina de la pildora no es boton pero si es barra`() {
        val x = bounds.left + 1f
        val y = bounds.top + 1f
        assertTrue(ControlBar.contains(x, y, WIDTH, HEIGHT, HOME_ONLY))
        assertNull(ControlBar.buttonAt(x, y, WIDTH, HEIGHT, HOME_ONLY))
    }

    @Test
    fun `el borde de abajo de la pantalla no es barra`() {
        assertFalse(ControlBar.contains(WIDTH / 2f, HEIGHT.toFloat(), WIDTH, HEIGHT, HOME_ONLY))
    }

    @Test
    fun `oculta no aparece al pasar por encima de la pildora`() {
        assertFalse(nextVisible(false, bounds.centerX, bounds.centerY))
    }

    @Test
    fun `aparece en la zona del borde de abajo en cualquier lugar`() {
        assertTrue(nextVisible(false, 0f, HEIGHT - ControlBar.REVEAL_ZONE_PX))
        assertTrue(nextVisible(false, bounds.centerX, HEIGHT.toFloat()))
    }

    @Test
    fun `visible no se oculta al subir hasta los botones`() {
        assertTrue(nextVisible(true, bounds.centerX, bounds.centerY))
        assertTrue(nextVisible(true, bounds.centerX, bounds.top - ControlBar.KEEP_MARGIN_PX))
    }

    @Test
    fun `visible se oculta al alejarse de la pildora`() {
        assertFalse(nextVisible(true, bounds.centerX, bounds.top - ControlBar.KEEP_MARGIN_PX - 1f))
        assertFalse(nextVisible(true, 0f, bounds.centerY))
    }

    private fun nextVisible(visible: Boolean, x: Float, y: Float) =
        ControlBar.nextVisible(visible, x, y, WIDTH, HEIGHT, HOME_ONLY)

    private companion object {
        const val WIDTH = 2340
        const val HEIGHT = 1080
        val HOME_ONLY = listOf(Button.HOME)
    }
}
