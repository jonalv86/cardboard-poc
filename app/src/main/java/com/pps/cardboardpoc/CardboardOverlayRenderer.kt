package com.pps.cardboardpoc

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import com.google.cardboard.sdk.CardboardView
import com.google.cardboard.sdk.HeadTransform
import com.google.cardboard.sdk.Viewport
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig

/**
 * Dibuja el frame capturado como un quad texturado, una vez por ojo, dentro del framebuffer lado a
 * lado que arma el Cardboard SDK, y encima la UI del visor ([OverlayUi]).
 *
 * La corrección de barril no se aplica acá: el SDK la aplica después sobre ese framebuffer, con la
 * malla de distorsión que calcula para cada lente.
 *
 * La imagen no se mueve con la cabeza. La orientación de la cabeza va a [headPointer], que es null
 * cuando el teléfono no tiene giroscopio.
 */
class CardboardOverlayRenderer(
    context: Context,
    private val headPointer: HeadPointer?
) : CardboardView.Renderer {

    /** Último frame publicado por la captura, todavía no subido a la textura. */
    private val pendingFrame = AtomicReference<Bitmap?>(null)

    private val ui = OverlayUi(context)

    /** Qué se ve a los costados de una imagen vertical. Se puede cambiar desde cualquier hilo. */
    @Volatile
    var portraitBackground = PortraitBackground.BLUR

    /**
     * Parte del ancho de cada ojo que ocupan los clones de la pantalla virtual cuando son ellos los
     * que la muestran, o 0 si la muestra este renderer. Con clones no se dibuja nada: quedan encima y
     * su UI va en otra superficie. Se puede cambiar desde cualquier hilo.
     */
    @Volatile
    var mirroredImageWidth = 0f

    /** Fondo a los costados de una imagen vertical. */
    enum class PortraitBackground {
        /** La misma imagen agrandada, desenfocada y oscurecida, como en los videos verticales. */
        BLUR,

        /** Negro. */
        BLACK
    }

    private var frameProgram: GlQuad.TexturedProgram? = null
    private var downsampleProgram: GlQuad.TexturedProgram? = null
    private var blurProgram: GlQuad.TexturedProgram? = null
    private var textureId = 0

    /**
     * Copia chica del frame de donde sale el fondo desenfocado, y el framebuffer con que se dibuja.
     * Valen 0 hasta que se usa ese fondo por primera vez.
     */
    private var blurTextureId = 0
    private var blurFramebufferId = 0
    private var blurWidth = 0
    private var blurHeight = 0

    /** Si la copia chica corresponde al frame que está en la textura. */
    private var blurUpToDate = false

    /** Dimensiones con las que está alocada la textura, para decidir entre texImage2D y texSubImage2D. */
    private var textureWidth = 0
    private var textureHeight = 0

    /** Si en este cuadro dibujan los clones en vez de este renderer; se decide una vez para los dos ojos. */
    private var mirrored = false

    private var frameAspect = 1f

    /**
     * Medio tamaño de la imagen en coordenadas del ojo (1 = el ojo entero), calculado una vez por
     * cuadro en [updateImageScale].
     */
    private var imageScaleX = 1f
    private var imageScaleY = 1f
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    /** Publica un frame nuevo. Se puede llamar desde cualquier hilo. */
    fun pushFrame(bitmap: Bitmap) {
        pendingFrame.set(bitmap)
    }

    override fun onSurfaceCreated(config: EGLConfig?) {
        ui.onSurfaceCreated()
        downsampleProgram = GlQuad.buildTexturedProgram(DOWNSAMPLE_FRAGMENT_SHADER)
        blurProgram = GlQuad.buildTexturedProgram(BLUR_FRAGMENT_SHADER)
        frameProgram = GlQuad.buildTexturedProgram(GlQuad.TEXTURED_FRAGMENT_SHADER) ?: return

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
        blurTextureId = 0
        blurFramebufferId = 0
        blurWidth = 0
        blurHeight = 0
        blurUpToDate = false
    }

    override fun onSurfaceChanged(width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        Log.d(TAG, "superficie estéreo=${width}x$height, por ojo=${width / 2}x$height")
    }

    override fun onNewFrame(headTransform: HeadTransform) {
        // Una sola subida por frame: onDrawEye corre dos veces sobre la misma textura.
        uploadPendingFrame()
        updateImageScale()
        val mirroredWidth = mirroredImageWidth
        mirrored = mirroredWidth > 0f
        if (!mirrored && showsBlurredBackground() && !blurUpToDate) updateBlur()
        headPointer?.update(headTransform, if (mirrored) mirroredWidth else imageScaleX)
        ui.update()
    }

    override fun onDrawEye(eye: CardboardView.Eye) {
        // El SDK ya limpió el framebuffer y dejó puesto el viewport de esta mitad. Limpiar de nuevo
        // acá borraría el ojo dibujado antes, porque glClear ignora el viewport.
        if (mirrored) return
        val frame = frameProgram ?: return

        val eyeWidth = surfaceWidth / 2f
        if (textureWidth != 0) {
            if (showsBlurredBackground() && blurUpToDate) drawBlurredBackground()
            GlQuad.draw(frame, textureId, imageScaleX, imageScaleY, 0f, 0f)
            ui.draw(imageScaleX, imageScaleY, eyeWidth)
        }
        ui.drawNotice(eyeWidth / surfaceHeight)
    }

    /**
     * Calcula el tamaño de la imagen dentro de cada ojo. Una imagen horizontal ocupa todo el ancho, sin
     * estirarse a lo alto. Una vertical no ocupa todo el alto: arriba y abajo quedaría fuera de lo que
     * se ve a través de la lente. Toma el alto que tendría la misma pantalla en horizontal, agrandado
     * [PORTRAIT_HEIGHT_RATIO] veces.
     */
    private fun updateImageScale() {
        if (surfaceHeight == 0) return
        val viewportAspect = (surfaceWidth / 2f) / surfaceHeight
        if (frameAspect > viewportAspect) {
            imageScaleX = 1f
            imageScaleY = viewportAspect / frameAspect
        } else {
            // En horizontal el aspecto de la pantalla es 1 / frameAspect.
            imageScaleY = (viewportAspect * frameAspect * PORTRAIT_HEIGHT_RATIO).coerceAtMost(1f)
            imageScaleX = frameAspect / viewportAspect * imageScaleY
        }
    }

    /** Si detrás de la imagen va el fondo desenfocado: solo con una imagen más angosta que el ojo. */
    private fun showsBlurredBackground(): Boolean =
        textureWidth != 0 && imageScaleX < 1f && portraitBackground == PortraitBackground.BLUR &&
            downsampleProgram != null && blurProgram != null

    /**
     * Reduce el frame a una copia chica, promediando una grilla de muestras por píxel de la copia: así
     * el fondo sale desenfocado sin titilar cuando el video se mueve. Se hace una vez por frame nuevo.
     */
    private fun updateBlur() {
        val downsample = downsampleProgram ?: return
        val width = BLUR_WIDTH_PX
        val height = (BLUR_WIDTH_PX / frameAspect).toInt().coerceAtLeast(1)
        if (width != blurWidth || height != blurHeight) createBlurTarget(width, height)
        if (blurFramebufferId == 0) return

        // El SDK dibuja los ojos en su propio framebuffer: hay que devolvérselo tal como estaba.
        val previousFramebuffer = IntArray(1)
        val previousViewport = IntArray(4)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, previousFramebuffer, 0)
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, previousViewport, 0)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, blurFramebufferId)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(downsample.id)
        // Cuatro por cuatro muestras repartidas sobre lo que cubre cada píxel de la copia.
        GLES20.glUniform2f(downsample.texel, 0.25f / width, 0.25f / height)
        GlQuad.draw(downsample, textureId, 1f, 1f, 0f, 0f)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, previousFramebuffer[0])
        GLES20.glViewport(
            previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]
        )
        blurUpToDate = true
    }

    /** Crea, o rehace con otro tamaño, la textura de la copia chica y su framebuffer. */
    private fun createBlurTarget(width: Int, height: Int) {
        deleteBlurTarget()
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        blurTextureId = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blurTextureId)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLES20.glGenFramebuffers(1, ids, 0)
        blurFramebufferId = ids[0]
        val previousFramebuffer = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, previousFramebuffer, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, blurFramebufferId)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, blurTextureId, 0
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, previousFramebuffer[0])
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "fondo desenfocado: framebuffer incompleto ($status)")
            deleteBlurTarget()
            return
        }
        blurWidth = width
        blurHeight = height
    }

    private fun deleteBlurTarget() {
        if (blurTextureId != 0) GLES20.glDeleteTextures(1, intArrayOf(blurTextureId), 0)
        if (blurFramebufferId != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(blurFramebufferId), 0)
        blurTextureId = 0
        blurFramebufferId = 0
        blurWidth = 0
        blurHeight = 0
    }

    /**
     * Dibuja detrás de la imagen vertical una franja del mismo alto y de todo el ancho del ojo, con la
     * misma imagen agrandada hasta llenarla: se ve el centro del frame, desenfocado, oscurecido y
     * apagándose hacia los costados del ojo.
     */
    private fun drawBlurredBackground() {
        val blur = blurProgram ?: return
        GLES20.glUseProgram(blur.id)
        GLES20.glUniform2f(blur.texel, 1f / blurWidth, 1f / blurHeight)
        GLES20.glUniform1f(blur.edgeFade, BLUR_EDGE_FADE_FRACTION)
        // Al llenar el ancho del ojo, de la imagen entra a lo alto la misma fracción que ella ocupa
        // del ancho. En la copia chica el borde de arriba de la imagen quedó en t = 1, al revés que en
        // el frame, así que la franja se toma invertida.
        val visible = imageScaleX
        GlQuad.draw(
            blur, blurTextureId, 1f, imageScaleY, 0f, 0f,
            alpha = BLUR_BRIGHTNESS, texScaleY = -visible, texOffsetY = 0.5f + visible / 2f
        )
    }

    override fun onFinishFrame(viewport: Viewport?) = Unit

    override fun onRendererShutdown() {
        if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        textureId = 0
        deleteBlurTarget()
        blurUpToDate = false
        listOfNotNull(frameProgram, downsampleProgram, blurProgram).forEach {
            GLES20.glDeleteProgram(it.id)
        }
        frameProgram = null
        downsampleProgram = null
        blurProgram = null
        ui.release()
        textureWidth = 0
        textureHeight = 0
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
        blurUpToDate = false
    }

    private companion object {
        const val TAG = "CardboardCapture"

        // Promedia 4 x 4 muestras separadas por uTexel alrededor del centro de cada píxel de destino.
        const val DOWNSAMPLE_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform vec2 uTexel;
            varying vec2 vTexCoord;
            void main() {
                vec4 sum = vec4(0.0);
                for (int i = 0; i < 4; i++) {
                    for (int j = 0; j < 4; j++) {
                        sum += texture2D(uTexture, vTexCoord + (vec2(float(i), float(j)) - 1.5) * uTexel);
                    }
                }
                gl_FragColor = sum / 16.0;
            }
        """

        // Suaviza la copia chica con un filtro de 3 x 3 píxeles (pesos 1-2-1), la oscurece con uAlpha
        // y la apaga hacia los costados del quad en una fracción uEdgeFade de su ancho.
        const val BLUR_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform vec2 uTexel;
            uniform float uAlpha;
            uniform float uEdgeFade;
            varying vec2 vTexCoord;
            varying float vQuadX;
            void main() {
                vec4 sum = vec4(0.0);
                for (int i = -1; i <= 1; i++) {
                    for (int j = -1; j <= 1; j++) {
                        float weight = (2.0 - abs(float(i))) * (2.0 - abs(float(j)));
                        sum += weight * texture2D(uTexture, vTexCoord + vec2(float(i), float(j)) * uTexel);
                    }
                }
                float fade = smoothstep(0.0, uEdgeFade, vQuadX) * smoothstep(0.0, uEdgeFade, 1.0 - vQuadX);
                gl_FragColor = vec4((sum / 16.0).rgb * uAlpha * fade, 1.0);
            }
        """

        /** Ancho de la copia chica del frame de donde sale el fondo desenfocado. */
        const val BLUR_WIDTH_PX = 32

        /** Brillo del fondo desenfocado respecto de la imagen (1 = igual de brillante). */
        const val BLUR_BRIGHTNESS = 0.5f

        /** Fracción del ancho del ojo en la que el fondo desenfocado se apaga hacia cada costado. */
        const val BLUR_EDGE_FADE_FRACTION = 0.3f

        /** Cuánto más alta se ve una imagen vertical que la misma pantalla en horizontal. */
        const val PORTRAIT_HEIGHT_RATIO = 1.1f
    }
}
