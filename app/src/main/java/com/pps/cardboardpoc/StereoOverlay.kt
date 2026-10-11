package com.pps.cardboardpoc

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.View
import android.view.WindowManager
import com.google.cardboard.sdk.CardboardView
import com.google.cardboard.sdk.Initialize

/**
 * Ventana flotante que dibuja el frame capturado en estéreo con el Cardboard SDK, para un visor
 * Cardboard. Vive por encima de cualquier app en primer plano, así que sobrevive a que el sistema
 * mande MainActivity a segundo plano.
 *
 * La ventana pertenece al servicio de accesibilidad: solo una ventana de ese tipo puede ser opaca y a
 * la vez dejar pasar los toques hacia la app de abajo, que es por donde entran los gestos del control.
 */
class StereoOverlay(private val context: AccessibilityService) {

    // Tiene que ser el WindowManager del propio servicio: es el que lleva el token que autoriza
    // TYPE_ACCESSIBILITY_OVERLAY.
    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val renderer = CardboardOverlayRenderer()

    private var cardboardView: CardboardView? = null

    /** Agrega la ventana al WindowManager. No hace nada si ya está agregada. */
    fun show() {
        if (cardboardView != null) return

        val sdkContext = buildSdkContext()
        // Carga la librería nativa y guarda la JavaVM y el contexto que el SDK va a consultar
        // después desde su capa nativa.
        Initialize.initialize(sdkContext)

        val params = buildLayoutParams()
        val view = CardboardView(sdkContext)
        view.layoutParams = params
        view.setRenderer(renderer)
        view.setStereoRenderMode(true)
        hideSdkButtons(view)
        view.onResume()

        windowManager.addView(view, params)
        cardboardView = view
        Log.d(TAG, "overlay agregado type=${params.type} flags=0x${params.flags.toString(16)}")
    }

    /** Publica un frame nuevo para los dos ojos. Se puede llamar desde cualquier hilo. */
    fun updateFrame(bitmap: Bitmap) {
        renderer.pushFrame(bitmap)
    }

    /** Quita la ventana. No hace nada si no está agregada. */
    fun hide() {
        val view = cardboardView ?: return
        cardboardView = null

        view.onPause()
        try {
            windowManager.removeView(view)
        } catch (_: IllegalArgumentException) {
            // El sistema pudo haber quitado la ventana por su cuenta (permiso revocado, por ejemplo).
        }
        view.onDestroy()
    }

    /**
     * Arma el contexto que consume el SDK.
     *
     * Para resolver la densidad de pantalla, el SDK llama a `context.getDisplay()` desde su capa
     * nativa. Un Context de Service no está asociado a ninguna pantalla y tira
     * UnsupportedOperationException; la excepción queda pendiente cuando vuelve a código nativo y
     * CheckJNI aborta el proceso en la siguiente llamada JNI. `createDisplayContext` devuelve un
     * contexto que sí está asociado a la pantalla.
     *
     * El tema encima hace falta porque el SDK infla su propio layout, con estilos propios.
     */
    private fun buildSdkContext(): Context {
        val display = context.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
        return ContextThemeWrapper(
            context.createDisplayContext(display), R.style.Theme_CardboardPOC
        )
    }

    /**
     * Oculta los dos botones del UI del SDK. El engranaje dispara el escaneo del QR del visor (sin
     * perfil escaneado el SDK usa los parámetros de Cardboard V1, y la activity del escáner ni siquiera
     * está declarada en el manifest), y la ventana no recibe toques, así que ninguno respondería.
     */
    private fun hideSdkButtons(view: CardboardView) {
        view.setOnSettingsButtonClick { }
        view.setOnBackButtonClick { }
        // setStereoRenderMode muestra los botones con un post al main looper, así que ocultarlos en
        // el acto no sobreviviría: hay que encolarlo detrás de ese post.
        mainHandler.post {
            listOf(
                com.google.cardboard.sdk.R.id.ui_settings_button,
                com.google.cardboard.sdk.R.id.ui_back_button
            ).forEach { view.findViewById<View>(it)?.visibility = View.GONE }
        }
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        // Android 12+ descarta los toques que atraviesan una ventana overlay opaca de una app
        // (BLOCK_UNTRUSTED_TOUCHES). TYPE_ACCESSIBILITY_OVERLAY cuenta como overlay de confianza, así
        // que puede ser opaca y NOT_TOUCHABLE a la vez: los gestos del control llegan a la app capturada.
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                // FLAG_SECURE evita que el overlay se espeje dentro de sí mismo al compartir la pantalla
                // completa. No lo excluye de la captura: lo pinta de negro, así que ese modo no sirve
                // igual y el modo que funciona es "una sola app", donde el overlay no se captura.
                WindowManager.LayoutParams.FLAG_SECURE or
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

    private companion object {
        const val TAG = "CardboardCapture"
    }
}
