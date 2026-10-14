package com.pps.cardboardpoc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.SystemClock
import android.util.Log
import android.view.ViewConfiguration
import com.google.cardboard.sdk.CardboardView
import com.google.cardboard.sdk.HeadTransform
import com.google.cardboard.sdk.Viewport
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp

/**
 * Dibuja el frame capturado como un quad texturado, una vez por ojo, dentro del framebuffer lado a
 * lado que arma el Cardboard SDK, y encima la barra de controles y la retícula del control. La UI se
 * dibuja en cada cuadro, así que se mueve aunque la pantalla capturada no cambie.
 *
 * La corrección de barril no se aplica acá: el SDK la aplica después sobre ese framebuffer, con la
 * malla de distorsión que calcula para cada lente.
 *
 * La imagen no se mueve con la cabeza. La orientación de la cabeza va a [headPointer], que es null
 * cuando el teléfono no tiene giroscopio.
 */
class CardboardOverlayRenderer(
    private val context: Context,
    private val headPointer: HeadPointer?
) : CardboardView.Renderer {

    /** Último frame publicado por la captura, todavía no subido a la textura. */
    private val pendingFrame = AtomicReference<Bitmap?>(null)

    /** Aspecto de la retícula. Se puede cambiar desde cualquier hilo. */
    @Volatile
    var reticleStyle = ReticleStyle()

    /** Distancia que tiene que recorrer un dedo apoyado para que la app lo tome como arrastre. */
    private val touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    /** Tiempo con el dedo quieto a partir del cual la app lo toma como pulsación larga. */
    private var longPressMs = ViewConfiguration.getLongPressTimeout().toLong()

    private var program = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var scaleHandle = 0
    private var offsetHandle = 0
    private var alphaHandle = 0
    private var textureHandle = 0
    private var textureId = 0
    private var noticeTextureId = 0

    /** Textura del ícono de cada botón de [ControlBar.buttons], en el mismo orden. */
    private var iconTextureIds = IntArray(0)

    private var reticleProgram: UiProgram? = null
    private var shapeProgram: UiProgram? = null
    private var glowProgram: UiProgram? = null

    /** Si en este cuadro se dibuja el aviso de modo mouse; se decide una vez para los dos ojos. */
    private var noticeVisible = false

    /** Dimensiones con las que está alocada la textura, para decidir entre texImage2D y texSubImage2D. */
    private var textureWidth = 0
    private var textureHeight = 0

    /**
     * Pantalla capturada y cursor, en píxeles, tomados una vez por cuadro para que los dos ojos dibujen
     * lo mismo. Sin tamaño de pantalla no se dibuja la UI.
     */
    private var screenWidth = 0
    private var screenHeight = 0
    private var cursorX = 0f
    private var cursorY = 0f

    /**
     * Estado visual de la UI, calculado una vez por cuadro en [onNewFrame]. Los valores animados van
     * de 0 a 1 y siguen a su objetivo con una constante de tiempo fija; no afectan ningún umbral del
     * input, solo cómo se ve.
     */
    private var lastFrameMs = 0L
    private var ringFill = 1f
    private var morph = 0f
    private var hold = 0f
    private var pulse = 0f
    private var glow = 0f
    private var barAlpha = BAR_DIMMED_ALPHA
    private var hoveredButton = -1

    /** Último borde tocado; se conserva para animar la salida después de dejarlo. */
    private var edgeDirX = 0
    private var edgeDirY = 0

    private var frameAspect = 1f
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    private val vertexBuffer = QUAD_VERTICES.toFloatBuffer()
    private val texCoordBuffer = QUAD_TEX_COORDS.toFloatBuffer()

    /** Publica un frame nuevo. Se puede llamar desde cualquier hilo. */
    fun pushFrame(bitmap: Bitmap) {
        pendingFrame.set(bitmap)
    }

    override fun onSurfaceCreated(config: EGLConfig?) {
        longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
        reticleProgram = buildUiProgram(RETICLE_FRAGMENT_SHADER)
        shapeProgram = buildUiProgram(SHAPE_FRAGMENT_SHADER)
        glowProgram = buildUiProgram(GLOW_FRAGMENT_SHADER)

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (program == 0) return

        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        scaleHandle = GLES20.glGetUniformLocation(program, "uScale")
        offsetHandle = GLES20.glGetUniformLocation(program, "uOffset")
        alphaHandle = GLES20.glGetUniformLocation(program, "uAlpha")
        textureHandle = GLES20.glGetUniformLocation(program, "uTexture")
        noticeTextureId = createNoticeTexture()
        iconTextureIds = IntArray(ControlBar.buttons.size) { createIconTexture(ControlBar.buttons[it]) }

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )

        // La textura queda vacía hasta el primer frame, así que un tamaño previo no aplica.
        textureWidth = 0
        textureHeight = 0
    }

    override fun onSurfaceChanged(width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        Log.d(TAG, "superficie estéreo=${width}x$height, por ojo=${width / 2}x$height")
    }

    override fun onNewFrame(headTransform: HeadTransform) {
        // Una sola subida por frame: onDrawEye corre dos veces sobre la misma textura.
        uploadPendingFrame()
        headPointer?.update(headTransform)
        updateUiState()
        val lastMouseEvent = JoystickManager.lastMouseModeEventMs
        noticeVisible = lastMouseEvent != 0L &&
            SystemClock.uptimeMillis() - lastMouseEvent < JoystickManager.MOUSE_MODE_NOTICE_MS
    }

    override fun onDrawEye(eye: CardboardView.Eye) {
        // El SDK ya limpió el framebuffer y dejó puesto el viewport de esta mitad. Limpiar de nuevo
        // acá borraría el ojo dibujado antes, porque glClear ignora el viewport.
        if (program == 0) return

        val viewportAspect = (surfaceWidth / 2f) / surfaceHeight
        // La UI es translúcida y los colores de los shaders y de los bitmaps están premultiplicados.
        // Se apaga al terminar para no dejarle el estado cambiado a la pasada de distorsión del SDK.
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        if (textureWidth != 0) {
            // La pantalla capturada es mucho más ancha que la mitad que le toca a cada ojo; sin esto
            // la imagen sale estirada a lo alto.
            val scaleX: Float
            val scaleY: Float
            if (frameAspect > viewportAspect) {
                scaleX = 1f
                scaleY = viewportAspect / frameAspect
            } else {
                scaleX = frameAspect / viewportAspect
                scaleY = 1f
            }
            drawTexturedQuad(textureId, scaleX, scaleY, 0f, 0f)
            if (screenWidth != 0) {
                val space = ScreenSpace(scaleX, scaleY, surfaceWidth / 2f)
                GLES20.glEnable(GLES20.GL_BLEND)
                drawEdgeGlow(space)
                drawControlBar(space)
                drawReticle(space)
                GLES20.glDisable(GLES20.GL_BLEND)
            }
        }

        if (noticeVisible && noticeTextureId != 0) {
            drawTexturedQuad(
                noticeTextureId,
                NOTICE_HALF_WIDTH,
                NOTICE_HALF_WIDTH * viewportAspect / NOTICE_ASPECT,
                0f,
                NOTICE_CENTER_Y
            )
        }
    }

    /**
     * Pasa de píxeles de la pantalla capturada a coordenadas del ojo, con la misma escala que el quad
     * del frame: así la UI queda sobre los mismos píxeles que va a recibir el click.
     */
    private inner class ScreenSpace(scaleX: Float, scaleY: Float, eyeWidthPx: Float) {
        private val ndcPerPxX = 2f * scaleX / screenWidth
        private val ndcPerPxY = 2f * scaleY / screenHeight

        /** Píxeles de pantalla que caen en un píxel del framebuffer, para el antialias de los shaders. */
        val pxPerFramebufferPx = screenWidth / (scaleX * eyeWidthPx)

        fun x(px: Float): Float = (px - screenWidth / 2f) * ndcPerPxX
        fun y(px: Float): Float = (screenHeight / 2f - px) * ndcPerPxY
        fun width(px: Float): Float = px * ndcPerPxX
        fun height(px: Float): Float = px * ndcPerPxY
    }

    /** Programa de un quad de UI cuya forma sale del fragment shader, en píxeles de pantalla. */
    private class UiProgram(val id: Int) {
        private val uniforms = HashMap<String, Int>()
        val position = GLES20.glGetAttribLocation(id, "aPosition")
        val center = uniform("uCenter")
        val halfSize = uniform("uHalfSize")
        val halfSizePx = uniform("uHalfSizePx")
        val aa = uniform("uAa")

        fun uniform(name: String): Int =
            uniforms.getOrPut(name) { GLES20.glGetUniformLocation(id, name) }
    }

    /**
     * Activa [ui] y ubica su quad centrado en ([centerX], [centerY]) con medio tamaño
     * ([halfWidth], [halfHeight]), todo en píxeles de pantalla. Los uniforms propios del programa
     * se cargan después, antes de [drawUiQuad].
     */
    private fun useUiQuad(
        ui: UiProgram,
        space: ScreenSpace,
        centerX: Float,
        centerY: Float,
        halfWidth: Float,
        halfHeight: Float
    ) {
        GLES20.glUseProgram(ui.id)
        GLES20.glUniform2f(ui.center, space.x(centerX), space.y(centerY))
        GLES20.glUniform2f(ui.halfSize, space.width(halfWidth), space.height(halfHeight))
        GLES20.glUniform2f(ui.halfSizePx, halfWidth, halfHeight)
        GLES20.glUniform1f(ui.aa, space.pxPerFramebufferPx)
    }

    private fun drawUiQuad(ui: UiProgram) {
        GLES20.glEnableVertexAttribArray(ui.position)
        GLES20.glVertexAttribPointer(ui.position, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(ui.position)
    }

    private fun drawTexturedQuad(
        texture: Int,
        scaleX: Float,
        scaleY: Float,
        offsetX: Float,
        offsetY: Float,
        alpha: Float = 1f
    ) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(textureHandle, 0)
        GLES20.glUniform2f(scaleHandle, scaleX, scaleY)
        GLES20.glUniform2f(offsetHandle, offsetX, offsetY)
        GLES20.glUniform1f(alphaHandle, alpha)

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    /**
     * Crea la textura con el aviso de modo mouse. Se dibuja con Canvas una sola vez: el texto es fijo
     * y solo cambia si se muestra o no.
     */
    private fun createNoticeTexture(): Int {
        val bitmap = Bitmap.createBitmap(
            NOTICE_WIDTH_PX, (NOTICE_WIDTH_PX / NOTICE_ASPECT).toInt(), Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        canvas.drawColor(NOTICE_BACKGROUND)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = bitmap.height * 0.32f
            textAlign = Paint.Align.CENTER
        }
        val centerX = bitmap.width / 2f
        canvas.drawText("Control en modo mouse", centerX, bitmap.height * 0.42f, paint)
        canvas.drawText("Pasalo a modo joystick", centerX, bitmap.height * 0.82f, paint)
        return uploadTexture(bitmap)
    }

    /** Rasteriza el ícono vectorial del botón a una textura, una sola vez. */
    private fun createIconTexture(button: ControlBar.Button): Int {
        val resId = when (button) {
            ControlBar.Button.HOME -> R.drawable.ic_apps_rounded
        }
        val drawable = context.getDrawable(resId) ?: return 0
        val bitmap = Bitmap.createBitmap(ICON_TEXTURE_PX, ICON_TEXTURE_PX, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, ICON_TEXTURE_PX, ICON_TEXTURE_PX)
        drawable.draw(Canvas(bitmap))
        return uploadTexture(bitmap)
    }

    /** Sube el bitmap a una textura nueva y lo libera. */
    private fun uploadTexture(bitmap: Bitmap): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
        return ids[0]
    }

    /** Brillo a lo largo del borde hacia el que apunta el indicador de avance. */
    private fun drawEdgeGlow(space: ScreenSpace) {
        val glowUi = glowProgram ?: return
        if (glow <= 0.01f || (edgeDirX == 0 && edgeDirY == 0)) return

        val halfWidth = if (edgeDirX != 0) GLOW_THICKNESS_PX / 2f else screenWidth / 2f
        val halfHeight = if (edgeDirY != 0) GLOW_THICKNESS_PX / 2f else screenHeight / 2f
        useUiQuad(
            glowUi, space,
            screenWidth / 2f + edgeDirX * (screenWidth / 2f - halfWidth),
            screenHeight / 2f + edgeDirY * (screenHeight / 2f - halfHeight),
            halfWidth, halfHeight
        )
        // En GL el eje vertical crece hacia arriba y en la pantalla hacia abajo.
        GLES20.glUniform2f(glowUi.uniform("uNormal"), edgeDirX.toFloat(), -edgeDirY.toFloat())
        GLES20.glUniform1f(glowUi.uniform("uStrength"), glow)
        drawUiQuad(glowUi)
    }

    /** Dibuja la píldora de la barra con sus botones, encima de la imagen capturada. */
    private fun drawControlBar(space: ScreenSpace) {
        val shapeUi = shapeProgram ?: return
        val bar = ControlBar.bounds(screenWidth, screenHeight)
        drawRoundedBox(
            shapeUi, space, bar.centerX, bar.centerY, bar.width / 2f, bar.height / 2f,
            fillAlpha = BAR_FILL_ALPHA * barAlpha, fillWhite = false,
            borderAlpha = BAR_BORDER_ALPHA * barAlpha
        )

        val radius = ControlBar.BUTTON_DIAMETER_PX / 2f
        val iconHalf = radius * ICON_SIZE_FRACTION
        ControlBar.buttons.indices.forEach { index ->
            val centerX = ControlBar.buttonCenterX(index, screenWidth, screenHeight)
            if (index == hoveredButton) {
                drawRoundedBox(
                    shapeUi, space, centerX, bar.centerY, radius, radius,
                    fillAlpha = BUTTON_HOVER_ALPHA * barAlpha, fillWhite = true, borderAlpha = 0f
                )
            }
            val icon = iconTextureIds.getOrElse(index) { 0 }
            if (icon != 0) {
                drawTexturedQuad(
                    icon,
                    space.width(iconHalf),
                    space.height(iconHalf),
                    space.x(centerX),
                    space.y(bar.centerY),
                    barAlpha
                )
            }
        }
    }

    /**
     * Rectángulo con las puntas redondeadas al máximo: una píldora, o un círculo si es cuadrado. El
     * relleno es blanco o negro con [fillAlpha], con un borde fino blanco de [borderAlpha].
     */
    private fun drawRoundedBox(
        ui: UiProgram,
        space: ScreenSpace,
        centerX: Float,
        centerY: Float,
        halfWidth: Float,
        halfHeight: Float,
        fillAlpha: Float,
        fillWhite: Boolean,
        borderAlpha: Float
    ) {
        // Un poco más grande que la forma, para que el antialias del contorno no quede recortado.
        val margin = 2f * space.pxPerFramebufferPx
        useUiQuad(ui, space, centerX, centerY, halfWidth + margin, halfHeight + margin)
        GLES20.glUniform2f(ui.uniform("uBoxHalf"), halfWidth, halfHeight)
        GLES20.glUniform1f(ui.uniform("uCornerRadius"), minOf(halfWidth, halfHeight))
        val fillLevel = if (fillWhite) fillAlpha else 0f
        GLES20.glUniform4f(ui.uniform("uFillColor"), fillLevel, fillLevel, fillLevel, fillAlpha)
        GLES20.glUniform4f(ui.uniform("uBorderColor"), borderAlpha, borderAlpha, borderAlpha, borderAlpha)
        GLES20.glUniform1f(ui.uniform("uBorderWidth"), BAR_BORDER_PX)
        drawUiQuad(ui)
    }

    /**
     * Dibuja la retícula sobre el cursor. Sobre un borde se transforma en el indicador de avance, que
     * se corre hacia adentro para verse entero.
     */
    private fun drawReticle(space: ScreenSpace) {
        val reticleUi = reticleProgram ?: return
        val style = reticleStyle
        val shift = (INDICATOR_RADIUS_PX + INDICATOR_EDGE_GAP_PX) * morph
        val centerX = cursorX - edgeDirX * shift
        val centerY = cursorY - edgeDirY * shift
        val half = maxOf(INDICATOR_RADIUS_PX, style.radiusPx) + RETICLE_MARGIN_PX
        useUiQuad(reticleUi, space, centerX, centerY, half, half)

        GLES20.glUniform1f(reticleUi.uniform("uRadius"), style.radiusPx)
        GLES20.glUniform1f(reticleUi.uniform("uIndicatorRadius"), INDICATOR_RADIUS_PX)
        GLES20.glUniform1f(reticleUi.uniform("uMorph"), morph)
        GLES20.glUniform1f(reticleUi.uniform("uFill"), ringFill)
        GLES20.glUniform1f(reticleUi.uniform("uHold"), hold)
        GLES20.glUniform1f(reticleUi.uniform("uPulse"), pulse)
        // En GL el eje vertical crece hacia arriba y en la pantalla hacia abajo.
        GLES20.glUniform2f(
            reticleUi.uniform("uDirection"), edgeDirX.toFloat(), -edgeDirY.toFloat()
        )
        setColorUniform(reticleUi.uniform("uRingColor"), style.ringColor)
        setColorUniform(reticleUi.uniform("uHoldColor"), style.holdColor)
        drawUiQuad(reticleUi)
    }

    private fun setColorUniform(handle: Int, color: Int) {
        GLES20.glUniform3f(
            handle, Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f
        )
    }

    /**
     * Toma el cursor y el estado del input una vez por cuadro, y avanza las animaciones de la UI con
     * el tiempo transcurrido desde el cuadro anterior.
     */
    private fun updateUiState() {
        screenWidth = JoystickManager.screenWidth
        screenHeight = JoystickManager.screenHeight
        if (screenWidth == 0 || screenHeight == 0) {
            screenWidth = 0
            return
        }
        cursorX = JoystickManager.cursorX.coerceIn(0f, screenWidth.toFloat())
        cursorY = JoystickManager.cursorY.coerceIn(0f, screenHeight.toFloat())

        val now = SystemClock.uptimeMillis()
        val elapsedMs = if (lastFrameMs == 0L) 0L else (now - lastFrameMs).coerceAtMost(MAX_FRAME_STEP_MS)
        lastFrameMs = now
        val step = 1f - exp(-elapsedMs / ANIMATION_TIME_CONSTANT_MS)

        val mode = ReticleState.mode(
            JoystickManager.press, JoystickManager.edgeContact,
            cursorX, cursorY, now, longPressMs, touchSlopPx
        )
        var targetMorph = 0f
        var targetHold = 0f
        var targetGlow = 0f
        pulse = 0f
        when (mode) {
            ReticleMode.Rest -> ringFill = 1f
            is ReticleMode.Pressing -> ringFill = mode.fill
            ReticleMode.Holding -> {
                ringFill = 1f
                targetHold = 1f
            }
            is ReticleMode.EdgeDwell -> {
                edgeDirX = mode.dirX
                edgeDirY = mode.dirY
                ringFill = mode.fill
                targetMorph = 1f
                targetGlow = GLOW_MIN + (GLOW_DWELL_MAX - GLOW_MIN) * mode.fill
            }
            is ReticleMode.EdgeScrolling -> {
                edgeDirX = mode.dirX
                edgeDirY = mode.dirY
                ringFill = 1f
                targetMorph = 1f
                val phase = (now % PULSE_PERIOD_MS).toFloat() / PULSE_PERIOD_MS
                pulse = 0.5f - 0.5f * cos(2f * PI.toFloat() * phase)
                targetGlow = GLOW_DWELL_MAX + GLOW_PULSE * pulse
            }
        }
        morph += (targetMorph - morph) * step
        hold += (targetHold - hold) * step
        glow += (targetGlow - glow) * step
        if (targetMorph == 0f && morph < 0.01f) {
            morph = 0f
            edgeDirX = 0
            edgeDirY = 0
        }

        val proximity = ControlBar.proximity(cursorX, cursorY, screenWidth, screenHeight)
        val targetBarAlpha = BAR_DIMMED_ALPHA + (1f - BAR_DIMMED_ALPHA) * proximity
        barAlpha += (targetBarAlpha - barAlpha) * step
        val hovered = ControlBar.buttonAt(cursorX, cursorY, screenWidth, screenHeight)
        hoveredButton = if (hovered == null) -1 else ControlBar.buttons.indexOf(hovered)
    }

    override fun onFinishFrame(viewport: Viewport?) = Unit

    override fun onRendererShutdown() {
        val textures = (intArrayOf(textureId, noticeTextureId) + iconTextureIds).filter { it != 0 }
        if (textures.isNotEmpty()) {
            GLES20.glDeleteTextures(textures.size, textures.toIntArray(), 0)
        }
        textureId = 0
        noticeTextureId = 0
        iconTextureIds = IntArray(0)
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        listOfNotNull(reticleProgram, shapeProgram, glowProgram).forEach {
            GLES20.glDeleteProgram(it.id)
        }
        reticleProgram = null
        shapeProgram = null
        glowProgram = null
        textureWidth = 0
        textureHeight = 0
        lastFrameMs = 0L
        pendingFrame.set(null)
    }

    private fun uploadPendingFrame() {
        val bitmap = pendingFrame.getAndSet(null) ?: return
        if (textureId == 0 || bitmap.isRecycled) return

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        if (bitmap.width != textureWidth || bitmap.height != textureHeight) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            textureWidth = bitmap.width
            textureHeight = bitmap.height
        } else {
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
        }
        frameAspect = bitmap.width.toFloat() / bitmap.height
    }

    private fun buildUiProgram(fragmentSource: String): UiProgram? {
        val id = buildProgram(UI_VERTEX_SHADER, fragmentSource)
        return if (id == 0) null else UiProgram(id)
    }

    private fun buildProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (vertexShader == 0 || fragmentShader == 0) return 0

        val id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, vertexShader)
        GLES20.glAttachShader(id, fragmentShader)
        GLES20.glLinkProgram(id)

        // Los shaders quedan referenciados por el programa; borrarlos acá libera sus fuentes.
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)

        val status = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "no se pudo linkear el programa: ${GLES20.glGetProgramInfoLog(id)}")
            GLES20.glDeleteProgram(id)
            return 0
        }
        return id
    }

    private fun compileShader(type: Int, source: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, source)
        GLES20.glCompileShader(id)

        val status = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "no se pudo compilar el shader: ${GLES20.glGetShaderInfoLog(id)}")
            GLES20.glDeleteShader(id)
            return 0
        }
        return id
    }

    private companion object {
        const val TAG = "CardboardCapture"

        val QUAD_VERTICES = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
        )

        // GLUtils sube la primera fila del bitmap en t=0, así que la esquina de arriba del quad
        // (y = 1 en NDC) es la que lleva t=0.
        val QUAD_TEX_COORDS = floatArrayOf(
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f
        )

        const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            uniform vec2 uScale;
            uniform vec2 uOffset;
            varying vec2 vTexCoord;
            void main() {
                vTexCoord = aTexCoord;
                gl_Position = vec4(aPosition * uScale + uOffset, 0.0, 1.0);
            }
        """

        const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform float uAlpha;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord) * uAlpha;
            }
        """

        const val NOTICE_WIDTH_PX = 1024
        const val NOTICE_ASPECT = 5f
        const val NOTICE_BACKGROUND = 0xFFB71C1C.toInt()

        /** Media anchura del aviso en coordenadas del ojo (1 = el ojo entero). */
        const val NOTICE_HALF_WIDTH = 0.7f

        // Por encima del centro, para no tapar el cursor y lejos de los bordes, donde la
        // distorsión de la lente lo deforma más.
        const val NOTICE_CENTER_Y = 0.4f

        /** Lado de la textura de cada ícono de la barra. */
        const val ICON_TEXTURE_PX = 128

        /** Medio lado del ícono respecto del radio del botón. */
        const val ICON_SIZE_FRACTION = 0.5f

        /** Opacidad de la barra con el cursor lejos; cerca llega a 1. */
        const val BAR_DIMMED_ALPHA = 0.35f
        const val BAR_FILL_ALPHA = 0.55f
        const val BAR_BORDER_ALPHA = 0.28f
        const val BAR_BORDER_PX = 2f
        const val BUTTON_HOVER_ALPHA = 0.22f

        /** Radio del indicador de avance por borde, en píxeles de la pantalla capturada. */
        const val INDICATOR_RADIUS_PX = 48f

        /** Separación entre el indicador de avance y el borde al que apunta. */
        const val INDICATOR_EDGE_GAP_PX = 6f

        /** Espacio alrededor de la retícula para el halo y el antialias. */
        const val RETICLE_MARGIN_PX = 8f

        /** Ancho del brillo sobre el borde activo. */
        const val GLOW_THICKNESS_PX = 64f
        const val GLOW_MIN = 0.12f
        const val GLOW_DWELL_MAX = 0.4f
        const val GLOW_PULSE = 0.15f

        /** Período del pulso del chevron mientras la pantalla avanza. */
        const val PULSE_PERIOD_MS = 700L

        /** Constante de tiempo de las transiciones visuales. */
        const val ANIMATION_TIME_CONSTANT_MS = 50f

        /** Paso máximo de las animaciones, para que un cuadro demorado no las haga saltar. */
        const val MAX_FRAME_STEP_MS = 100L

        // Quad de UI: vLocal en píxeles de pantalla con el eje vertical hacia arriba, y vUnit de -1 a 1.
        const val UI_VERTEX_SHADER = """
            attribute vec2 aPosition;
            uniform vec2 uCenter;
            uniform vec2 uHalfSize;
            uniform vec2 uHalfSizePx;
            varying vec2 vLocal;
            varying vec2 vUnit;
            void main() {
                vUnit = aPosition;
                vLocal = aPosition * uHalfSizePx;
                gl_Position = vec4(uCenter + aPosition * uHalfSize, 0.0, 1.0);
            }
        """

        // Las formas salen de distancias con signo en píxeles; uAa es cuántos píxeles de pantalla
        // caen en un píxel del framebuffer, el ancho del antialias. Los colores van premultiplicados.
        //
        // uMorph pasa de la retícula (0) al indicador de borde (1): el anillo crece hasta el radio
        // del indicador, aparece el disco oscuro y el chevron, y se van el punto y el halo. El anillo
        // se llena desde arriba en sentido horario hasta uFill; el resto queda tenue.
        const val RETICLE_FRAGMENT_SHADER = """
            precision mediump float;
            uniform float uAa;
            uniform float uRadius;
            uniform float uIndicatorRadius;
            uniform float uMorph;
            uniform float uFill;
            uniform float uHold;
            uniform float uPulse;
            uniform vec2 uDirection;
            uniform vec3 uRingColor;
            uniform vec3 uHoldColor;
            varying vec2 vLocal;

            const float TAU = 6.2831853;

            float coverage(float d) {
                return clamp(0.5 - d / uAa, 0.0, 1.0);
            }

            vec4 over(vec4 top, vec4 bottom) {
                return top + bottom * (1.0 - top.a);
            }

            float segment(vec2 p, vec2 a, vec2 b) {
                vec2 pa = p - a;
                vec2 ba = b - a;
                float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
                return length(pa - ba * h);
            }

            void main() {
                float r = mix(uRadius, uIndicatorRadius, uMorph);
                float d = length(vLocal);
                vec3 active = mix(uRingColor, uHoldColor, uHold);
                float stroke = mix(mix(3.5, 6.0, uHold), 4.0, uMorph);
                float dotRadius = mix(4.0, 5.0, uHold);
                vec4 color = vec4(0.0);

                float disc = coverage(d - r) * uMorph * 0.55;
                color = over(vec4(0.0, 0.0, 0.0, disc), color);

                float ringDistance = abs(d - r) - stroke * 0.5;
                float halo = max(coverage(ringDistance - 1.5), coverage(d - dotRadius - 1.5));
                halo *= 0.35 * (1.0 - uMorph);
                color = over(vec4(0.0, 0.0, 0.0, halo), color);

                float angle = atan(vLocal.x, vLocal.y);
                float progress = fract(angle / TAU + 1.0);
                float filled = step(progress, uFill);
                float ring = coverage(ringDistance) * mix(0.35, 1.0, filled);
                color = over(vec4(active * ring, ring), color);

                float centerDot = coverage(d - dotRadius) * (1.0 - uMorph);
                color = over(vec4(active * centerDot, centerDot), color);

                vec2 across = vec2(-uDirection.y, uDirection.x);
                vec2 q = vec2(dot(vLocal, uDirection), abs(dot(vLocal, across)));
                float tip = r * (0.14 + 0.08 * uPulse);
                float arm = r * 0.32;
                float chevron = segment(q, vec2(tip, 0.0), vec2(tip - arm, arm)) - r * 0.07;
                float chevronAlpha = coverage(chevron) * uMorph;
                color = over(vec4(vec3(chevronAlpha), chevronAlpha), color);

                gl_FragColor = color;
            }
        """

        // Rectángulo de medio tamaño uBoxHalf con puntas de radio uCornerRadius, relleno y con un
        // borde interior de uBorderWidth.
        const val SHAPE_FRAGMENT_SHADER = """
            precision mediump float;
            uniform float uAa;
            uniform vec2 uBoxHalf;
            uniform float uCornerRadius;
            uniform vec4 uFillColor;
            uniform vec4 uBorderColor;
            uniform float uBorderWidth;
            varying vec2 vLocal;

            void main() {
                vec2 q = abs(vLocal) - uBoxHalf + vec2(uCornerRadius);
                float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - uCornerRadius;
                float inside = clamp(0.5 - d / uAa, 0.0, 1.0);
                float border = inside *
                    clamp(0.5 - (abs(d + uBorderWidth * 0.5) - uBorderWidth * 0.5) / uAa, 0.0, 1.0);
                vec4 fill = uFillColor * inside;
                vec4 edge = uBorderColor * border;
                gl_FragColor = edge + fill * (1.0 - edge.a);
            }
        """

        // Brillo blanco que se desvanece desde el borde (uNormal apunta hacia él) hacia adentro, y
        // también hacia las puntas del borde.
        const val GLOW_FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec2 uNormal;
            uniform float uStrength;
            varying vec2 vUnit;

            void main() {
                float t = (dot(vUnit, uNormal) + 1.0) * 0.5;
                float along = abs(dot(vUnit, vec2(-uNormal.y, uNormal.x)));
                float alpha = uStrength * t * t * (1.0 - smoothstep(0.7, 1.0, along));
                gl_FragColor = vec4(alpha);
            }
        """
    }
}

private fun FloatArray.toFloatBuffer(): FloatBuffer =
    ByteBuffer.allocateDirect(size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(this@toFloatBuffer)
            position(0)
        }
