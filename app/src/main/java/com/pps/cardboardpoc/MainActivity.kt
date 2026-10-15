package com.pps.cardboardpoc

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.pps.cardboardpoc.ui.theme.CeluVRTheme
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager

    /** Si el servicio figura activado en Ajustes, aunque no esté corriendo. */
    private val accessibilityEnabled = mutableStateOf(false)

    /** Si el servicio de Shizuku está corriendo; con él el visor usa la pantalla virtual. */
    private val shizukuRunning = mutableStateOf(false)

    /** App que abre el panel al arrancar el visor, a la espera del permiso de Shizuku. */
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

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        Shizuku.addBinderReceivedListenerSticky(shizukuReceived)
        Shizuku.addBinderDeadListener(shizukuDead)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionResult)

        setContent {
            val systemInDark = isSystemInDarkTheme()
            var isDarkTheme by remember { mutableStateOf(systemInDark) }
            CeluVRTheme(darkTheme = isDarkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val serviceConnected by JoystickManager.serviceConnected.collectAsState()
                    val fullScreenRejected by ScreenCaptureService.fullScreenRejected.collectAsState()
                    CaptureScreen(
                        supported = isViewerSupported(),
                        fullScreenRejected = fullScreenRejected,
                        serviceConnected = serviceConnected,
                        serviceEnabled = accessibilityEnabled.value,
                        shizukuRunning = shizukuRunning.value,
                        onStartClick = { requestCapture() },
                        isDarkTheme = isDarkTheme,
                        onToggleDarkMode = { isDarkTheme = !isDarkTheme }
                    )
                }
            }
        }
        handleDebugCommand(intent)
    }

    override fun onResume() {
        super.onResume()
        accessibilityEnabled.value = isAccessibilityServiceEnabled()
        shizukuRunning.value = Shizuku.pingBinder()
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
     * visor sobre la pantalla virtual de Shizuku, con [EXTRA_DEBUG_OPEN] opcional.
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
        val packageToOpen = intent.getStringExtra(EXTRA_DEBUG_OPEN)
        when {
            packageToOpen != null -> PanelActivity.show(this, packageToOpen)
            intent.getBooleanExtra(EXTRA_DEBUG_HOME, false) -> PanelActivity.show(this)
        }
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

        if (Shizuku.pingBinder()) {
            startVirtualViewer(null)
        } else {
            launchProjection()
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
    shizukuRunning: Boolean,
    onStartClick: () -> Unit,
    isDarkTheme: Boolean = false,
    onToggleDarkMode: () -> Unit = {}
) {
    var showInfoDialog by remember { mutableStateOf(false) }
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
                IconButton(onClick = { showInfoDialog = true }) {
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
                label = if (shizukuRunning) "Shizuku VR" else "Captura App",
                active = shizukuRunning,
                modifier = Modifier.weight(1f)
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
                        serviceConnected && shizukuRunning ->
                            "Shizuku activo: Iniciar abre el panel de apps directo en el visor con máxima fluidez y rendimiento estéreo."
                        serviceConnected && fullScreenRejected ->
                            "Se eligió \"Pantalla completa\" (se ve en negro). Tocá Iniciar, elegí \"Una sola app\" y seleccioná $appName."
                        serviceConnected ->
                            "Tocá Iniciar, elegí \"Una sola app\" y seleccioná $appName para abrir el panel inmersivo en el visor."
                        serviceEnabled ->
                            "El servicio de accesibilidad está activado pero detenido. Apágalo y vuelve a encenderlo en Ajustes."
                        else ->
                            "Falta activar el servicio de accesibilidad \"$serviceLabel\" para dibujar la vista estéreo."
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

    if (showInfoDialog) {
        AboutScreen(onBack = { showInfoDialog = false })
    }
}

@Composable
fun StatusChip(label: String, active: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
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
                color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
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
                    text = "Acerca de",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            // Scrollable Content
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                // App Icon
                Icon(
                    painter = painterResource(id = R.drawable.ic_celuvr_logo),
                    contentDescription = "Icono de la aplicación",
                    modifier = Modifier.size(88.dp),
                    tint = Color.Unspecified
                )

                // App Name & Version
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Versión 1.0",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Developers Card
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
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "DESARROLLADORES",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text("Jonatan Alvarez", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("Lucas Llanos", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("Fabián Mamani", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                }

                // Subject Card
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
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "MATERIA",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Práctica Profesional Supervisada",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // Institution Card with Logo
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_university),
                            contentDescription = "Logo Institución UNGS",
                            modifier = Modifier.size(48.dp),
                            tint = Color.Unspecified
                        )
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "INSTITUCIÓN",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Universidad Nacional de General Sarmiento - UNGS",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
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
                shizukuRunning = false,
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
                shizukuRunning = false,
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




