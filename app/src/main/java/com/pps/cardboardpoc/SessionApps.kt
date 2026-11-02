package com.pps.cardboardpoc

import android.content.ComponentName

/**
 * Lo que el panel recuerda durante la sesión actual del visor: las apps abiertas, de la más reciente a
 * la más vieja, las que hay que abrir en una tarea propia y las que solo funcionan en una orientación.
 */
object SessionApps {

    private val used = mutableListOf<ComponentName>()
    private val ownTask = mutableSetOf<ComponentName>()

    /** Orientación fija de cada app, por paquete: true vertical, false horizontal. */
    private val lockedOrientation = mutableMapOf<String, Boolean>()

    /** Registra que se abrió [component]; pasa a ser la más reciente. */
    @Synchronized
    fun markUsed(component: ComponentName) {
        used.remove(component)
        used.add(0, component)
    }

    /** Devuelve la lista de componentes recientes. */
    @Synchronized
    fun getRecent(): List<ComponentName> = used.toList()

    /** Posición de cada app usada (0 = la más reciente), tomada de una vez para ordenar sin cambios en medio. */
    @Synchronized
    fun ranks(): Map<ComponentName, Int> = used.withIndex().associate { (index, app) -> app to index }

    /** Registra que [component] se cierra sola si se la abre dentro de la tarea del visor. */
    @Synchronized
    fun markNeedsOwnTask(component: ComponentName) {
        ownTask.add(component)
    }

    /** Si [component] hay que abrirla en una tarea propia. */
    @Synchronized
    fun needsOwnTask(component: ComponentName): Boolean = component in ownTask

    /**
     * Registra que la app de [packageName] solo funciona en vertical ([portrait] true) o en horizontal.
     * Reemplaza lo anterior: una app puede tener pantallas fijas en cada orientación.
     */
    @Synchronized
    fun lockOrientation(packageName: String, portrait: Boolean) {
        lockedOrientation[packageName] = portrait
    }

    /** Orientación fija de la app de [packageName]: true vertical, false horizontal, null cualquiera. */
    @Synchronized
    fun lockedPortrait(packageName: String): Boolean? = lockedOrientation[packageName]

    /** Olvida lo registrado; se llama al terminar la sesión del visor. */
    @Synchronized
    fun clear() {
        used.clear()
        ownTask.clear()
        lockedOrientation.clear()
    }
}
