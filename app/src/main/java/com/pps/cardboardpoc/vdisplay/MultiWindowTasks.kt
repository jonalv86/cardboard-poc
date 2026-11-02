package com.pps.cardboardpoc.vdisplay

import android.graphics.Rect

/**
 * Tareas de las apps abiertas en la pantalla virtual del visor. Se abren en modo multiventana, donde
 * Android no respeta la orientación que pide la app: una que solo funciona en vertical se dibuja en
 * horizontal ocupando toda la pantalla, en vez de encajada con bandas. A cambio, el tamaño de esas
 * tareas no sigue solo al de la pantalla y hay que fijarlo.
 */
object MultiWindowTasks {

    /** WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW, oculto. */
    const val WINDOWING_MODE_MULTI_WINDOW = 6

    /** ActivityTaskManager.RESIZE_MODE_SYSTEM, oculto: el cambio lo pide el sistema, no el usuario. */
    private const val RESIZE_MODE_SYSTEM = 0

    /**
     * Lleva las tareas multiventana de [displayId] a [width] x [height], ocupando toda la pantalla.
     * [activityTaskManager] es la interfaz oculta IActivityTaskManager con los permisos de adb.
     */
    fun fit(activityTaskManager: Any, displayId: Int, width: Int, height: Int) {
        val target = Rect(0, 0, width, height)
        val methods = activityTaskManager.javaClass.methods
        val tasks = methods.first { it.name == "getAllRootTaskInfosOnDisplay" }
            .invoke(activityTaskManager, displayId) as List<*>
        val resize = methods.first { it.name == "resizeTask" }
        for (task in tasks.filterNotNull()) {
            val windowingMode = task.javaClass.getMethod("getWindowingMode").invoke(task)
            if (windowingMode != WINDOWING_MODE_MULTI_WINDOW) continue
            if (task.javaClass.getField("bounds").get(task) == target) continue
            val taskId = task.javaClass.getField("taskId").getInt(task)
            resize.invoke(activityTaskManager, taskId, target, RESIZE_MODE_SYSTEM)
        }
    }
}
