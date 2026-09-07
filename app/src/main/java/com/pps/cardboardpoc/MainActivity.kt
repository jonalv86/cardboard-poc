package com.pps.cardboardpoc

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import com.pps.cardboardpoc.ui.theme.CardboardPOCTheme
import androidx.compose.foundation.Image as ComposeImage

class MainActivity : ComponentActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val latestFrame = mutableStateOf<Bitmap?>(null)

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startCapture(result.resultCode, result.data!!)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setContent {
            CardboardPOCTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CaptureScreen(frame = latestFrame.value, onStartClick = { requestCapture() })
                }
            }
        }
    }

    private fun requestCapture() {
        // Android 14+ exige un foreground service activo antes de pedir MediaProjection
        ContextCompat.startForegroundService(this, Intent(this, ScreenCaptureService::class.java))
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)

        // Obligatorio en Android 14+: registrar el callback ANTES de createVirtualDisplay,
        // si no, tira IllegalStateException y la app se cierra.
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                virtualDisplay?.release()
                imageReader?.close()
                virtualDisplay = null
                imageReader = null
            }
        }, Handler(Looper.getMainLooper()))

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val mainHandler = Handler(Looper.getMainLooper())

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            val bitmap = imageToBitmap(image)
            image.close()
            mainHandler.post { latestFrame.value = bitmap }
        }, mainHandler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "CardboardPocCapture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(plane.buffer)
        return Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
    }

    override fun onDestroy() {
        super.onDestroy()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        stopService(Intent(this, ScreenCaptureService::class.java))
    }
}

@Composable
fun CaptureScreen(frame: Bitmap?, onStartClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Button(onClick = onStartClick, modifier = Modifier.fillMaxWidth()) {
            Text("Iniciar captura de pantalla")
        }
        if (frame != null) {
            val imageBitmap = frame.asImageBitmap()
            Row(modifier = Modifier.fillMaxSize()) {
                ComposeImage(imageBitmap, "Ojo izquierdo", modifier = Modifier.weight(1f).fillMaxSize())
                ComposeImage(imageBitmap, "Ojo derecho", modifier = Modifier.weight(1f).fillMaxSize())
            }
        } else {
            Text("Todavía no hay captura. Tocá el botón y aceptá el permiso del sistema.")
        }
    }
}