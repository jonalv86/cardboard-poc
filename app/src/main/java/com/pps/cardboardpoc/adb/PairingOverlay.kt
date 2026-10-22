package com.pps.cardboardpoc.adb

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.pps.cardboardpoc.R

/**
 * Ventanita flotante con teclado numérico propio para tipear el código de emparejamiento SIN cambiar de
 * app. Es un overlay de accesibilidad (TYPE_ACCESSIBILITY_OVERLAY), no uno de aplicación: el diálogo
 * "Vincular dispositivo con código" del sistema oculta los overlays comunes (anti-tapjacking), pero no
 * los de accesibilidad, que son de confianza. Así queda encima del diálogo, que sigue en primer plano
 * anunciando su servicio mientras CeluVR empareja por detrás.
 *
 * La ventana pertenece al servicio de accesibilidad: solo su WindowManager lleva el token que autoriza
 * este tipo de overlay.
 */
class PairingOverlay(private val host: AccessibilityService) {

    private val windowManager = host.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val themedContext = ContextThemeWrapper(host, R.style.Theme_CeluVR)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = host.resources.displayMetrics.density

    private var root: View? = null
    private var codeView: TextView? = null
    private var statusView: TextView? = null
    private var pairButton: Button? = null

    private val code = StringBuilder()
    private var working = false

    private var onPaired: (() -> Unit)? = null

    fun show(onPaired: () -> Unit, onCancel: () -> Unit) {
        if (root != null) return
        this.onPaired = onPaired
        val view = buildView(onCancel)
        root = view

        val params = WindowManager.LayoutParams(
            (host.resources.displayMetrics.widthPixels * 0.9f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // No toma el foco (no le roba el teclado ni el Atrás al diálogo del sistema), pero sí recibe
            // toques porque no lleva NOT_TOUCHABLE: su propio teclado numérico responde.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(40)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
        }
        windowManager.addView(view, params)
    }

    fun hide() {
        val view = root ?: return
        root = null
        runCatching { windowManager.removeView(view) }
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private fun buildView(onCancel: () -> Unit): View {
        val card = LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#1E1E2E"))
                setStroke(dp(1), Color.parseColor("#5B5BD6"))
            }
        }

        card.addView(TextView(themedContext).apply {
            text = "Emparejar depuración inalámbrica"
            setTextColor(Color.WHITE)
            textSize = 16f
        })
        card.addView(TextView(themedContext).apply {
            text = "Dejá abierto el diálogo \"Vincular dispositivo con código\" del sistema y escribí acá su código."
            setTextColor(Color.parseColor("#B0B0C0"))
            textSize = 12f
            setPadding(0, dp(6), 0, dp(12))
        })

        codeView = TextView(themedContext).apply {
            setTextColor(Color.WHITE)
            textSize = 28f
            gravity = Gravity.CENTER
            letterSpacing = 0.3f
            setPadding(0, 0, 0, dp(12))
        }
        card.addView(codeView)

        card.addView(buildKeypad())

        statusView = TextView(themedContext).apply {
            setTextColor(Color.parseColor("#B0B0C0"))
            textSize = 12f
            setPadding(0, dp(10), 0, 0)
        }
        card.addView(statusView)

        card.addView(LinearLayout(themedContext).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
            addView(textButton("Cancelar") { if (!working) { hide(); onCancel() } }, rowParam())
            pairButton = textButton("Emparejar") { submit() }.also { it.isEnabled = false }
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
                line.addView(button, LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
            }
            grid.addView(line)
        }
        return grid
    }

    private fun keyButton(label: String, onClick: () -> Unit): Button =
        Button(themedContext).apply {
            text = label
            textSize = 18f
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
        statusView?.text = "Emparejando…"
        AdbServerLauncher.pair(host, code.toString()) { ok ->
            mainHandler.post {
                if (ok) {
                    statusView?.text = "¡Emparejado!"
                    hide()
                    onPaired?.invoke()
                } else {
                    working = false
                    code.setLength(0)
                    renderCode()
                    statusView?.text = "No se pudo. Reabrí el diálogo del sistema y probá de nuevo."
                }
            }
        }
    }
}
