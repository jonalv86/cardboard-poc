package com.pps.cardboardpoc.vdisplay

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Log

internal const val METHOD_SET_SERVER = "setServer"
internal const val KEY_SERVER = "server"

/**
 * Recibe el binder del proceso servidor ([VDisplayServer]) y se lo pasa a [VDisplayConnection]. El
 * servidor, que corre como shell, llama a `call` sobre este provider con el binder dentro del Bundle;
 * es el mismo mecanismo con que Shizuku entrega su binder a las apps.
 *
 * Solo acepta la llamada del usuario shell (o root): es quien lanza el servidor.
 */
class VDisplayReceiverProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val uid = Binder.getCallingUid()
        if (uid != Process.SHELL_UID && uid != Process.ROOT_UID) {
            Log.w(TAG, "provider: se ignora un pedido de uid=$uid")
            return null
        }
        if (method == METHOD_SET_SERVER) {
            val binder = extras?.getBinder(KEY_SERVER)
            if (binder != null) VDisplayConnection.onServerReceived(binder)
        }
        return null
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
    ): Int = 0

    private companion object {
        const val TAG = "CardboardCapture"
    }
}
