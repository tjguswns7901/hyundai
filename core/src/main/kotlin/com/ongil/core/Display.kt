package com.ongil.core

/** 운전자가 고르는 표시 단위 [MEET]. 내부 계산은 항상 m/s. */
enum class SpeedUnit { KMH, MPS }

object Display {
    fun speed(mps: Double, u: SpeedUnit) = if (u == SpeedUnit.KMH) mps * 3.6 else mps
    fun accel(mps2: Double, u: SpeedUnit) = if (u == SpeedUnit.KMH) mps2 * 3.6 else mps2
    fun speedLabel(u: SpeedUnit) = if (u == SpeedUnit.KMH) "km/h" else "m/s"
    fun accelLabel(u: SpeedUnit) = if (u == SpeedUnit.KMH) "km/h/s" else "m/s²"
}
