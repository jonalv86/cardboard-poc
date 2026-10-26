package com.pps.cardboardpoc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.opengl.GLES20
import android.os.SystemClock
import android.view.ViewConfiguration
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp

/**
 * UI del visor que va encima de la imagen capturada: la retícula del control, la barra de controles,
 * el brillo del borde hacia el que avanza la pantalla y el aviso de modo mouse. Cada superficie GL
 * que la muestra tiene su propia instancia, con sus programas y texturas, y la usa solo desde su hilo
 * de render.
 *
 * El estado se toma una vez por cuadro en [update], así todo lo que se dibuja en ese cuadro muestra lo
 * mismo. Se dibuja en cada cuadro, así que se mueve aunque la pantalla capturada no cambie.
 */
class OverlayUi(private val context: Context) {

    /** Aspecto de la retícula. Se puede cambiar desde cualquier hilo. */
    @Volatile
    var reticleStyle = ReticleStyle()

    /** Distancia que tiene que recorrer un dedo apoyado para que la app lo tome como arrastre. */
    private val touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    /** Tiempo con el dedo quieto a partir del cual la app lo toma como pulsación larga. */
    private var longPressMs = ViewConfiguration.getLongPressTimeout().toLong()

    private var texturedProgram: GlQuad.TexturedProgram? = null
    private var reticleProgram: UiProgram? = null
    private var shapeProgram: UiProgram? = null
    private var glowProgram: UiProgram? = null
    private var noticeTextureId = 0

    /** Textura del ícono de cada [ControlBar.Button], indexada por su ordinal. */
    private var iconTextureIds = IntArray(0)

    /** Si en este cuadro se dibuja el aviso de modo mouse. */
    private var noticeVisible = false

    /**
     * Pantalla capturada y cursor, en píxeles, tomados una vez por cuadro. Sin tamaño de pantalla no
     * se dibuja la UI.
     */
    private var screenWidth = 0
    private var screenHeight = 0
    private var cursorX = 0f
    private var cursorY = 0f

    /**
     * Estado visual de la UI. Los valores animados van de 0 a 1 y siguen a su objetivo con una
     * constante de tiempo fija; no afectan ningún umbral del input, solo cómo se ve.
     */
    private var lastFrameMs = 0L
    private var ringFill = 1f
    private var morph = 0f
    private var hold = 0f
    private var pulse = 0f
    private var glow = 0f
    private var barReveal = 0f
    private var hoveredButton = -1

    /** Botones de la barra en este cuadro; [hoveredButton] es una posición en esta lista. */
    private var barButtons = ControlBar.buttons

    /** Si la pantalla capturada está en vertical, para resaltar su botón. */
    private var portraitActive = false

    /** Último borde tocado; se conserva para animar la salida después de dejarlo. */
    private var edgeDirX = 0
    private var edgeDirY = 0

    /** Crea los programas y las texturas. Corre al crearse la superficie, en su hilo de render. */
    fun onSurfaceCreated() {
        longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
        reticleProgram = buildUiProgram(RETICLE_FRAGMENT_SHADER)
        shapeProgram = buildUiProgram(SHAPE_FRAGMENT_SHADER)
        glowProgram = buildUiProgram(GLOW_FRAGMENT_SHADER)
        texturedProgram = GlQuad.buildTexturedProgram(GlQuad.TEXTURED_FRAGMENT_SHADER) ?: return
        noticeTextureId = createNoticeTexture()
        iconTextureIds = IntArray(ControlBar.Button.entries.size) {
            createIconTexture(ControlBar.Button.entries[it])
        }
    }

    /**
     * Toma el cursor y el estado del input, y avanza las animaciones con el tiempo transcurrido desde
     * el cuadro anterior. Una vez por cuadro, antes de dibujar.
     */
    fun update() {
        val lastMouseEvent = JoystickManager.lastMouseModeEventMs
        noticeVisible = lastMouseEvent != 0L &&
            SystemClock.uptimeMillis() - lastMouseEvent < JoystickManager.MOUSE_MODE_NOTICE_MS

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

        val barVisible = JoystickManager.controlBarVisible
        barReveal += ((if (barVisible) 1f else 0f) - barReveal) * step
        barButtons = ControlBar.buttons
        portraitActive = ScreenCaptureService.portrait.value
        val hovered = if (barVisible) {
            ControlBar.buttonAt(cursorX, cursorY, screenWidth, screenHeight, barButtons)
        } else {
            null
        }
        hoveredButton = if (hovered == null) -1 else barButtons.indexOf(hovered)
    }

    /**
     * Dibuja el brillo del borde, la barra y la retícula sobre la imagen capturada, que ocupa el medio
     * tamaño ([imageScaleX], [imageScaleY]) del viewport actual, de [viewportWidthPx] de ancho. Así la
     * UI queda sobre los mismos píxeles que va a recibir el click. Sin pantalla no dibuja nada.
     */
    fun draw(imageScaleX: Float, imageScaleY: Float, viewportWidthPx: Float) {
        if (screenWidth == 0) return
        val space = ScreenSpace(imageScaleX, imageScaleY, viewportWidthPx)
        // La UI es translúcida y los colores de los shaders y de los bitmaps están premultiplicados.
        // Se apaga al terminar para no dejarle el estado cambiado a quien dibuje después.
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glEnable(GLES20.GL_BLEND)
        drawEdgeGlow(space)
        drawControlBar(space)
        drawReticle(space)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /**
     * Dibuja el aviso de modo mouse, si toca en este cuadro, centrado arriba en el viewport actual,
     * cuyo ancho dividido el alto es [viewportAspect].
     */
    fun drawNotice(viewportAspect: Float) {
        val textured = texturedProgram ?: return
        if (!noticeVisible || noticeTextureId == 0) return
        GlQuad.draw(
            textured,
            noticeTextureId,
            NOTICE_HALF_WIDTH,
            NOTICE_HALF_WIDTH * viewportAspect / NOTICE_ASPECT,
            0f,
            NOTICE_CENTER_Y
        )
    }

    /** Libera los programas y las texturas. Corre al apagarse el render, en su hilo. */
    fun release() {
        val textures = (intArrayOf(noticeTextureId) + iconTextureIds).filter { it != 0 }
        if (textures.isNotEmpty()) {
            GLES20.glDeleteTextures(textures.size, textures.toIntArray(), 0)
        }
        noticeTextureId = 0
        iconTextureIds = IntArray(0)
        texturedProgram?.let { GLES20.glDeleteProgram(it.id) }
        texturedProgram = null
        listOfNotNull(reticleProgram, shapeProgram, glowProgram).forEach {
            GLES20.glDeleteProgram(it.id)
        }
        reticleProgram = null
        shapeProgram = null
        glowProgram = null
        lastFrameMs = 0L
    }

    /**
     * Pasa de píxeles de la pantalla capturada a coordenadas del viewport, con la misma escala que la
     * imagen capturada.
     */
    private inner class ScreenSpace(scaleX: Float, scaleY: Float, viewportWidthPx: Float) {
        private val ndcPerPxX = 2f * scaleX / screenWidth
        private val ndcPerPxY = 2f * scaleY / screenHeight

        /** Píxeles de pantalla que caen en un píxel del framebuffer, para el antialias de los shaders. */
        val pxPerFramebufferPx = screenWidth / (scaleX * viewportWidthPx)

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
        GLES20.glVertexAttribPointer(ui.position, 2, GLES20.GL_FLOAT, false, 0, GlQuad.vertices)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(ui.position)
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
        return GlQuad.uploadTexture(bitmap)
    }

    /** Rasteriza el ícono vectorial del botón a una textura, una sola vez. */
    private fun createIconTexture(button: ControlBar.Button): Int {
        val resId = when (button) {
            ControlBar.Button.HOME -> R.drawable.ic_apps_rounded
            ControlBar.Button.PORTRAIT -> R.drawable.ic_screen_rotation
        }
        val drawable = context.getDrawable(resId) ?: return 0
        val bitmap = Bitmap.createBitmap(ICON_TEXTURE_PX, ICON_TEXTURE_PX, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, ICON_TEXTURE_PX, ICON_TEXTURE_PX)
        drawable.draw(Canvas(bitmap))
        return GlQuad.uploadTexture(bitmap)
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

    /**
     * Dibuja la píldora de la barra con sus botones, encima de la imagen capturada. Al aparecer sube
     * desde el borde de abajo mientras se hace opaca; el recorrido es el margen inferior, así no se
     * sale de la imagen.
     */
    private fun drawControlBar(space: ScreenSpace) {
        val shapeUi = shapeProgram ?: return
        val textured = texturedProgram ?: return
        if (barReveal < 0.01f) return
        val bar = ControlBar.bounds(screenWidth, screenHeight, barButtons)
        val centerY = bar.centerY + (1f - barReveal) * ControlBar.BOTTOM_MARGIN_PX
        drawRoundedBox(
            shapeUi, space, bar.centerX, centerY, bar.width / 2f, bar.height / 2f,
            fillAlpha = BAR_FILL_ALPHA * barReveal, fillWhite = false,
            borderAlpha = BAR_BORDER_ALPHA * barReveal
        )

        val radius = ControlBar.BUTTON_DIAMETER_PX / 2f
        val iconHalf = radius * ICON_SIZE_FRACTION
        barButtons.forEachIndexed { index, button ->
            val centerX = ControlBar.buttonCenterX(index, screenWidth, screenHeight, barButtons)
            val active = button == ControlBar.Button.PORTRAIT && portraitActive
            val highlight = (if (active) BUTTON_ACTIVE_ALPHA else 0f) +
                (if (index == hoveredButton) BUTTON_HOVER_ALPHA else 0f)
            if (highlight > 0f) {
                drawRoundedBox(
                    shapeUi, space, centerX, centerY, radius, radius,
                    fillAlpha = highlight * barReveal, fillWhite = true,
                    borderAlpha = if (active) BAR_BORDER_ALPHA * barReveal else 0f
                )
            }
            val icon = iconTextureIds.getOrElse(button.ordinal) { 0 }
            if (icon != 0) {
                GlQuad.draw(
                    textured,
                    icon,
                    space.width(iconHalf),
                    space.height(iconHalf),
                    space.x(centerX),
                    space.y(centerY),
                    barReveal
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

    private fun buildUiProgram(fragmentSource: String): UiProgram? {
        val id = GlQuad.buildProgram(UI_VERTEX_SHADER, fragmentSource)
        return if (id == 0) null else UiProgram(id)
    }

    private companion object {
        const val NOTICE_WIDTH_PX = 1024
        const val NOTICE_ASPECT = 5f
        const val NOTICE_BACKGROUND = 0xFFB71C1C.toInt()

        /** Media anchura del aviso en coordenadas del viewport (1 = el viewport entero). */
        const val NOTICE_HALF_WIDTH = 0.7f

        // Por encima del centro, para no tapar el cursor y lejos de los bordes, donde la
        // distorsión de la lente lo deforma más.
        const val NOTICE_CENTER_Y = 0.4f

        /** Lado de la textura de cada ícono de la barra. */
        const val ICON_TEXTURE_PX = 128

        /** Medio lado del ícono respecto del radio del botón. */
        const val ICON_SIZE_FRACTION = 0.5f

        const val BAR_FILL_ALPHA = 0.55f
        const val BAR_BORDER_ALPHA = 0.28f
        const val BAR_BORDER_PX = 2f
        const val BUTTON_HOVER_ALPHA = 0.22f
        const val BUTTON_ACTIVE_ALPHA = 0.3f

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
