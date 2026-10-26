package com.pps.cardboardpoc

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Lo que comparten las superficies GL del visor: el quad de -1 a 1 que se escala y se corre para
 * dibujar cada cosa, el programa que lo dibuja con una textura y la compilación de programas.
 */
internal object GlQuad {

    private const val TAG = "CardboardCapture"

    val vertices: FloatBuffer = floatArrayOf(
        -1f, -1f,
        1f, -1f,
        -1f, 1f,
        1f, 1f
    ).toFloatBuffer()

    // GLUtils sube la primera fila del bitmap en t=0, así que la esquina de arriba del quad
    // (y = 1 en NDC) es la que lleva t=0.
    private val texCoords: FloatBuffer = floatArrayOf(
        0f, 1f,
        1f, 1f,
        0f, 0f,
        1f, 0f
    ).toFloatBuffer()

    // vQuadX va de 0 a 1 a lo ancho del quad, para lo que dependa de la posición dentro de él.
    const val TEXTURED_VERTEX_SHADER = """
        attribute vec2 aPosition;
        attribute vec2 aTexCoord;
        uniform vec2 uScale;
        uniform vec2 uOffset;
        uniform vec2 uTexScale;
        uniform vec2 uTexOffset;
        varying vec2 vTexCoord;
        varying float vQuadX;
        void main() {
            vTexCoord = aTexCoord * uTexScale + uTexOffset;
            vQuadX = aTexCoord.x;
            gl_Position = vec4(aPosition * uScale + uOffset, 0.0, 1.0);
        }
    """

    const val TEXTURED_FRAGMENT_SHADER = """
        precision mediump float;
        uniform sampler2D uTexture;
        uniform float uAlpha;
        varying vec2 vTexCoord;
        void main() {
            gl_FragColor = texture2D(uTexture, vTexCoord) * uAlpha;
        }
    """

    /** Programa que dibuja un quad con una textura; los uniforms que no usa quedan en -1 y se ignoran. */
    class TexturedProgram(val id: Int) {
        val position = GLES20.glGetAttribLocation(id, "aPosition")
        val texCoord = GLES20.glGetAttribLocation(id, "aTexCoord")
        val scale = GLES20.glGetUniformLocation(id, "uScale")
        val offset = GLES20.glGetUniformLocation(id, "uOffset")
        val texScale = GLES20.glGetUniformLocation(id, "uTexScale")
        val texOffset = GLES20.glGetUniformLocation(id, "uTexOffset")
        val alpha = GLES20.glGetUniformLocation(id, "uAlpha")
        val texture = GLES20.glGetUniformLocation(id, "uTexture")
        val texel = GLES20.glGetUniformLocation(id, "uTexel")
        val edgeFade = GLES20.glGetUniformLocation(id, "uEdgeFade")
    }

    fun buildTexturedProgram(fragmentSource: String): TexturedProgram? {
        val id = buildProgram(TEXTURED_VERTEX_SHADER, fragmentSource)
        return if (id == 0) null else TexturedProgram(id)
    }

    /**
     * Dibuja [texture] con [textured] en un quad de medio tamaño ([scaleX], [scaleY]) corrido
     * ([offsetX], [offsetY]). De la textura toma la franja vertical que empieza en [texOffsetY] y mide
     * [texScaleY]; con una medida negativa la toma invertida.
     */
    fun draw(
        textured: TexturedProgram,
        texture: Int,
        scaleX: Float,
        scaleY: Float,
        offsetX: Float,
        offsetY: Float,
        alpha: Float = 1f,
        texScaleY: Float = 1f,
        texOffsetY: Float = 0f
    ) {
        GLES20.glUseProgram(textured.id)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(textured.texture, 0)
        GLES20.glUniform2f(textured.scale, scaleX, scaleY)
        GLES20.glUniform2f(textured.offset, offsetX, offsetY)
        GLES20.glUniform2f(textured.texScale, 1f, texScaleY)
        GLES20.glUniform2f(textured.texOffset, 0f, texOffsetY)
        GLES20.glUniform1f(textured.alpha, alpha)

        GLES20.glEnableVertexAttribArray(textured.position)
        GLES20.glVertexAttribPointer(textured.position, 2, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glEnableVertexAttribArray(textured.texCoord)
        GLES20.glVertexAttribPointer(textured.texCoord, 2, GLES20.GL_FLOAT, false, 0, texCoords)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(textured.position)
        GLES20.glDisableVertexAttribArray(textured.texCoord)
    }

    /** Sube el bitmap a una textura nueva y lo libera. */
    fun uploadTexture(bitmap: Bitmap): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
        return ids[0]
    }

    fun buildProgram(vertexSource: String, fragmentSource: String): Int {
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

    private fun FloatArray.toFloatBuffer(): FloatBuffer =
        ByteBuffer.allocateDirect(size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(this@toFloatBuffer)
                position(0)
            }
}
