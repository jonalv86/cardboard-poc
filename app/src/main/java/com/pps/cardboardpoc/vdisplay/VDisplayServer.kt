package com.pps.cardboardpoc.vdisplay

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplayConfig
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.Surface

/**
 * Proceso servidor que crea y maneja la pantalla virtual del visor. Se lanza con app_process desde el
 * APK con los permisos de adb (usuario shell), que es lo que permite una pantalla "confiable" donde se
 * abren apps ajenas a fps completos. Reemplaza a Shizuku: hace solo esto, y solo le responde a CeluVR.
 *
 * La app recibe el binder de este servidor en [VDisplayReceiverProvider] y lo maneja desde
 * [com.pps.cardboardpoc.vdisplay.ServerDisplay].
 */
object VDisplayServer {

    private const val TAG = "CardboardCapture"

    /** El sistema exige que el paquete declarado coincida con el usuario que llama (shell). */
    private const val SHELL_PACKAGE = "com.android.shell"

    private const val AUTHORITY = "com.pps.cardboardpoc.vdisplay"

    /** Flags ocultos de VirtualDisplayConfig; los públicos salen de DisplayManager. */
    private const val FLAG_SUPPORTS_TOUCH = 1 shl 6
    private const val FLAG_TRUSTED = 1 shl 10
    private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
    private const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
    private const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            run()
        } catch (t: Throwable) {
            Log.e(TAG, "servidor: error fatal", t)
            @Suppress("DEPRECATION")
            System.exit(1)
        }
    }

    private fun run() {
        // El ActivityThread del sistema crea un Handler en su constructor, así que el hilo necesita un
        // Looper preparado antes (en este build de One UI systemMain() no lo prepara solo). Inicializarlo
        // deja además el proceso con el pool de hilos de binder que atiende las llamadas de la app.
        if (Looper.myLooper() == null) Looper.prepareMainLooper()
        val activityThread = Class.forName("android.app.ActivityThread")
        val systemMain = activityThread.getMethod("systemMain").invoke(null)
        activityThread.getMethod("getSystemContext").invoke(systemMain)

        val displayManager = displayManagerService()
        val stub = ServerStub(displayManager)

        deliverBinder(stub)
        Log.d(TAG, "servidor: binder entregado a la app, a la espera de pedidos")

        Looper.loop()
    }

    /**
     * Entrega el binder [stub] al provider de la app ([VDisplayReceiverProvider]). Pasa por
     * `getContentProviderExternal` de ActivityManager en vez del camino común, que exige que el paquete
     * del contexto coincida con el uid: como shell ese paquete sería "android", de otro uid, y lo
     * rechaza. `getContentProviderExternal` usa un permiso que shell sí tiene; es lo que hace scrcpy.
     */
    private fun deliverBinder(stub: IBinder) {
        val am = asInterface("android.app.IActivityManager", serviceBinder("activity"))
        val token = Binder()
        val holder = getContentProviderExternal(am, token)
            ?: error("no se obtuvo el provider $AUTHORITY (¿la app está abierta?)")
        try {
            val provider = holder.javaClass.getField("provider").get(holder)
            val extras = Bundle().apply { putBinder(KEY_SERVER, stub) }
            callProvider(provider, extras)
        } finally {
            am.javaClass.methods.first { it.name == "removeContentProviderExternal" }
                .invoke(am, AUTHORITY, token)
        }
    }

    /** getContentProviderExternal cambió de firma entre versiones: con y sin el parámetro de etiqueta. */
    private fun getContentProviderExternal(am: Any, token: IBinder): Any? {
        val method = am.javaClass.methods.first { it.name == "getContentProviderExternal" }
        val args: Array<Any?> = if (method.parameterTypes.size == 4) {
            arrayOf(AUTHORITY, 0, token, "CeluVR")
        } else {
            arrayOf(AUTHORITY, 0, token)
        }
        return method.invoke(am, *args)
    }

    /**
     * Llama a `call` del IContentProvider. En Android 12+ el primer parámetro es un AttributionSource
     * (uid + paquete del que llama); en versiones previas es el nombre de paquete, con o sin featureId.
     */
    private fun callProvider(provider: Any, extras: Bundle) {
        val call = provider.javaClass.methods
            .filter { it.name == "call" }
            .maxByOrNull { it.parameterTypes.size }!!
        val first = call.parameterTypes[0].name
        val args: Array<Any?> = when {
            first == "android.content.AttributionSource" ->
                arrayOf(shellAttributionSource(), AUTHORITY, METHOD_SET_SERVER, null, extras)

            call.parameterTypes.size == 6 ->
                arrayOf(SHELL_PACKAGE, null, AUTHORITY, METHOD_SET_SERVER, null, extras)

            else ->
                arrayOf(SHELL_PACKAGE, AUTHORITY, METHOD_SET_SERVER, null, extras)
        }
        call.invoke(provider, *args)
    }

    /** AttributionSource con el uid y el paquete de shell, para que el sistema acepte la llamada. */
    private fun shellAttributionSource(): Any {
        val builder = Class.forName("android.content.AttributionSource\$Builder")
            .getConstructor(Int::class.javaPrimitiveType)
            .newInstance(Process.SHELL_UID)
        builder.javaClass.getMethod("setPackageName", String::class.java)
            .invoke(builder, SHELL_PACKAGE)
        return builder.javaClass.getMethod("build").invoke(builder)!!
    }

    /** Interfaz oculta IDisplayManager, obtenida directo como shell (sin ShizukuBinderWrapper). */
    private fun displayManagerService(): Any =
        asInterface("android.hardware.display.IDisplayManager", serviceBinder(Context.DISPLAY_SERVICE))

    private fun serviceBinder(name: String): IBinder =
        Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, name) as IBinder

    private fun asInterface(name: String, binder: IBinder): Any =
        Class.forName("$name\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)!!

    /**
     * Atiende los pedidos de la app. Guarda el callback de la pantalla, que las interfaces ocultas usan
     * como identificador para redimensionarla, cambiarle la superficie y liberarla.
     */
    private class ServerStub(private val displayManager: Any) : IVDisplayServer.Stub() {

        private var callback: Any? = null

        override fun create(surface: Surface, width: Int, height: Int, densityDpi: Int): Int {
            release()
            // El sistema solo usa el callback como identificador de la pantalla y para avisar pausas,
            // que acá no hacen falta: alcanza con un Binder vacío detrás del proxy.
            val cb = asInterface("android.hardware.display.IVirtualDisplayCallback", Binder())

            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                FLAG_SUPPORTS_TOUCH or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or
                FLAG_ALWAYS_UNLOCKED or FLAG_TOUCH_FEEDBACK_DISABLED
            val config = VirtualDisplayConfig.Builder("CeluVR", width, height, densityDpi)
                .setFlags(flags)
                .setSurface(surface)
                .build()

            val create = displayManager.javaClass.methods.first { it.name == "createVirtualDisplay" }
            val displayId = create.invoke(displayManager, config, cb, null, SHELL_PACKAGE) as Int
            callback = cb
            Log.d(TAG, "servidor: pantalla virtual id=$displayId ${width}x$height dpi=$densityDpi")
            return displayId
        }

        override fun setSurface(surface: Surface?) {
            callback?.let { invoke("setVirtualDisplaySurface", it, surface) }
        }

        override fun resize(width: Int, height: Int, densityDpi: Int) {
            callback?.let { invoke("resizeVirtualDisplay", it, width, height, densityDpi) }
        }

        override fun release() {
            callback?.let { invoke("releaseVirtualDisplay", it) }
            callback = null
        }

        override fun shutdown() {
            release()
            Log.d(TAG, "servidor: apagado a pedido de la app")
            @Suppress("DEPRECATION")
            System.exit(0)
        }

        private fun invoke(name: String, vararg args: Any?) {
            displayManager.javaClass.methods.first { it.name == name }.invoke(displayManager, *args)
        }
    }
}
