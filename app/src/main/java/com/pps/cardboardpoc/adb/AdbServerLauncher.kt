package com.pps.cardboardpoc.adb

import android.content.Context
import android.util.Log
import com.pps.cardboardpoc.vdisplay.VDisplayConnection
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Convierte a CeluVR en cliente adb del propio celular: se conecta al adbd local (depuración
 * inalámbrica) y lanza [com.pps.cardboardpoc.vdisplay.VDisplayServer] por un shell stream, sin
 * computadora. El proceso servidor vive mientras el stream siga abierto.
 *
 * El emparejamiento (una vez por celular, con el código que muestra el sistema) autoriza la clave de
 * [AdbConnectionManager]; después alcanza con tener la depuración inalámbrica encendida.
 */
object AdbServerLauncher {

    private const val TAG = "CardboardCapture"

    enum class Status { IDLE, CONNECTING, NEEDS_PAIRING, WIRELESS_OFF, LAUNCHING, READY, ERROR }

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status

    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var shellStream: AdbStream? = null

    @Volatile
    private var connected = false

    @Volatile
    private var appContext: Context? = null

    /** Si en este proceso ya se limpiaron las vinculaciones viejas de CeluVR: alcanza con una vez. */
    @Volatile
    private var oldPairingsRemoved = false

    enum class PairResult {
        PAIRED,

        /** No apareció el servicio de vinculación: el diálogo del sistema no está abierto. */
        NO_DIALOG,

        /** El adbd rechazó el código: no coincide o el diálogo ya lo invalidó. */
        REJECTED
    }

    /**
     * Empareja con el adbd local usando el código que muestra el diálogo "Vincular dispositivo con
     * código" del sistema. El host y el puerto los anuncia mDNS mientras ese diálogo está abierto: se
     * usa [target] si quien llama ya los resolvió y, si no, se buscan acá.
     */
    fun pair(context: Context, code: String, target: Pair<String, Int>?, onDone: (PairResult) -> Unit) {
        appContext = context.applicationContext
        executor.submit {
            val resolved = target ?: discoverPairing(context, PAIRING_DISCOVERY_MS)
            if (resolved == null) {
                Log.w(TAG, "adb: no apareció el servicio de vinculación (¿el diálogo del sistema está abierto?)")
                onDone(PairResult.NO_DIALOG)
                return@submit
            }
            var ok = tryPair(context, resolved, code)
            if (!ok && target != null) {
                // El puerto que vio quien llama puede ser de un diálogo anterior que el sistema no avisó
                // que cerró: si mDNS anuncia otro, el código es de ese.
                val fresh = discoverPairing(context, PAIRING_REDISCOVERY_MS)
                if (fresh != null && fresh != resolved) ok = tryPair(context, fresh, code)
            }
            onDone(if (ok) PairResult.PAIRED else PairResult.REJECTED)
        }
    }

    private fun tryPair(context: Context, target: Pair<String, Int>, code: String): Boolean {
        val (host, port) = target
        val ok = runCatching {
            AdbConnectionManager.getInstance(context).pair(host, port, code)
        }.getOrElse {
            Log.w(TAG, "adb: emparejamiento falló contra $host:$port", it)
            false
        }
        Log.d(TAG, "adb: emparejamiento ${if (ok) "OK" else "falló"} ($host:$port)")
        return ok
    }

    /**
     * Descubre por mDNS el host y el puerto del servicio de vinculación, que el sistema anuncia mientras
     * el diálogo del código está abierto. Si el anuncio no trae host, cae a la IP local de la Wi-Fi.
     */
    private fun discoverPairing(context: Context, timeoutMs: Long): Pair<String, Int>? {
        val port = AtomicInteger(-1)
        val host = AtomicReference<String?>()
        val latch = CountDownLatch(1)
        val mdns = AdbMdns(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { address, resolvedPort ->
            if (resolvedPort > 0) {
                port.set(resolvedPort)
                address?.hostAddress?.let { host.set(it) }
                latch.countDown()
            }
        }
        mdns.start()
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            mdns.stop()
        }
        val resolvedPort = port.get().takeIf { it > 0 } ?: return null
        val resolvedHost = host.get() ?: localWifiIp() ?: return null
        return resolvedHost to resolvedPort
    }

    /** Primera IPv4 no-loopback de una interfaz activa (la de la Wi-Fi). */
    private fun localWifiIp(): String? =
        NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress

    /**
     * Si el modo fluido puede trabajar sin pasos extra: ya hay conexión o servidor vivos, o CeluVR tiene
     * el permiso para encender la depuración inalámbrica sola (lo que implica que ya se emparejó y conectó
     * al menos una vez). Es un chequeo local: no abre ninguna conexión. No cubre Shizuku, que se consulta
     * aparte.
     */
    /**
     * Si el modo fluido está configurado: ya se emparejó y conectó al menos una vez (flag persistente),
     * o hay una conexión/servidor vivos ahora. El flag se apaga si el adbd rechaza la clave (p. ej. al
     * desvincular) o con [forget]. No cubre Shizuku, que se consulta aparte.
     */
    fun isFluidAvailable(context: Context): Boolean =
        connected || VDisplayConnection.isAvailable() || isConfigured(context)

    /** Flag persistente: el modo fluido quedó configurado (emparejado + conectado). */
    fun isConfigured(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CONFIGURED, false)

    private fun setConfigured(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CONFIGURED, value).apply()
    }

    /**
     * Flag persistente: el usuario apagó el modo fluido con [turnOff] y la vinculación sigue guardada. Se
     * apaga al volver a conectar, al olvidar el emparejamiento o si el adbd ya no reconoce la clave.
     */
    fun isTurnedOff(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_TURNED_OFF, false)

    private fun setTurnedOff(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_TURNED_OFF, value).apply()
    }

    private enum class ConnectResult { CONNECTED, NEEDS_PAIRING, FAILED }

    /**
     * Conecta al adbd local reintentando [attempts] veces: tras encender la depuración inalámbrica el
     * adbd tarda en anunciarse por mDNS, así que el primer intento puede no encontrarlo todavía.
     */
    private fun connect(manager: AbsAdbConnectionManager, context: Context, attempts: Int): ConnectResult {
        for (i in 0 until attempts) {
            val ok = try {
                manager.autoConnect(context, CONNECT_TIMEOUT_MS)
            } catch (e: AdbPairingRequiredException) {
                return ConnectResult.NEEDS_PAIRING
            } catch (t: Throwable) {
                Log.w(TAG, "adb: intento de conexión falló", t)
                false
            }
            if (ok) return ConnectResult.CONNECTED
            if (i < attempts - 1) Thread.sleep(CONNECT_RETRY_MS)
        }
        return ConnectResult.FAILED
    }

    /**
     * Conecta al adbd local (reusa la conexión viva si la hay). Con el modo fluido ya configurado,
     * enciende la depuración inalámbrica sola y reintenta. Marca/limpia el flag persistente según el
     * resultado: CONNECTED lo prende; NEEDS_PAIRING (el adbd rechazó la clave, p. ej. desvinculado) lo
     * apaga. Corre en el executor.
     */
    private fun doConnect(context: Context, manager: AbsAdbConnectionManager): ConnectResult {
        // Si la depuración inalámbrica se apagó, la conexión anterior está muerta aunque el flag siga en
        // true: descartarla para reconectar en vez de usar un stream caído.
        if (connected && !WirelessDebugging.isEnabled(context)) {
            runCatching { shellStream?.close() }
            runCatching { manager.close() }
            shellStream = null
            connected = false
        }
        if (connected) return ConnectResult.CONNECTED
        _status.value = Status.CONNECTING
        val canEnable = WirelessDebugging.hasPermission(context)
        if (canEnable && !WirelessDebugging.isEnabled(context)) {
            WirelessDebugging.enable(context)
            Thread.sleep(WIRELESS_START_MS)
        }
        val result = connect(manager, context, if (canEnable) CONNECT_ATTEMPTS else 1)
        Log.d(TAG, "adb: conexión al adbd local: $result")
        when (result) {
            ConnectResult.CONNECTED -> {
                connected = true
                // Con la conexión viva, CeluVR se concede WRITE_SECURE_SETTINGS por adb para encender la
                // depuración inalámbrica sola de acá en más. Es de una sola vez.
                if (!canEnable) grantWriteSecureSettings(context, manager)
                setConfigured(context, true)
                setTurnedOff(context, false)
                if (!oldPairingsRemoved) {
                    removeOldPairings(context, manager, all = false)
                    oldPairingsRemoved = true
                }
            }
            ConnectResult.NEEDS_PAIRING -> {
                setConfigured(context, false)
                setTurnedOff(context, false)
            }
            ConnectResult.FAILED -> Unit
        }
        return result
    }

    /**
     * Asegura que el servidor esté corriendo: conecta al adbd local si hace falta y lanza el proceso.
     * [onReady] recibe true si el binder del servidor quedó disponible; false si no se pudo (el llamador
     * cae a la captura normal). No muestra el emparejamiento: eso es explícito, vía [configure].
     */
    fun ensureServer(context: Context, onReady: (Boolean) -> Unit) {
        if (VDisplayConnection.isAvailable()) {
            onReady(true)
            return
        }
        appContext = context.applicationContext
        executor.submit {
            try {
                val manager = AdbConnectionManager.getInstance(context)
                val result = doConnect(context, manager)
                if (result != ConnectResult.CONNECTED) {
                    _status.value =
                        if (result == ConnectResult.NEEDS_PAIRING) Status.NEEDS_PAIRING else Status.WIRELESS_OFF
                    onReady(false)
                    return@submit
                }
                _status.value = Status.LAUNCHING
                launchServerProcess(context, manager)
                val ready = waitForBinder()
                _status.value = if (ready) Status.READY else Status.ERROR
                onReady(ready)
            } catch (t: Throwable) {
                Log.w(TAG, "adb: no se pudo asegurar el servidor", t)
                connected = false
                _status.value = Status.ERROR
                onReady(false)
            }
        }
    }

    /**
     * Setup explícito del modo fluido: conecta al adbd local (el emparejamiento se hizo antes con
     * [pair]) y deja el flag de configurado. [onDone] recibe si quedó configurado.
     */
    fun configure(context: Context, onDone: (Boolean) -> Unit) {
        appContext = context.applicationContext
        executor.submit {
            val ok = try {
                doConnect(context, AdbConnectionManager.getInstance(context)) == ConnectResult.CONNECTED
            } catch (t: Throwable) {
                Log.w(TAG, "adb: configuración falló", t)
                false
            }
            onDone(ok)
        }
    }

    /**
     * Apaga el modo fluido sin olvidar el emparejamiento: termina el servidor y corta la conexión, pero
     * conserva la clave adb y su vinculación. La depuración inalámbrica queda como está, porque otra app
     * puede estar usándola. Para volver alcanza con [configure], que reconecta con esa clave.
     */
    fun turnOff(context: Context, onDone: () -> Unit) {
        appContext = context.applicationContext
        executor.submit {
            VDisplayConnection.shutdown()
            runCatching { shellStream?.close() }
            shellStream = null
            runCatching { AdbConnectionManager.getInstance(context).close() }
            connected = false
            setConfigured(context, false)
            setTurnedOff(context, true)
            _status.value = Status.IDLE
            onDone()
        }
    }

    /**
     * Olvida el modo fluido: desvincula a CeluVR de la Depuración inalámbrica si puede conectarse, corta
     * la conexión, borra el flag y apaga la depuración inalámbrica. Hay que emparejar de nuevo para volver.
     * La clave adb se conserva: libadb arma el contexto TLS una sola vez por proceso con la primera clave
     * que usa, así que con una clave nueva el emparejamiento andaría pero la conexión presentaría la vieja.
     */
    fun forget(context: Context, onDone: () -> Unit) {
        appContext = context.applicationContext
        executor.submit {
            VDisplayConnection.shutdown()
            runCatching { shellStream?.close() }
            shellStream = null
            val manager = AdbConnectionManager.getInstance(context)
            // Sin Wi-Fi no hay con qué conectar, y los reintentos solo harían esperar: la entrada queda en
            // la lista y se limpia en el próximo emparejamiento.
            val reachable = (connected || WirelessDebugging.hasWifi(context)) &&
                runCatching { doConnect(context, manager) == ConnectResult.CONNECTED }.getOrDefault(false)
            if (reachable) removeOldPairings(context, manager, all = true)
            runCatching { manager.close() }
            connected = false
            setConfigured(context, false)
            setTurnedOff(context, false)
            runCatching { WirelessDebugging.disable(context) }
            oldPairingsRemoved = false
            _status.value = Status.IDLE
            onDone()
        }
    }

    /**
     * Corre `pm grant` por el shell para que CeluVR tenga WRITE_SECURE_SETTINGS. El permiso es de nivel
     * development, así que shell lo puede conceder; queda concedido entre reinicios. Espera a que el
     * comando termine para que el permiso esté vigente al seguir.
     */
    private fun grantWriteSecureSettings(context: Context, manager: AbsAdbConnectionManager) {
        try {
            runShellCommand(manager, "pm grant ${context.packageName} ${WirelessDebugging.PERMISSION}")
            if (WirelessDebugging.hasPermission(context)) {
                Log.d(TAG, "adb: WRITE_SECURE_SETTINGS concedido (auto-encendido al arrancar disponible)")
            } else {
                Log.w(TAG, "adb: pm grant no concedió WRITE_SECURE_SETTINGS")
            }
        } catch (e: Exception) {
            Log.w(TAG, "adb: no se pudo conceder WRITE_SECURE_SETTINGS", e)
        }
    }

    /**
     * Corre [command] en el shell del adbd local y devuelve lo que imprimió. El comando cierra el stream
     * al terminar: leer hasta el cierre es la forma de esperarlo, y ese cierre llega como IOException,
     * que acá es lo normal.
     */
    private fun runShellCommand(manager: AbsAdbConnectionManager, command: String): String {
        val stream = manager.openStream("shell:$command")
        val output = StringBuilder()
        try {
            BufferedReader(InputStreamReader(stream.openInputStream())).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    output.appendLine(line)
                    line = reader.readLine()
                }
            }
        } catch (_: IOException) {
        }
        runCatching { stream.close() }
        return output.toString().trim()
    }

    /**
     * Desvincula las entradas de CeluVR que no son la de la conexión actual (o todas, con [all]) con
     * [PairedDevicesCleaner], que necesita correr como shell.
     */
    private fun removeOldPairings(context: Context, manager: AbsAdbConnectionManager, all: Boolean) {
        try {
            val command = "CLASSPATH=${context.applicationInfo.sourceDir} " +
                "app_process / ${PairedDevicesCleaner::class.java.name}" +
                if (all) " ${PairedDevicesCleaner.ARG_ALL}" else ""
            Log.d(TAG, "adb: limpieza de vinculaciones: ${runShellCommand(manager, command)}")
        } catch (e: Exception) {
            Log.w(TAG, "adb: no se pudieron limpiar las vinculaciones viejas", e)
        }
    }

    private fun launchServerProcess(context: Context, manager: AbsAdbConnectionManager) {
        val existing = shellStream
        if (existing != null && !existing.isClosed) return
        val command = "CLASSPATH=${context.applicationInfo.sourceDir} " +
            "app_process / com.pps.cardboardpoc.vdisplay.VDisplayServer"
        val stream = manager.openStream("shell:$command")
        shellStream = stream
        Thread({ drain(stream) }, "adb-server-output").start()
        Log.d(TAG, "adb: servidor lanzado por shell stream")
    }

    /** Vuelca la salida del proceso servidor al log, para diagnóstico. */
    private fun drain(stream: AdbStream) {
        try {
            BufferedReader(InputStreamReader(stream.openInputStream())).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    Log.d(TAG, "adb-server: $line")
                    line = reader.readLine()
                }
            }
        } catch (e: Exception) {
            // El stream se cerró: el servidor terminó.
        }
    }

    private fun waitForBinder(): Boolean {
        val deadline = System.currentTimeMillis() + BINDER_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (VDisplayConnection.isAvailable()) return true
            Thread.sleep(100)
        }
        return VDisplayConnection.isAvailable()
    }

    /** Cierra el stream (termina el servidor) y corta la conexión adb. */
    fun stop() {
        val context = appContext
        executor.submit {
            runCatching { shellStream?.close() }
            shellStream = null
            if (context != null) runCatching { AdbConnectionManager.getInstance(context).close() }
            connected = false
            _status.value = Status.IDLE
        }
    }

    private const val CONNECT_TIMEOUT_MS = 5000L
    private const val BINDER_WAIT_MS = 6000L
    private const val PAIRING_DISCOVERY_MS = 15000L
    private const val PAIRING_REDISCOVERY_MS = 3000L

    /** Reintentos de conexión tras encender la depuración inalámbrica, y espera entre ellos. */
    private const val CONNECT_ATTEMPTS = 3
    private const val CONNECT_RETRY_MS = 1500L

    private const val PREFS = "celuvr_fluid"
    private const val KEY_CONFIGURED = "configured"
    private const val KEY_TURNED_OFF = "turned_off"

    /** Espera tras encender la depuración inalámbrica, para que el adbd arranque y se anuncie por mDNS. */
    private const val WIRELESS_START_MS = 2500L
}
