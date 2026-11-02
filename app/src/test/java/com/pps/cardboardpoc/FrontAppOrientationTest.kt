package com.pps.cardboardpoc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontAppOrientationTest {

    private val orientation = FrontAppOrientation()

    @Test
    fun `una app que solo funciona en vertical no se pasa sola a vertical`() {
        assertFalse(orientation.portrait(false, INSTAGRAM, WindowFit.PORTRAIT_ONLY))
    }

    @Test
    fun `la vertical elegida se mantiene en la misma app`() {
        orientation.portrait(false, INSTAGRAM, WindowFit.PORTRAIT_ONLY)
        assertTrue(orientation.portrait(true, INSTAGRAM, WindowFit.FULL))
    }

    @Test
    fun `al pasar a otra app vuelve a horizontal`() {
        orientation.portrait(true, INSTAGRAM, WindowFit.FULL)
        assertFalse(orientation.portrait(true, CHROME, WindowFit.FULL))
    }

    @Test
    fun `un dialogo de otra app no cambia la orientacion`() {
        orientation.portrait(true, INSTAGRAM, WindowFit.FULL)
        assertTrue(orientation.portrait(true, PERMISSIONS, WindowFit.PARTIAL))
        assertTrue(orientation.portrait(true, INSTAGRAM, WindowFit.FULL))
    }

    @Test
    fun `sin app al frente no cambia la orientacion`() {
        orientation.portrait(true, INSTAGRAM, WindowFit.FULL)
        assertTrue(orientation.portrait(true, null, WindowFit.PARTIAL))
    }

    @Test
    fun `una pantalla que solo funciona en horizontal vuelve a horizontal`() {
        orientation.portrait(true, YOUTUBE, WindowFit.FULL)
        assertFalse(orientation.portrait(true, YOUTUBE, WindowFit.LANDSCAPE_ONLY))
    }

    @Test
    fun `la primera app vista respeta la vertical elegida`() {
        assertTrue(orientation.portrait(true, INSTAGRAM, WindowFit.FULL))
    }

    private companion object {
        const val INSTAGRAM = "com.instagram.android"
        const val CHROME = "com.android.chrome"
        const val YOUTUBE = "com.google.android.youtube"
        const val PERMISSIONS = "com.google.android.permissioncontroller"
    }
}
