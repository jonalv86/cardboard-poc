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
    fun `la cercania va de uno junto a la barra a cero lejos`() {
        assertEquals(1f, ControlBar.proximity(bounds.centerX, bounds.centerY, WIDTH, HEIGHT), 0f)
        val near = bounds.top - ControlBar.NEAR_DISTANCE_PX
        assertEquals(1f, ControlBar.proximity(bounds.centerX, near, WIDTH, HEIGHT), 0.001f)
        val middle = bounds.top - (ControlBar.NEAR_DISTANCE_PX + ControlBar.FAR_DISTANCE_PX) / 2f
        assertEquals(0.5f, ControlBar.proximity(bounds.centerX, middle, WIDTH, HEIGHT), 0.001f)
        assertEquals(0f, ControlBar.proximity(bounds.centerX, 0f, WIDTH, HEIGHT), 0f)
    }

    private companion object {
        const val WIDTH = 2340
        const val HEIGHT = 1080
    }
}
