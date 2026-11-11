package com.pps.cardboardpoc.adb

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.widget.Toast
import com.pps.cardboardpoc.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Lleva el setup del modo fluido mientras el usuario anda por los ajustes del sistema: abre el ajuste
 * del paso actual, deja encima el flotante del código y, al vincular, conecta y vuelve a CeluVR. Vive
 * fuera de la actividad porque el flotante pertenece al servicio de accesibilidad y la actividad puede
 * destruirse mientras está en segundo plano.
 */
object FluidSetup {

    enum class State {
        IDLE,

        /** Probando si la vinculación que CeluVR ya tiene sigue sirviendo, antes de pedir un código. */
        CHECKING,

        /** El flotante está sobre los ajustes, esperando el código. */
        IN_PROGRESS,

        /** Vinculado: conectando al adbd para dejar el modo fluido configurado. */
        CONNECTING,

        /** Configurado vinculando con un código desde los ajustes del sistema. */
        CONFIGURED,

        /** Encendido con la vinculación guardada, sin salir de CeluVR. */
        RECONNECTED,

        /** Se vinculó pero no se pudo conectar. */
        CONNECT_FAILED
    }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlay: PairingOverlay? = null

    /**
     * Arranca (o vuelve a encender) el modo fluido. Si se puede llegar al adbd, primero prueba conectar con
     * la clave que CeluVR ya tiene: si el celular la sigue teniendo vinculada, queda configurado sin pedir
     * código, y no se suma otra entrada de CeluVR a "Dispositivos vinculados". Si no sirve, abre el ajuste
     * del paso actual y deja el flotante minimizado encima.
     */
    fun start(activity: Activity, host: AccessibilityService) {
        if (_state.value == State.CHECKING) return
        if (overlay == null && canTryStoredPairing(activity)) {
            _state.value = State.CHECKING
            AdbServerLauncher.configure(activity) { ok ->
                mainHandler.post {
                    if (ok) {
                        _state.value = State.RECONNECTED
                        Toast.makeText(activity, "Modo fluido encendido con la vinculación guardada", Toast.LENGTH_SHORT).show()
                    } else {
                        _state.value = State.IDLE
                        openSettingsWithOverlay(activity, host)
                    }
                }
            }
            return
        }
        openSettingsWithOverlay(activity, host)
    }

    /**
     * Si tiene sentido probar la clave guardada: hace falta Wi-Fi y la Depuración inalámbrica encendida,
     * o el permiso para que CeluVR la encienda sola (que solo tiene si ya conectó alguna vez).
     */
    private fun canTryStoredPairing(context: Context): Boolean {
        val step = FluidSetupStep.current(context)
        return step != FluidSetupStep.DEVELOPER_OPTIONS &&
            (step == FluidSetupStep.PAIR || WirelessDebugging.hasPermission(context)) &&
            WirelessDebugging.hasWifi(context)
    }

    private fun openSettingsWithOverlay(activity: Activity, host: AccessibilityService) {
        FluidSetupStep.openSettings(activity, FluidSetupStep.current(activity))
        if (overlay != null) return
        _state.value = State.IN_PROGRESS
        overlay = PairingOverlay(host).also { created ->
            created.show(onPaired = { onPaired(host) }, onCancel = { finish(host, State.IDLE) })
        }
    }

    /**
     * Quita el flotante al volver a CeluVR: ahí ya no hace falta y taparía la app. Si estaba conectando,
     * la conexión sigue y su resultado llega igual por [state].
     */
    fun dismiss() {
        overlay?.hide()
        overlay = null
        if (_state.value == State.IN_PROGRESS) _state.value = State.IDLE
    }

    private fun onPaired(host: AccessibilityService) {
        _state.value = State.CONNECTING
        AdbServerLauncher.configure(host) { ok ->
            mainHandler.post { finish(host, if (ok) State.CONFIGURED else State.CONNECT_FAILED) }
        }
    }

    private fun finish(context: Context, result: State) {
        overlay?.hide()
        overlay = null
        _state.value = result
        if (result == State.CONFIGURED) Toast.makeText(context, "Modo fluido configurado", Toast.LENGTH_SHORT).show()
        // Los ajustes se abrieron desde CeluVR y quedan en su tarea, encima de MainActivity: CLEAR_TOP los
        // saca para que Atrás no vuelva a ellos.
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            ),
            ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()
        )
    }
}
