package com.pps.cardboardpoc

import com.pps.cardboardpoc.EdgeZone.Edge
import org.junit.Assert.assertEquals
import org.junit.Test

class EdgeZoneTest {

    @Test
    fun `lejos de los bordes no hay borde`() {
        assertEquals(EdgeZone.NONE, edgeAt(WIDTH / 2f, HEIGHT / 2f, EdgeZone.NONE))
    }

    @Test
    fun `tocar un borde lo toma`() {
        assertEquals(BOTTOM, edgeAt(WIDTH / 2f, HEIGHT.toFloat(), EdgeZone.NONE))
        assertEquals(LEFT, edgeAt(0f, HEIGHT / 2f, EdgeZone.NONE))
    }

    @Test
    fun `cerca de un borde sin tocarlo no lo toma`() {
        assertEquals(EdgeZone.NONE, edgeAt(WIDTH / 2f, HEIGHT - 0.3f, EdgeZone.NONE))
    }

    @Test
    fun `el temblor no suelta el borde tomado`() {
        assertEquals(BOTTOM, edgeAt(WIDTH / 2f, HEIGHT - 0.3f, BOTTOM))
        assertEquals(BOTTOM, edgeAt(WIDTH / 2f, HEIGHT - EdgeZone.EXIT_MARGIN_PX, BOTTOM))
        assertEquals(LEFT, edgeAt(EdgeZone.EXIT_MARGIN_PX, HEIGHT / 2f, LEFT))
    }

    @Test
    fun `alejarse mas que el margen suelta el borde`() {
        val away = HEIGHT - EdgeZone.EXIT_MARGIN_PX - 1f
        assertEquals(EdgeZone.NONE, edgeAt(WIDTH / 2f, away, BOTTOM))
        assertEquals(EdgeZone.NONE, edgeAt(EdgeZone.EXIT_MARGIN_PX + 1f, HEIGHT / 2f, LEFT))
    }

    @Test
    fun `en una esquina gana el borde vertical`() {
        assertEquals(BOTTOM, edgeAt(0f, HEIGHT.toFloat(), EdgeZone.NONE))
        assertEquals(BOTTOM, edgeAt(0f, HEIGHT.toFloat(), LEFT))
    }

    @Test
    fun `el borde vertical tomado se mantiene al tocar uno lateral`() {
        assertEquals(BOTTOM, edgeAt(0f, HEIGHT - 1f, BOTTOM))
    }

    @Test
    fun `soltar un borde y tocar otro toma el nuevo`() {
        assertEquals(RIGHT, edgeAt(WIDTH.toFloat(), HEIGHT / 2f, LEFT))
    }

    private fun edgeAt(x: Float, y: Float, current: Edge) =
        EdgeZone.edgeAt(x, y, WIDTH, HEIGHT, current)

    private companion object {
        const val WIDTH = 2340
        const val HEIGHT = 1080
        val BOTTOM = Edge(0, 1)
        val LEFT = Edge(-1, 0)
        val RIGHT = Edge(1, 0)
    }
}
