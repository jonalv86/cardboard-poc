package com.pps.cardboardpoc

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplayConfig
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresApi
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * Pantalla virtual propia del visor, creada con los permisos de adb que presta Shizuku. Una app común
 * solo puede crear pantallas "no confiables", donde Android no deja abrir apps ajenas; esta es
 * confiable, así que cualquier app corre en ella a fps completos, y la ventana estéreo, que vive en la
 * pantalla física, nunca entra en lo que se lee.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class ShizukuDisplay private constructor(
    private val displayManager: Any,
    private val callback: Any,
    override val displayId: Int
) : VirtualDisplayBackend {

    override fun setSurface(surface: Surface?) {
        invoke("setVirtualDisplaySurface", callback, surface)
    }

    override fun resize(width: Int, height: Int, densityDpi: Int) {
        invoke("resizeVirtualDisplay", callback, width, height, densityDpi)
    }

    override fun release() {
        invoke("releaseVirtualDisplay", callback)
    }

    private fun invoke(name: String, vararg args: Any?) {
        displayManager.javaClass.methods.first { it.name == name }.invoke(displayManager, *args)
    }

    companion object {
        private const val TAG = "CardboardCapture"

        /** Flags ocultos de VirtualDisplayConfig; los públicos salen de DisplayManager. */
        private const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        private const val FLAG_TRUSTED = 1 shl 10
        private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
        private const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
        private const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13

        /** El servicio de Shizuku corre como el usuario shell, y el sistema exige su paquete. */
        private const val SHELL_PACKAGE = "com.android.shell"

        /** Si Shizuku está corriendo y CeluVR tiene su permiso. */
        fun isAvailable(): Boolean =
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

        /**
         * Crea la pantalla dibujando sobre [surface]. Sin las banderas de decoraciones del sistema no
         * tiene barra de navegación ni launcher: el panel del visor hace de inicio.
         */
        fun create(surface: Surface, width: Int, height: Int, densityDpi: Int): ShizukuDisplay {
            HiddenApiBypass.addHiddenApiExemptions("L")
            val binder = ShizukuBinderWrapper(
                SystemServiceHelper.getSystemService(Context.DISPLAY_SERVICE)
            )
            val displayManager = asInterface("android.hardware.display.IDisplayManager", binder)
            // El sistema solo usa el callback como identificador de la pantalla y para avisar pausas,
            // que acá no hacen falta: alcanza con un Binder vacío detrás del proxy.
            val callback = asInterface("android.hardware.display.IVirtualDisplayCallback", Binder())

            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                FLAG_SUPPORTS_TOUCH or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or
                FLAG_ALWAYS_UNLOCKED or FLAG_TOUCH_FEEDBACK_DISABLED
            val config = VirtualDisplayConfig.Builder("CeluVR", width, height, densityDpi)
                .setFlags(flags)
                .setSurface(surface)
                .build()

            val create = displayManager.javaClass.methods.first { it.name == "createVirtualDisplay" }
            val displayId = create.invoke(displayManager, config, callback, null, SHELL_PACKAGE) as Int
            Log.d(TAG, "pantalla virtual creada id=$displayId ${width}x$height dpi=$densityDpi")
            return ShizukuDisplay(displayManager, callback, displayId)
        }

        private fun asInterface(name: String, binder: IBinder): Any =
            Class.forName("$name\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)!!
    }
}
