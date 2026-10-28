package com.pps.cardboardpoc

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.pps.cardboardpoc.adb.PairingOverlay
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

    /** Ventanita flotante para tipear el código de emparejamiento sobre el diálogo del sistema. */
    private var pairingOverlay: PairingOverlay? = null

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

        setContent {
            val systemInDark = isSystemInDarkTheme()
            var isDarkTheme by remember { mutableStateOf(systemInDark) }
            val prefs = remember { getSharedPreferences("celuvr_prefs", Context.MODE_PRIVATE) }
            var gyroEnabled by remember { mutableStateOf(prefs.getBoolean("gyro_enabled", true)) }
            var gyroSensitivity by remember { mutableStateOf(prefs.getFloat("gyro_sensitivity", 1.0f)) }
            var gyroAcceleration by remember { mutableStateOf(prefs.getFloat("gyro_acceleration", 1.0f)) }
            val deviceHasGyroscope = remember { hasGyroscope() }
            var showSettingsScreen by remember { mutableStateOf(false) }
            var showInfoScreen by remember { mutableStateOf(false) }

            CeluVRTheme(darkTheme = isDarkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val serviceConnected by JoystickManager.serviceConnected.collectAsState()
                    val fullScreenRejected by ScreenCaptureService.fullScreenRejected.collectAsState()
                    val adbStatus by AdbServerLauncher.status.collectAsState()
                    val fluidActive = shizukuRunning.value ||
                        fluidConfigured.value ||
                        adbStatus == AdbServerLauncher.Status.READY ||
                        adbStatus == AdbServerLauncher.Status.LAUNCHING

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
                                },
                                onBack = { showSettingsScreen = false }
                            )
                        }
                        showInfoScreen -> {
                            AboutScreen(
                                onBack = { showInfoScreen = false },
                                configured = fluidConfigured.value,
                                onConfigure = { startFluidSetup() },
                                onForget = { forgetFluid() }
                            )
                        }
                        else -> {
                            CaptureScreen(
                                supported = isViewerSupported(),
                                fullScreenRejected = fullScreenRejected,
                                serviceConnected = serviceConnected,
                                serviceEnabled = accessibilityEnabled.value,
                                fluidActive = fluidActive,
                                onStartClick = { requestCapture() },
                                fluidConfigured = fluidConfigured.value,
                                onConfigureFluid = { startFluidSetup() },
                                onForgetFluid = { forgetFluid() },
                                isDarkTheme = isDarkTheme,
                                onToggleDarkMode = { isDarkTheme = !isDarkTheme },
                                onOpenSettings = { showSettingsScreen = true },
                                onOpenInfo = { showInfoScreen = true }
                            )
                        }
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
        fluidConfigured.value = AdbServerLauncher.isFluidAvailable(this)
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
        if (ScreenCaptureService.isCapturing) return
        if (!JoystickManager.serviceConnected.value) {
            Log.w(TAG, "servidor: falta el servicio de accesibilidad")
            return
        }
        if (!ServerDisplay.isAvailable()) {
            Log.w(TAG, "servidor: no está conectado; lanzalo con app_process")
            return
        }
        ContextCompat.startForegroundService(
            this, ScreenCaptureService.startServerIntent(this, openPackage)
        )
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
            // INICIAR nunca mete la ventanita de emparejar de prepo: eso es explícito, desde Acerca de.
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
        AdbServerLauncher.ensureServer(this) { ready ->
            runOnUiThread {
                if (ready) startServerViewer(openPackage) else launchProjection()
            }
        }
    }

    /**
     * Setup explícito del modo fluido (botón "Configurar" en Acerca de): muestra la ventanita para
     * tipear el código de emparejamiento y, al emparejar, conecta y deja el modo fluido configurado.
     * La ventanita es un overlay del servicio de accesibilidad, que el diálogo de seguridad no oculta.
     */
    private fun startFluidSetup() {
        val host = JoystickManager.accessibilityService
        if (host == null) {
            // La ventanita de emparejar es un overlay del servicio de accesibilidad: sin el servicio no
            // hay dónde dibujarla, así que primero se manda a activarlo.
            openAccessibilitySettings()
            return
        }
        pairingOverlay?.hide()
        pairingOverlay = PairingOverlay(host).also { overlay ->
            overlay.show(
                onPaired = {
                    runOnUiThread {
                        pairingOverlay = null
                        AdbServerLauncher.configure(this) {
                            runOnUiThread { fluidConfigured.value = AdbServerLauncher.isFluidAvailable(this) }
                        }
                    }
                },
                onCancel = { runOnUiThread { pairingOverlay = null } }
            )
        }
    }

    /** Olvida el modo fluido (botón en Acerca de): corta la conexión, borra la config y la clave adb. */
    private fun forgetFluid() {
        AdbServerLauncher.forget(this) {
            runOnUiThread { fluidConfigured.value = AdbServerLauncher.isFluidAvailable(this) }
        }
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

    private companion object {
        /** Paquete de la app a abrir desde el panel (atajo de prueba por adb). */
        const val EXTRA_DEBUG_OPEN = "abrir"

        /** Con true vuelve al panel (atajo de prueba por adb). */
        const val EXTRA_DEBUG_HOME = "inicio"

        /** Con true arranca el visor sobre la pantalla virtual de Shizuku (atajo de prueba por adb). */
        const val EXTRA_DEBUG_VIRTUAL = "pantalla_virtual"

        /** Con true pasa la pantalla virtual a vertical y con false la vuelve a horizontal (atajo de prueba por adb). */
        const val EXTRA_DEBUG_PORTRAIT = "vertical"

        const val SHIZUKU_REQUEST_CODE = 1
        const val TAG = "CardboardCapture"
    }
}

/**
 * El visor necesita Android 14: la captura de una sola app, el aviso de que la app capturada dejó de
 * verse y la captura por ventanas no existen antes, y la pantalla completa sale en negro.
 */
private fun isViewerSupported(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

@Composable
fun CaptureScreen(
    supported: Boolean,
    fullScreenRejected: Boolean,
    serviceConnected: Boolean,
    serviceEnabled: Boolean,
    fluidActive: Boolean,
    onStartClick: () -> Unit,
    fluidConfigured: Boolean = false,
    onConfigureFluid: () -> Unit = {},
    onForgetFluid: () -> Unit = {},
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
                label = if (fluidActive) "Modo fluido" else "Modo normal",
                active = fluidActive,
                modifier = Modifier.weight(1f),
                neutralWhenInactive = true
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
                        fluidActive ->
                            "Modo fluido activo: tocá Iniciar y el panel de apps abre directo en el visor, a máxima fluidez."
                        fullScreenRejected ->
                            "Se eligió \"Pantalla completa\" (se ve en negro). Tocá Iniciar, elegí \"Una sola app\" y seleccioná $appName."
                        else ->
                            "Tocá Iniciar: con el modo fluido configurado el panel abre directo; si no, elegí \"Una sola app\" y seleccioná $appName. El modo fluido (opcional) se activa desde Acerca de."
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
fun AboutScreen(
    onBack: () -> Unit,
    configured: Boolean = false,
    onConfigure: () -> Unit = {},
    onForget: () -> Unit = {}
) {
    BackHandler(onBack = onBack)
    val violet = MaterialTheme.colorScheme.primary
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
                        text = "Versión 1.1",
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

                FluidModeSection(
                    accent = violet,
                    configured = configured,
                    onConfigure = onConfigure,
                    onForget = onForget
                )
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
 * Sección desplegable que explica y guía el modo fluido (opcional, por depuración inalámbrica). Arranca
 * plegada. Según [configured], muestra los pasos para configurarlo con un botón "Configurar modo fluido",
 * o la confirmación de que ya está configurado con la opción de olvidar el emparejamiento.
 */
@Composable
private fun FluidModeSection(
    accent: Color,
    configured: Boolean,
    onConfigure: () -> Unit,
    onForget: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Divider()
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_speed),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = accent
            )
            AboutLabel("MODO FLUIDO (OPCIONAL)", accent)
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Contraer" else "Expandir",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Text(
                    text = "Por defecto se ve cualquier app, pero los videos y juegos pueden ir entrecortados. El modo fluido los muestra a máxima velocidad.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (configured) {
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
                        Text(
                            text = "Modo fluido configurado. Tocá Iniciar y el panel de apps abre directo en el visor.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Text(
                        text = "Tras reiniciar el celular, CeluVR vuelve a activar la Depuración inalámbrica sola; no hay que repetir el emparejamiento.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = onForget,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Olvidar emparejamiento")
                    }
                } else {
                    FluidStep(1, "Activá Opciones de desarrollador: en Ajustes › Acerca del teléfono, tocá 7 veces el número de compilación.", accent)
                    FluidStep(2, "Activá la Depuración inalámbrica en Opciones de desarrollador.", accent)
                    FluidStep(3, "Tocá \"Configurar modo fluido\" y escribí el código de vinculación del sistema.", accent)
                    Button(
                        onClick = onConfigure,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    ) {
                        Text("Configurar modo fluido")
                    }
                }
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
private fun FluidStep(number: Int, text: String, accent: Color) {
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
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
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
    voiceEnabled: Boolean,
    voicePermissionMissing: Boolean,
    onVoiceEnabledChange: (Boolean) -> Unit,
    onOpenAppPermissions: () -> Unit,
    onResetToDefaults: () -> Unit,
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
                                    text = "Decí \"tocar\", \"agarrar\", \"soltar\", \"centrar\" o \"volver\", " +
                                        "solos y con una pausa entre uno y otro",
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
            voiceEnabled = false,
            voicePermissionMissing = true,
            onVoiceEnabledChange = {},
            onOpenAppPermissions = {},
            onResetToDefaults = {},
            onBack = {}
        )
    }
}





