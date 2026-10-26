package com.pps.cardboardpoc.vdisplay;

import android.view.Surface;
import android.view.SurfaceControl;

/**
 * Lo que la app le pide al proceso servidor para manejar la pantalla virtual. El servidor corre con
 * los permisos de adb (usuario shell), lanzado por app_process, así que puede crear una pantalla
 * "confiable" donde cualquier app ajena se abre a fps completos.
 */
interface IVDisplayServer {

    /** Crea la pantalla dibujando sobre [surface] y devuelve su id. */
    int create(in Surface surface, int width, int height, int densityDpi);

    /** Cambia la superficie donde se dibuja; null la deja sin destino. */
    void setSurface(in @nullable Surface surface);

    /** Cambia el tamaño y la densidad de la pantalla. */
    void resize(int width, int height, int densityDpi);

    /** Destruye la pantalla. Las apps que estaban en ella pasan a la pantalla física. */
    void release();

    /** Libera la pantalla y termina el proceso servidor. */
    void shutdown();

    /**
     * Clona el árbol de capas de la pantalla y devuelve la raíz del clon, para colgarla en otra
     * pantalla. Se clonan las capas, no los píxeles: el video protegido lo compone la pantalla de
     * destino. Null si no hay pantalla o el sistema no lo permite.
     */
    @nullable SurfaceControl mirror();
}
