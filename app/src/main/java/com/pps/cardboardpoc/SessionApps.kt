package com.pps.cardboardpoc

import android.content.ComponentName

/**
 * Lo que el panel recuerda durante la sesión actual del visor: las apps abiertas, de la más reciente a
 * la más vieja, y las que hay que abrir en una tarea propia.
 */
object SessionApps {

    private val used = mutableListOf<ComponentName>()
    private val ownTask = mutableSetOf<ComponentName>()

    /** Registra que se abrió [component]; pasa a ser la más reciente. */
    @Synchronized
    fun markUsed(component: ComponentName) {
        used.remove(component)
        used.add(0, component)
    }

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

    /** Olvida lo registrado; se llama al terminar la sesión del visor. */
    @Synchronized
    fun clear() {
        used.clear()
        ownTask.clear()
    }
}
