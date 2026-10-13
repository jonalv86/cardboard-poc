package com.pps.cardboardpoc

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.pps.cardboardpoc.ui.theme.CardboardPOCTheme

class MainActivity : ComponentActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager

    /** Si el servicio figura activado en Ajustes, aunque no esté corriendo. */
    private val accessibilityEnabled = mutableStateOf(false)

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
        super.onCreate(savedInstanceState)
        mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setContent {
            CardboardPOCTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val serviceConnected by JoystickManager.serviceConnected.collectAsState()
                    val fullScreenRejected by ScreenCaptureService.fullScreenRejected.collectAsState()
                    CaptureScreen(
                        supported = isViewerSupported(),
                        fullScreenRejected = fullScreenRejected,
                        serviceConnected = serviceConnected,
                        serviceEnabled = accessibilityEnabled.value,
                        onStartClick = { requestCapture() },
                        onStopClick = { stopCapture() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        accessibilityEnabled.value = isAccessibilityServiceEnabled()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugCommand(intent)
    }

    /**
     * Atajos para manejar el visor por adb en las pruebas, solo en compilaciones depurables y con el
     * visor andando: [EXTRA_DEBUG_OPEN] abre una app desde el panel como si se la eligiera ahí, y
     * [EXTRA_DEBUG_HOME] vuelve al panel como el botón "Inicio".
     */
    private fun handleDebugCommand(intent: Intent) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        if (!ScreenCaptureService.isCapturing) return
        val packageToOpen = intent.getStringExtra(EXTRA_DEBUG_OPEN)
        when {
            packageToOpen != null -> PanelActivity.show(this, packageToOpen)
            intent.getBooleanExtra(EXTRA_DEBUG_HOME, false) -> PanelActivity.show(this)
        }
    }

    private fun requestCapture() {
        if (!isViewerSupported()) return

        // El servicio de accesibilidad es el dueño de la ventana estéreo y el que traduce el control,
        // y solo se puede activar a mano desde Ajustes.
        if (!JoystickManager.serviceConnected.value) {
            openAccessibilitySettings()
            return
        }

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
    private fun stopCapture() {
        stopService(Intent(this, ScreenCaptureService::class.java))
    }

    private companion object {
        /** Paquete de la app a abrir desde el panel (atajo de prueba por adb). */
        const val EXTRA_DEBUG_OPEN = "abrir"

        /** Con true vuelve al panel (atajo de prueba por adb). */
        const val EXTRA_DEBUG_HOME = "inicio"
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
    onStartClick: () -> Unit,
    onStopClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(onClick = onStartClick, enabled = supported, modifier = Modifier.fillMaxWidth()) {
            Text("Iniciar visor")
        }
        OutlinedButton(onClick = onStopClick, modifier = Modifier.fillMaxWidth()) {
            Text("Detener captura")
        }
        Text(
            when {
                !supported ->
                    "El visor requiere Android 14 o superior (este equipo tiene Android " +
                        "${Build.VERSION.RELEASE})."
                serviceConnected && fullScreenRejected ->
                    "Se eligió \"Pantalla completa\", que dentro del visor se ve en negro. Tocá " +
                        "Iniciar visor, elegí \"Una sola app\" y en la lista elegí CardboardPOC."
                serviceConnected ->
                    "Tocá Iniciar visor, elegí \"Una sola app\" y en la lista elegí CardboardPOC: " +
                        "se abre el panel de apps dentro del visor. La fluidez depende de cada app: " +
                        "las que se abren dentro del visor se ven fluidas, y las que el sistema abre " +
                        "aparte (Chrome, YouTube, Maps y otras) se ven a unos 3 cuadros por segundo " +
                        "y sin video. El botón \"Inicio\", debajo de la imagen, vuelve al panel."
                serviceEnabled ->
                    "El servicio \"Joystick Controller (CardboardPOC)\" figura activado pero no está " +
                        "corriendo (puede pasar después de reinstalar la app). Tocá Iniciar, buscalo " +
                        "en la lista (en Samsung, dentro de \"Apps instaladas\"), apagalo y volvé a " +
                        "prenderlo."
                else ->
                    "Falta activar el servicio de accesibilidad \"Joystick Controller " +
                        "(CardboardPOC)\": dibuja la vista estéreo y traduce el control. Tocá Iniciar, " +
                        "buscalo en la lista (en Samsung, dentro de \"Apps instaladas\") y activalo."
            }
        )
        Text(
            "Control VR BOX: tiene que estar en modo joystick. Stick = mover el cursor, " +
                "gatillo = tocar, botón A = atrás. En modo mouse el sistema dibuja su propio " +
                "puntero, que no se ve en estéreo."
        )
    }
}
