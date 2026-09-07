package com.pps.cardboardpoc

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.pps.cardboardpoc.ui.theme.CardboardPOCTheme

class MainActivity : ComponentActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private val overlayGranted = mutableStateOf(false)

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

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // ACTION_MANAGE_OVERLAY_PERMISSION siempre vuelve con RESULT_CANCELED: hay que reconsultar.
        if (Settings.canDrawOverlays(this)) requestCapture()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setContent {
            CardboardPOCTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CaptureScreen(
                        overlayGranted = overlayGranted.value,
                        onStartClick = { requestCapture() },
                        onStopClick = { stopCapture() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        overlayGranted.value = Settings.canDrawOverlays(this)
    }

    private fun requestCapture() {
        // El permiso de overlay no se pide con un diálogo: hay que mandar al usuario a Ajustes.
        if (!Settings.canDrawOverlays(this)) {
            overlayPermissionLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }

        // Android 14+ exige un foreground service activo antes de pedir MediaProjection
        ContextCompat.startForegroundService(this, Intent(this, ScreenCaptureService::class.java))
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun stopCapture() {
        stopService(Intent(this, ScreenCaptureService::class.java))
    }
}

@Composable
fun CaptureScreen(
    overlayGranted: Boolean,
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
            if (overlayGranted) {
                "Tocá Iniciar y elegí \"Una sola app\". La vista estéreo se dibuja en una ventana " +
                    "flotante sobre esa app, así que se sigue viendo cuando el sistema la trae al frente."
            } else {
                "Falta el permiso \"Mostrar sobre otras apps\". Tocá Iniciar para ir a Ajustes y habilitarlo."
            }
        )
    }
}
