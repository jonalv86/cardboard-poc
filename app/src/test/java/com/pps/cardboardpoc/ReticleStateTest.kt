package com.pps.cardboardpoc

import org.junit.Assert.assertEquals
import org.junit.Test

class ReticleStateTest {

    private fun mode(
        press: JoystickManager.Press? = null,
        edge: JoystickManager.EdgeContact? = null,
        cursorX: Float = 100f,
        cursorY: Float = 100f,
        nowMs: Long = 1_000L
    ) = ReticleState.mode(press, edge, cursorX, cursorY, nowMs, LONG_PRESS_MS, TOUCH_SLOP_PX)

    @Test
    fun `sin dedo ni borde queda en reposo`() {
        assertEquals(ReticleMode.Rest, mode())
    }

    @Test
    fun `el dedo apoyado llena el anillo en el tiempo de pulsacion larga`() {
        val press = JoystickManager.Press(startMs = 1_000L, originX = 100f, originY = 100f)
        assertEquals(ReticleMode.Pressing(0f), mode(press, nowMs = 1_000L))
        assertEquals(ReticleMode.Pressing(0.5f), mode(press, nowMs = 1_200L))
    }

    @Test
    fun `al cumplir el tiempo de pulsacion larga pasa a sostenida`() {
        val press = JoystickManager.Press(startMs = 1_000L, originX = 100f, originY = 100f)
        assertEquals(ReticleMode.Holding, mode(press, nowMs = 1_400L))
    }

    @Test
    fun `al superar el slop pasa a sostenida antes de tiempo`() {
        val press = JoystickManager.Press(startMs = 1_000L, originX = 100f, originY = 100f)
        assertEquals(ReticleMode.Pressing(0.25f), mode(press, cursorX = 100f + TOUCH_SLOP_PX, nowMs = 1_100L))
        assertEquals(ReticleMode.Holding, mode(press, cursorX = 101f + TOUCH_SLOP_PX, nowMs = 1_100L))
    }

    @Test
    fun `en el borde se llena durante la espera y despues avanza`() {
        val edge = JoystickManager.EdgeContact(dirX = 1, dirY = 0, startMs = 1_000L)
        val halfway = 1_000L + JoystickManager.EDGE_DWELL_MS / 2
        assertEquals(ReticleMode.EdgeDwell(1, 0, 0.5f), mode(edge = edge, nowMs = halfway))
        val done = 1_000L + JoystickManager.EDGE_DWELL_MS
        assertEquals(ReticleMode.EdgeScrolling(1, 0), mode(edge = edge, nowMs = done))
    }

    @Test
    fun `el borde tiene prioridad sobre el dedo`() {
        val press = JoystickManager.Press(startMs = 1_000L, originX = 100f, originY = 100f)
        val edge = JoystickManager.EdgeContact(dirX = 0, dirY = -1, startMs = 1_000L)
        assertEquals(ReticleMode.EdgeDwell(0, -1, 0f), mode(press, edge))
    }

    private companion object {
        const val LONG_PRESS_MS = 400L
        const val TOUCH_SLOP_PX = 24f
    }
}
