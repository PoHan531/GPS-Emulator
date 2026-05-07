package com.gpsemulator

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {

    // ── 主框架 ──────────────────────────────────────────────────────────────
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var mapView: MapView

    // 頂部 FAB
    private lateinit var fabMenu: FloatingActionButton
    private lateinit var fabSearch: FloatingActionButton

    // 地圖疊加 UI
    private lateinit var tvSimBadge: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvMapCoords: TextView
    private lateinit var btnSetHere: Button
    private lateinit var btnCopyCoords: ImageButton
    private lateinit var fabMyLocation: FloatingActionButton
    private lateinit var btnStopAll: FloatingActionButton

    // 抽屜標題
    private lateinit var headerLocation: View
    private lateinit var headerHistory: View
    private lateinit var headerRoute: View

    // ── 資料 ────────────────────────────────────────────────────────────────
    private val waypoints = mutableListOf<RoutePoint>()
    private var speedKmh: Int = 30

    // 從 dialog 持久化的 adapter（避免每次開啟 dialog 都重建）
    private lateinit var historyManager: HistoryManager

    private var mockService: MockLocationService? = null
    private var serviceBound = false
    private var currentMarker: Marker? = null
    private var routePolyline: Polyline? = null
    private val waypointMarkers = mutableListOf<Marker>()
    private var pickingFromMap = false
    private var pickingForRouteIndex = -1

    private lateinit var locationManager: LocationManager

    // 新鮮 GPS 請求的 listener（避免 leak）
    private var freshLocationListener: LocationListener? = null
    private val freshLocationTimeout = Handler(Looper.getMainLooper())

    // ── Service 連線 ────────────────────────────────────────────────────────
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            mockService = (service as MockLocationService.LocalBinder).getService()
            serviceBound = true
            mockService?.registerStatusCallback { msg -> runOnUiThread { tvStatus.text = msg } }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            serviceBound = false; mockService = null
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────
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
        setupDrawer()
        requestPermissions()
        bindMockService()
        tryInitialLocation()
    }

    private fun bindViews() {
        drawerLayout   = findViewById(R.id.drawer_layout)
        mapView        = findViewById(R.id.map_view)
        fabMenu        = findViewById(R.id.fab_menu)
        fabSearch      = findViewById(R.id.fab_search)
        tvSimBadge     = findViewById(R.id.tv_sim_badge)
        tvStatus       = findViewById(R.id.tv_status)
        tvMapCoords    = findViewById(R.id.tv_map_coords)
        btnSetHere     = findViewById(R.id.btn_set_here)
        btnCopyCoords  = findViewById(R.id.btn_copy_coords)
        fabMyLocation  = findViewById(R.id.fab_my_location)
        btnStopAll     = findViewById(R.id.btn_stop_all)
        headerLocation = findViewById(R.id.header_location)
        headerHistory  = findViewById(R.id.header_history)
        headerRoute    = findViewById(R.id.header_route)
    }

    // ── 地圖設定 ────────────────────────────────────────────────────────────
    private fun setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(15.0)
        mapView.controller.setCenter(GeoPoint(25.0330, 121.5654))

        // 地圖捲動/縮放時更新底部座標顯示
        mapView.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean { updateCoordsFromCenter(); return false }
            override fun onZoom(event: ZoomEvent?): Boolean { updateCoordsFromCenter(); return false }
        })

        // 地圖點擊事件
        mapView.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                if (pickingFromMap) { onMapPointPicked(p); return true }
                return false
            }
            override fun longPressHelper(p: GeoPoint): Boolean {
                showPickActionDialog(p); return true
            }
        }))
    }

    private fun updateCoordsFromCenter() {
        val c = mapView.mapCenter
        tvMapCoords.text = "%.6f, %.6f".format(c.latitude, c.longitude)
    }

    // ── 抽屜與按鈕設定 ──────────────────────────────────────────────────────
    private fun setupDrawer() {
        fabMenu.setOnClickListener { drawerLayout.openDrawer(GravityCompat.START) }
        fabSearch.setOnClickListener { showSearchDialog() }

        // 底部「設定此位置」：以地圖中心點開始模擬
        btnSetHere.setOnClickListener {
            val c = mapView.mapCenter
            setStaticLocation(c.latitude, c.longitude)
        }

        // 複製座標
        btnCopyCoords.setOnClickListener {
            val coords = tvMapCoords.text.toString()
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("座標", coords))
            Toast.makeText(this, "已複製：$coords", Toast.LENGTH_SHORT).show()
        }

        // 右下 FAB：取得裝置最新 GPS 位置（非快取）
        fabMyLocation.setOnClickListener { requestFreshLocation() }

        // 停止模擬
        btnStopAll.setOnClickListener { stopAllSimulation() }

        // 各節點擊彈出視窗
        headerLocation.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            showLocationDialog()
        }
        headerHistory.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            showHistoryDialog()
        }
        headerRoute.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            showRouteDialog()
        }
    }

    // ── 搜尋對話框（座標 + 關鍵字地名） ────────────────────────────────────
    private fun showSearchDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 8)
        }

        val etInput = EditText(this).apply {
            hint = "經緯度 (25.033, 121.565) 或地名"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            setTextColor(0xFF212121.toInt())
            setHintTextColor(0xFF9E9E9E.toInt())
        }
        layout.addView(etInput)

        val tvHint = TextView(this).apply {
            text = "輸入「緯度, 經度」直接跳轉，或輸入地名搜尋"
            textSize = 11f
            setTextColor(0xFF9E9E9E.toInt())
            setPadding(0, 4, 0, 0)
        }
        layout.addView(tvHint)

        val dialog = AlertDialog.Builder(this, R.style.LightDialog)
            .setTitle("🔍  搜尋位置")
            .setView(layout)
            .setPositiveButton("搜尋", null)
            .setNegativeButton("取消", null)
            .create()

        // 顯示後設定視窗寬度為緊湊尺寸
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.88).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val input = etInput.text.toString().trim()
            if (input.isEmpty()) return@setOnClickListener

            // 判斷是否為「緯度, 經度」格式
            val coordRegex = Regex("""^\s*(-?\d+\.?\d*)\s*,\s*(-?\d+\.?\d*)\s*$""")
            val match = coordRegex.find(input)
            if (match != null) {
                val lat = match.groupValues[1].toDoubleOrNull()
                val lon = match.groupValues[2].toDoubleOrNull()
                if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                    mapView.controller.animateTo(GeoPoint(lat, lon))
                    mapView.controller.setZoom(16.0)
                    tvMapCoords.text = "%.6f, %.6f".format(lat, lon)
                    dialog.dismiss()
                    return@setOnClickListener
                }
            }

            // 否則用 Nominatim 搜尋地名
            tvHint.text = "搜尋中..."
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            searchByKeyword(input) { results ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                if (results.isEmpty()) {
                    tvHint.text = "找不到結果，請再試試其他關鍵字"
                    return@searchByKeyword
                }
                // 如果只有一個結果，直接前往
                if (results.size == 1) {
                    val (_, lat, lon) = results[0]
                    mapView.controller.animateTo(GeoPoint(lat, lon))
                    mapView.controller.setZoom(16.0)
                    tvMapCoords.text = "%.6f, %.6f".format(lat, lon)
                    dialog.dismiss()
                    return@searchByKeyword
                }
                // 多個結果讓使用者選擇
                dialog.dismiss()
                showSearchResultsDialog(results)
            }
        }

        // 自動開啟鍵盤
        etInput.requestFocus()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    private fun searchByKeyword(query: String, callback: (List<Triple<String, Double, Double>>) -> Unit) {
        Thread {
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val url = URL("https://nominatim.openstreetmap.org/search?q=$encodedQuery&format=json&limit=5&accept-language=zh-TW,en")
                val conn = url.openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", packageName)
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                val response = conn.inputStream.bufferedReader().readText()
                val arr = org.json.JSONArray(response)
                val list = mutableListOf<Triple<String, Double, Double>>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val name = obj.getString("display_name")
                    val lat = obj.getString("lat").toDoubleOrNull() ?: continue
                    val lon = obj.getString("lon").toDoubleOrNull() ?: continue
                    // 顯示名稱只取前兩段（省略過長的地址）
                    val shortName = name.split(",").take(2).joinToString(",").trim()
                    list.add(Triple(shortName, lat, lon))
                }
                runOnUiThread { callback(list) }
            } catch (e: Exception) {
                runOnUiThread { callback(emptyList()) }
            }
        }.start()
    }

    private fun showSearchResultsDialog(results: List<Triple<String, Double, Double>>) {
        val names = results.map { it.first }.toTypedArray()
        AlertDialog.Builder(this, R.style.LightDialog)
            .setTitle("請選擇地點")
            .setItems(names) { _, which ->
                val (_, lat, lon) = results[which]
                mapView.controller.animateTo(GeoPoint(lat, lon))
                mapView.controller.setZoom(16.0)
                tvMapCoords.text = "%.6f, %.6f".format(lat, lon)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ── 設定位置對話框 ───────────────────────────────────────────────────────
    private fun showLocationDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_location, null)
        val etLat        = dialogView.findViewById<EditText>(R.id.et_latitude)
        val etLon        = dialogView.findViewById<EditText>(R.id.et_longitude)
        val btnSet       = dialogView.findViewById<Button>(R.id.btn_set_location)
        val btnMy        = dialogView.findViewById<Button>(R.id.btn_my_location)
        val btnMapPick   = dialogView.findViewById<Button>(R.id.btn_set_on_map)
        val btnSave      = dialogView.findViewById<Button>(R.id.btn_save_location)

        // 同步目前地圖中心點
        val c = mapView.mapCenter
        etLat.setText("%.6f".format(c.latitude))
        etLon.setText("%.6f".format(c.longitude))

        val dialog = AlertDialog.Builder(this, R.style.LightDialog)
            .setTitle("📍  設定位置清單")
            .setView(dialogView)
            .setNegativeButton("關閉", null)
            .create()

        btnSet.setOnClickListener {
            val lat = etLat.text.toString().toDoubleOrNull()
            val lon = etLon.text.toString().toDoubleOrNull()
            if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                Toast.makeText(this, "請輸入有效的經緯度", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            setStaticLocation(lat, lon)
            dialog.dismiss()
        }

        btnMy.setOnClickListener {
            fetchRealLocationIntoFields(etLat, etLon)
        }

        btnMapPick.setOnClickListener {
            pickingFromMap = true; pickingForRouteIndex = -1
            tvStatus.text = "請點擊地圖選擇位置..."
            Toast.makeText(this, "請在地圖上點擊選取位置", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
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

        dialog.show()
        setDialogFullWidth(dialog)
    }

    // ── 歷史紀錄對話框 ───────────────────────────────────────────────────────
    private fun showHistoryDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_history, null)
        val rvLocs    = dialogView.findViewById<RecyclerView>(R.id.rv_saved_locations)
        val rvRoutes  = dialogView.findViewById<RecyclerView>(R.id.rv_saved_routes)
        val tvNoLocs  = dialogView.findViewById<TextView>(R.id.tv_no_locations)
        val tvNoRts   = dialogView.findViewById<TextView>(R.id.tv_no_routes)

        val locAdapter = SavedLocationAdapter(
            mutableListOf(),
            onLoad = { item ->
                setStaticLocation(item.latitude, item.longitude)
                Toast.makeText(this, "已載入：${item.name}", Toast.LENGTH_SHORT).show()
            },
            onDelete = { item ->
                historyManager.deleteLocation(item.id)
                refreshHistoryInView(locAdapter = rvLocs.adapter as SavedLocationAdapter,
                    routeAdapter = rvRoutes.adapter as? SavedRouteAdapter,
                    tvNoLocs = tvNoLocs, tvNoRts = tvNoRts)
            }
        )
        val routeAdapter = SavedRouteAdapter(
            mutableListOf(),
            onLoad = { item ->
                waypoints.clear()
                waypoints.addAll(item.points)
                speedKmh = item.speedKmh
                updateRouteOnMap()
                Toast.makeText(this, "已載入路徑：${item.name}", Toast.LENGTH_SHORT).show()
            },
            onDelete = { item ->
                historyManager.deleteRoute(item.id)
                refreshHistoryInView(locAdapter = rvLocs.adapter as SavedLocationAdapter,
                    routeAdapter = rvRoutes.adapter as? SavedRouteAdapter,
                    tvNoLocs = tvNoLocs, tvNoRts = tvNoRts)
            }
        )

        rvLocs.layoutManager = LinearLayoutManager(this)
        rvLocs.adapter = locAdapter
        rvRoutes.layoutManager = LinearLayoutManager(this)
        rvRoutes.adapter = routeAdapter

        refreshHistoryInView(locAdapter, routeAdapter, tvNoLocs, tvNoRts)

        val scrollView = ScrollView(this).apply {
            addView(dialogView)
        }

        val dialog = AlertDialog.Builder(this, R.style.LightDialog)
            .setTitle("📋  設定位置歷史紀錄")
            .setView(scrollView)
            .setNegativeButton("關閉", null)
            .create()
        dialog.show()
        setDialogFullWidth(dialog)
    }

    private fun refreshHistoryInView(
        locAdapter: SavedLocationAdapter?,
        routeAdapter: SavedRouteAdapter?,
        tvNoLocs: TextView,
        tvNoRts: TextView?
    ) {
        val locs = historyManager.getLocations()
        locAdapter?.refresh(locs)
        tvNoLocs.visibility = if (locs.isEmpty()) View.VISIBLE else View.GONE

        val routes = historyManager.getRoutes()
        routeAdapter?.refresh(routes)
        tvNoRts?.visibility = if (routes.isEmpty()) View.VISIBLE else View.GONE
    }

    // ── 路徑規劃對話框 ───────────────────────────────────────────────────────
    private fun showRouteDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_route, null)
        val rvWpts        = dialogView.findViewById<RecyclerView>(R.id.rv_waypoints)
        val tvNoWpts      = dialogView.findViewById<TextView>(R.id.tv_no_waypoints)
        val btnAdd        = dialogView.findViewById<Button>(R.id.btn_add_waypoint)
        val btnClear      = dialogView.findViewById<Button>(R.id.btn_clear_route)
        val etSpeed       = dialogView.findViewById<EditText>(R.id.et_speed_value)
        val btnMinus      = dialogView.findViewById<Button>(R.id.btn_speed_minus)
        val btnPlus       = dialogView.findViewById<Button>(R.id.btn_speed_plus)
        val btnStart      = dialogView.findViewById<Button>(R.id.btn_start_route)
        val btnSave       = dialogView.findViewById<Button>(R.id.btn_save_route)

        etSpeed.setText(speedKmh.toString())
        tvNoWpts.visibility = if (waypoints.isEmpty()) View.VISIBLE else View.GONE

        val adapter = WaypointAdapter(
            waypoints,
            onDelete = { index ->
                waypoints.removeAt(index)
                (rvWpts.adapter as? WaypointAdapter)?.let {
                    it.notifyItemRemoved(index)
                    it.notifyItemRangeChanged(index, waypoints.size)
                }
                tvNoWpts.visibility = if (waypoints.isEmpty()) View.VISIBLE else View.GONE
                updateRouteOnMap()
            },
            onClick = { index ->
                mapView.controller.animateTo(GeoPoint(waypoints[index].latitude, waypoints[index].longitude))
            }
        )
        rvWpts.layoutManager = LinearLayoutManager(this)
        rvWpts.adapter = adapter

        fun syncSpeed() {
            val v = etSpeed.text.toString().toIntOrNull()
            if (v != null && v in 1..300) speedKmh = v else etSpeed.setText(speedKmh.toString())
        }

        btnMinus.setOnClickListener { syncSpeed(); speedKmh = (speedKmh - 10).coerceAtLeast(5); etSpeed.setText(speedKmh.toString()) }
        btnPlus.setOnClickListener  { syncSpeed(); speedKmh = (speedKmh + 10).coerceAtMost(300); etSpeed.setText(speedKmh.toString()) }

        // 更新開始按鈕文字
        fun refreshStartBtn() {
            btnStart.text = if (mockService?.isRunning == true) "⏹ 停止路徑模擬" else "▶ 開始路徑模擬"
        }
        refreshStartBtn()

        val dialog = AlertDialog.Builder(this, R.style.LightDialog)
            .setTitle("🗺  路徑規劃清單")
            .setView(ScrollView(this).apply { addView(dialogView) })
            .setNegativeButton("關閉", null)
            .create()

        btnAdd.setOnClickListener { showAddWaypointDialogForRoute(adapter, tvNoWpts, dialog) }

        btnClear.setOnClickListener {
            waypoints.clear()
            adapter.notifyDataSetChanged()
            tvNoWpts.visibility = View.VISIBLE
            updateRouteOnMap()
            stopAllSimulation()
            refreshStartBtn()
        }

        btnStart.setOnClickListener {
            syncSpeed()
            if (mockService?.isRunning == true) {
                stopAllSimulation()
            } else {
                if (waypoints.size < 2) {
                    Toast.makeText(this, "至少需要 2 個路徑點", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val intent = Intent(this, MockLocationService::class.java).apply {
                    putExtra(MockLocationService.EXTRA_MODE, MockLocationService.MODE_ROUTE)
                    putExtra(MockLocationService.EXTRA_WAYPOINTS, ArrayList(waypoints))
                    putExtra(MockLocationService.EXTRA_SPEED_KMH, speedKmh.toFloat())
                }
                ContextCompat.startForegroundService(this, intent)
                setSimulationActive(true)
                tvStatus.text = "路徑模擬啟動中..."
                dialog.dismiss()
            }
            refreshStartBtn()
        }

        btnSave.setOnClickListener {
            syncSpeed()
            if (waypoints.size < 2) {
                Toast.makeText(this, "至少需要 2 個路徑點才能儲存", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showSaveNameDialog("儲存路徑") { name ->
                historyManager.saveRoute(name, waypoints.toList(), speedKmh)
                Toast.makeText(this, "已儲存路徑：$name", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
        setDialogFullWidth(dialog)
    }

    private fun showAddWaypointDialogForRoute(
        adapter: WaypointAdapter,
        tvNoWpts: TextView,
        parentDialog: AlertDialog,
        editIndex: Int = -1
    ) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_waypoint, null)
        val etName  = dialogView.findViewById<EditText>(R.id.et_wp_name)
        val etLat   = dialogView.findViewById<EditText>(R.id.et_wp_lat)
        val etLon   = dialogView.findViewById<EditText>(R.id.et_wp_lon)
        val etDwell = dialogView.findViewById<EditText>(R.id.et_wp_dwell)
        val btnPick = dialogView.findViewById<Button>(R.id.btn_pick_from_map)

        if (editIndex >= 0) {
            val pt = waypoints[editIndex]
            etName.setText(pt.name); etLat.setText(pt.latitude.toString())
            etLon.setText(pt.longitude.toString())
            etDwell.setText(if (pt.dwellSeconds > 0) pt.dwellSeconds.toString() else "")
        }

        val d = AlertDialog.Builder(this, R.style.LightDialog)
            .setTitle(if (editIndex >= 0) "編輯路徑點" else "新增路徑點")
            .setView(dialogView)
            .setPositiveButton("確認", null)
            .setNegativeButton("取消", null)
            .create()

        btnPick.setOnClickListener {
            d.dismiss()
            parentDialog.dismiss()
            pickingFromMap = true
            pickingForRouteIndex = if (editIndex >= 0) editIndex else waypoints.size
            tvStatus.text = "請點擊地圖選擇路徑點位置..."
        }

        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val lat = etLat.text.toString().toDoubleOrNull()
            val lon = etLon.text.toString().toDoubleOrNull()
            if (lat == null || lon == null) {
                Toast.makeText(this, "請輸入有效的經緯度", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val pt = RoutePoint(lat, lon, etName.text.toString().trim(), etDwell.text.toString().toIntOrNull() ?: 0)
            if (editIndex >= 0) {
                waypoints[editIndex] = pt; adapter.notifyItemChanged(editIndex)
            } else {
                waypoints.add(pt); adapter.notifyItemInserted(waypoints.size - 1)
            }
            tvNoWpts.visibility = if (waypoints.isEmpty()) View.VISIBLE else View.GONE
            updateRouteOnMap(); d.dismiss()
        }
    }

    // ── 輔助：設定 Dialog 寬度為螢幕的 92% ──────────────────────────────────
    private fun setDialogFullWidth(dialog: AlertDialog) {
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    // ── 模擬控制 ────────────────────────────────────────────────────────────
    private fun setSimulationActive(active: Boolean) {
        btnStopAll.visibility = if (active) View.VISIBLE else View.GONE
        tvSimBadge.visibility = if (active) View.VISIBLE else View.GONE
    }

    private fun stopAllSimulation() {
        mockService?.stopSimulation()
        setSimulationActive(false)
        // 停止後返回裝置實際位置
        requestFreshLocation()
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
        tvMapCoords.text = "%.6f, %.6f".format(lat, lon)

        val intent = Intent(this, MockLocationService::class.java).apply {
            putExtra(MockLocationService.EXTRA_MODE, MockLocationService.MODE_STATIC)
            putExtra(MockLocationService.EXTRA_LAT, lat)
            putExtra(MockLocationService.EXTRA_LON, lon)
        }
        ContextCompat.startForegroundService(this, intent)
        setSimulationActive(true)
        tvStatus.text = "靜態模式：%.6f, %.6f".format(lat, lon)
    }

    // ── 取得「最新」GPS 位置（非快取） ──────────────────────────────────────
    @SuppressLint("MissingPermission")
    private fun requestFreshLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return

        // 移除舊的 listener
        freshLocationListener?.let { locationManager.removeUpdates(it) }
        freshLocationTimeout.removeCallbacksAndMessages(null)

        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        val enabled = providers.filter { locationManager.isProviderEnabled(it) }
        if (enabled.isEmpty()) { tvStatus.text = "請開啟 GPS 或網路定位"; return }

        tvStatus.text = "正在取得目前位置..."
        var received = false

        freshLocationListener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                if (received) return
                received = true
                freshLocationTimeout.removeCallbacksAndMessages(null)
                locationManager.removeUpdates(this)
                runOnUiThread {
                    val gp = GeoPoint(loc.latitude, loc.longitude)
                    mapView.controller.animateTo(gp)
                    mapView.controller.setZoom(16.0)
                    tvMapCoords.text = "%.6f, %.6f".format(loc.latitude, loc.longitude)
                    tvStatus.text = "目前位置：%.6f, %.6f".format(loc.latitude, loc.longitude)
                }
            }
            @Suppress("DEPRECATION")
            override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        }

        try {
            locationManager.requestLocationUpdates(enabled.first(), 0L, 0f, freshLocationListener!!, mainLooper)
        } catch (_: SecurityException) { return }

        // 5 秒後若仍未收到，改用快取值
        freshLocationTimeout.postDelayed({
            if (!received) {
                freshLocationListener?.let { locationManager.removeUpdates(it) }
                // fallback: last known
                for (p in providers) {
                    try {
                        val loc = locationManager.getLastKnownLocation(p) ?: continue
                        val gp = GeoPoint(loc.latitude, loc.longitude)
                        mapView.controller.animateTo(gp)
                        tvMapCoords.text = "%.6f, %.6f".format(loc.latitude, loc.longitude)
                        tvStatus.text = "目前位置（快取）：%.6f, %.6f".format(loc.latitude, loc.longitude)
                        break
                    } catch (_: SecurityException) {}
                }
            }
        }, 5000L)
    }

    // 取得位置並填入 EditText（用於設定位置對話框）
    @SuppressLint("MissingPermission")
    private fun fetchRealLocationIntoFields(etLat: EditText, etLon: EditText) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "需要位置權限", Toast.LENGTH_SHORT).show(); return
        }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        var best: Location? = null
        for (p in providers) {
            if (!locationManager.isProviderEnabled(p)) continue
            try {
                val loc = locationManager.getLastKnownLocation(p)
                if (loc != null && (best == null || loc.accuracy < best.accuracy)) best = loc
            } catch (_: SecurityException) {}
        }
        if (best != null) {
            etLat.setText("%.6f".format(best.latitude))
            etLon.setText("%.6f".format(best.longitude))
            return
        }
        Toast.makeText(this, "正在取得位置...", Toast.LENGTH_SHORT).show()
        val enabled = providers.filter { locationManager.isProviderEnabled(it) }
        if (enabled.isEmpty()) return
        var received = false
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                if (received) return
                received = true
                locationManager.removeUpdates(this)
                runOnUiThread {
                    etLat.setText("%.6f".format(loc.latitude))
                    etLon.setText("%.6f".format(loc.longitude))
                }
            }
            @Suppress("DEPRECATION")
            override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        }
        try { locationManager.requestLocationUpdates(enabled.first(), 0L, 0f, listener, mainLooper) }
        catch (_: SecurityException) {}
    }

    private fun applyRealLocation(loc: Location) {
        val gp = GeoPoint(loc.latitude, loc.longitude)
        mapView.controller.animateTo(gp)
        mapView.controller.setZoom(16.0)
        tvMapCoords.text = "%.6f, %.6f".format(loc.latitude, loc.longitude)
        tvStatus.text = "目前位置：%.6f, %.6f".format(loc.latitude, loc.longitude)
    }

    // ── 地圖點選處理 ────────────────────────────────────────────────────────
    private fun showPickActionDialog(geoPoint: GeoPoint) {
        AlertDialog.Builder(this, R.style.LightDialog)
            .setTitle("%.5f, %.5f".format(geoPoint.latitude, geoPoint.longitude))
            .setItems(arrayOf("📍 設為靜態模擬位置", "➕ 新增為路徑點", "取消")) { _, which ->
                when (which) {
                    0 -> setStaticLocation(geoPoint.latitude, geoPoint.longitude)
                    1 -> {
                        waypoints.add(RoutePoint(geoPoint.latitude, geoPoint.longitude))
                        updateRouteOnMap()
                        Toast.makeText(this, "已新增路徑點", Toast.LENGTH_SHORT).show()
                    }
                }
            }.show()
    }

    private fun onMapPointPicked(geoPoint: GeoPoint) {
        pickingFromMap = false
        val lat = geoPoint.latitude; val lon = geoPoint.longitude
        if (pickingForRouteIndex == -1) {
            setStaticLocation(lat, lon)
        } else {
            val point = RoutePoint(lat, lon)
            if (pickingForRouteIndex < waypoints.size) {
                waypoints[pickingForRouteIndex] = point
            } else {
                waypoints.add(point)
            }
            updateRouteOnMap()
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
        } else mapView.controller.animateTo(geoPoints[0])
    }

    // ── 通用對話框 ───────────────────────────────────────────────────────────
    private fun showSaveNameDialog(hint: String, onConfirm: (String) -> Unit) {
        val et = EditText(this).apply { setPadding(40, 20, 40, 20); this.hint = hint }
        AlertDialog.Builder(this, R.style.LightDialog)
            .setTitle("請輸入名稱")
            .setView(et)
            .setPositiveButton("儲存") { _, _ -> onConfirm(et.text.toString().trim().ifEmpty { hint }) }
            .setNegativeButton("取消", null)
            .show()
    }

    // ── 服務綁定 ────────────────────────────────────────────────────────────
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
                mapView.controller.setCenter(GeoPoint(loc.latitude, loc.longitude))
                mapView.controller.setZoom(15.0)
                tvMapCoords.text = "%.6f, %.6f".format(loc.latitude, loc.longitude)
                tvStatus.text = "目前位置：%.6f, %.6f".format(loc.latitude, loc.longitude)
                return
            } catch (_: SecurityException) {}
        }
        // 若無快取，請求一次新位置
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                locationManager.removeUpdates(this)
                runOnUiThread { applyRealLocation(loc) }
            }
            @Suppress("DEPRECATION")
            override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        }
        try {
            val p = providers.firstOrNull { locationManager.isProviderEnabled(it) } ?: return
            locationManager.requestLocationUpdates(p, 0L, 0f, listener, mainLooper)
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

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onResume()  { super.onResume();  mapView.onResume();  if (!serviceBound) bindMockService() }
    override fun onPause()   { super.onPause();   mapView.onPause() }
    override fun onDestroy() {
        freshLocationListener?.let { locationManager.removeUpdates(it) }
        freshLocationTimeout.removeCallbacksAndMessages(null)
        if (serviceBound) { unbindService(serviceConnection); serviceBound = false }
        mapView.onDetach()
        super.onDestroy()
    }
}
