package com.pps.cardboardpoc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView

/**
 * Ventana flotante que dibuja el frame capturado duplicado en dos mitades, para un visor Cardboard.
 * Vive por encima de cualquier app en primer plano, así que sobrevive a que el sistema mande
 * MainActivity a segundo plano.
 */
class StereoOverlay(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var root: View? = null
    private var eyeLeft: ImageView? = null
    private var eyeRight: ImageView? = null

    /** Agrega la ventana al WindowManager. No hace nada si ya está agregada. */
    fun show() {
        if (root != null) return

        val view = LayoutInflater.from(context).inflate(R.layout.overlay_stereo, null)
        windowManager.addView(view, buildLayoutParams())
        root = view
        eyeLeft = view.findViewById(R.id.eyeLeft)
        eyeRight = view.findViewById(R.id.eyeRight)
    }

    /** Publica un frame nuevo en los dos ojos. Debe invocarse desde el main thread. */
    fun updateFrame(bitmap: Bitmap) {
        eyeLeft?.setImageBitmap(bitmap)
        eyeRight?.setImageBitmap(bitmap)
    }

    /** Quita la ventana. No hace nada si no está agregada. */
    fun hide() {
        val view = root ?: return
        root = null
        eyeLeft = null
        eyeRight = null
        try {
            windowManager.removeView(view)
        } catch (_: IllegalArgumentException) {
            // El sistema pudo haber quitado la ventana por su cuenta (permiso revocado, por ejemplo).
        }
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // NOT_TOUCHABLE deja pasar los toques a la app de abajo: el overlay solo dibuja.
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
    }
}
