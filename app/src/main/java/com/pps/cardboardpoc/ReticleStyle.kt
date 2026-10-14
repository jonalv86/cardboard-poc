package com.pps.cardboardpoc

/** Aspecto configurable de la retícula. */
data class ReticleStyle(
    /** Radio del anillo en reposo, en píxeles de la pantalla capturada. */
    val radiusPx: Float = 20f,
    /** Color del anillo y del punto, ARGB; el alfa se ignora. */
    val ringColor: Int = 0xFFFFFFFF.toInt(),
    /** Color del anillo y del punto durante la pulsación sostenida, ARGB; el alfa se ignora. */
    val holdColor: Int = 0xFF5DD6B0.toInt()
)
