package com.pps.cardboardpoc

import android.view.Surface
import android.view.SurfaceControl
import com.pps.cardboardpoc.vdisplay.IVDisplayServer
import com.pps.cardboardpoc.vdisplay.VDisplayConnection

/**
 * Pantalla virtual creada por el proceso servidor propio de CeluVR, lanzado con app_process y los
 * permisos de adb. Alternativa a [ShizukuDisplay] que no depende de la app Shizuku: el servidor se
 * conecta por [VDisplayConnection]. Cada operación es una llamada al servidor por binder.
 */
class ServerDisplay private constructor(
    private val server: IVDisplayServer,
    override val displayId: Int
) : VirtualDisplayBackend {

    override fun setSurface(surface: Surface?) {
        server.setSurface(surface)
    }

    override fun resize(width: Int, height: Int, densityDpi: Int) {
        server.resize(width, height, densityDpi)
    }

    override fun release() {
        server.release()
    }

    override fun mirror(): SurfaceControl? = server.mirror()

    companion object {
        /** Si el servidor propio está conectado y vivo. */
        fun isAvailable(): Boolean = VDisplayConnection.isAvailable()

        /** Crea la pantalla dibujando sobre [surface]. El servidor ya tiene que estar conectado. */
        fun create(surface: Surface, width: Int, height: Int, densityDpi: Int): ServerDisplay {
            val server = VDisplayConnection.server
                ?: error("el servidor de pantalla virtual no está conectado")
            val displayId = server.create(surface, width, height, densityDpi)
            return ServerDisplay(server, displayId)
        }
    }
}
