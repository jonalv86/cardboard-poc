package com.pps.cardboardpoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomWindowTest {

    @Test
    fun `sin zoom la ventana es la pantalla entera y la vista coincide con la pantalla`() {
        val window = ZoomWindow.of(WIDTH, HEIGHT, LANDSCAPE, 1f)
        assertEquals(LANDSCAPE.scaleX, window.quadHalfWidth, DELTA)
        assertEquals(LANDSCAPE.scaleY, window.quadHalfHeight, DELTA)
        assertEquals(0f, window.fractionLeft, DELTA)
        assertEquals(0f, window.fractionTop, DELTA)
        assertEquals(1f, window.fractionWidth, DELTA)
        assertEquals(1f, window.fractionHeight, DELTA)
        assertEquals(WIDTH.toFloat(), window.viewWidth, 0.5f)
        assertEquals(HEIGHT.toFloat(), window.viewHeight, 0.5f)
        assertEquals(700f, window.toViewX(700f), DELTA)
        assertEquals(300f, window.toViewY(300f), DELTA)
    }

    @Test
    fun `con zoom horizontal la imagen llena el ancho y crece a lo alto hasta el tope`() {
        val window = ZoomWindow.of(WIDTH, HEIGHT, LANDSCAPE, 2f)
        assertEquals(1f, window.quadHalfWidth, DELTA)
        assertEquals(LANDSCAPE.maxHalfHeight, window.quadHalfHeight, DELTA)
        assertEquals(0.5f, window.fractionWidth, DELTA)
        assertEquals(LANDSCAPE.maxHalfHeight / (LANDSCAPE.scaleY * 2f), window.fractionHeight, DELTA)
    }

    @Test
    fun `una vertical crece a lo ancho sin recortar hasta llenar el ojo`() {
        val window = ZoomWindow.of(HEIGHT, WIDTH, PORTRAIT, 1.5f)
        assertEquals(PORTRAIT.scaleX * 1.5f, window.quadHalfWidth, DELTA)
        assertEquals(1f, window.fractionWidth, DELTA)
        assertTrue(window.fractionHeight < 1f)
    }

    @Test
    fun `una imagen que llena su lugar recorta lo mismo en los dos ejes`() {
        val window = ZoomWindow.of(WIDTH, HEIGHT, ZoomFit(1f, 1f, 1f), 2f)
        assertEquals(0.5f, window.fractionWidth, DELTA)
        assertEquals(0.5f, window.fractionHeight, DELTA)
        assertEquals(WIDTH.toFloat(), window.viewWidth, 0.5f)
    }

    @Test
    fun `sin zona muerta la ventana se centra en el cursor`() {
        val window = ZoomWindow.of(WIDTH, HEIGHT, LANDSCAPE, 2f).follow(WIDTH * 0.6f, HEIGHT / 2f, 0f)
        assertEquals(0.6f - window.fractionWidth / 2f, window.fractionLeft, DELTA)
        assertEquals(window.viewWidth / 2f, window.toViewX(WIDTH * 0.6f), 0.5f)
    }

    @Test
    fun `la ventana no se sale de la pantalla aunque el cursor llegue al borde`() {
        val window = ZoomWindow.of(WIDTH, HEIGHT, LANDSCAPE, 2f).follow(WIDTH.toFloat(), HEIGHT.toFloat(), 0f)
        assertEquals(1f, window.fractionLeft + window.fractionWidth, DELTA)
        assertEquals(1f, window.fractionTop + window.fractionHeight, DELTA)
        assertEquals(window.viewWidth, window.toViewX(WIDTH.toFloat()), 0.5f)
    }

    @Test
    fun `con zona muerta completa la ventana no se mueve mientras el cursor esta adentro`() {
        val start = ZoomWindow.of(WIDTH, HEIGHT, LANDSCAPE, 2f)
        val moved = start.follow(WIDTH * 0.7f, HEIGHT / 2f, 1f)
        assertEquals(start.fractionLeft, moved.fractionLeft, DELTA)
    }

    @Test
    fun `con zona muerta completa el cursor empuja el borde de la ventana`() {
        val start = ZoomWindow.of(WIDTH, HEIGHT, LANDSCAPE, 2f)
        val moved = start.follow(WIDTH * 0.8f, HEIGHT / 2f, 1f)
        assertEquals(0.8f, moved.fractionLeft + moved.fractionWidth, DELTA)
        assertEquals(moved.viewWidth, moved.toViewX(WIDTH * 0.8f), 0.5f)
    }

    @Test
    fun `en el medio de la pantalla la ventana con zoom no llega al borde de abajo`() {
        val window = ZoomWindow.of(WIDTH, HEIGHT, LANDSCAPE, 3f)
        assertTrue(window.fractionTop + window.fractionHeight < 1f)
    }

    @Test
    fun `el boton de zoom recorre los niveles y vuelve al primero`() {
        assertEquals(1.5f, ZoomLevels.next(1f), DELTA)
        assertEquals(2f, ZoomLevels.next(1.5f), DELTA)
        assertEquals(1f, ZoomLevels.next(2f), DELTA)
        assertEquals(1f, ZoomLevels.next(2.5f), DELTA)
    }

    @Test
    fun `acercar y alejar suben y bajan un nivel sin dar la vuelta`() {
        assertEquals(1.5f, ZoomLevels.higher(1f), DELTA)
        assertEquals(2f, ZoomLevels.higher(2f), DELTA)
        assertEquals(2f, ZoomLevels.higher(2.5f), DELTA)
        assertEquals(1.5f, ZoomLevels.lower(2f), DELTA)
        assertEquals(1f, ZoomLevels.lower(1f), DELTA)
        assertEquals(2f, ZoomLevels.lower(2.5f), DELTA)
    }

    @Test
    fun `al cambiar el zoom el punto bajo el cursor queda en el mismo lugar de la vista`() {
        val cursorX = WIDTH * 0.7f
        val cursorY = HEIGHT * 0.4f
        val before = ZoomWindow.of(WIDTH, HEIGHT, LANDSCAPE, 1.5f).follow(cursorX, cursorY, 1f)
        val after = before.rebuilt(WIDTH, HEIGHT, LANDSCAPE, 2f, cursorX, cursorY)
        assertEquals(2f, after.zoom, DELTA)
        assertEquals(
            before.toViewX(cursorX) - before.viewWidth / 2f,
            after.toViewX(cursorX) - after.viewWidth / 2f,
            0.5f
        )
    }

    private companion object {
        const val WIDTH = 2340
        const val HEIGHT = 1080
        const val DELTA = 1e-4f

        /** Pantalla horizontal en un ojo de 1170 x 1080: llena el ancho y la mitad del alto. */
        val LANDSCAPE = ZoomFit(1f, 0.5f, 0.55f)

        /** La misma pantalla en vertical, con el alto de la horizontal agrandado 1,1 veces. */
        val PORTRAIT = ZoomFit(0.2343f, 0.55f, 0.55f)
    }
}
