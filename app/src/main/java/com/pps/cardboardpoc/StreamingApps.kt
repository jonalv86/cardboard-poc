package com.pps.cardboardpoc

/**
 * Apps de streaming que protegen sus videos con DRM. En la imagen capturada de la pantalla virtual el
 * video les sale en negro, así que mientras están al frente el visor las muestra con clones de esa
 * pantalla, que compone el sistema.
 */
object StreamingApps {

    private val drmPackages = setOf(
        "com.netflix.mediaclient",
        "com.disney.disneyplus",
        "com.amazon.avod.thirdpartyclient",
        "com.wbd.stream",
        "com.hbo.hbonow",
        "com.cbs.app",
        "com.cbs.ott",
        "com.hulu.plus",
        "com.peacocktv.peacockandroid",
        "com.crunchyroll.crunchyroid",
        "com.mubi",
        "com.google.android.videos"
    )

    /** Si la app de [packageName] protege sus videos con DRM. */
    fun usesDrm(packageName: String): Boolean = packageName in drmPackages
}
