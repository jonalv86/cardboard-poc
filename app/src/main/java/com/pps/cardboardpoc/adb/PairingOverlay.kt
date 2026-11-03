package com.pps.cardboardpoc.adb

import android.accessibilityservice.AccessibilityService
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.pps.cardboardpoc.R
import io.github.muntashirakon.adb.android.AdbMdns
import kotlin.math.hypot

/**
 * Flotante para tipear el código de vinculación SIN salir de los ajustes del sistema, que invalidan el
 * código si se cierra el diálogo "Vincular dispositivo con código". Alterna entre una burbuja chica con
 * el ícono de la app y un panel compacto con teclado numérico propio; los dos se arrastran para no tapar
 * el código del sistema. El panel se abre solo cuando aparece el diálogo del sistema, que se detecta por
 * el servicio de vinculación que anuncia por mDNS mientras está abierto.
 *
 * Es un overlay de accesibilidad (TYPE_ACCESSIBILITY_OVERLAY), no uno de aplicación: el diálogo del
 * sistema oculta los overlays comunes (anti-tapjacking), pero no los de accesibilidad, que son de
 * confianza. La ventana pertenece al servicio de accesibilidad: solo su WindowManager lleva el token que
 * autoriza este tipo de overlay.
 */
class PairingOverlay(private val host: AccessibilityService) {

    private val windowManager = host.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val themedContext = ContextThemeWrapper(host, R.style.Theme_CeluVR)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = host.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(host).scaledTouchSlop

    private var bubble: View? = null
    private var panel: View? = null
    private var attached: View? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var panelParams: WindowManager.LayoutParams

    private var codeView: TextView? = null
    private var hintView: TextView? = null
    private var settingsButton: Button? = null
    private var statusView: TextView? = null
    private var pairButton: Button? = null

    private val code = StringBuilder()
    private var working = false
    private var paired = false

    /** Si el diálogo del sistema está abierto, según el anuncio mDNS de su servicio de vinculación. */
    private var dialogOpen = false
    private var pairingTarget: Pair<String, Int>? = null
    private var mdns: AdbMdns? = null

    private var onPaired: (() -> Unit)? = null

    /** Marco que señala en los ajustes la opción que hay que tocar en el paso actual. */
    private var highlight: View? = null
    private var highlightPulse: ObjectAnimator? = null
    private lateinit var highlightParams: WindowManager.LayoutParams

    private val labelsByStep = mutableMapOf<FluidSetupStep, List<String>>()

    private val pairLabel by lazy { FluidSetupStep.pairWithCodeLabel(host) }

    private val highlightPoll = object : Runnable {
        override fun run() {
            updateHighlight()
            mainHandler.postDelayed(this, HIGHLIGHT_POLL_MS)
        }
    }

    private val settingsObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) = renderHint()
    }

    fun show(onPaired: () -> Unit, onCancel: () -> Unit) {
        if (bubble != null) return
        this.onPaired = onPaired
        bubbleParams = windowParams(WindowManager.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            x = host.resources.displayMetrics.widthPixels - dp(BUBBLE_DP) - dp(12)
            y = host.resources.displayMetrics.heightPixels / 3
        }
        val panelWidth = minOf((host.resources.displayMetrics.widthPixels * 0.92f).toInt(), dp(360))
        // Arranca arriba: One UI muestra el diálogo del código abajo.
        panelParams = windowParams(panelWidth, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { y = dp(48) }
        highlightParams = WindowManager.LayoutParams(
            0, 0,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
        }
        bubble = buildBubble()
        panel = buildPanel(onCancel)
        attach(bubble!!, bubbleParams)
        mainHandler.post(highlightPoll)

        host.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED), false, settingsObserver
        )
        host.contentResolver.registerContentObserver(WirelessDebugging.settingUri, false, settingsObserver)
        startWatchingDialog()
    }

    fun hide() {
        if (bubble == null) return
        mainHandler.removeCallbacks(highlightPoll)
        removeHighlight()
        stopWatchingDialog()
        host.contentResolver.unregisterContentObserver(settingsObserver)
        attached?.let { runCatching { windowManager.removeView(it) } }
        attached = null
        bubble = null
        panel = null
    }

    private fun windowParams(width: Int, gravity: Int) = WindowManager.LayoutParams(
        width,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // No toma el foco (no le roba el teclado ni el Atrás al diálogo del sistema), pero sí recibe
        // toques porque no lleva NOT_TOUCHABLE: su propio teclado numérico responde.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        this.gravity = gravity
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
    }

    private fun attach(view: View, params: WindowManager.LayoutParams) {
        if (attached === view) return
        attached?.let { runCatching { windowManager.removeView(it) } }
        windowManager.addView(view, params)
        attached = view
    }

    private fun expand() {
        val view = panel ?: return
        renderHint()
        attach(view, panelParams)
    }

    private fun minimize() {
        val view = bubble ?: return
        attach(view, bubbleParams)
    }

    private fun startWatchingDialog() {
        mdns = AdbMdns(host, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { address, port ->
            mainHandler.post { onPairingServiceChanged(address?.hostAddress, port) }
        }.also { it.start() }
    }

    private fun stopWatchingDialog() {
        mdns?.stop()
        mdns = null
    }

    private fun onPairingServiceChanged(address: String?, port: Int) {
        if (bubble == null || paired) return
        val wasOpen = dialogOpen
        dialogOpen = port > 0
        pairingTarget = if (port > 0 && address != null) address to port else null
        if (dialogOpen && !wasOpen) {
            statusView?.text = ""
            expand()
        } else if (!dialogOpen && wasOpen && !working) {
            code.setLength(0)
            renderCode()
            statusView?.text = "Se cerró el diálogo del sistema y su código ya no sirve. Volvé a tocar \"$pairLabel\"."
        }
        renderHint()
    }

    /**
     * Ubica en la ventana de Ajustes la opción del paso actual y la enmarca. Los ajustes no avisan cuándo
     * cambia su pantalla (el servicio no se suscribe a eventos), así que se consulta cada
     * [HIGHLIGHT_POLL_MS]; con el diálogo del sistema abierto ya no hay nada que señalar.
     */
    private fun updateHighlight() {
        val bounds = if (dialogOpen || paired) null else findTargetBounds()
        if (bounds == null) {
            removeHighlight()
            return
        }
        val margin = dp(4)
        highlightParams.x = bounds.left - margin
        highlightParams.y = bounds.top - margin
        highlightParams.width = bounds.width() + 2 * margin
        highlightParams.height = bounds.height() + 2 * margin
        val view = highlight
        if (view != null) {
            runCatching { windowManager.updateViewLayout(view, highlightParams) }
            return
        }
        val frame = View(themedContext).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setStroke(dp(3), Color.parseColor("#7C7CFF"))
                setColor(Color.parseColor("#227C7CFF"))
            }
        }
        windowManager.addView(frame, highlightParams)
        highlight = frame
        highlightPulse = ObjectAnimator.ofFloat(frame, View.ALPHA, 1f, 0.35f).apply {
            duration = 700
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun removeHighlight() {
        highlightPulse?.cancel()
        highlightPulse = null
        highlight?.let { runCatching { windowManager.removeView(it) } }
        highlight = null
    }

    /**
     * Límites en pantalla de la fila de Ajustes que hay que tocar en el paso actual, o null si no está a
     * la vista. Cada paso prueba sus textos en orden: el primero que aparece es el más cercano a la meta.
     */
    private fun findTargetBounds(): Rect? {
        // Sin suscripción a eventos, la lista de ventanas queda cacheada con las del momento en que se
        // consultó por primera vez.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) host.clearCache()
        val roots = runCatching { host.windows.mapNotNull { it.root } }.getOrDefault(emptyList())
            .filter { it.packageName == FluidSetupStep.SETTINGS_PACKAGE }
        if (roots.isEmpty()) return null
        for (label in targetLabels(FluidSetupStep.current(host))) {
            for (root in roots) {
                // Solo filas tocables: el mismo texto aparece como título de la pantalla, que no se toca.
                val row = root.findAccessibilityNodeInfosByText(label)
                    .filter { it.isVisibleToUser }
                    .firstNotNullOfOrNull { clickableRow(it) } ?: continue
                return Rect().also { row.getBoundsInScreen(it) }
            }
        }
        return null
    }

    private fun targetLabels(step: FluidSetupStep): List<String> =
        labelsByStep.getOrPut(step) { FluidSetupStep.targetLabels(host, step) }

    /** La fila tocable que contiene al texto: en Ajustes el título no es tocable, la fila entera sí. */
    private fun clickableRow(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(6) {
            val candidate = current ?: return null
            if (candidate.isClickable) return candidate
            current = candidate.parent
        }
        return null
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private fun cardBackground(radiusDp: Int) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(Color.parseColor("#1E1E2E"))
        setStroke(dp(1), Color.parseColor("#5B5BD6"))
    }

    private fun buildBubble(): View {
        val view = FrameLayout(themedContext).apply {
            background = cardBackground(BUBBLE_DP / 2)
            contentDescription = "Abrir el código de vinculación"
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(
                ImageView(themedContext).apply { setImageResource(R.drawable.ic_celuvr_logo) },
                FrameLayout.LayoutParams(dp(BUBBLE_DP - 16), dp(BUBBLE_DP - 16))
            )
        }
        view.setOnTouchListener(Dragger({ bubble }, { bubbleParams }, horizontal = true) { expand() })
        return view
    }

    private fun buildPanel(onCancel: () -> Unit): View {
        val card = LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(12))
            background = cardBackground(16)
        }

        val header = LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL
            setOnTouchListener(Dragger({ panel }, { panelParams }, horizontal = false, onTap = null))
        }
        header.addView(View(themedContext).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(2).toFloat()
                setColor(Color.parseColor("#5A5A70"))
            }
        }, LinearLayout.LayoutParams(dp(36), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setMargins(0, dp(2), 0, dp(4))
        })
        header.addView(LinearLayout(themedContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(themedContext).apply {
                text = "Código de vinculación"
                setTextColor(Color.WHITE)
                textSize = 15f
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(themedContext).apply {
                text = "—"
                contentDescription = "Minimizar"
                setTextColor(Color.WHITE)
                textSize = 18f
                gravity = Gravity.CENTER
                setOnClickListener { minimize() }
            }, LinearLayout.LayoutParams(dp(40), dp(36)))
        })
        card.addView(header)

        hintView = TextView(themedContext).apply {
            setTextColor(Color.parseColor("#B0B0C0"))
            textSize = 12f
            setPadding(0, dp(2), 0, dp(4))
        }
        card.addView(hintView)

        settingsButton = textButton("") { openCurrentStepSettings() }
        card.addView(settingsButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(4), dp(2), dp(4), dp(6)) })

        codeView = TextView(themedContext).apply {
            setTextColor(Color.WHITE)
            textSize = 24f
            gravity = Gravity.CENTER
            letterSpacing = 0.3f
            setPadding(0, dp(4), 0, dp(6))
        }
        card.addView(codeView)

        card.addView(buildKeypad())

        statusView = TextView(themedContext).apply {
            setTextColor(Color.parseColor("#B0B0C0"))
            textSize = 12f
            setPadding(0, dp(6), 0, 0)
        }
        card.addView(statusView)

        card.addView(LinearLayout(themedContext).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
            addView(textButton("Cancelar") { if (!working) { hide(); onCancel() } }, rowParam())
            pairButton = textButton("Vincular") { submit() }.also { it.isEnabled = false }
            addView(pairButton, rowParam())
        })

        renderCode()
        return card
    }

    private fun buildKeypad(): View {
        val grid = LinearLayout(themedContext).apply { orientation = LinearLayout.VERTICAL }
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", ""))
        for (row in rows) {
            val line = LinearLayout(themedContext).apply { orientation = LinearLayout.HORIZONTAL }
            for (key in row) {
                val button = when (key) {
                    "" -> View(themedContext)
                    "⌫" -> keyButton(key) { onBackspace() }
                    else -> keyButton(key) { onDigit(key) }
                }
                line.addView(button, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            }
            grid.addView(line)
        }
        return grid
    }

    private fun keyButton(label: String, onClick: () -> Unit): Button =
        Button(themedContext).apply {
            text = label
            textSize = 17f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#2A2A3C"))
            }
            setOnClickListener { if (!working) onClick() }
        }

    private fun textButton(label: String, onClick: () -> Unit): Button =
        Button(themedContext).apply {
            text = label
            textSize = 14f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#5B5BD6"))
            }
            setOnClickListener { onClick() }
        }

    private fun rowParam() =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { setMargins(dp(4), 0, dp(4), 0) }

    /** Indica qué falta según el estado real del teléfono, mientras el diálogo del sistema no esté abierto. */
    private fun renderHint() {
        val hint = hintView ?: return
        val button = settingsButton ?: return
        if (dialogOpen) {
            hint.text = "Escribí el código de 6 dígitos que muestra el sistema."
            button.visibility = View.GONE
            return
        }
        val step = FluidSetupStep.current(host)
        hint.text = when (step) {
            FluidSetupStep.DEVELOPER_OPTIONS ->
                "Tocá 7 veces \"Número de compilación\" para activar las Opciones de desarrollador (en Samsung está dentro de \"Información de software\")."
            FluidSetupStep.WIRELESS_DEBUGGING ->
                "Activá \"Depuración inalámbrica\" y entrá a esa opción."
            FluidSetupStep.PAIR ->
                "Entrá a \"Depuración inalámbrica\" y tocá \"$pairLabel\": CeluVR te marca dónde."
        }
        button.text = if (step == FluidSetupStep.DEVELOPER_OPTIONS) "Ir a Acerca del teléfono" else "Ir a Depuración inalámbrica"
        button.visibility = View.VISIBLE
    }

    private fun openCurrentStepSettings() {
        FluidSetupStep.openSettings(host, FluidSetupStep.current(host))
        minimize()
    }

    private fun onDigit(digit: String) {
        if (code.length >= 6) return
        code.append(digit)
        renderCode()
    }

    private fun onBackspace() {
        if (code.isNotEmpty()) code.deleteCharAt(code.length - 1)
        renderCode()
    }

    private fun renderCode() {
        val shown = buildString {
            for (i in 0 until 6) {
                append(if (i < code.length) code[i] else '•')
                if (i < 5) append(' ')
            }
        }
        codeView?.text = shown
        pairButton?.isEnabled = code.length == 6 && !working
    }

    private fun submit() {
        if (code.length != 6 || working) return
        working = true
        pairButton?.isEnabled = false
        statusView?.text = "Vinculando…"
        AdbServerLauncher.pair(host, code.toString(), pairingTarget) { result ->
            mainHandler.post {
                if (bubble == null) return@post
                when (result) {
                    AdbServerLauncher.PairResult.PAIRED -> {
                        paired = true
                        stopWatchingDialog()
                        statusView?.text = "¡Vinculado! Conectando…"
                        onPaired?.invoke()
                    }
                    AdbServerLauncher.PairResult.NO_DIALOG -> {
                        working = false
                        renderCode()
                        statusView?.text = "No se encontró el diálogo del sistema. Abrí \"$pairLabel\" y volvé a tocar Vincular."
                    }
                    AdbServerLauncher.PairResult.REJECTED -> {
                        working = false
                        code.setLength(0)
                        renderCode()
                        statusView?.text = "El código no coincide o ya venció. Revisá el que muestra el sistema y probá de nuevo."
                    }
                }
            }
        }
    }

    /**
     * Arrastra la ventana que devuelve [window] moviendo su [params]. Un toque sin arrastre llama a
     * [onTap]. Con gravedad abajo, la y de la ventana se mide desde el borde inferior y crece hacia arriba.
     */
    private inner class Dragger(
        private val window: () -> View?,
        private val params: () -> WindowManager.LayoutParams,
        private val horizontal: Boolean,
        private val onTap: (() -> Unit)?
    ) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val view = window() ?: return false
            val p = params()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = p.x
                    startY = p.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && hypot(dx, dy) > touchSlop) dragging = true
                    if (dragging) {
                        val metrics = host.resources.displayMetrics
                        if (horizontal) {
                            p.x = (startX + dx).toInt().coerceIn(0, maxOf(0, metrics.widthPixels - view.width))
                        }
                        val fromBottom = p.gravity and Gravity.VERTICAL_GRAVITY_MASK == Gravity.BOTTOM
                        val y = if (fromBottom) startY - dy else startY + dy
                        p.y = y.toInt().coerceIn(0, maxOf(0, metrics.heightPixels - view.height))
                        runCatching { windowManager.updateViewLayout(view, p) }
                    }
                }
                MotionEvent.ACTION_UP -> if (!dragging) {
                    v.performClick()
                    onTap?.invoke()
                }
            }
            return true
        }
    }

    private companion object {
        const val BUBBLE_DP = 56
        const val HIGHLIGHT_POLL_MS = 700L
    }
}
