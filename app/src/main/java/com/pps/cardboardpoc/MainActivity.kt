package com.pps.cardboardpoc

import android.Manifest
import android.app.Activity
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.AnimatedVectorDrawable
import android.hardware.Sensor
import android.hardware.SensorManager
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.widget.ImageView
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.pps.cardboardpoc.adb.AdbServerLauncher
import com.pps.cardboardpoc.adb.FluidSetup
import com.pps.cardboardpoc.adb.FluidSetupStep
import com.pps.cardboardpoc.adb.WirelessDebugging
import com.pps.cardboardpoc.vdisplay.VDisplayConnection
import com.pps.cardboardpoc.ui.theme.CeluVRTheme
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager

    /** Si el servicio figura activado en Ajustes, aunque no esté corriendo. */
    private val accessibilityEnabled = mutableStateOf(false)

    /** Si el servicio de Shizuku está corriendo; con él el visor usa la pantalla virtual. */
    private val shizukuRunning = mutableStateOf(false)

    /** Si el modo fluido puede trabajar (config lista), detectado al abrir sin conectar. */
    private val fluidConfigured = mutableStateOf(false)

    /** Si el modo fluido se apagó con la vinculación guardada, lista para volver a encenderlo. */
    private val fluidTurnedOff = mutableStateOf(false)

    /** Mientras se olvida el emparejamiento, que conecta al adbd para desvincular y tarda unos segundos. */
    private val forgettingFluid = mutableStateOf(false)

    /** Paso del setup del modo fluido según los ajustes del teléfono. */
    private val fluidStep = mutableStateOf(FluidSetupStep.DEVELOPER_OPTIONS)

    /** Si hay alguna red Wi-Fi conectada, que el modo fluido necesita para conectar al adbd local. */
    private val wifiConnected = mutableStateOf(false)

    private val wifiNetworks = mutableSetOf<Network>()

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = runOnUiThread {
            wifiNetworks += network
            wifiConnected.value = true
        }

        override fun onLost(network: Network) = runOnUiThread {
            wifiNetworks -= network
            wifiConnected.value = wifiNetworks.isNotEmpty()
        }
    }

    /** Arranque del visor en modo fluido: mientras dura, la pantalla queda bloqueada con el loader. */
    private val startState = mutableStateOf<StartState>(StartState.Idle)

    private val mainHandler = Handler(Looper.getMainLooper())

    private val viewerStartTimeout = Runnable {
        if (startState.value == StartState.Starting && !ScreenCaptureService.isCapturing) {
            startState.value = StartState.Failed("El modo fluido se conectó, pero el visor no llegó a abrirse. Volvé a intentar o seguí en modo normal.")
        }
    }

    /** Si los comandos de voz están activados y con permiso de micrófono. */
    private val voiceEnabled = mutableStateOf(false)

    /** Si se pidió activar la voz y falta el permiso de micrófono. */
    private val voicePermissionMissing = mutableStateOf(false)

    private val microphonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        VoiceCommands.setEnabled(this, granted)
        voiceEnabled.value = granted
        voicePermissionMissing.value = !granted
    }

    /** App que abre el panel al arrancar el visor, a la espera del permiso de Shizuku o del emparejamiento. */
    private var pendingOpenPackage: String? = null

    private val shizukuReceived = Shizuku.OnBinderReceivedListener { shizukuRunning.value = true }
    private val shizukuDead = Shizuku.OnBinderDeadListener { shizukuRunning.value = false }

    private val shizukuPermissionResult =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != SHIZUKU_REQUEST_CODE) return@OnRequestPermissionResultListener
            runOnUiThread {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    startVirtualViewer(pendingOpenPackage)
                } else {
                    Log.w(TAG, "pantalla virtual: se negó el permiso de Shizuku, se usa la captura")
                    launchProjection()
                }
                pendingOpenPackage = null
            }
        }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            ContextCompat.startForegroundService(
                this, ScreenCaptureService.startIntent(this, result.resultCode, data)
            )
        }
    }

    private val accessibilitySettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Ajustes siempre vuelve con RESULT_CANCELED: hay que reconsultar.
        if (JoystickManager.serviceConnected.value) requestCapture()
    }

    private fun hasGyroscope(): Boolean {
        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        return sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_GYROSCOPE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        Shizuku.addBinderReceivedListenerSticky(shizukuReceived)
        Shizuku.addBinderDeadListener(shizukuDead)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionResult)
        wifiConnected.value = WirelessDebugging.hasWifi(this)
        // Una Wi-Fi sin internet también sirve para la depuración inalámbrica.
        getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            wifiCallback
        )

        setContent {
            val systemInDark = isSystemInDarkTheme()
            var isDarkTheme by remember { mutableStateOf(systemInDark) }
            val prefs = remember { getSharedPreferences("celuvr_prefs", Context.MODE_PRIVATE) }
            var gyroEnabled by remember { mutableStateOf(prefs.getBoolean("gyro_enabled", true)) }
            var gyroSensitivity by remember { mutableStateOf(prefs.getFloat("gyro_sensitivity", 1.0f)) }
            var gyroAcceleration by remember { mutableStateOf(prefs.getFloat("gyro_acceleration", 1.0f)) }
            var zoomStartLevel by remember { mutableStateOf(ZoomSettings.startLevel(this)) }
            var headSmoothingMs by remember { mutableStateOf(ZoomSettings.headSmoothingMs(this)) }
            val deviceHasGyroscope = remember { hasGyroscope() }
            var showSettingsScreen by remember { mutableStateOf(false) }
            var showInfoScreen by remember { mutableStateOf(false) }

            CeluVRTheme(darkTheme = isDarkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val serviceConnected by JoystickManager.serviceConnected.collectAsState()
                    val fullScreenRejected by ScreenCaptureService.fullScreenRejected.collectAsState()
                    val adbStatus by AdbServerLauncher.status.collectAsState()
                    val fluidSetupState by FluidSetup.state.collectAsState()
                    LaunchedEffect(fluidSetupState) {
                        refreshFluidState()
                        if (fluidSetupState == FluidSetup.State.CONFIGURED) {
                            showSettingsScreen = false
                            showInfoScreen = false
                        }
                    }
                    val capturing by ScreenCaptureService.capturing.collectAsState()
                    LaunchedEffect(capturing) {
                        if (capturing && startState.value == StartState.Starting) {
                            mainHandler.removeCallbacks(viewerStartTimeout)
                            startState.value = StartState.Idle
                        }
                    }
                    val serverRunning = adbStatus == AdbServerLauncher.Status.READY ||
                        adbStatus == AdbServerLauncher.Status.LAUNCHING
                    // Sin Wi-Fi no hay cómo conectar al adbd local; un servidor ya vivo no la necesita.
                    val fluidNeedsWifi = !shizukuRunning.value && fluidConfigured.value &&
                        !wifiConnected.value && !serverRunning
                    val fluidActive = shizukuRunning.value || serverRunning ||
                        (fluidConfigured.value && !fluidNeedsWifi)

                    when {
                        showSettingsScreen -> {
                            SettingsScreen(
                                hasGyroscope = deviceHasGyroscope,
                                gyroEnabled = gyroEnabled,
                                gyroSensitivity = gyroSensitivity,
                                gyroAcceleration = gyroAcceleration,
                                onGyroEnabledChange = { enabled ->
                                    gyroEnabled = enabled
                                    prefs.edit().putBoolean("gyro_enabled", enabled).apply()
                                },
                                onGyroSensitivityChange = { sens ->
                                    gyroSensitivity = sens
                                    prefs.edit().putFloat("gyro_sensitivity", sens).apply()
                                },
                                onGyroAccelerationChange = { accel ->
                                    gyroAcceleration = accel
                                    prefs.edit().putFloat("gyro_acceleration", accel).apply()
                                },
                                zoomStartLevel = zoomStartLevel,
                                onZoomStartLevelChange = { level ->
                                    zoomStartLevel = level
                                    ZoomSettings.setStartLevel(this, level)
                                },
                                headSmoothingMs = headSmoothingMs,
                                onHeadSmoothingChange = { smoothing ->
                                    headSmoothingMs = smoothing
                                    ZoomSettings.setHeadSmoothingMs(this, smoothing)
                                },
                                voiceEnabled = voiceEnabled.value,
                                voicePermissionMissing = voicePermissionMissing.value,
                                onVoiceEnabledChange = { onVoiceEnabledChange(it) },
                                onOpenAppPermissions = { openAppPermissions() },
                                onResetToDefaults = {
                                    onVoiceEnabledChange(false)
                                    gyroEnabled = true
                                    gyroSensitivity = 1.0f
                                    gyroAcceleration = 1.0f
                                    prefs.edit()
                                        .putBoolean("gyro_enabled", true)
                                        .putFloat("gyro_sensitivity", 1.0f)
                                        .putFloat("gyro_acceleration", 1.0f)
                                        .apply()
                                    zoomStartLevel = ZoomSettings.DEFAULT_START_LEVEL
                                    ZoomSettings.setStartLevel(this, ZoomSettings.DEFAULT_START_LEVEL)
                                    headSmoothingMs = ZoomSettings.DEFAULT_HEAD_SMOOTHING_MS
                                    ZoomSettings.setHeadSmoothingMs(this, ZoomSettings.DEFAULT_HEAD_SMOOTHING_MS)
                                },
                                fluidConfigured = fluidConfigured.value,
                                shizukuRunning = shizukuRunning.value,
                                serviceConnected = serviceConnected,
                                fluidSetupStep = fluidStep.value,
                                fluidSetupFailed = fluidSetupState == FluidSetup.State.CONNECT_FAILED,
                                onConfigureFluid = { startFluidSetup() },
                                onForgetFluid = { forgetFluid() },
                                fluidTurnedOff = fluidTurnedOff.value,
                                onTurnOffFluid = { turnOffFluid() },
                                onBack = { showSettingsScreen = false }
                            )
                        }
                        showInfoScreen -> {
                            AboutScreen(onBack = { showInfoScreen = false })
                        }
                        else -> {
                            CaptureScreen(
                                supported = isViewerSupported(),
                                fullScreenRejected = fullScreenRejected,
                                serviceConnected = serviceConnected,
                                serviceEnabled = accessibilityEnabled.value,
                                fluidActive = fluidActive,
                                fluidNeedsWifi = fluidNeedsWifi,
                                onStartClick = { requestCapture() },
                                isDarkTheme = isDarkTheme,
                                onToggleDarkMode = { isDarkTheme = !isDarkTheme },
                                onOpenSettings = { showSettingsScreen = true },
                                onOpenInfo = { showInfoScreen = true }
                            )
                        }
                    }

                    if (fluidSetupState == FluidSetup.State.CHECKING) {
                        StartingOverlay("Buscando una vinculación anterior…")
                    }
                    if (forgettingFluid.value) {
                        StartingOverlay("Olvidando el emparejamiento…")
                    }
                    when (val start = startState.value) {
                        StartState.Starting -> StartingOverlay("Iniciando el modo fluido…")
                        is StartState.Failed -> StartFailedDialog(
                            message = start.message,
                            onUseNormalMode = {
                                startState.value = StartState.Idle
                                launchProjection()
                            },
                            onDismiss = { startState.value = StartState.Idle }
                        )
                        StartState.Idle -> Unit
                    }
                }
            }
        }
        handleDebugCommand(intent)
    }

    override fun onResume() {
        super.onResume()
        accessibilityEnabled.value = isAccessibilityServiceEnabled()
        shizukuRunning.value = Shizuku.pingBinder()
        refreshFluidState()
        FluidSetup.dismiss()
        refreshVoiceState()
    }

    /** Relee la preferencia y el permiso de voz: el permiso se puede quitar desde los ajustes del sistema. */
    private fun refreshVoiceState() {
        val granted = VoiceCommands.hasPermission(this)
        // El aviso solo aparece después de intentar activar la voz: si el permiso llegó desde los ajustes
        // del sistema, la voz queda activada.
        if (voicePermissionMissing.value && granted) VoiceCommands.setEnabled(this, true)
        if (granted) {
            voicePermissionMissing.value = false
        } else if (VoiceCommands.isEnabled(this)) {
            voicePermissionMissing.value = true
        }
        voiceEnabled.value = VoiceCommands.shouldListen(this)
    }

    private fun onVoiceEnabledChange(enabled: Boolean) {
        if (enabled && !VoiceCommands.hasPermission(this)) {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        VoiceCommands.setEnabled(this, enabled)
        if (!enabled) voicePermissionMissing.value = false
        refreshVoiceState()
    }

    /** Abre la ficha de la app en los ajustes del sistema, donde se otorga el micrófono si ya se negó. */
    private fun openAppPermissions() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(viewerStartTimeout)
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(wifiCallback)
        Shizuku.removeBinderReceivedListener(shizukuReceived)
        Shizuku.removeBinderDeadListener(shizukuDead)
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionResult)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugCommand(intent)
    }

    /**
     * Atajos para manejar el visor por adb en las pruebas, solo en compilaciones depurables y con el
     * visor andando: [EXTRA_DEBUG_OPEN] abre una app desde el panel como si se la eligiera ahí, y
     * [EXTRA_DEBUG_HOME] vuelve al panel como el botón "Inicio". [EXTRA_DEBUG_VIRTUAL] arranca el
     * visor sobre la pantalla virtual de Shizuku, con [EXTRA_DEBUG_OPEN] opcional, y
     * [EXTRA_DEBUG_PORTRAIT] pasa esa pantalla a vertical o la vuelve a horizontal.
     */
    private fun handleDebugCommand(intent: Intent) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        if (intent.getBooleanExtra(EXTRA_DEBUG_VIRTUAL, false)) {
            if (Shizuku.pingBinder()) {
                startVirtualViewer(intent.getStringExtra(EXTRA_DEBUG_OPEN))
            } else {
                Log.w(TAG, "pantalla virtual: Shizuku no está corriendo")
            }
            return
        }
        if (!ScreenCaptureService.isCapturing) return
        if (intent.hasExtra(EXTRA_DEBUG_PORTRAIT)) {
            startService(
                ScreenCaptureService.portraitIntent(
                    this, intent.getBooleanExtra(EXTRA_DEBUG_PORTRAIT, false)
                )
            )
            return
        }
        val packageToOpen = intent.getStringExtra(EXTRA_DEBUG_OPEN)
        when {
            packageToOpen != null -> PanelActivity.show(this, packageToOpen)
            intent.getBooleanExtra(EXTRA_DEBUG_HOME, false) -> PanelActivity.show(this)
        }
    }

    /**
     * Arranca el visor sobre la pantalla virtual del servidor propio de CeluVR, que ya tiene que estar
     * conectado. Lo llama [ensureServerThenStart] una vez que el servidor entregó su binder.
     */
    private fun startServerViewer(openPackage: String?) {
        if (ScreenCaptureService.isCapturing) {
            startState.value = StartState.Idle
            return
        }
        if (!JoystickManager.serviceConnected.value) {
            Log.w(TAG, "servidor: falta el servicio de accesibilidad")
            startState.value = StartState.Failed("Se detuvo el servicio de accesibilidad de CeluVR, que dibuja el visor. Volvé a activarlo en Ajustes y tocá Iniciar.")
            return
        }
        if (!ServerDisplay.isAvailable()) {
            Log.w(TAG, "servidor: no está conectado; lanzalo con app_process")
            startState.value = StartState.Failed(fluidStartFailure(AdbServerLauncher.Status.ERROR))
            return
        }
        ContextCompat.startForegroundService(
            this, ScreenCaptureService.startServerIntent(this, openPackage)
        )
        mainHandler.removeCallbacks(viewerStartTimeout)
        mainHandler.postDelayed(viewerStartTimeout, VIEWER_START_TIMEOUT_MS)
    }

    /**
     * Arranca el visor sobre la pantalla virtual de Shizuku, que ya tiene que estar corriendo. Si falta
     * el permiso de Shizuku lo pide, con su propio diálogo: al aceptarlo arranca, y al negarlo se usa
     * la captura de pantalla.
     */
    private fun startVirtualViewer(openPackage: String?) {
        if (ScreenCaptureService.isCapturing) return
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            pendingOpenPackage = openPackage
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
            return
        }
        ContextCompat.startForegroundService(
            this, ScreenCaptureService.startVirtualIntent(this, openPackage)
        )
    }

    /**
     * Arranca el visor. Con Shizuku corriendo usa la pantalla virtual, donde toda app se ve a fps
     * completos y no hace falta elegir nada; sin Shizuku, la captura de pantalla con su selector.
     */
    private fun requestCapture() {
        if (!isViewerSupported()) return

        // El servicio de accesibilidad es el dueño de la ventana estéreo y el que traduce el control,
        // y solo se puede activar a mano desde Ajustes.
        if (!JoystickManager.serviceConnected.value) {
            openAccessibilitySettings()
            return
        }

        when {
            Shizuku.pingBinder() -> startVirtualViewer(null)
            // Solo con el modo fluido ya configurado se intenta por adb; si no, captura normal. Así
            // INICIAR nunca mete la ventanita de emparejar de prepo: eso es explícito, desde Ajustes.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && AdbServerLauncher.isConfigured(this) ->
                ensureServerThenStart(null)
            else -> launchProjection()
        }
    }

    /**
     * Arranca el visor sobre el servidor propio (modo fluido ya configurado): conecta al adbd local y,
     * si algo falla, cae a la captura de pantalla de siempre. No muestra el emparejamiento.
     */
    private fun ensureServerThenStart(openPackage: String?) {
        // Un servidor ya vivo corre en el propio celular y no necesita la red; para conectar uno nuevo
        // sí hace falta la Wi-Fi, y sin ella los reintentos de conexión solo harían esperar.
        if (!VDisplayConnection.isAvailable() && !WirelessDebugging.hasWifi(this)) {
            startState.value = StartState.Failed(
                "El modo fluido necesita que el celular esté conectado a una red Wi-Fi. Conectate y volvé a tocar Iniciar, o seguí en modo normal."
            )
            return
        }
        startState.value = StartState.Starting
        AdbServerLauncher.ensureServer(this) { ready ->
            runOnUiThread {
                if (ready) {
                    startServerViewer(openPackage)
                } else {
                    startState.value = StartState.Failed(fluidStartFailure(AdbServerLauncher.status.value))
                }
            }
        }
    }

    /** Explica por qué no arrancó el modo fluido, según dónde se cortó la conexión con el adbd local. */
    private fun fluidStartFailure(status: AdbServerLauncher.Status): String = when {
        status == AdbServerLauncher.Status.NEEDS_PAIRING ->
            "El celular ya no reconoce a CeluVR: se quitó la vinculación de la Depuración inalámbrica. Volvé a configurar el modo fluido desde Ajustes, o seguí en modo normal."
        !WirelessDebugging.hasWifi(this) ->
            "Se cortó la Wi-Fi mientras se conectaba. El modo fluido necesita una red Wi-Fi: conectate y volvé a tocar Iniciar, o seguí en modo normal."
        status == AdbServerLauncher.Status.WIRELESS_OFF ->
            "No se pudo conectar con la Depuración inalámbrica. Verificá que esté activada en Opciones de desarrollador y volvé a intentar, o seguí en modo normal."
        else ->
            "Se conectó con la Depuración inalámbrica, pero el servidor del modo fluido no arrancó. Volvé a intentar o seguí en modo normal."
    }

    /**
     * Setup explícito del modo fluido (botón en Ajustes): lleva al ajuste del paso actual y deja encima
     * el flotante para tipear el código de vinculación. Al vincular, [FluidSetup] conecta y vuelve acá.
     */
    private fun startFluidSetup() {
        val host = JoystickManager.accessibilityService
        if (host == null) {
            // El flotante es un overlay del servicio de accesibilidad: sin el servicio no hay dónde
            // dibujarlo, así que primero se manda a activarlo.
            openAccessibilitySettings()
            return
        }
        FluidSetup.start(this, host)
    }

    /** Olvida el modo fluido (botón en Ajustes): corta la conexión, borra la config y la clave adb. */
    private fun forgetFluid() {
        forgettingFluid.value = true
        AdbServerLauncher.forget(this) {
            runOnUiThread {
                forgettingFluid.value = false
                refreshFluidState()
            }
        }
    }

    /** Apaga el modo fluido (botón en Ajustes) conservando el emparejamiento para volver a encenderlo. */
    private fun turnOffFluid() {
        AdbServerLauncher.turnOff(this) { runOnUiThread { refreshFluidState() } }
    }

    private fun refreshFluidState() {
        fluidConfigured.value = AdbServerLauncher.isFluidAvailable(this)
        fluidTurnedOff.value = AdbServerLauncher.isTurnedOff(this)
        fluidStep.value = FluidSetupStep.current(this)
    }

    private fun launchProjection() {
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    /**
     * Abre Ajustes de accesibilidad. La pantalla de detalle de un servicio puntual exige un permiso de
     * sistema, así que el usuario tiene que buscar el servicio en la lista.
     */
    private fun openAccessibilitySettings() {
        accessibilitySettingsLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val component = ComponentName(this, JoystickAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == component }
    }

    companion object {
        /** Paquete de la app a abrir desde el panel (atajo de prueba por adb). */
        private const val EXTRA_DEBUG_OPEN = "abrir"

        /** Con true vuelve al panel (atajo de prueba por adb). */
        private const val EXTRA_DEBUG_HOME = "inicio"

        /** Con true arranca el visor sobre la pantalla virtual de Shizuku (atajo de prueba por adb). */
        private const val EXTRA_DEBUG_VIRTUAL = "pantalla_virtual"

        /** Con true pasa la pantalla virtual a vertical y con false la vuelve a horizontal (atajo de prueba por adb). */
        private const val EXTRA_DEBUG_PORTRAIT = "vertical"

        private const val SHIZUKU_REQUEST_CODE = 1
        private const val TAG = "CardboardCapture"

        /** Cuánto se espera a que el visor abra una vez que el servidor del modo fluido está listo. */
        private const val VIEWER_START_TIMEOUT_MS = 10_000L

        /**
         * Trae la pantalla principal al frente en la pantalla física desde cualquier contexto. Con
         * NEW_TASK el sistema usa la tarea de CeluVR si ya existe, y REORDER_TO_FRONT sube esta
         * actividad dentro de ella sin cerrar las apps abiertas encima. Fuera de primer plano solo
         * funciona desde el servicio de accesibilidad, que exime a la app del bloqueo de Android a
         * abrir actividades desde segundo plano.
         */
        fun bringToFront(context: Context) {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()
            )
        }
    }
}

/**
 * El visor necesita Android 14: la captura de una sola app, el aviso de que la app capturada dejó de
 * verse y la captura por ventanas no existen antes, y la pantalla completa sale en negro.
 */
private fun isViewerSupported(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

/** Estado del arranque del visor en modo fluido, desde que se toca Iniciar hasta que abre o falla. */
private sealed interface StartState {
    data object Idle : StartState
    data object Starting : StartState
    data class Failed(val message: String) : StartState
}

/** Tapa la pantalla con el ícono girando sobre su eje vertical y [text], y no deja tocar nada mientras tanto. */
@Composable
private fun StartingOverlay(text: String) {
    BackHandler {}
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.92f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // El vector animado corre en el hilo de render: sigue girando aunque el hilo principal se
            // trabe arrancando el visor, cosa que una animación de Compose no puede.
            AndroidView(
                factory = { context ->
                    ImageView(context).apply {
                        val logo = context.getDrawable(R.drawable.loader_celuvr_logo) as AnimatedVectorDrawable
                        setImageDrawable(logo)
                        logo.start()
                    }
                },
                modifier = Modifier.size(96.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

@Composable
private fun StartFailedDialog(message: String, onUseNormalMode: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("No se pudo iniciar el modo fluido") },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onUseNormalMode) { Text("Usar modo normal") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cerrar") }
        }
    )
}

@Composable
fun CaptureScreen(
    supported: Boolean,
    fullScreenRejected: Boolean,
    serviceConnected: Boolean,
    serviceEnabled: Boolean,
    fluidActive: Boolean,
    onStartClick: () -> Unit,
    fluidNeedsWifi: Boolean = false,
    isDarkTheme: Boolean = false,
    onToggleDarkMode: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenInfo: () -> Unit = {}
) {
    val appName = stringResource(R.string.app_name)
    val serviceLabel = stringResource(R.string.accessibility_service_label)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Bar: Futuristic Header + Action Icons side-by-side (ONLY icons)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_celuvr_logo),
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = Color.Unspecified
                )
                Text(
                    text = appName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onToggleDarkMode) {
                    Text(
                        text = if (isDarkTheme) "☀️" else "🌙",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Ajustes",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onOpenInfo) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Información",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Status HUD Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusChip(
                label = if (supported) "Android 14+" else "Req. Android 14",
                active = supported,
                modifier = Modifier.weight(1f)
            )
            StatusChip(
                label = if (serviceConnected) "Joystick OK" else "Sin Accesibilidad",
                active = serviceConnected,
                modifier = Modifier.weight(1f)
            )
            StatusChip(
                label = when {
                    fluidNeedsWifi -> "Fluido sin Wi-Fi"
                    fluidActive -> "Modo fluido"
                    else -> "Modo normal"
                },
                active = fluidActive,
                modifier = Modifier.weight(1f),
                neutralWhenInactive = !fluidNeedsWifi
            )
        }

        // Hero Action Button
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Button(
                onClick = onStartClick,
                enabled = supported,
                modifier = Modifier
                    .defaultMinSize(minWidth = 180.dp, minHeight = 48.dp)
            ) {
                Text(
                    text = "INICIAR",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Guide / Instruction HUD Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "ESTADO DEL SISTEMA VR",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = when {
                        !supported ->
                            "El visor requiere Android 14 o superior (este equipo tiene Android ${Build.VERSION.RELEASE})."
                        !serviceConnected && serviceEnabled ->
                            "El servicio de accesibilidad está activado pero detenido. Apágalo y vuelve a encenderlo en Ajustes."
                        !serviceConnected ->
                            "Falta activar el servicio de accesibilidad \"$serviceLabel\" para dibujar la vista estéreo."
                        fluidNeedsWifi ->
                            "El modo fluido está configurado, pero necesita Wi-Fi y el celular no está conectado a ninguna red. Conectate para usarlo, o tocá Iniciar y seguí en modo normal."
                        fluidActive ->
                            "Modo fluido activo: tocá Iniciar y el panel de apps abre directo en el visor, a máxima fluidez."
                        fullScreenRejected ->
                            "Se eligió \"Pantalla completa\" (se ve en negro). Tocá Iniciar, elegí \"Una sola app\" y seleccioná $appName."
                        else ->
                            "Tocá Iniciar: con el modo fluido configurado el panel abre directo; si no, elegí \"Una sola app\" y seleccioná $appName. El modo fluido (opcional) se activa desde Ajustes."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // VR Box Controller HUD Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "CONTROL VR BOX (Modo Joystick)",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text("• Stick: Mover cursor en estéreo", style = MaterialTheme.typography.bodySmall)
                Text("• Gatillo: Tocar / Seleccionar", style = MaterialTheme.typography.bodySmall)
                Text("• Botón A: Volver / Atrás", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * Chip de estado. Activo: color primario. Inactivo: rojo si es un estado que pide acción, o gris neutro
 * si [neutralWhenInactive] (un estado válido, como "Modo normal", que no debe leerse como alerta).
 */
@Composable
fun StatusChip(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    neutralWhenInactive: Boolean = false
) {
    val container = when {
        active -> MaterialTheme.colorScheme.primaryContainer
        neutralWhenInactive -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.errorContainer
    }
    val content = when {
        active -> MaterialTheme.colorScheme.onPrimaryContainer
        neutralWhenInactive -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = container
    ) {
        Box(
            modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = content
            )
        }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val violet = MaterialTheme.colorScheme.primary
    val context = LocalContext.current
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull()
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Top Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                IconButton(onClick = onBack) {
                    Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Volver")
                }
                Text(
                    text = "Acerca de",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                // Encabezado centrado: ícono, nombre, versión y descripción.
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Icon(
                        painter = painterResource(id = R.drawable.ic_celuvr_logo),
                        contentDescription = "Icono de la aplicación",
                        modifier = Modifier.size(96.dp),
                        tint = Color.Unspecified
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Versión ${versionName.orEmpty()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = violet,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Visualizá tu celular en un visor VR, en estéreo y con control por joystick o giroscopio.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                }

                AboutInfoRow(
                    label = "DESARROLLADORES",
                    accent = violet,
                    value = "Jonatan Alvarez · Lucas Llanos · Fabián Mamani"
                )
                AboutInfoRow(
                    label = "MATERIA",
                    accent = violet,
                    value = "Práctica Profesional Supervisada"
                )

                Divider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AboutLabel("INSTITUCIÓN", violet)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_university),
                            contentDescription = "Logo UNGS",
                            modifier = Modifier.size(40.dp),
                            tint = Color.Unspecified
                        )
                        Text(
                            text = "Universidad Nacional de\nGeneral Sarmiento",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun AboutLabel(text: String, accent: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = accent,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp
    )
}

@Composable
private fun AboutInfoRow(label: String, accent: Color, value: String) {
    Divider()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        AboutLabel(label, accent)
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Sección de Ajustes que explica y guía el modo fluido (opcional, por depuración inalámbrica o Shizuku).
 * Si está [configured], muestra la confirmación con las opciones de apagarlo (conserva la vinculación) u
 * olvidar el emparejamiento; si está [turnedOff], el botón para volver a encenderlo; si no, los pasos
 * marcando los ya hechos según [setupStep], con un único botón que lleva al ajuste que falta.
 */
@Composable
private fun FluidModeCard(
    configured: Boolean,
    shizukuRunning: Boolean,
    serviceConnected: Boolean,
    setupStep: FluidSetupStep,
    setupFailed: Boolean,
    onConfigure: () -> Unit,
    onForget: () -> Unit,
    turnedOff: Boolean,
    onTurnOff: () -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    val context = LocalContext.current
    val pairLabel = remember { FluidSetupStep.pairWithCodeLabel(context) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_speed),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = accent
                )
                Text(
                    text = "MODO FLUIDO (OPCIONAL)",
                    style = MaterialTheme.typography.labelLarge,
                    color = accent,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "Por defecto se ve cualquier app, pero los videos y juegos pueden ir entrecortados. El modo fluido los muestra a máxima velocidad.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Modo fluido",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = when {
                            configured -> "Encendido: el panel de apps abre directo en el visor"
                            turnedOff -> "Apagado. La vinculación sigue guardada: al encenderlo no hace falta otro código"
                            else -> "Configuralo con los pasos de abajo para poder encenderlo"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = configured,
                    onCheckedChange = { on -> if (on) onConfigure() else onTurnOff() },
                    enabled = configured || turnedOff
                )
            }
            if (shizukuRunning) {
                FluidStatusRow("Activo con Shizuku: mientras Shizuku esté iniciado no hace falta emparejar.", accent)
            }
            if (configured || turnedOff) {
                if (configured) {
                    Text(
                        text = "Tras reiniciar el celular, CeluVR vuelve a activar la Depuración inalámbrica sola; no hay que repetir el emparejamiento.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (turnedOff && !serviceConnected) {
                    Text(
                        text = "Para encenderlo hace falta el servicio de accesibilidad de CeluVR: el switch te lleva a activarlo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(
                    onClick = onForget,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Olvidar emparejamiento")
                }
            } else {
                FluidStep(
                    1,
                    "Activá las Opciones de desarrollador: en Acerca del teléfono, tocá 7 veces el número de compilación (en Samsung, dentro de Información de software).",
                    accent,
                    done = setupStep != FluidSetupStep.DEVELOPER_OPTIONS
                )
                FluidStep(
                    2,
                    "Activá la Depuración inalámbrica en Opciones de desarrollador.",
                    accent,
                    done = setupStep == FluidSetupStep.PAIR
                )
                FluidStep(
                    3,
                    "Entrá a Depuración inalámbrica, tocá \"$pairLabel\" y escribí el código en el botón flotante de CeluVR.",
                    accent,
                    done = false
                )
                if (setupFailed) {
                    Text(
                        text = "Se vinculó, pero no se pudo conectar. Verificá que la Depuración inalámbrica siga activa y probá de nuevo.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (!serviceConnected) {
                    Text(
                        text = "El botón flotante necesita el servicio de accesibilidad de CeluVR: activalo primero.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    onClick = onConfigure,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                ) {
                    Text(
                        when {
                            !serviceConnected -> "Activar accesibilidad"
                            setupStep == FluidSetupStep.DEVELOPER_OPTIONS -> "Ir a Acerca del teléfono"
                            else -> "Ir a Depuración inalámbrica"
                        }
                    )
                }
                if (serviceConnected) {
                    Text(
                        text = "En los ajustes aparece el ícono de CeluVR flotando: tocalo para escribir el código. Lo podés arrastrar para que no tape nada.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (!shizukuRunning) {
                Text(
                    text = "Alternativa: con la app Shizuku iniciada, el modo fluido funciona sin este emparejamiento.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun FluidStatusRow(text: String, accent: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = 0.10f))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = accent
        )
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FluidStep(number: Int, text: String, accent: Color, done: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(accent),
            contentAlignment = Alignment.Center
        ) {
            if (done) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Hecho",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text(
                    text = number.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
            modifier = Modifier.weight(1f)
        )
    }
}

@Preview(showBackground = true)
@Composable
fun CaptureScreenPreview() {
    CeluVRTheme {
        Surface {
            CaptureScreen(
                supported = true,
                fullScreenRejected = false,
                serviceConnected = true,
                serviceEnabled = true,
                fluidActive = false,
                onStartClick = {}
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun CaptureScreenDarkPreview() {
    CeluVRTheme(darkTheme = true) {
        Surface {
            CaptureScreen(
                supported = true,
                fullScreenRejected = false,
                serviceConnected = true,
                serviceEnabled = true,
                fluidActive = false,
                onStartClick = {},
                isDarkTheme = true
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun AboutScreenPreview() {
    CeluVRTheme {
        AboutScreen(onBack = {})
    }
}

@Composable
fun SettingsScreen(
    hasGyroscope: Boolean,
    gyroEnabled: Boolean,
    gyroSensitivity: Float,
    gyroAcceleration: Float,
    onGyroEnabledChange: (Boolean) -> Unit,
    onGyroSensitivityChange: (Float) -> Unit,
    onGyroAccelerationChange: (Float) -> Unit,
    zoomStartLevel: Float,
    onZoomStartLevelChange: (Float) -> Unit,
    headSmoothingMs: Float,
    onHeadSmoothingChange: (Float) -> Unit,
    voiceEnabled: Boolean,
    voicePermissionMissing: Boolean,
    onVoiceEnabledChange: (Boolean) -> Unit,
    onOpenAppPermissions: () -> Unit,
    onResetToDefaults: () -> Unit,
    fluidConfigured: Boolean,
    shizukuRunning: Boolean,
    serviceConnected: Boolean,
    fluidSetupStep: FluidSetupStep,
    fluidSetupFailed: Boolean,
    onConfigureFluid: () -> Unit,
    onForgetFluid: () -> Unit,
    fluidTurnedOff: Boolean,
    onTurnOffFluid: () -> Unit,
    onBack: () -> Unit
) {
    var showResetConfirmDialog by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Top Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Volver"
                    )
                }
                Text(
                    text = "Ajustes",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            // Scrollable Content
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                // Gyroscope Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "SENSOR DE GIROSCOPIO",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )

                        // Toggle Option
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Puntero por giroscopio",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Mover el cursor con el movimiento de la cabeza",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = gyroEnabled && hasGyroscope,
                                onCheckedChange = onGyroEnabledChange,
                                enabled = hasGyroscope
                            )
                        }

                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        // Sensitivity Slider Option
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Sensibilidad",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = String.format(Locale.US, "%.1fx", gyroSensitivity),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Slider(
                                value = gyroSensitivity,
                                onValueChange = onGyroSensitivityChange,
                                valueRange = 0.5f..2.0f,
                                steps = 15,
                                enabled = hasGyroscope && gyroEnabled
                            )
                        }

                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        // Acceleration Slider Option
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Aceleración del puntero",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = String.format(Locale.US, "%.1fx", gyroAcceleration),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Slider(
                                value = gyroAcceleration,
                                onValueChange = onGyroAccelerationChange,
                                valueRange = 1.0f..2.0f,
                                steps = 10,
                                enabled = hasGyroscope && gyroEnabled
                            )
                        }

                        // Legend if no gyroscope
                        if (!hasGyroscope) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.errorContainer
                            ) {
                                Text(
                                    text = "⚠️ Tu dispositivo no cuenta con sensor de giroscopio. Estas opciones se encuentran deshabilitadas.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "ZOOM",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )

                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Zoom al iniciar",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = String.format(Locale.US, "%.1fx", zoomStartLevel),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Slider(
                                value = zoomStartLevel,
                                onValueChange = onZoomStartLevelChange,
                                valueRange = ZoomLevels.STEPS.first()..ZoomLevels.STEPS.last(),
                                steps = ZoomLevels.STEPS.size - 2
                            )
                        }

                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Suavizado del movimiento",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = if (headSmoothingMs < 1f) {
                                        "Apagado"
                                    } else {
                                        String.format(Locale.US, "%.0f ms", headSmoothingMs)
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Slider(
                                value = headSmoothingMs,
                                onValueChange = onHeadSmoothingChange,
                                valueRange = 0f..150f,
                                steps = 14,
                                enabled = hasGyroscope && gyroEnabled
                            )
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "COMANDOS DE VOZ",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Control por voz",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Decí \"tocar\", \"agarrar\", \"soltar\", \"centrar\", \"volver\", " +
                                        "\"acercar\" o \"alejar\", solos y con una pausa entre uno y otro",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = voiceEnabled, onCheckedChange = onVoiceEnabledChange)
                        }
                        if (voicePermissionMissing) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.errorContainer
                            ) {
                                Column(modifier = Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp)) {
                                    Text(
                                        text = "⚠️ Sin permiso de micrófono no se pueden usar los comandos de voz.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    TextButton(
                                        onClick = onOpenAppPermissions,
                                        modifier = Modifier.align(Alignment.End)
                                    ) {
                                        Text("Dar permiso")
                                    }
                                }
                            }
                        }
                    }
                }

                FluidModeCard(
                    configured = fluidConfigured,
                    shizukuRunning = shizukuRunning,
                    serviceConnected = serviceConnected,
                    setupStep = fluidSetupStep,
                    setupFailed = fluidSetupFailed,
                    onConfigure = onConfigureFluid,
                    onForget = onForgetFluid,
                    turnedOff = fluidTurnedOff,
                    onTurnOff = onTurnOffFluid
                )

                // Reset Button
                OutlinedButton(
                    onClick = { showResetConfirmDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                ) {
                    Text("Restablecer", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (showResetConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showResetConfirmDialog = false },
            title = { Text("Restablecer valores") },
            text = { Text("¿Deseas restablecer los ajustes a sus valores predeterminados?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirmDialog = false
                        onResetToDefaults()
                    }
                ) {
                    Text("Restablecer")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
    CeluVRTheme {
        SettingsScreen(
            hasGyroscope = true,
            gyroEnabled = true,
            gyroSensitivity = 1.0f,
            gyroAcceleration = 1.0f,
            onGyroEnabledChange = {},
            onGyroSensitivityChange = {},
            onGyroAccelerationChange = {},
            zoomStartLevel = 1.0f,
            onZoomStartLevelChange = {},
            headSmoothingMs = 60f,
            onHeadSmoothingChange = {},
            voiceEnabled = false,
            voicePermissionMissing = true,
            onVoiceEnabledChange = {},
            onOpenAppPermissions = {},
            onResetToDefaults = {},
            fluidConfigured = false,
            shizukuRunning = false,
            serviceConnected = true,
            fluidSetupStep = FluidSetupStep.WIRELESS_DEBUGGING,
            fluidSetupFailed = false,
            onConfigureFluid = {},
            onForgetFluid = {},
            fluidTurnedOff = false,
            onTurnOffFluid = {},
            onBack = {}
        )
    }
}





