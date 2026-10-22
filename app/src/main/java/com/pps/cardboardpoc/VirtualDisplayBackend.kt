package com.pps.cardboardpoc

import android.view.Surface

/**
 * Pantalla virtual confiable del visor, sin importar quién presta los permisos de adb: Shizuku
 * ([ShizukuDisplay]) o el servidor propio de CeluVR ([ServerDisplay]). El pipeline de captura la maneja
 * igual en los dos casos.
 */
interface VirtualDisplayBackend {

    /** Id de la pantalla virtual, para abrir apps en ella y dirigir los gestos. */
    val displayId: Int

    /** Cambia la superficie donde se dibuja la pantalla; null la deja sin destino. */
    fun setSurface(surface: Surface?)

    /** Cambia el tamaño y la densidad de la pantalla. */
    fun resize(width: Int, height: Int, densityDpi: Int)

    /** Destruye la pantalla. Las apps que estaban en ella pasan a la pantalla física. */
    fun release()
}
