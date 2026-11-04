package com.pps.cardboardpoc.adb

import android.os.IBinder

/**
 * Proceso de una sola pasada, lanzado con app_process como shell (que tiene MANAGE_DEBUGGING), que
 * desvincula de la Depuración inalámbrica las entradas de CeluVR. Cada emparejamiento con una clave nueva
 * deja una entrada más en "Dispositivos vinculados", así que sin esto la lista crece con cada setup.
 *
 * Sin argumentos conserva la entrada conectada (la de la conexión desde la que se lanzó) y, si no hay
 * ninguna conectada, no toca nada para no dejar a CeluVR sin vinculación. Con [ARG_ALL] desvincula todas.
 */
object PairedDevicesCleaner {

    const val ARG_ALL = "--todas"

    private class PairedDevice(val name: String?, val guid: String, val connected: Boolean)

    @JvmStatic
    fun main(args: Array<String>) {
        val removeAll = args.contains(ARG_ALL)
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "adb") as IBinder
        val adb = Class.forName("android.debug.IAdbManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)!!

        val ours = pairedDevices(adb).filter { it.name?.startsWith(AdbConnectionManager.DEVICE_NAME) == true }
        if (!removeAll && ours.none { it.connected }) {
            println("ninguna entrada de CeluVR conectada (${ours.size} en total): no se desvincula nada")
            return
        }
        val unpair = adb.javaClass.getMethod("unpairDevice", String::class.java)
        var removed = 0
        for (device in ours) {
            if (!removeAll && device.connected) continue
            unpair.invoke(adb, device.guid)
            removed++
        }
        println("desvinculadas $removed de ${ours.size} entradas de CeluVR")
    }

    /**
     * Lee `getPairedDevices`, que según la versión devuelve un Map de clave a PairDevice o un arreglo de
     * FingerprintAndPairDevice; y PairDevice expone sus datos con getters o, como parcelable AIDL, con
     * campos públicos.
     */
    private fun pairedDevices(adb: Any): List<PairedDevice> {
        val raw = adb.javaClass.getMethod("getPairedDevices").invoke(adb)
        val devices: List<Any> = when (raw) {
            is Map<*, *> -> raw.values.filterNotNull()
            is Array<*> -> raw.filterNotNull().map { property(it, "device") ?: it }
            else -> emptyList()
        }
        return devices.mapNotNull { device ->
            val guid = property(device, "guid") as? String ?: return@mapNotNull null
            PairedDevice(
                name = (property(device, "deviceName") ?: property(device, "name")) as? String,
                guid = guid,
                connected = (property(device, "connected") as? Boolean) == true
            )
        }
    }

    private fun property(target: Any, name: String): Any? {
        val capitalized = name.replaceFirstChar { it.uppercase() }
        for (getter in listOf("get$capitalized", "is$capitalized")) {
            runCatching { return target.javaClass.getMethod(getter).invoke(target) }
        }
        return runCatching { target.javaClass.getField(name).get(target) }.getOrNull()
    }
}
