package com.pps.cardboardpoc.vdisplay

import android.os.IBinder
import android.util.Log

/**
 * Guarda el binder del proceso servidor una vez que [VDisplayReceiverProvider] lo recibe, para que el
 * resto de la app lo use. Vive mientras vive el proceso de la app.
 */
object VDisplayConnection {

    private const val TAG = "CardboardCapture"

    @Volatile
    var server: IVDisplayServer? = null
        private set

    private val deathRecipient = IBinder.DeathRecipient {
        Log.w(TAG, "servidor de pantalla virtual: el proceso murió")
        server = null
    }

    fun onServerReceived(binder: IBinder) {
        val connected = IVDisplayServer.Stub.asInterface(binder)
        try {
            binder.linkToDeath(deathRecipient, 0)
        } catch (e: Exception) {
            Log.w(TAG, "servidor de pantalla virtual: no se pudo observar su muerte", e)
        }
        server = connected
        Log.d(TAG, "servidor de pantalla virtual conectado")
    }

    /** Si hay un servidor conectado y su proceso sigue vivo. */
    fun isAvailable(): Boolean {
        val current = server ?: return false
        return try {
            current.asBinder().pingBinder()
        } catch (e: Exception) {
            false
        }
    }
}
