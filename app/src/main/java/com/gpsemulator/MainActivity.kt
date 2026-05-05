package com.gpsemulator

import android.Manifest
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
import android.text.InputType
import android.view.LayoutInflater
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

    // Location panel
    private lateinit var etLat: EditText
    private lateinit var etLon: EditText
    private lateinit var btnSetLocation: Button
    private lateinit var btnSetOnMap: Button
    private lateinit var btnMyLocation: Button
    private lateinit var tvStatus: TextView

    // Route panel
    private lateinit var rvWaypoints: RecyclerView
    private lateinit var btnAddWaypoint: Button
    private lateinit var btnClearRoute: Button
    private lateinit var btnStartRoute: Button
    private lateinit var btnSpeedMinus: Button
    private lateinit var btnSpeedPlus: Button
    private lateinit var etSpeedValue: EditText

    private val waypoints = mutableListOf<RoutePoint>()
    private lateinit var waypointAdapter: WaypointAdapter
    private var speedKmh: Int = 30

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
            val binder = service as MockLocationService.LocalBinder
            mockService = binder.getService()
            serviceBound = true
            mockService?.setStatusCallback { msg ->
                runOnUiThread { tvStatus.text = msg }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            serviceBound = false
            mockService = null
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

        bindViews()
        setupMap()
        setupTabs()
        setupLocationPanel()
        setupRoutePanel()
        requestPermissions()
        bindService()
    }

    private fun bindViews() {
        mapView = findViewById(R.id.map_view)
        tabLayout = findViewById(R.id.tab_layout)
        panelLocation = findViewById(R.id.panel_location)
        panelRoute = findViewById(R.id.panel_route)

        etLat = findViewById(R.id.et_latitude)
        etLon = findViewById(R.id.et_longitude)
        btnSetLocation = findViewById(R.id.btn_set_location)
        btnSetOnMap = findViewById(R.id.btn_set_on_map)
        btnMyLocation = findViewById(R.id.btn_my_location)
        tvStatus = findViewById(R.id.tv_status)

        rvWaypoints = findViewById(R.id.rv_waypoints)
        btnAddWaypoint = findViewById(R.id.btn_add_waypoint)
        btnClearRoute = findViewById(R.id.btn_clear_route)
        btnStartRoute = findViewById(R.id.btn_start_route)
        btnSpeedMinus = findViewById(R.id.btn_speed_minus)
        btnSpeedPlus = findViewById(R.id.btn_speed_plus)
        etSpeedValue = findViewById(R.id.et_speed_value)
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

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                panelLocation.visibility = if (tab.position == 0) android.view.View.VISIBLE else android.view.View.GONE
                panelRoute.visibility   = if (tab.position == 1) android.view.View.VISIBLE else android.view.View.GONE
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
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

        btnSetOnMap.setOnClickListener {
            pickingFromMap = true
            pickingForRouteIndex = -1
            tvStatus.text = "請點擊地圖選擇位置..."
            Toast.makeText(this, "請點擊地圖選擇位置", Toast.LENGTH_SHORT).show()
        }

        btnMyLocation.setOnClickListener { fetchRealLocation() }
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
            waypoints.clear()
            waypointAdapter.notifyDataSetChanged()
            updateRouteOnMap()
            mockService?.stopSimulation()
            btnStartRoute.text = "▶ 開始路徑模擬"
        }

        etSpeedValue.setText(speedKmh.toString())
        etSpeedValue.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) syncSpeedFromInput()
        }

        btnSpeedMinus.setOnClickListener {
            syncSpeedFromInput()
            speedKmh = (speedKmh - 10).coerceAtLeast(5)
            etSpeedValue.setText(speedKmh.toString())
        }
        btnSpeedPlus.setOnClickListener {
            syncSpeedFromInput()
            speedKmh = (speedKmh + 10).coerceAtMost(200)
            etSpeedValue.setText(speedKmh.toString())
        }

        btnStartRoute.setOnClickListener {
            if (mockService?.isRunning == true) {
                mockService?.stopSimulation()
                btnStartRoute.text = "▶ 開始路徑模擬"
            } else {
                startRouteSimulation()
            }
        }
    }

    private fun syncSpeedFromInput() {
        val v = etSpeedValue.text.toString().toIntOrNull()
        if (v != null && v in 1..300) speedKmh = v
        else etSpeedValue.setText(speedKmh.toString())
    }

    private fun fetchRealLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "需要位置權限", Toast.LENGTH_SHORT).show()
            return
        }

        tvStatus.text = "正在取得目前位置..."
        btnMyLocation.isEnabled = false

        // Try last known location first (fast)
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        var best: Location? = null
        for (p in providers) {
            try {
                val loc = locationManager.getLastKnownLocation(p)
                if (loc != null && (best == null || loc.accuracy < best.accuracy)) best = loc
            } catch (_: Exception) {}
        }

        if (best != null) {
            applyRealLocation(best)
            return
        }

        // Request fresh location
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                locationManager.removeUpdates(this)
                runOnUiThread { applyRealLocation(location) }
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        }

        try {
            locationManager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, mainLooper)
        } catch (e: Exception) {
            btnMyLocation.isEnabled = true
            tvStatus.text = "無法取得位置：${e.message}"
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

    private fun setStaticLocation(lat: Double, lon: Double) {
        val geoPoint = GeoPoint(lat, lon)
        if (currentMarker == null) {
            currentMarker = Marker(mapView).apply {
                title = "模擬位置"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
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
        tvStatus.text = "靜態模式：%.6f, %.6f".format(lat, lon)
    }

    private fun startRouteSimulation() {
        if (waypoints.size < 2) {
            Toast.makeText(this, "至少需要 2 個路徑點", Toast.LENGTH_SHORT).show()
            return
        }
        syncSpeedFromInput()
        val intent = Intent(this, MockLocationService::class.java).apply {
            putExtra(MockLocationService.EXTRA_MODE, MockLocationService.MODE_ROUTE)
            putExtra(MockLocationService.EXTRA_WAYPOINTS, ArrayList(waypoints))
            putExtra(MockLocationService.EXTRA_SPEED_KMH, speedKmh.toFloat())
        }
        ContextCompat.startForegroundService(this, intent)
        btnStartRoute.text = "⏹ 停止路徑模擬"
        tvStatus.text = "路徑模擬啟動中..."
    }

    private fun showAddWaypointDialog(editIndex: Int = -1) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_waypoint, null)
        val etName   = dialogView.findViewById<EditText>(R.id.et_wp_name)
        val etWpLat  = dialogView.findViewById<EditText>(R.id.et_wp_lat)
        val etWpLon  = dialogView.findViewById<EditText>(R.id.et_wp_lon)
        val etDwell  = dialogView.findViewById<EditText>(R.id.et_wp_dwell)
        val btnPick  = dialogView.findViewById<Button>(R.id.btn_pick_from_map)

        if (editIndex >= 0) {
            val pt = waypoints[editIndex]
            etName.setText(pt.name)
            etWpLat.setText(pt.latitude.toString())
            etWpLon.setText(pt.longitude.toString())
            etDwell.setText(if (pt.dwellSeconds > 0) pt.dwellSeconds.toString() else "")
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (editIndex >= 0) "編輯路徑點" else "新增路徑點")
            .setView(dialogView)
            .setPositiveButton("確認", null)
            .setNegativeButton("取消", null)
            .create()

        btnPick.setOnClickListener {
            dialog.dismiss()
            pickingFromMap = true
            pickingForRouteIndex = if (editIndex >= 0) editIndex else waypoints.size
            tvStatus.text = "請點擊地圖選擇路徑點位置..."
            Toast.makeText(this, "請點擊地圖選擇位置", Toast.LENGTH_SHORT).show()
            tabLayout.getTabAt(0)?.select()
        }

        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val lat = etWpLat.text.toString().toDoubleOrNull()
            val lon = etWpLon.text.toString().toDoubleOrNull()
            if (lat == null || lon == null) {
                Toast.makeText(this, "請輸入有效的經緯度", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val point = RoutePoint(lat, lon, etName.text.toString().trim(), etDwell.text.toString().toIntOrNull() ?: 0)
            if (editIndex >= 0) {
                waypoints[editIndex] = point
                waypointAdapter.notifyItemChanged(editIndex)
            } else {
                waypoints.add(point)
                waypointAdapter.notifyItemInserted(waypoints.size - 1)
            }
            updateRouteOnMap()
            dialog.dismiss()
        }
    }

    private fun showPickActionDialog(geoPoint: GeoPoint) {
        AlertDialog.Builder(this)
            .setTitle("%.5f, %.5f".format(geoPoint.latitude, geoPoint.longitude))
            .setItems(arrayOf("設為靜態模擬位置", "新增為路徑點", "取消")) { _, which ->
                when (which) {
                    0 -> {
                        etLat.setText(geoPoint.latitude.toString())
                        etLon.setText(geoPoint.longitude.toString())
                        setStaticLocation(geoPoint.latitude, geoPoint.longitude)
                    }
                    1 -> {
                        waypoints.add(RoutePoint(geoPoint.latitude, geoPoint.longitude))
                        waypointAdapter.notifyItemInserted(waypoints.size - 1)
                        updateRouteOnMap()
                        tabLayout.getTabAt(1)?.select()
                    }
                }
            }.show()
    }

    private fun onMapPointPicked(geoPoint: GeoPoint) {
        pickingFromMap = false
        val lat = geoPoint.latitude
        val lon = geoPoint.longitude

        if (pickingForRouteIndex == -1) {
            etLat.setText(lat.toString())
            etLon.setText(lon.toString())
            setStaticLocation(lat, lon)
        } else {
            val point = RoutePoint(lat, lon)
            if (pickingForRouteIndex < waypoints.size) {
                waypoints[pickingForRouteIndex] = point
                waypointAdapter.notifyItemChanged(pickingForRouteIndex)
            } else {
                waypoints.add(point)
                waypointAdapter.notifyItemInserted(waypoints.size - 1)
            }
            updateRouteOnMap()
            tabLayout.getTabAt(1)?.select()
            tvStatus.text = "已新增路徑點 %.6f, %.6f".format(lat, lon)
        }
        pickingForRouteIndex = -1
    }

    private fun updateRouteOnMap() {
        waypointMarkers.forEach { mapView.overlays.remove(it) }
        waypointMarkers.clear()
        routePolyline?.let { mapView.overlays.remove(it) }
        routePolyline = null

        if (waypoints.isEmpty()) { mapView.invalidate(); return }

        val geoPoints = waypoints.map { GeoPoint(it.latitude, it.longitude) }

        if (geoPoints.size >= 2) {
            routePolyline = Polyline().apply {
                setPoints(geoPoints)
                color = 0xFF2196F3.toInt()
                width = 8f
            }
            mapView.overlays.add(routePolyline)
        }

        waypoints.forEachIndexed { index, point ->
            val marker = Marker(mapView).apply {
                position = GeoPoint(point.latitude, point.longitude)
                title = if (point.name.isNotBlank()) point.name else "路徑點 ${index + 1}"
                snippet = "%.6f, %.6f".format(point.latitude, point.longitude)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            waypointMarkers.add(marker)
            mapView.overlays.add(marker)
        }

        mapView.invalidate()

        if (geoPoints.size >= 2) {
            val center = GeoPoint(
                (geoPoints.minOf { it.latitude } + geoPoints.maxOf { it.latitude }) / 2,
                (geoPoints.minOf { it.longitude } + geoPoints.maxOf { it.longitude }) / 2
            )
            mapView.controller.animateTo(center)
        } else {
            mapView.controller.animateTo(geoPoints[0])
        }
    }

    private fun bindService() {
        val intent = Intent(this, MockLocationService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun requestPermissions() {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val toRequest = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (toRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), 100)
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        if (!serviceBound) bindService()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onDestroy() {
        if (serviceBound) { unbindService(serviceConnection); serviceBound = false }
        mapView.onDetach()
        super.onDestroy()
    }
}
