package com.pps.cardboardpoc

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.media.projection.MediaProjectionManager
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
                    CaptureScreen(
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

    private fun requestCapture() {
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
}

@Composable
fun CaptureScreen(
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
        Button(onClick = onStartClick, modifier = Modifier.fillMaxWidth()) {
            Text("Iniciar captura de pantalla")
        }
        OutlinedButton(onClick = onStopClick, modifier = Modifier.fillMaxWidth()) {
            Text("Detener captura")
        }
        Text(
            when {
                serviceConnected ->
                    "Tocá Iniciar y elegí \"Una sola app\": con \"Pantalla completa\" la vista estéreo " +
                        "sale en negro. La vista se dibuja en una ventana flotante sobre esa app, así " +
                        "que se sigue viendo cuando el sistema la trae al frente."
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
