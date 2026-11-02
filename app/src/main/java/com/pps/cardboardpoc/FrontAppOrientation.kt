package com.pps.cardboardpoc

/**
 * Orientación de la pantalla virtual según la app al frente. Toda app se ve en horizontal hasta que el
 * usuario la pasa a vertical, aunque solo funcione en vertical: el sistema la muestra centrada, con
 * bandas. Vuelve sola a horizontal al pasar a otra app y cuando lo que queda al frente solo funciona
 * en horizontal.
 */
class FrontAppOrientation {

    /** Paquete de la última ventana principal de app que estuvo al frente. */
    private var lastApp: String? = null

    /**
     * Si la pantalla tiene que quedar en vertical, estando en [portrait], con la ventana de
     * [packageName] al frente encajada como [fit]. Sin app al frente o con un diálogo encima, no cambia.
     */
    fun portrait(portrait: Boolean, packageName: String?, fit: WindowFit): Boolean {
        if (packageName == null || !fit.isMain) return portrait
        val appChanged = lastApp != null && lastApp != packageName
        lastApp = packageName
        return portrait && !appChanged && fit != WindowFit.LANDSCAPE_ONLY
    }
}
