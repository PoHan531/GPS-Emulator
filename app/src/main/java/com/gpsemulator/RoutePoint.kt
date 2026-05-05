package com.gpsemulator

import java.io.Serializable

data class RoutePoint(
    val latitude: Double,
    val longitude: Double,
    val name: String = "",
    val dwellSeconds: Int = 0
) : Serializable {
    override fun toString(): String {
        val label = if (name.isNotBlank()) name else "%.6f, %.6f".format(latitude, longitude)
        return if (dwellSeconds > 0) "$label (停留 ${dwellSeconds}s)" else label
    }
}
