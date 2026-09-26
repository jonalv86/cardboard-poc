package com.pps.cardboardpoc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
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
 */
class StereoOverlay(private val context: Context) {

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
        view.setOnBackButtonClick { context.startService(ScreenCaptureService.stopIntent(context)) }
        hideSettingsButton(view)
        view.onResume()

        windowManager.addView(view, params)
        cardboardView = view
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
     * Deja inerte el engranaje del UI del SDK, que dispara el escaneo del QR del visor: sin perfil
     * escaneado el SDK usa los parámetros de Cardboard V1, y la activity del escáner ni siquiera
     * está declarada en el manifest.
     */
    private fun hideSettingsButton(view: CardboardView) {
        view.setOnSettingsButtonClick { }
        // setStereoRenderMode muestra los botones con un post al main looper, así que ocultarlo en
        // el acto no sobreviviría: hay que encolarlo detrás de ese post.
        mainHandler.post {
            view.findViewById<View>(com.google.cardboard.sdk.R.id.ui_settings_button)?.visibility =
                View.GONE
        }
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
                // FLAG_SECURE excluye la ventana del espejado de MediaProjection. Sin esto, al
                // compartir la pantalla completa el overlay forma parte de lo que se captura y
                // termina dibujándose dentro de sí mismo, generación tras generación.
                WindowManager.LayoutParams.FLAG_SECURE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            // OPAQUE evita que el compositor mezcle la ventana con las capas de abajo: se ve solo
            // el contenido capturado. Con TRANSLUCENT la app real se transparenta por detrás.
            PixelFormat.OPAQUE // PixelFormat.TRANSLUCENT para que tome los touch de fondo
        ).apply {
            alpha = 1f //0.7f Para que se vea el fondo

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
