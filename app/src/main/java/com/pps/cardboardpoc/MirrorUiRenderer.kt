package com.pps.cardboardpoc

import android.content.Context
import android.graphics.RectF
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.roundToInt

/**
 * Dibuja la UI del visor ([OverlayUi]) sobre una superficie transparente, encima de cada rectángulo
 * de [eyeRects]: los que ocupan los clones de la pantalla virtual. Los clones no pasan por el render
 * estéreo, así que su UI tampoco: va sin corrección de lente, igual que ellos, para quedar sobre los
 * mismos píxeles.
 */
class MirrorUiRenderer(context: Context) : GLSurfaceView.Renderer {

    private val ui = OverlayUi(context)

    /** Rectángulo de cada clon, en píxeles de la superficie. Se puede cambiar desde cualquier hilo. */
    @Volatile
    var eyeRects: List<RectF> = emptyList()

    private var surfaceHeight = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        ui.onSurfaceCreated()
        GLES20.glClearColor(0f, 0f, 0f, 0f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        ui.update()
        for (rect in eyeRects) {
            // En GL el origen está abajo a la izquierda; en la ventana, arriba.
            GLES20.glViewport(
                rect.left.roundToInt(),
                surfaceHeight - rect.bottom.roundToInt(),
                rect.width().roundToInt(),
                rect.height().roundToInt()
            )
            ui.draw(1f, 1f, rect.width())
            ui.drawNotice(rect.width() / rect.height())
        }
    }
}
