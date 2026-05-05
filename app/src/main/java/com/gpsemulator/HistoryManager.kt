package com.gpsemulator

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SavedLocation(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val latitude: Double,
    val longitude: Double
)

data class SavedRoute(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val points: List<RoutePoint>,
    val speedKmh: Int = 30
)

class HistoryManager(context: Context) {

    private val prefs = context.getSharedPreferences("gps_history", Context.MODE_PRIVATE)

    // ── Locations ──────────────────────────────────────────────

    fun saveLocation(name: String, lat: Double, lon: Double): SavedLocation {
        val item = SavedLocation(name = name, latitude = lat, longitude = lon)
        val list = getLocations().toMutableList().apply { add(0, item) }
        prefs.edit().putString("locations", locationsToJson(list.take(50))).apply()
        return item
    }

    fun getLocations(): List<SavedLocation> {
        return try {
            val arr = JSONArray(prefs.getString("locations", "[]") ?: "[]")
            (0 until arr.length()).map { i ->
                arr.getJSONObject(i).run {
                    SavedLocation(getString("id"), getString("name"), getDouble("lat"), getDouble("lon"))
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    fun deleteLocation(id: String) {
        prefs.edit().putString("locations", locationsToJson(getLocations().filter { it.id != id })).apply()
    }

    private fun locationsToJson(list: List<SavedLocation>): String {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().apply {
            put("id", it.id); put("name", it.name)
            put("lat", it.latitude); put("lon", it.longitude)
        }) }
        return arr.toString()
    }

    // ── Routes ─────────────────────────────────────────────────

    fun saveRoute(name: String, points: List<RoutePoint>, speedKmh: Int): SavedRoute {
        val item = SavedRoute(name = name, points = points, speedKmh = speedKmh)
        val list = getRoutes().toMutableList().apply { add(0, item) }
        prefs.edit().putString("routes", routesToJson(list.take(20))).apply()
        return item
    }

    fun getRoutes(): List<SavedRoute> {
        return try {
            val arr = JSONArray(prefs.getString("routes", "[]") ?: "[]")
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val pArr = obj.getJSONArray("points")
                val pts = (0 until pArr.length()).map { j ->
                    pArr.getJSONObject(j).run {
                        RoutePoint(getDouble("lat"), getDouble("lon"), optString("name", ""), optInt("dwell", 0))
                    }
                }
                SavedRoute(obj.getString("id"), obj.getString("name"), pts, obj.optInt("speed", 30))
            }
        } catch (_: Exception) { emptyList() }
    }

    fun deleteRoute(id: String) {
        prefs.edit().putString("routes", routesToJson(getRoutes().filter { it.id != id })).apply()
    }

    private fun routesToJson(list: List<SavedRoute>): String {
        val arr = JSONArray()
        list.forEach { route ->
            val pts = JSONArray()
            route.points.forEach { pt -> pts.put(JSONObject().apply {
                put("lat", pt.latitude); put("lon", pt.longitude)
                put("name", pt.name); put("dwell", pt.dwellSeconds)
            }) }
            arr.put(JSONObject().apply {
                put("id", route.id); put("name", route.name)
                put("speed", route.speedKmh); put("points", pts)
            })
        }
        return arr.toString()
    }
}
