package com.pps.cardboardpoc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.SystemClock
import android.util.Log
import com.google.cardboard.sdk.CardboardView
import com.google.cardboard.sdk.HeadTransform
import com.google.cardboard.sdk.Viewport
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig

/**
 * Dibuja el frame capturado como un quad texturado, una vez por ojo, dentro del framebuffer lado a
 * lado que arma el Cardboard SDK, y encima el cursor del control. El cursor se dibuja en cada cuadro,
 * así que se mueve aunque la pantalla capturada no cambie.
 *
 * La corrección de barril no se aplica acá: el SDK la aplica después sobre ese framebuffer, con la
 * malla de distorsión que calcula para cada lente.
 */
class CardboardOverlayRenderer : CardboardView.Renderer {

    /** Último frame publicado por la captura, todavía no subido a la textura. */
    private val pendingFrame = AtomicReference<Bitmap?>(null)

    private var program = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var scaleHandle = 0
    private var offsetHandle = 0
    private var textureHandle = 0
    private var textureId = 0
    private var noticeTextureId = 0

    /** Si en este cuadro se dibuja el aviso de modo mouse; se decide una vez para los dos ojos. */
    private var noticeVisible = false

    /** Dimensiones con las que está alocada la textura, para decidir entre texImage2D y texSubImage2D. */
    private var textureWidth = 0
    private var textureHeight = 0

    private var cursorProgram = 0
    private var cursorPositionHandle = 0
    private var cursorCenterHandle = 0
    private var cursorHalfSizeHandle = 0
    private var cursorDirectionHandle = 0
    private var cursorFillHandle = 0

    /**
     * Borde sobre el que está el cursor, tomado una vez por cuadro: dirección (-1, 0 o 1 por eje, en
     * coordenadas de pantalla) y fracción de la espera cumplida. Con el llenado negativo se dibuja el
     * cursor común.
     */
    private var edgeDirX = 0
    private var edgeDirY = 0
    private var edgeFill = -1f

    /**
     * Posición del cursor como fracción de la pantalla (0..1), tomada una vez por cuadro para que los
     * dos ojos lo dibujen en el mismo lugar. Negativa si todavía no hay tamaño de pantalla.
     */
    private var cursorFractionX = -1f
    private var cursorFractionY = -1f

    private var frameAspect = 1f
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    private val vertexBuffer = QUAD_VERTICES.toFloatBuffer()
    private val texCoordBuffer = QUAD_TEX_COORDS.toFloatBuffer()
    private val eulerAngles = FloatArray(3)
    private var framesSeen = 0

    /** Publica un frame nuevo. Se puede llamar desde cualquier hilo. */
    fun pushFrame(bitmap: Bitmap) {
        pendingFrame.set(bitmap)
    }

    override fun onSurfaceCreated(config: EGLConfig?) {
        cursorProgram = buildProgram(CURSOR_VERTEX_SHADER, CURSOR_FRAGMENT_SHADER)
        cursorPositionHandle = GLES20.glGetAttribLocation(cursorProgram, "aPosition")
        cursorCenterHandle = GLES20.glGetUniformLocation(cursorProgram, "uCenter")
        cursorHalfSizeHandle = GLES20.glGetUniformLocation(cursorProgram, "uHalfSize")
        cursorDirectionHandle = GLES20.glGetUniformLocation(cursorProgram, "uDirection")
        cursorFillHandle = GLES20.glGetUniformLocation(cursorProgram, "uFill")

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (program == 0) return

        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        scaleHandle = GLES20.glGetUniformLocation(program, "uScale")
        offsetHandle = GLES20.glGetUniformLocation(program, "uOffset")
        textureHandle = GLES20.glGetUniformLocation(program, "uTexture")
        noticeTextureId = createNoticeTexture()

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
        snapshotCursor()
        val lastMouseEvent = JoystickManager.lastMouseModeEventMs
        noticeVisible = lastMouseEvent != 0L &&
            SystemClock.uptimeMillis() - lastMouseEvent < JoystickManager.MOUSE_MODE_NOTICE_MS
        logHeadOrientation(headTransform)
    }

    override fun onDrawEye(eye: CardboardView.Eye) {
        // El SDK ya limpió el framebuffer y dejó puesto el viewport de esta mitad. Limpiar de nuevo
        // acá borraría el ojo dibujado antes, porque glClear ignora el viewport.
        if (program == 0) return

        val viewportAspect = (surfaceWidth / 2f) / surfaceHeight
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
            drawCursor(scaleX, scaleY)
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

    private fun drawTexturedQuad(
        texture: Int,
        scaleX: Float,
        scaleY: Float,
        offsetX: Float,
        offsetY: Float
    ) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(textureHandle, 0)
        GLES20.glUniform2f(scaleHandle, scaleX, scaleY)
        GLES20.glUniform2f(offsetHandle, offsetX, offsetY)

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

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
        return ids[0]
    }

    /**
     * Dibuja el cursor encima del frame, en el mismo sistema de coordenadas que el quad: así queda
     * sobre el mismo píxel de la pantalla capturada que va a recibir el click. Mientras el cursor está
     * sobre un borde se dibuja como una flecha hacia ese borde que se llena durante la espera.
     */
    private fun drawCursor(scaleX: Float, scaleY: Float) {
        if (cursorProgram == 0 || cursorFractionX < 0f) return

        val onEdge = edgeFill >= 0f
        val radiusPx = if (onEdge) ARROW_RADIUS_PX else CURSOR_RADIUS_PX
        var fractionX = cursorFractionX
        var fractionY = cursorFractionY
        if (onEdge) {
            // El cursor está sobre el borde: la flecha se corre hacia adentro para que se vea entera.
            fractionX -= edgeDirX * radiusPx / textureWidth
            fractionY -= edgeDirY * radiusPx / textureHeight
        }
        val centerX = (fractionX * 2f - 1f) * scaleX
        val centerY = (1f - fractionY * 2f) * scaleY

        GLES20.glUseProgram(cursorProgram)
        GLES20.glUniform2f(cursorCenterHandle, centerX, centerY)
        // El radio se expresa en píxeles del frame, así que con la misma escala del quad el cursor
        // queda circular aunque el frame y la mitad de cada ojo tengan proporciones distintas.
        GLES20.glUniform2f(
            cursorHalfSizeHandle,
            radiusPx / textureWidth * 2f * scaleX,
            radiusPx / textureHeight * 2f * scaleY
        )
        // En GL el eje vertical crece hacia arriba y en la pantalla hacia abajo.
        GLES20.glUniform2f(cursorDirectionHandle, edgeDirX.toFloat(), -edgeDirY.toFloat())
        GLES20.glUniform1f(cursorFillHandle, edgeFill)
        GLES20.glEnableVertexAttribArray(cursorPositionHandle)
        GLES20.glVertexAttribPointer(cursorPositionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(cursorPositionHandle)
    }

    private fun snapshotCursor() {
        val width = JoystickManager.screenWidth
        val height = JoystickManager.screenHeight
        if (width == 0 || height == 0) {
            cursorFractionX = -1f
            return
        }
        cursorFractionX = (JoystickManager.cursorX / width).coerceIn(0f, 1f)
        cursorFractionY = (JoystickManager.cursorY / height).coerceIn(0f, 1f)

        val contact = JoystickManager.edgeContact
        if (contact == null) {
            edgeFill = -1f
        } else {
            edgeDirX = contact.dirX
            edgeDirY = contact.dirY
            edgeFill = contact.fill(SystemClock.uptimeMillis())
        }
    }

    override fun onFinishFrame(viewport: Viewport?) = Unit

    override fun onRendererShutdown() {
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        if (noticeTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(noticeTextureId), 0)
            noticeTextureId = 0
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        if (cursorProgram != 0) {
            GLES20.glDeleteProgram(cursorProgram)
            cursorProgram = 0
        }
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
    }

    /**
     * Deja rastro de la orientación que reporta el head tracker del SDK. En esta fase la imagen no
     * se mueve con la cabeza: el log es la única forma de confirmar que el tracker lee bien.
     */
    private fun logHeadOrientation(headTransform: HeadTransform) {
        if (framesSeen++ % HEAD_LOG_EVERY_N_FRAMES != 0) return

        headTransform.getEulerAngles(eulerAngles, 0)
        Log.d(
            TAG,
            String.format(
                Locale.US,
                "cabeza: pitch=%.1f yaw=%.1f roll=%.1f",
                Math.toDegrees(eulerAngles[0].toDouble()),
                Math.toDegrees(eulerAngles[1].toDouble()),
                Math.toDegrees(eulerAngles[2].toDouble())
            )
        )
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
        const val HEAD_LOG_EVERY_N_FRAMES = 60

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
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
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

        /** Radio del cursor en píxeles de la pantalla capturada. */
        const val CURSOR_RADIUS_PX = 20f

        const val CURSOR_VERTEX_SHADER = """
            attribute vec2 aPosition;
            uniform vec2 uCenter;
            uniform vec2 uHalfSize;
            varying vec2 vLocal;
            void main() {
                vLocal = aPosition;
                gl_Position = vec4(uCenter + aPosition * uHalfSize, 0.0, 1.0);
            }
        """

        /** Radio de la flecha de borde, en píxeles de la pantalla capturada. */
        const val ARROW_RADIUS_PX = 48f

        // Sin borde (uFill < 0): disco rojo con centro blanco, que se distingue sobre fondos claros y
        // oscuros. En un borde: punta de flecha con muesca atrás, orientada hacia uDirection, con contorno
        // blanco, gris por dentro y azul desde atrás hasta la fracción uFill del largo. La forma sale de
        // dos distancias con signo: a los lados de la punta (d1) y a los lados de la muesca (d2).
        const val CURSOR_FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec2 uDirection;
            uniform float uFill;
            varying vec2 vLocal;

            const float TIP = 0.78;
            const float BACK = -0.72;
            const float HALF_WIDTH = 0.72;
            const float NOTCH = -0.3;
            const float OUTLINE = 0.07;

            void main() {
                if (uFill < 0.0) {
                    float d = length(vLocal);
                    if (d > 1.0) discard;
                    gl_FragColor = d < 0.4 ? vec4(1.0) : vec4(1.0, 0.0, 0.0, 1.0);
                    return;
                }
                float along = dot(vLocal, uDirection);
                float across = abs(dot(vLocal, vec2(-uDirection.y, uDirection.x)));

                float k1 = HALF_WIDTH / (TIP - BACK);
                float d1 = (across + k1 * along - k1 * TIP) / sqrt(1.0 + k1 * k1);
                float k2 = (NOTCH - BACK) / HALF_WIDTH;
                float d2 = (NOTCH - k2 * across - along) / sqrt(1.0 + k2 * k2);
                float d = max(d1, d2);

                if (d > OUTLINE) discard;
                if (d > 0.0) {
                    gl_FragColor = vec4(1.0);
                    return;
                }
                float progress = (along - BACK) / (TIP - BACK);
                gl_FragColor = progress <= uFill
                    ? vec4(0.15, 0.45, 1.0, 1.0)
                    : vec4(0.4, 0.4, 0.4, 1.0);
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
