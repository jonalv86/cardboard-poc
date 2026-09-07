package com.pps.cardboardpoc

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
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
    private var sizeLogged = false

    /** Agrega la ventana al WindowManager. No hace nada si ya está agregada. */
    @SuppressLint("InflateParams")
    fun show() {
        if (root != null) return

        // El root de una ventana no tiene parent al inflarse, así que pierde los layout_width y
        // layout_height del XML: los define el WindowManager con estos params.
        val params = buildLayoutParams()
        val view = LayoutInflater.from(context).inflate(R.layout.overlay_stereo, null)
        view.layoutParams = params
        windowManager.addView(view, params)
        root = view
        eyeLeft = view.findViewById(R.id.eyeLeft)
        eyeRight = view.findViewById(R.id.eyeRight)
    }

    /** Publica un frame nuevo en los dos ojos. Debe invocarse desde el main thread. */
    fun updateFrame(bitmap: Bitmap) {
        val left = eyeLeft ?: return
        val right = eyeRight ?: return

        if (!sizeLogged && left.width > 0) {
            sizeLogged = true
            Log.d(
                TAG,
                "root=${root?.width}x${root?.height} ojoIzq=${left.width}x${left.height} " +
                    "ojoDer=${right.width}x${right.height} bitmap=${bitmap.width}x${bitmap.height}"
            )
        }

        left.setImageBitmap(bitmap)
        right.setImageBitmap(bitmap)
    }

    /** Quita la ventana. No hace nada si no está agregada. */
    fun hide() {
        val view = root ?: return
        root = null
        eyeLeft = null
        eyeRight = null
        sizeLogged = false
        try {
            windowManager.removeView(view)
        } catch (_: IllegalArgumentException) {
            // El sistema pudo haber quitado la ventana por su cuenta (permiso revocado, por ejemplo).
        }
    }

    private companion object {
        const val TAG = "CardboardCapture"
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // La ventana consume los toques en lugar de dejarlos pasar. Android 12+ limita el alpha a 0.8
        // en toda ventana overlay que sea pass-through, como defensa contra interfaces falsas
        // superpuestas, y acá hace falta opacidad total. La contrapartida es que la app capturada
        // deja de recibir toques: para accionar sobre ella hay que pasar por un AccessibilityService,
        // único camino por el que una app normal puede despachar gestos a otra.
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            // OPAQUE evita que el compositor mezcle la ventana con las capas de abajo: se ve solo
            // el contenido capturado. Con TRANSLUCENT la app real se transparenta por detrás.
            PixelFormat.OPAQUE
        ).apply {
            alpha = 1f

            // Desde Android 11 los flags LAYOUT_IN_SCREEN / LAYOUT_NO_LIMITS no bastan: sin renunciar
            // a los insets la ventana se encoge y deja ver la app real por las franjas de las barras.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setFitInsetsTypes(0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
    }
}
