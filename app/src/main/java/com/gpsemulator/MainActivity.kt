package com.gpsemulator

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var tabLayout: TabLayout
    private lateinit var panelLocation: LinearLayout
    private lateinit var panelRoute: LinearLayout
    private lateinit var panelHistory: ScrollView

    // Toolbar
    private lateinit var tvStatus: TextView
    private lateinit var btnStopAll: Button
    private lateinit var tvSimBadge: TextView

    // Location panel
    private lateinit var etLat: EditText
    private lateinit var etLon: EditText
    private lateinit var btnSetLocation: Button
    private lateinit var btnMyLocation: Button
    private lateinit var btnSetOnMap: Button
    private lateinit var btnSaveLocation: Button

    // Route panel
    private lateinit var rvWaypoints: RecyclerView
    private lateinit var btnAddWaypoint: Button
    private lateinit var btnClearRoute: Button
    private lateinit var btnStartRoute: Button
    private lateinit var btnSaveRoute: Button
    private lateinit var btnSpeedMinus: Button
    private lateinit var btnSpeedPlus: Button
    private lateinit var etSpeedValue: EditText

    // History panel
    private lateinit var rvSavedLocations: RecyclerView
    private lateinit var rvSavedRoutes: RecyclerView
    private lateinit var tvNoLocations: TextView
    private lateinit var tvNoRoutes: TextView

    private val waypoints = mutableListOf<RoutePoint>()
    private lateinit var waypointAdapter: WaypointAdapter
    private var speedKmh: Int = 30

    private lateinit var historyManager: HistoryManager
    private lateinit var savedLocAdapter: SavedLocationAdapter
    private lateinit var savedRouteAdapter: SavedRouteAdapter

    private var mockService: MockLocationService? = null
    private var serviceBound = false
    private var currentMarker: Marker? = null
    private var routePolyline: Polyline? = null
    private val waypointMarkers = mutableListOf<Marker>()
    private var pickingFromMap = false
    private var pickingForRouteIndex = -1

    private lateinit var locationManager: LocationManager

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            mockService = (service as MockLocationService.LocalBinder).getService()
            serviceBound = true
            mockService?.setStatusCallback { msg -> runOnUiThread { tvStatus.text = msg } }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            serviceBound = false; mockService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }
        setContentView(R.layout.activity_main)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        historyManager = HistoryManager(this)

        bindViews()
        setupMap()
        setupTabs()
        setupLocationPanel()
        setupRoutePanel()
        setupHistoryPanel()
        requestPermissions()
        bindMockService()
        // Try to center map on current location at startup
        tryInitialLocation()
    }

    private fun bindViews() {
        mapView = findViewById(R.id.map_view)
        tabLayout = findViewById(R.id.tab_layout)
        panelLocation = findViewById(R.id.panel_location)
        panelRoute = findViewById(R.id.panel_route)
        panelHistory = findViewById(R.id.panel_history)

        tvStatus = findViewById(R.id.tv_status)
        btnStopAll = findViewById(R.id.btn_stop_all)
        tvSimBadge = findViewById(R.id.tv_sim_badge)

        etLat = findViewById(R.id.et_latitude)
        etLon = findViewById(R.id.et_longitude)
        btnSetLocation = findViewById(R.id.btn_set_location)
        btnMyLocation = findViewById(R.id.btn_my_location)
        btnSetOnMap = findViewById(R.id.btn_set_on_map)
        btnSaveLocation = findViewById(R.id.btn_save_location)

        rvWaypoints = findViewById(R.id.rv_waypoints)
        btnAddWaypoint = findViewById(R.id.btn_add_waypoint)
        btnClearRoute = findViewById(R.id.btn_clear_route)
        btnStartRoute = findViewById(R.id.btn_start_route)
        btnSaveRoute = findViewById(R.id.btn_save_route)
        btnSpeedMinus = findViewById(R.id.btn_speed_minus)
        btnSpeedPlus = findViewById(R.id.btn_speed_plus)
        etSpeedValue = findViewById(R.id.et_speed_value)

        rvSavedLocations = findViewById(R.id.rv_saved_locations)
        rvSavedRoutes = findViewById(R.id.rv_saved_routes)
        tvNoLocations = findViewById(R.id.tv_no_locations)
        tvNoRoutes = findViewById(R.id.tv_no_routes)
    }

    private fun setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(15.0)
        mapView.controller.setCenter(GeoPoint(25.0330, 121.5654))

        val receiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                if (pickingFromMap) { onMapPointPicked(p); return true }
                return false
            }
            override fun longPressHelper(p: GeoPoint): Boolean {
                showPickActionDialog(p); return true
            }
        }
        mapView.overlays.add(MapEventsOverlay(receiver))
    }

    private fun setupTabs() {
        tabLayout.addTab(tabLayout.newTab().setText("📍 設定位置"))
        tabLayout.addTab(tabLayout.newTab().setText("🗺️ 路徑規劃"))
        tabLayout.addTab(tabLayout.newTab().setText("📋 歷史紀錄"))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                panelLocation.visibility = if (tab.position == 0) View.VISIBLE else View.GONE
                panelRoute.visibility    = if (tab.position == 1) View.VISIBLE else View.GONE
                panelHistory.visibility  = if (tab.position == 2) View.VISIBLE else View.GONE
                if (tab.position == 2) refreshHistory()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {
                if (tab.position == 2) refreshHistory()
            }
        })
    }

    private fun setupLocationPanel() {
        btnSetLocation.setOnClickListener {
            val lat = etLat.text.toString().toDoubleOrNull()
            val lon = etLon.text.toString().toDoubleOrNull()
            if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                Toast.makeText(this, "請輸入有效的經緯度", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            setStaticLocation(lat, lon)
        }

        btnMyLocation.setOnClickListener { fetchRealLocation() }

        btnSetOnMap.setOnClickListener {
            pickingFromMap = true; pickingForRouteIndex = -1
            tvStatus.text = "請點擊地圖選擇位置..."
            Toast.makeText(this, "請點擊地圖選擇位置", Toast.LENGTH_SHORT).show()
        }

        btnSaveLocation.setOnClickListener {
            val lat = etLat.text.toString().toDoubleOrNull()
            val lon = etLon.text.toString().toDoubleOrNull()
            if (lat == null || lon == null) {
                Toast.makeText(this, "請先輸入有效的經緯度", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showSaveNameDialog("儲存位置") { name ->
                historyManager.saveLocation(name, lat, lon)
                Toast.makeText(this, "已儲存：$name", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupRoutePanel() {
        waypointAdapter = WaypointAdapter(
            waypoints,
            onDelete = { index ->
                waypoints.removeAt(index)
                waypointAdapter.notifyItemRemoved(index)
                waypointAdapter.notifyItemRangeChanged(index, waypoints.size)
                updateRouteOnMap()
            },
            onClick = { index ->
                mapView.controller.animateTo(GeoPoint(waypoints[index].latitude, waypoints[index].longitude))
            }
        )
        rvWaypoints.layoutManager = LinearLayoutManager(this)
        rvWaypoints.adapter = waypointAdapter

        btnAddWaypoint.setOnClickListener { showAddWaypointDialog() }

        btnClearRoute.setOnClickListener {
            waypoints.clear(); waypointAdapter.notifyDataSetChanged(); updateRouteOnMap()
            stopAllSimulation()
        }

        etSpeedValue.setText(speedKmh.toString())
        etSpeedValue.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) syncSpeedFromInput() }
        btnSpeedMinus.setOnClickListener {
            syncSpeedFromInput(); speedKmh = (speedKmh - 10).coerceAtLeast(5)
            etSpeedValue.setText(speedKmh.toString())
        }
        btnSpeedPlus.setOnClickListener {
            syncSpeedFromInput(); speedKmh = (speedKmh + 10).coerceAtMost(300)
            etSpeedValue.setText(speedKmh.toString())
        }

        btnStartRoute.setOnClickListener {
            if (mockService?.isRunning == true) {
                stopAllSimulation()
            } else {
                startRouteSimulation()
            }
        }

        btnSaveRoute.setOnClickListener {
            if (waypoints.size < 2) {
                Toast.makeText(this, "至少需要 2 個路徑點才能儲存", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            syncSpeedFromInput()
            showSaveNameDialog("儲存路徑") { name ->
                historyManager.saveRoute(name, waypoints.toList(), speedKmh)
                Toast.makeText(this, "已儲存路徑：$name", Toast.LENGTH_SHORT).show()
            }
        }

        // Global stop button
        btnStopAll.setOnClickListener { stopAllSimulation() }
    }

    private fun setupHistoryPanel() {
        savedLocAdapter = SavedLocationAdapter(
            mutableListOf(),
            onLoad = { item ->
                etLat.setText(item.latitude.toString())
                etLon.setText(item.longitude.toString())
                setStaticLocation(item.latitude, item.longitude)
                tabLayout.getTabAt(0)?.select()
                Toast.makeText(this, "已載入：${item.name}", Toast.LENGTH_SHORT).show()
            },
            onDelete = { item ->
                historyManager.deleteLocation(item.id)
                refreshHistory()
            }
        )
        savedRouteAdapter = SavedRouteAdapter(
            mutableListOf(),
            onLoad = { item ->
                waypoints.clear()
                waypoints.addAll(item.points)
                waypointAdapter.notifyDataSetChanged()
                speedKmh = item.speedKmh
                etSpeedValue.setText(speedKmh.toString())
                updateRouteOnMap()
                tabLayout.getTabAt(1)?.select()
                Toast.makeText(this, "已載入路徑：${item.name}", Toast.LENGTH_SHORT).show()
            },
            onDelete = { item ->
                historyManager.deleteRoute(item.id)
                refreshHistory()
            }
        )
        rvSavedLocations.layoutManager = LinearLayoutManager(this)
        rvSavedLocations.adapter = savedLocAdapter
        rvSavedRoutes.layoutManager = LinearLayoutManager(this)
        rvSavedRoutes.adapter = savedRouteAdapter
    }

    private fun refreshHistory() {
        val locs = historyManager.getLocations()
        savedLocAdapter.refresh(locs)
        tvNoLocations.visibility = if (locs.isEmpty()) View.VISIBLE else View.GONE

        val routes = historyManager.getRoutes()
        savedRouteAdapter.refresh(routes)
        tvNoRoutes.visibility = if (routes.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun syncSpeedFromInput() {
        val v = etSpeedValue.text.toString().toIntOrNull()
        if (v != null && v in 1..300) speedKmh = v else etSpeedValue.setText(speedKmh.toString())
    }

    private fun setSimulationActive(active: Boolean) {
        btnStopAll.isEnabled = active
        btnStopAll.alpha = if (active) 1.0f else 0.4f
        tvSimBadge.visibility = if (active) View.VISIBLE else View.GONE
        btnStartRoute.text = if (active && waypoints.size >= 2) "⏹ 停止路徑模擬" else "▶ 開始路徑模擬"
    }

    private fun stopAllSimulation() {
        mockService?.stopSimulation()
        setSimulationActive(false)
    }

    private fun setStaticLocation(lat: Double, lon: Double) {
        val geoPoint = GeoPoint(lat, lon)
        if (currentMarker == null) {
            currentMarker = Marker(mapView).apply {
                title = "模擬位置"; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            mapView.overlays.add(currentMarker)
        }
        currentMarker?.position = geoPoint
        mapView.controller.animateTo(geoPoint)
        mapView.invalidate()

        val intent = Intent(this, MockLocationService::class.java).apply {
            putExtra(MockLocationService.EXTRA_MODE, MockLocationService.MODE_STATIC)
            putExtra(MockLocationService.EXTRA_LAT, lat)
            putExtra(MockLocationService.EXTRA_LON, lon)
        }
        ContextCompat.startForegroundService(this, intent)
        setSimulationActive(true)
        tvStatus.text = "靜態模式：%.6f, %.6f".format(lat, lon)
    }

    private fun startRouteSimulation() {
        if (waypoints.size < 2) { Toast.makeText(this, "至少需要 2 個路徑點", Toast.LENGTH_SHORT).show(); return }
        syncSpeedFromInput()
        val intent = Intent(this, MockLocationService::class.java).apply {
            putExtra(MockLocationService.EXTRA_MODE, MockLocationService.MODE_ROUTE)
            putExtra(MockLocationService.EXTRA_WAYPOINTS, ArrayList(waypoints))
            putExtra(MockLocationService.EXTRA_SPEED_KMH, speedKmh.toFloat())
        }
        ContextCompat.startForegroundService(this, intent)
        setSimulationActive(true)
        tvStatus.text = "路徑模擬啟動中..."
    }

    @SuppressLint("MissingPermission")
    private fun fetchRealLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "需要位置權限", Toast.LENGTH_SHORT).show(); return
        }

        tvStatus.text = "正在取得目前位置..."; btnMyLocation.isEnabled = false

        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        var best: Location? = null
        for (p in providers) {
            if (!locationManager.isProviderEnabled(p)) continue
            try {
                val loc = locationManager.getLastKnownLocation(p)
                if (loc != null && (best == null || loc.accuracy < best.accuracy)) best = loc
            } catch (_: SecurityException) {}
        }
        if (best != null) { applyRealLocation(best); return }

        val enabledProviders = providers.filter { locationManager.isProviderEnabled(it) }
        if (enabledProviders.isEmpty()) {
            btnMyLocation.isEnabled = true; tvStatus.text = "請開啟 GPS 或網路定位"; return
        }

        var received = false
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!received) {
                    received = true
                    locationManager.removeUpdates(this)
                    runOnUiThread { applyRealLocation(location) }
                }
            }
            @Suppress("DEPRECATION")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        try {
            locationManager.requestLocationUpdates(enabledProviders.first(), 0L, 0f, listener, mainLooper)
        } catch (e: SecurityException) {
            btnMyLocation.isEnabled = true; tvStatus.text = "無法取得位置：${e.message}"
        }
    }

    private fun applyRealLocation(loc: Location) {
        btnMyLocation.isEnabled = true
        etLat.setText("%.6f".format(loc.latitude))
        etLon.setText("%.6f".format(loc.longitude))
        mapView.controller.animateTo(GeoPoint(loc.latitude, loc.longitude))
        mapView.controller.setZoom(16.0)
        tvStatus.text = "目前位置：%.6f, %.6f".format(loc.latitude, loc.longitude)
    }

    private fun showSaveNameDialog(hint: String, onConfirm: (String) -> Unit) {
        val et = EditText(this).apply {
            setPadding(40, 20, 40, 20)
            this.hint = hint
        }
        AlertDialog.Builder(this)
            .setTitle("請輸入名稱")
            .setView(et)
            .setPositiveButton("儲存") { _, _ ->
                val name = et.text.toString().trim().ifEmpty { hint }
                onConfirm(name)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAddWaypointDialog(editIndex: Int = -1) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_waypoint, null)
        val etName  = dialogView.findViewById<EditText>(R.id.et_wp_name)
        val etWpLat = dialogView.findViewById<EditText>(R.id.et_wp_lat)
        val etWpLon = dialogView.findViewById<EditText>(R.id.et_wp_lon)
        val etDwell = dialogView.findViewById<EditText>(R.id.et_wp_dwell)
        val btnPick = dialogView.findViewById<Button>(R.id.btn_pick_from_map)

        if (editIndex >= 0) {
            val pt = waypoints[editIndex]
            etName.setText(pt.name); etWpLat.setText(pt.latitude.toString())
            etWpLon.setText(pt.longitude.toString())
            etDwell.setText(if (pt.dwellSeconds > 0) pt.dwellSeconds.toString() else "")
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (editIndex >= 0) "編輯路徑點" else "新增路徑點")
            .setView(dialogView).setPositiveButton("確認", null).setNegativeButton("取消", null).create()

        btnPick.setOnClickListener {
            dialog.dismiss()
            pickingFromMap = true
            pickingForRouteIndex = if (editIndex >= 0) editIndex else waypoints.size
            tvStatus.text = "請點擊地圖選擇路徑點位置..."
            tabLayout.getTabAt(0)?.select()
        }

        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val lat = etWpLat.text.toString().toDoubleOrNull()
            val lon = etWpLon.text.toString().toDoubleOrNull()
            if (lat == null || lon == null) { Toast.makeText(this, "請輸入有效的經緯度", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val point = RoutePoint(lat, lon, etName.text.toString().trim(), etDwell.text.toString().toIntOrNull() ?: 0)
            if (editIndex >= 0) { waypoints[editIndex] = point; waypointAdapter.notifyItemChanged(editIndex) }
            else { waypoints.add(point); waypointAdapter.notifyItemInserted(waypoints.size - 1) }
            updateRouteOnMap(); dialog.dismiss()
        }
    }

    private fun showPickActionDialog(geoPoint: GeoPoint) {
        AlertDialog.Builder(this)
            .setTitle("%.5f, %.5f".format(geoPoint.latitude, geoPoint.longitude))
            .setItems(arrayOf("📍 設為靜態模擬位置", "➕ 新增為路徑點", "取消")) { _, which ->
                when (which) {
                    0 -> { etLat.setText(geoPoint.latitude.toString()); etLon.setText(geoPoint.longitude.toString()); setStaticLocation(geoPoint.latitude, geoPoint.longitude) }
                    1 -> { waypoints.add(RoutePoint(geoPoint.latitude, geoPoint.longitude)); waypointAdapter.notifyItemInserted(waypoints.size - 1); updateRouteOnMap(); tabLayout.getTabAt(1)?.select() }
                }
            }.show()
    }

    private fun onMapPointPicked(geoPoint: GeoPoint) {
        pickingFromMap = false
        val lat = geoPoint.latitude; val lon = geoPoint.longitude
        if (pickingForRouteIndex == -1) {
            etLat.setText(lat.toString()); etLon.setText(lon.toString()); setStaticLocation(lat, lon)
        } else {
            val point = RoutePoint(lat, lon)
            if (pickingForRouteIndex < waypoints.size) { waypoints[pickingForRouteIndex] = point; waypointAdapter.notifyItemChanged(pickingForRouteIndex) }
            else { waypoints.add(point); waypointAdapter.notifyItemInserted(waypoints.size - 1) }
            updateRouteOnMap(); tabLayout.getTabAt(1)?.select()
            tvStatus.text = "已新增路徑點 %.6f, %.6f".format(lat, lon)
        }
        pickingForRouteIndex = -1
    }

    private fun updateRouteOnMap() {
        waypointMarkers.forEach { mapView.overlays.remove(it) }; waypointMarkers.clear()
        routePolyline?.let { mapView.overlays.remove(it) }; routePolyline = null

        if (waypoints.isEmpty()) { mapView.invalidate(); return }

        val geoPoints = waypoints.map { GeoPoint(it.latitude, it.longitude) }
        if (geoPoints.size >= 2) {
            routePolyline = Polyline().apply { setPoints(geoPoints); color = 0xFF2196F3.toInt(); width = 8f }
            mapView.overlays.add(routePolyline)
        }
        waypoints.forEachIndexed { i, pt ->
            val m = Marker(mapView).apply {
                position = GeoPoint(pt.latitude, pt.longitude)
                title = if (pt.name.isNotBlank()) pt.name else "路徑點 ${i + 1}"
                snippet = "%.6f, %.6f".format(pt.latitude, pt.longitude)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            waypointMarkers.add(m); mapView.overlays.add(m)
        }
        mapView.invalidate()
        if (geoPoints.size >= 2) {
            mapView.controller.animateTo(GeoPoint(
                (geoPoints.minOf { it.latitude } + geoPoints.maxOf { it.latitude }) / 2,
                (geoPoints.minOf { it.longitude } + geoPoints.maxOf { it.longitude }) / 2
            ))
        } else { mapView.controller.animateTo(geoPoints[0]) }
    }

    private fun bindMockService() {
        bindService(Intent(this, MockLocationService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    @SuppressLint("MissingPermission")
    private fun tryInitialLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return

        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        for (provider in providers) {
            try {
                if (!locationManager.isProviderEnabled(provider)) continue
                val loc = locationManager.getLastKnownLocation(provider) ?: continue
                etLat.setText("%.6f".format(loc.latitude))
                etLon.setText("%.6f".format(loc.longitude))
                mapView.controller.setCenter(GeoPoint(loc.latitude, loc.longitude))
                mapView.controller.setZoom(15.0)
                tvStatus.text = "目前位置：%.6f, %.6f".format(loc.latitude, loc.longitude)
                return
            } catch (_: SecurityException) {}
        }

        // No last known — request fresh once in background
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                locationManager.removeUpdates(this)
                runOnUiThread {
                    etLat.setText("%.6f".format(loc.latitude))
                    etLon.setText("%.6f".format(loc.longitude))
                    mapView.controller.animateTo(GeoPoint(loc.latitude, loc.longitude))
                    mapView.controller.setZoom(15.0)
                    tvStatus.text = "目前位置：%.6f, %.6f".format(loc.latitude, loc.longitude)
                }
            }
            @Suppress("DEPRECATION")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        try {
            val available = providers.firstOrNull { locationManager.isProviderEnabled(it) } ?: return
            locationManager.requestLocationUpdates(available, 0L, 0f, listener, mainLooper)
        } catch (_: SecurityException) {}
    }

    private fun requestPermissions() {
        val needed = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        val toRequest = needed.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (toRequest.isNotEmpty()) ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), 100)
        else tryInitialLocation()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) tryInitialLocation()
    }

    override fun onResume() { super.onResume(); mapView.onResume(); if (!serviceBound) bindMockService() }
    override fun onPause() { super.onPause(); mapView.onPause() }
    override fun onDestroy() {
        if (serviceBound) { unbindService(serviceConnection); serviceBound = false }
        mapView.onDetach(); super.onDestroy()
    }
}
