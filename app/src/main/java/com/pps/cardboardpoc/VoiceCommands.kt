package com.pps.cardboardpoc

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.io.IOException

/**
 * Escucha el micrófono durante la sesión y traduce "tocar", "agarrar", "soltar", "centrar" y "volver"
 * en acciones del cursor. Una frase cuenta como comando solo si es esa palabra sola.
 */
class VoiceCommands(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())

    private var model: Model? = null
    private var recognizer: Recognizer? = null

    /** Hilo que lee el micrófono y alimenta al reconocedor mientras [listening] sea true. */
    private var audioThread: Thread? = null

    @Volatile
    private var listening = false

    /** Si ya se pidió [stop]: un modelo que termina de cargar después se descarta. */
    private var stopped = false

    /**
     * Copia el modelo de los assets la primera vez, lo carga en otro hilo y empieza a escuchar.
     */
    fun start() {
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        Thread({
            val loaded = try {
                Model(StorageService.sync(context, ASSET_MODEL_DIR, MODEL_TARGET_DIR))
            } catch (e: IOException) {
                Log.e(TAG, "no se pudo cargar el modelo", e)
                null
            }
            handler.post {
                if (loaded == null) return@post
                if (stopped) {
                    loaded.close()
                    return@post
                }
                model = loaded
                listen(loaded)
            }
        }, "VoskModelLoad").start()
    }

    /** Deja de escuchar y libera el modelo. */
    fun stop() {
        stopped = true
        listening = false
        audioThread?.join()
        audioThread = null
        recognizer?.close()
        recognizer = null
        model?.close()
        model = null
    }

    private fun listen(model: Model) {
        val rec = try {
            Recognizer(model, SAMPLE_RATE.toFloat()).apply {
                setEndpointerDelays(START_SILENCE_S, END_SILENCE_S, MAX_PHRASE_S)
            }
        } catch (e: IOException) {
            Log.e(TAG, "no se pudo crear el reconocedor", e)
            return
        }
        recognizer = rec
        val bufferBytes = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
            READ_SAMPLES * 2 * 4
        )
        @SuppressLint("MissingPermission")
        val record = AudioRecord(
            AUDIO_SOURCE, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "no se pudo abrir el micrófono")
            record.release()
            return
        }
        record.startRecording()
        listening = true
        audioThread = Thread({
            val buffer = ShortArray(READ_SAMPLES)
            // El comando que muestra el parcial de la frase en curso y desde cuándo, y si ya se ejecutó.
            var partialCommand: Command? = null
            var partialSinceMs = 0L
            var executed = false
            while (listening) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                if (rec.acceptWaveForm(buffer, read)) {
                    val command = commandFor(JSONObject(rec.result).optString("text"))
                    if (command != null && !executed) execute(command)
                    partialCommand = null
                    executed = false
                } else {
                    val command = commandFor(JSONObject(rec.partialResult).optString("partial"))
                    val now = SystemClock.uptimeMillis()
                    if (command != partialCommand) {
                        partialCommand = command
                        partialSinceMs = now
                    } else if (command != null && !executed && now - partialSinceMs >= STABLE_PARTIAL_MS) {
                        executed = true
                        execute(command)
                    }
                }
            }
            record.stop()
            record.release()
        }, "VoskAudio").apply { start() }
    }

    /** Ejecuta [command] en el hilo principal. */
    private fun execute(command: Command) {
        handler.post {
            if (stopped) return@post
            when (command) {
                Command.CLICK -> JoystickManager.voiceClick()
                Command.GRAB -> JoystickManager.voiceGrab()
                Command.RELEASE -> JoystickManager.voiceRelease()
                Command.CENTER -> JoystickManager.recenter()
                Command.BACK -> JoystickManager.performBack()
            }
        }
    }

    enum class Command { CLICK, GRAB, RELEASE, CENTER, BACK }

    companion object {
        private const val TAG = "VoiceCommands"
        private const val SAMPLE_RATE = 16000

        /**
         * Segundos tras los que se cierra una frase en la que el reconocedor todavía no ve ninguna palabra.
         * Una palabra corta a veces recién aparece al cerrar la frase: con los 5 s del modelo, el comando
         * llegaba hasta 5 s tarde.
         */
        private const val START_SILENCE_S = 1.5f

        /**
         * Silencio tras la última palabra que cierra la frase cuando el reconocedor está seguro; Vosk
         * espera 0,5 y 1 s más cuando lo está menos. El modelo pide 0,5 s.
         */
        private const val END_SILENCE_S = 0.3f

        /**
         * Cuánto tiene que durar sin cambios un parcial que es solo un comando para ejecutarlo sin esperar
         * el cierre de la frase. Si la frase sigue, la palabra siguiente aparece en el parcial antes.
         */
        private const val STABLE_PARTIAL_MS = 300L

        /** Largo máximo de una frase, en segundos. */
        private const val MAX_PHRASE_S = 20f

        /** Muestras por lectura del micrófono: 100 ms a 16 kHz. */
        private const val READ_SAMPLES = 1600

        /**
         * Fuente del micrófono. VOICE_COMMUNICATION activa la cancelación de eco, pero no resta el audio
         * multimedia y sube la ganancia: con un video sonando dispara más comandos.
         */
        private const val AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION

        /** Carpeta del modelo dentro de los assets; la arma la tarea downloadVoskModel. */
        private const val ASSET_MODEL_DIR = "model-es"

        /** Carpeta del almacenamiento de la app a la que se copia el modelo para poder cargarlo. */
        private const val MODEL_TARGET_DIR = "model"

        /**
         * Frase transcrita y el comando que dispara. "toca" cubre el "tocá" del voseo; el modelo a veces
         * transcribe "soltar" como "soldar" y "agarrar" como "agarra".
         */
        private val COMMANDS = mapOf(
            "tocar" to Command.CLICK, "toca" to Command.CLICK,
            "agarrar" to Command.GRAB, "agarra" to Command.GRAB,
            "soltar" to Command.RELEASE, "soldar" to Command.RELEASE,
            "centrar" to Command.CENTER,
            "volver" to Command.BACK
        )

        /**
         * Comando que dispara una frase transcrita, o null. Una frase de un video o de una charla puede
         * contener la palabra; un comando es la frase entera.
         */
        fun commandFor(text: String): Command? = COMMANDS[text.trim()]

        private const val PREFS = "celuvr_prefs"
        private const val KEY_ENABLED = "voice_enabled"

        /** Si la app tiene permiso de micrófono. */
        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        /** Si el usuario activó los comandos de voz en ajustes, haya o no permiso de micrófono. */
        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        /** Guarda si el usuario activó los comandos de voz. */
        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        }

        /** Si la sesión tiene que escuchar: activado en ajustes y con permiso de micrófono. */
        fun shouldListen(context: Context): Boolean = isEnabled(context) && hasPermission(context)
    }
}
