package com.pps.cardboardpoc

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.SurfaceControl
import android.view.View
import android.view.WindowManager
import com.google.cardboard.sdk.CardboardView
import com.google.cardboard.sdk.Initialize
import com.google.cardboard.sdk.LensDistortion
import com.google.cardboard.sdk.QrCode
import com.google.cardboard.sdk.nativetypes.EyeType
import com.google.cardboard.sdk.nativetypes.UvPoint

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
    private val renderer = CardboardOverlayRenderer(context, HeadPointer.createIfAvailable(context))

    private var cardboardView: CardboardView? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var backHitArea: View? = null

    /** Clones de la pantalla virtual colgados de esta ventana, uno por ojo. Vacío sin clones. */
    private var mirrors: List<SurfaceControl> = emptyList()
    private var mirrorSourceWidth = 0
    private var mirrorSourceHeight = 0
    private val mirrorLayoutListener =
        View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutMirrors() }

    /** Ventana con la UI encima de los clones, y lo que la dibuja. Null sin clones. */
    private var mirrorUi: GLSurfaceView? = null
    private var mirrorUiRenderer: MirrorUiRenderer? = null

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
        setUpSdkButtons(view)
        view.onResume()

        windowManager.addView(view, params)
        cardboardView = view
        windowParams = params
        Log.d(TAG, "overlay agregado type=${params.type} flags=0x${params.flags.toString(16)}")
    }

    /**
     * Quita FLAG_SECURE para que la ventana estéreo entre en capturas de pantalla, grabaciones y
     * Smart View. Solo es seguro una vez descartado que la proyección propia sea de pantalla completa.
     */
    fun allowScreenCapture() {
        val view = cardboardView ?: return
        val params = windowParams ?: return
        if (params.flags and WindowManager.LayoutParams.FLAG_SECURE == 0) return
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
        windowManager.updateViewLayout(view, params)
        Log.d(TAG, "overlay sin FLAG_SECURE flags=0x${params.flags.toString(16)}")
    }

    /** Publica un frame nuevo para los dos ojos. Se puede llamar desde cualquier hilo. */
    fun updateFrame(bitmap: Bitmap) {
        renderer.pushFrame(bitmap)
    }

    /**
     * Muestra [left] y [right], clones de la pantalla virtual de [sourceWidth] x [sourceHeight], uno
     * centrado en cada lente y por encima de la imagen estéreo, que deja de dibujarse. Los compone el
     * sistema en la pantalla física, que es segura: ahí se ve el video protegido que la captura recibe
     * en negro. A cambio no llevan la corrección de lente, que necesitaría leer esos píxeles. Devuelve
     * false, y libera los clones, si la ventana no está.
     */
    fun showMirrors(
        left: SurfaceControl,
        right: SurfaceControl,
        sourceWidth: Int,
        sourceHeight: Int
    ): Boolean {
        val view = cardboardView
        val root = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) view?.rootSurfaceControl else null
        if (view == null || root == null) {
            left.release()
            right.release()
            return false
        }
        hideMirrors()
        mirrors = listOf(left, right)
        mirrorSourceWidth = sourceWidth
        mirrorSourceHeight = sourceHeight
        mirrors.forEach { root.buildReparentTransaction(it)?.apply() }
        showMirrorUi()
        view.addOnLayoutChangeListener(mirrorLayoutListener)
        layoutMirrors()
        return true
    }

    /**
     * Agrega la ventana con la UI encima de los clones. Entre ventanas del mismo tipo queda arriba la
     * última agregada, así que tapa a la estéreo y a los clones que cuelgan de ella.
     */
    private fun showMirrorUi() {
        val uiRenderer = MirrorUiRenderer(context)
        val surface = GLSurfaceView(context).apply {
            setEGLContextClientVersion(2)
            // Con canal alfa: fuera de la UI la superficie queda transparente y deja ver los clones.
            setEGLConfigChooser(8, 8, 8, 8, 0, 0)
            holder.setFormat(PixelFormat.TRANSLUCENT)
            setZOrderOnTop(true)
            setRenderer(uiRenderer)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { ignoreInsets() }
        windowManager.addView(surface, params)
        mirrorUi = surface
        mirrorUiRenderer = uiRenderer
    }

    /** Cambia el tamaño de la pantalla que muestran los clones, al pasarla a vertical u horizontal. */
    fun setMirrorSource(width: Int, height: Int) {
        mirrorSourceWidth = width
        mirrorSourceHeight = height
        layoutMirrors()
    }

    /** Descuelga y libera los clones, y la imagen estéreo vuelve a dibujarse. No hace nada si no hay. */
    fun hideMirrors() {
        if (mirrors.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        cardboardView?.removeOnLayoutChangeListener(mirrorLayoutListener)
        mirrorUi?.let { removeWindow(it) }
        mirrorUi = null
        mirrorUiRenderer = null
        renderer.mirroredImageWidth = 0f
        val transaction = SurfaceControl.Transaction()
        mirrors.forEach { transaction.reparent(it, null) }
        transaction.apply()
        mirrors.forEach { it.release() }
        mirrors = emptyList()
        Log.d(TAG, "clones: quitados")
    }

    /**
     * Centra cada clon en su lente, con el tamaño más grande que entra en su mitad de pantalla sin
     * salirse de ella, y le pasa los rectángulos a la UI que va encima.
     */
    private fun layoutMirrors() {
        val view = cardboardView ?: return
        if (mirrors.isEmpty() || view.width == 0 || mirrorSourceWidth == 0) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val eyeWidth = view.width / 2f
        val centers = lensCenters(view.width, view.height)
        val halfWidth = centers.withIndex().minOf { (eye, center) ->
            minOf(center.x - eye * eyeWidth, (eye + 1) * eyeWidth - center.x)
        }
        val halfHeight = centers.minOf { minOf(it.y, view.height - it.y) }
        val scale = MIRROR_FILL *
            minOf(2f * halfWidth / mirrorSourceWidth, 2f * halfHeight / mirrorSourceHeight)
        val width = mirrorSourceWidth * scale
        val height = mirrorSourceHeight * scale
        val rects = centers.map {
            RectF(it.x - width / 2f, it.y - height / 2f, it.x + width / 2f, it.y + height / 2f)
        }

        val transaction = SurfaceControl.Transaction()
        mirrors.zip(rects).forEach { (mirror, rect) ->
            transaction
                .setLayer(mirror, Int.MAX_VALUE)
                .setScale(mirror, scale, scale)
                .setPosition(mirror, rect.left, rect.top)
                .setCrop(mirror, Rect(0, 0, mirrorSourceWidth, mirrorSourceHeight))
                .setVisibility(mirror, true)
        }
        transaction.apply()
        mirrorUiRenderer?.eyeRects = rects
        renderer.mirroredImageWidth = width / eyeWidth
        Log.d(
            TAG,
            "clones: ${mirrorSourceWidth}x$mirrorSourceHeight escala=$scale " +
                "lentes=${centers.joinToString { "(${it.x.toInt()},${it.y.toInt()})" }} " +
                "ventana=${view.width}x${view.height}"
        )
    }

    /**
     * Centro de cada lente en una ventana de [width] x [height], izquierda primero: el punto de la
     * pantalla que cada ojo ve de frente. Sale de los mismos parámetros del visor que usa el SDK para
     * la imagen estéreo: los del QR escaneado o, si no hay, los de Cardboard V1.
     */
    private fun lensCenters(width: Int, height: Int): List<PointF> {
        val params = QrCode.getSavedDeviceParams() ?: QrCode.getCardboardV1DeviceParams()
        val lens = LensDistortion(params, width, height)
        try {
            val projection = FloatArray(16)
            return listOf(EyeType.LEFT, EyeType.RIGHT).map { eye ->
                lens.getEyeProjectionMatrix(eye, PROJECTION_NEAR, PROJECTION_FAR, projection)
                // El frustum de cada ojo es asimétrico, y su corrimiento dice en qué punto de la
                // textura del ojo cae la dirección de frente.
                val ahead = UvPoint((1f - projection[8]) / 2f, (1f - projection[9]) / 2f)
                val screen = lens.undistortedUvForDistortedUv(ahead, eye)
                    ?: return@map PointF((eye + 0.5f) * width / 2f, height / 2f)
                // En la pantalla del SDK v crece hacia arriba; en la ventana, y crece hacia abajo.
                PointF(screen.u * width, (1f - screen.v) * height)
            }
        } finally {
            lens.close()
        }
    }

    /** Quita la ventana. No hace nada si no está agregada. */
    fun hide() {
        hideMirrors()
        val view = cardboardView ?: return
        cardboardView = null

        windowParams = null
        view.onPause()
        removeWindow(view)
        backHitArea?.let { removeWindow(it) }
        backHitArea = null
        view.onDestroy()
    }

    private fun removeWindow(view: View) {
        try {
            windowManager.removeView(view)
        } catch (_: IllegalArgumentException) {
            // El sistema pudo haber quitado la ventana por su cuenta (servicio desactivado, por ejemplo).
        }
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
            context.createDisplayContext(display), R.style.Theme_CeluVR
        )
    }

    /**
     * Oculta el engranaje del UI del SDK, que dispara el escaneo del QR del visor (sin perfil escaneado
     * el SDK usa los parámetros de Cardboard V1, y la activity del escáner ni siquiera está declarada en
     * el manifest), y conecta la X a una zona táctil propia que detiene la captura.
     */
    private fun setUpSdkButtons(view: CardboardView) {
        view.setOnSettingsButtonClick { }
        // setStereoRenderMode muestra los botones con un post al main looper, así que tocarlos en el
        // acto no sobreviviría: hay que encolarlo detrás de ese post.
        mainHandler.post {
            view.findViewById<View>(com.google.cardboard.sdk.R.id.ui_settings_button)?.visibility =
                View.GONE
            val back = view.findViewById<View>(com.google.cardboard.sdk.R.id.ui_back_button)
                ?: return@post
            back.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> placeBackHitArea(v) }
            placeBackHitArea(back)
        }
    }

    /**
     * Ubica sobre la X del SDK una ventana transparente que sí recibe toques. La ventana estéreo es
     * NOT_TOUCHABLE entera y Android no ofrece una API pública para hacer táctil solo una parte, así que
     * la X por sí sola no respondería. Es para el dedo, con el celular fuera del visor: la X está en la
     * esquina de la pantalla física, fuera de la imagen de cada ojo, donde el cursor no llega.
     */
    private fun placeBackHitArea(back: View) {
        if (cardboardView == null || back.width == 0 || back.visibility != View.VISIBLE) return

        val location = IntArray(2)
        back.getLocationOnScreen(location)
        val params = WindowManager.LayoutParams(
            back.width,
            back.height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = location[0]
            y = location[1]
            ignoreInsets()
        }

        val hitArea = backHitArea
        if (hitArea == null) {
            val view = View(context).apply {
                setOnClickListener {
                    Log.d(TAG, "X del visor: se detiene la captura")
                    context.startService(ScreenCaptureService.stopIntent(context))
                }
            }
            windowManager.addView(view, params)
            backHitArea = view
        } else {
            windowManager.updateViewLayout(hitArea, params)
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
                // Si en el selector se eligió la pantalla completa, la proyección propia incluiría esta
                // ventana y la espejaría dentro de sí misma. FLAG_SECURE la hace llegar en negro, que es
                // lo que permite detectar esa elección; con "una sola app" no se captura y se quita.
                WindowManager.LayoutParams.FLAG_SECURE or
                // Con la pantalla apagada el sistema corta la proyección y la sesión del visor termina;
                // dentro del visor nadie toca la pantalla, así que sin esto se apagaría por inactividad.
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            // OPAQUE evita que el compositor mezcle la ventana con las capas de abajo: se ve solo
            // el contenido capturado. Con TRANSLUCENT la app real se transparenta por detrás.
            PixelFormat.OPAQUE
        ).apply {
            alpha = 1f
            // Dentro del visor el teléfono está siempre acostado, y el SDK parte la pantalla en dos
            // ojos a lo ancho. Fijar la orientación desde esta ventana deja la pantalla horizontal
            // aunque al frente haya una app vertical: el sistema la muestra centrada, con bandas.
            screenOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            ignoreInsets()
        }
    }

    /**
     * Desde Android 11 los flags LAYOUT_IN_SCREEN / LAYOUT_NO_LIMITS no bastan: sin renunciar a los
     * insets la ventana se encoge y deja ver la app real por las franjas de las barras. También deja a
     * las dos ventanas en coordenadas de pantalla, que es lo que necesita la zona táctil de la X.
     */
    private fun WindowManager.LayoutParams.ignoreInsets() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setFitInsetsTypes(0)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    private companion object {
        const val TAG = "CardboardCapture"

        /** Fracción del lugar disponible alrededor de su lente que ocupa cada clon, del lado que lo limita. */
        const val MIRROR_FILL = 0.9f

        /** Planos de corte para pedir la proyección de cada ojo; solo importa su forma, no la distancia. */
        const val PROJECTION_NEAR = 0.1f
        const val PROJECTION_FAR = 100f
    }
}
