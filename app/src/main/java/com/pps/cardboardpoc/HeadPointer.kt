package com.pps.cardboardpoc

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.google.cardboard.sdk.HeadTransform
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign

/**
 * Convierte la orientación de la cabeza que entrega el head tracker del Cardboard SDK en un
 * desplazamiento del cursor. Toma como frente la orientación del inicio de la captura, o la del
 * último centrado. Se alimenta una vez por cuadro desde el hilo de render.
 */
class HeadPointer(
    private val sensitivity: Float = 1.0f,
    private val acceleration: Float = 1.0f
) {

    private val eulerAngles = FloatArray(3)
    private var framesSeen = 0

    private var hasReference = false
    private var referenceYaw = 0f
    private var referencePitch = 0f

    /** Giro desde el frente ya filtrado, en radianes. */
    private var smoothedYaw = 0f
    private var smoothedPitch = 0f
    private var lastUpdateNanos = 0L

    private var handledRecenters = JoystickManager.recenterCount

    /**
     * Lee la orientación de la cabeza del cuadro actual. [imageWidthFraction] es la parte del ancho del
     * ojo que ocupa la imagen: 1 en horizontal, menos en vertical.
     */
    fun update(headTransform: HeadTransform, imageWidthFraction: Float) {
        // Los primeros cuadros pueden llegar antes de que la fusión de sensores se asiente; tomar el
        // frente ahí lo dejaría corrido.
        if (framesSeen++ < WARMUP_FRAMES) {
            JoystickManager.setHeadOffset(0f, 0f)
            return
        }

        headTransform.getEulerAngles(eulerAngles, 0)
        val pitch = eulerAngles[0]
        val yaw = eulerAngles[1]
        val now = System.nanoTime()
        val recenters = JoystickManager.recenterCount
        if (!hasReference || recenters != handledRecenters) {
            handledRecenters = recenters
            referenceYaw = yaw
            referencePitch = pitch
            hasReference = true
            smoothedYaw = 0f
            smoothedPitch = 0f
            lastUpdateNanos = now
            Log.d(TAG, "frente: yaw=${degrees(yaw)} pitch=${degrees(pitch)}")
        }

        val deltaYaw = wrapAngle(yaw - referenceYaw)
        val deltaPitch = pitch - referencePitch

        // Aplicar curva de aceleración si acceleration > 1.0 (suaviza movimientos sutiles y escala los amplios)
        val effectiveYaw = if (acceleration > 1.0f) {
            sign(deltaYaw) * Math.pow(abs(deltaYaw).toDouble(), acceleration.toDouble()).toFloat()
        } else {
            deltaYaw
        }

        val effectivePitch = if (acceleration > 1.0f) {
            sign(deltaPitch) * Math.pow(abs(deltaPitch).toDouble(), acceleration.toDouble()).toFloat()
        } else {
            deltaPitch
        }

        // El filtro depende del tiempo transcurrido y no de los cuadros, así el suavizado no cambia si
        // baja la tasa de refresco.
        val elapsedMs = (now - lastUpdateNanos) / 1_000_000f
        lastUpdateNanos = now
        val weight = 1f - exp(-elapsedMs / SMOOTHING_MS)
        smoothedYaw += (effectiveYaw - smoothedYaw) * weight
        smoothedPitch += (effectivePitch - smoothedPitch) * weight

        // El yaw crece al girar a la izquierda y el pitch al mirar arriba; en pantalla, x crece a la
        // derecha e y hacia abajo. Los dos ejes usan la misma escala para que el giro se sienta igual
        // en cualquier dirección. La escala va por el ancho del ojo y no por el de la imagen: una imagen
        // vertical no llena el ojo a lo ancho, y el cursor tiene que acompañar a la cabeza igual que en
        // horizontal.
        val eyeWidthPx = JoystickManager.screenWidth / imageWidthFraction.coerceIn(0.05f, 1f)
        val pixelsPerRadian = (eyeWidthPx / Math.toRadians(FULL_WIDTH_DEGREES).toFloat()) * sensitivity
        val offsetX = -smoothedYaw * pixelsPerRadian
        val offsetY = -smoothedPitch * pixelsPerRadian
        JoystickManager.setHeadOffset(offsetX, offsetY)

        if (framesSeen % LOG_EVERY_N_FRAMES == 0) {
            Log.d(
                TAG,
                "cabeza: yaw=${degrees(yaw)} pitch=${degrees(pitch)} " +
                    "desde el frente: yaw=${degrees(deltaYaw)} pitch=${degrees(deltaPitch)} " +
                    "desplazamiento=(${offsetX.toInt()}, ${offsetY.toInt()}) px"
            )
        }
    }

    companion object {
        private const val TAG = "GyroDebug"
        private const val WARMUP_FRAMES = 30
        private const val LOG_EVERY_N_FRAMES = 60

        /** Giro de la cabeza, en grados, que lleva el cursor de un costado del ojo al otro. */
        private const val FULL_WIDTH_DEGREES = 80.0

        /**
         * Constante de tiempo del filtro contra el temblor, en ms. Más alta deja el cursor más quieto
         * pero lo hace seguir a la cabeza con más retraso.
         */
        private const val SMOOTHING_MS = 60f

        /**
         * Con true se ignora el giroscopio aunque el teléfono lo tenga, para probar el modo solo
         * joystick sin otro equipo.
         */
        private const val SIMULATE_NO_GYROSCOPE = false

        /** Devuelve un HeadPointer si el teléfono tiene giroscopio, o null si el cursor va solo con el joystick. */
        fun createIfAvailable(context: Context): HeadPointer? {
            val prefs = context.getSharedPreferences("celuvr_prefs", Context.MODE_PRIVATE)
            val hasGyroscope =
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_GYROSCOPE)
            val gyroEnabled = prefs.getBoolean("gyro_enabled", true)
            val sensitivity = prefs.getFloat("gyro_sensitivity", 1.0f)
            val acceleration = prefs.getFloat("gyro_acceleration", 1.0f)
            val enabled = hasGyroscope && gyroEnabled && !SIMULATE_NO_GYROSCOPE
            Log.d(
                TAG,
                "giroscopio=$hasGyroscope gyroEnabled=$gyroEnabled sensitivity=$sensitivity acceleration=$acceleration -> " +
                    if (enabled) "cursor con cabeza y joystick" else "cursor solo con joystick"
            )
            return if (enabled) HeadPointer(sensitivity, acceleration) else null
        }

        /** Lleva un ángulo a [-π, π], para que cruzar el ±180° del yaw no dé un salto de una vuelta. */
        private fun wrapAngle(radians: Float): Float {
            var angle = radians
            while (angle > Math.PI) angle -= (2 * Math.PI).toFloat()
            while (angle < -Math.PI) angle += (2 * Math.PI).toFloat()
            return angle
        }

        private fun degrees(radians: Float): String =
            String.format(Locale.US, "%.1f", Math.toDegrees(radians.toDouble()))
    }
}
