package com.dji.droneparking


import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import com.dji.droneparking.util.FlightPlanner
import com.dji.droneparking.util.Tools.showToast
import com.dji.droneparking.util.WaypointMissionManager
import com.mapbox.mapboxsdk.camera.CameraPosition
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.maps.MapView
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.OnMapReadyCallback
import com.mapbox.mapboxsdk.style.layers.PropertyFactory
import com.mapbox.services.android.telemetry.permissions.PermissionsListener
import com.mapbox.services.android.telemetry.permissions.PermissionsManager
import com.riis.cattlecounter.util.distanceToSegment
import dji.common.mission.waypoint.WaypointMission
import dji.common.model.LocationCoordinate2D
import dji.sdk.base.BaseProduct
import dji.sdk.media.MediaManager
import dji.sdk.products.Aircraft
import dji.ux.widget.TakeOffWidget
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.*

class FlightPlanActivity : FragmentActivity(), PermissionsListener, OnMapReadyCallback {

    private lateinit var mContext: Context
    private lateinit var flightPlanLine: Line
    private lateinit var polygonCoords: MutableList<LatLng>
    private lateinit var flightPlan: List<LatLng>
    private lateinit var manager: WaypointMissionManager
    private val droneInstance: BaseProduct? = null
    private lateinit var downloader: DJIImageDownloader

    //    private Button clearFlightPath;
    private lateinit var aircraft: Aircraft
    private var cameraDidUpdate = false
    private lateinit var stitchBtn: Button
    private lateinit var btnCancelFlight: Button
    private lateinit var overlayView: LinearLayout
    private lateinit var layoutConfirmPlan: LinearLayout
    private lateinit var layoutCancelPlan: LinearLayout
    private lateinit var confirmFlightButton: Button
    private lateinit var takeoffWidget: TakeOffWidget
    private val mediaManager: MediaManager? = null
    private lateinit var permissionsManager: PermissionsManager
    private lateinit var mapboxMap: MapboxMap
    private lateinit var mapView: MapView
    private lateinit var symbolManager: SymbolManager
    private var fillManager: FillManager? = null
    private val symbols: MutableList<Symbol?> = ArrayList<Symbol?>()
    private val flightPathSymbols: MutableList<Symbol> = ArrayList<Symbol>()
    private var symbol: Symbol? = null
    private var droneIconSymbol: Symbol? = null
    private var fill: Fill? = null
    private val flightPlan2D: MutableList<LocationCoordinate2D> = ArrayList()
    private var lineManager: LineManager? = null
    private var line: Line? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mContext = getApplicationContext()
        Mapbox.getInstance(mContext, getString(R.string.mapbox_api_key))
        setContentView(R.layout.activity_flight_plan_googlemaps)
        confirmFlightButton = findViewById<Button>(R.id.start_flight_button)

//        clearFlightPath = findViewById(R.id.btn_clear_flight_path);
//        clearFlightPath.setVisibility(View.GONE);
//        clearFlightPath.setOnClickListener(v -> clearMapViews());
        mapView = findViewById(R.id.mapView)
        mapView!!.onCreate(savedInstanceState)
        mapView!!.getMapAsync(this)
        downloader = DJIImageDownloader(
            this,
            (getApplication() as MApplication).getSdkManager(), null
        )
        takeoffWidget = findViewById(R.id.takeoff_widget_flight_plan)
        val builder = AlertDialog.Builder(this)
        val dialog: AlertDialog = builder
            .setMessage(R.string.ensure_clear_sd)
            .setTitle(R.string.title_clear_sd)
            .setNegativeButton(R.string.no, null)
            .setPositiveButton(R.string.yes) { dialogInterface, i -> downloader.cleanDrone() }
            .create()
        dialog.show()
        initStitchBtn()
        initConfirmLayout()
        initCloseOverlayButton()
        polygonCoords = ArrayList()
        EventBus.getDefault().register(this)
    }

    protected override fun onStop() {
        super.onStop()
        EventBus.getDefault().unregister(this)
    }

    override fun onMapReady(mapboxMap: MapboxMap) {
        mapboxMap.setStyle(Style.SATELLITE_STREETS, OnStyleLoadedListener { style: String ->
            enableLocationComponent(style)
            var bm: Bitmap? =
                Tools.bitmapFromVectorDrawable(mContext, R.drawable.ic_waypoint_marker_unvisited)
            mapboxMap.getStyle().addImage("ic_waypoint_marker_unvisited", bm)
            bm = Tools.bitmapFromVectorDrawable(mContext, R.drawable.ic_drone)
            mapboxMap.getStyle().addImage("ic_drone", bm)
            fillManager = FillManager(mapView, mapboxMap, style)
            lineManager = LineManager(mapView, mapboxMap, style)
            symbolManager = SymbolManager(mapView, mapboxMap, style)
            symbolManager.setIconAllowOverlap(true)
        })
        this@FlightPlanActivity.mapboxMap = mapboxMap
        val position = CameraPosition.Builder()
            .zoom(18.0)
            .build()
        mapboxMap.animateCamera(CameraUpdateFactory.newCameraPosition(position))
        mapboxMap.addOnMapClickListener(MapboxMap.OnMapClickListener { point: LatLng ->
            symbol = symbolManager.create(
                SymbolOptions()
                    .withLatLng(LatLng(point))
                    .withIconImage("ic_waypoint_marker_unvisited")
                    .withIconHaloWidth(2.0f)
                    .withDraggable(true)
            )
            symbols.add(symbol)
            //            clearFlightPath.setVisibility(View.VISIBLE);
            //Add new point to the list
            var minDistanceIndex = -1
            var next_index: Int
            confirmFlightButton!!.visibility = View.GONE
            if (polygonCoords!!.size < 3) {
                polygonCoords!!.add(point)
                updatePoly()
            } else {
                var minDistance = Double.MAX_VALUE
                for (i in polygonCoords!!.indices) {
                    next_index = if (i == polygonCoords!!.size - 1) {
                        0
                    } else {
                        i + 1
                    }
                    val distance: Double =
                        distanceToSegment(polygonCoords!![i], polygonCoords!![next_index], point)
                    if (distance < minDistance) {
                        minDistance = distance
                        minDistanceIndex = i + 1
                    }
                }
                polygonCoords!!.add(minDistanceIndex, point)
                updatePoly()
                try {
                    drawFlightPlan()
                } catch (ignored: Exception) {
                }
            }
            layoutConfirmPlan.setVisibility(View.VISIBLE)
            true
        })
    }

    private fun drawFlightPlan() {
        if (polygonCoords!!.size < 3) return
        confirmFlightButton!!.visibility = View.VISIBLE
        var minLat = Int.MAX_VALUE.toDouble()
        var minLon = Int.MAX_VALUE.toDouble()
        var maxLat = Int.MIN_VALUE.toDouble()
        var maxLon = Int.MIN_VALUE.toDouble()
        for (c in polygonCoords!!) {
            if (c.latitude < minLat) minLat = c.latitude
            if (c.latitude > maxLat) maxLat = c.latitude
            if (c.longitude < minLon) minLon = c.longitude
            if (c.longitude > maxLon) maxLon = c.longitude
        }
        val newPoints: MutableList<LatLng> = ArrayList()
        newPoints.add(LatLng(minLat, minLon))
        newPoints.add(LatLng(minLat, maxLon))
        newPoints.add(LatLng(maxLat, maxLon))
        newPoints.add(LatLng(maxLat, minLon))
        try {
            flightPlan = FlightPlanner.createFlightPlan(newPoints, 95.0f, polygonCoords)
            for (coord in flightPlan!!) {
                flightPlan2D.add(LocationCoordinate2D(coord.latitude, coord.longitude))
            }
            if (flightPlanLine != null) {
                lineManager.delete(flightPlanLine)
                symbolManager.delete(flightPathSymbols)
            }
            for (point in flightPlan!!) {
                val flightPathSymbol: Symbol = symbolManager.create(
                    SymbolOptions()
                        .withLatLng(LatLng(point))
                        .withIconImage("ic_waypoint_marker_unvisited")
                        .withIconSize(0.5f)
                )
                flightPathSymbols.add(flightPathSymbol)
            }
            val lineOptions: LineOptions = LineOptions()
                .withLatLngs(flightPlan)
                .withLineWidth(2.0f)
                .withLineColor(PropertyFactory.fillColor(Color.parseColor("#FFFFFF")).value)
            flightPlanLine = lineManager.create(lineOptions)
        } catch (e: FlightPlanner.NotEnoughPointsException) {
            showToast(
                this@FlightPlanActivity,
                "Could not create flight plan! Try a larger area."
            )
            clearMapViews()
            showOriginalControls()
            e.printStackTrace()
        }
    }

    private fun enableLocationComponent(loadedMapStyle: Style) {
        // Check if permissions are enabled and if not request
        if (PermissionsManager.areLocationPermissionsGranted(mContext)) {

            // Get an instance of the component
            val locationComponent: LocationComponent = mapboxMap.getLocationComponent()

            // Activate with options
            locationComponent.activateLocationComponent(
                LocationComponentActivationOptions.builder(mContext, loadedMapStyle).build()
            )

            // Enable to make component visible
            locationComponent.setLocationComponentEnabled(true)

            // Set the component's camera mode
            locationComponent.setCameraMode(CameraMode.TRACKING)

            // Set the component's render mode
            locationComponent.setRenderMode(RenderMode.COMPASS)
        } else {
            permissionsManager = PermissionsManager(mContext as PermissionsListener?)
            permissionsManager.requestLocationPermissions(mContext as Activity?)
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun getDroneLocation(droneLocation: DroneLocationEvent) {
        if (droneIconSymbol != null) {
            symbolManager.delete(droneIconSymbol)
        }
        if (!cameraDidUpdate) {
            val position = CameraPosition.Builder() // Sets the new camera position
                .target(LatLng(droneLocation.Latitude, droneLocation.Longitude))
                .zoom(17.0) // Sets the zoom
                .build() // Creates a CameraPosition from the builder
            runOnUiThread(Runnable {
                mapboxMap.animateCamera(
                    CameraUpdateFactory
                        .newCameraPosition(position), 2000
                )
            })
            cameraDidUpdate = true
        }
        droneIconSymbol = symbolManager.create(
            SymbolOptions()
                .withLatLng(LatLng(droneLocation.Latitude, droneLocation.Longitude))
                .withIconImage("ic_drone")
                .withDraggable(false)
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        permissionsManager.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onExplanationNeeded(permissionsToExplain: List<String?>?) {
        Toast.makeText(this, "Need your location", Toast.LENGTH_LONG).show()
    }

    override fun onPermissionResult(granted: Boolean) {
        if (granted) {
            mapboxMap.getStyle { loadedMapStyle: Style -> enableLocationComponent(loadedMapStyle) }
        } else {
            Toast.makeText(this, "Location permission not granted", Toast.LENGTH_LONG)
                .show()
            finish()
        }
    }

    // Redraw polygons fill after having changed the container values.
    private fun updatePoly() {
        val polygonCoordsCopy: MutableList<LatLng> = ArrayList(polygonCoords)
        polygonCoordsCopy.add(polygonCoords!![0])
        if (line != null) lineManager.delete(line)
        val lineOpts: LineOptions = LineOptions()
            .withLineColor(PropertyFactory.lineColor("#FFFFFF").value)
            .withLatLngs(polygonCoordsCopy)
        line = lineManager.create(lineOpts)
        val fillCoords: MutableList<List<LatLng>> = ArrayList()
        fillCoords.add(polygonCoordsCopy)
        if (fill != null) fillManager.delete(fill)
        val fillOpts: FillOptions = FillOptions()
            .withFillColor(PropertyFactory.fillColor(Color.parseColor("#81FFFFFF")).value)
            .withLatLngs(fillCoords)
        fill = fillManager.create(fillOpts)
    }

    private fun initConfirmLayout() {
        layoutConfirmPlan = findViewById(R.id.ll_confirm_flight_plan)
        layoutCancelPlan = findViewById(R.id.ll_cancel_flight_plan)
        val btnConfirmFlight: Button = findViewById(R.id.start_flight_button)
        val btnCancelFlightPlan: Button = findViewById(R.id.cancel_flight_plan_button)
        btnCancelFlight = findViewById(R.id.cancel_flight_button)
        aircraft =
            (application as MApplication).getSdkManager().getAircraftInstance()
        btnConfirmFlight.setOnClickListener { view: View? ->
            if (aircraft == null) {
                Toast.makeText(mContext, "Please connect to a drone", Toast.LENGTH_SHORT).show()
            } else if (flightPlan != null) {
                val djiMission: WaypointMission =
                    FlightPlanner.createFlightMissionFromCoordinates(flightPlan2D)
                val operator: WaypointMissionOperator =
                    DJISDKManager.getInstance().getMissionControl().getWaypointMissionOperator()
                manager = WaypointMissionManager(
                    djiMission,
                    operator,
                    findViewById(R.id.label_flight_plan),
                    this@FlightPlanActivity
                )
                manager.startMission()
                symbolManager.delete(droneIconSymbol)
                layoutConfirmPlan.setVisibility(View.GONE)
                layoutCancelPlan.setVisibility(View.VISIBLE)
            } else {
                checkWaypointMissionOperator()
            }
        }
        btnCancelFlightPlan.setOnClickListener { view: View? ->
            layoutConfirmPlan.setVisibility(View.GONE)
            clearMapViews()
        }
        btnCancelFlight!!.setOnClickListener { view: View? ->
            if (manager == null) return@setOnClickListener
            manager.stopFlight()
            clearMapViews()
            layoutCancelPlan.setVisibility(View.GONE)
        }
    }

    fun checkWaypointMissionOperator() {
        if (DJISDKManager.getInstance().getMissionControl() == null) {
            Toast.makeText(this, "Check GPS", Toast.LENGTH_SHORT).show()
        }
    }

    fun animateNextButton() {
        runOnUiThread(Runnable {
            stitchBtn!!.animate()
                .alpha(1.0f)
                .translationX(0f)
                .setListener(null)
                .start()
            takeoffWidget.setVisibility(View.VISIBLE)
            btnCancelFlight!!.visibility = View.INVISIBLE
            clearMapViews()
        })
    }

    private fun showOriginalControls() {
        layoutCancelPlan.setVisibility(View.GONE)
        layoutConfirmPlan.setVisibility(View.GONE)
    }

    private fun clearMapViews() {
        if (symbols.size > 0) {
            symbolManager.delete(symbols)
        }
        if (flightPathSymbols.size > 0) {
            symbolManager.delete(flightPathSymbols)
        }
        if (flightPlanLine != null) {
            lineManager.delete(flightPlanLine)
        }
        fillManager.delete(fill)
        lineManager.delete(line)
        symbols.clear()
        polygonCoords!!.clear()
        flightPlan2D.clear()
        //        clearFlightPath.setVisibility(View.GONE);
    }

    private val isNetworkConnected: Boolean
        private get() {
            val cm: ConnectivityManager =
                getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            return cm.getActiveNetworkInfo() != null && cm.getActiveNetworkInfo().isConnected()
        }

    private fun initStitchBtn() {
        stitchBtn = findViewById<Button>(R.id.btn_stitch)
        stitchBtn!!.setOnClickListener { view: View ->
            if (isNetworkConnected) {
                val intent = Intent(view.context, ImageProgressActivity::class.java)
                view.context.startActivity(intent)
            } else {
                try {
                    AlertDialog.Builder(this@FlightPlanActivity)
                        .setMessage("No internet connection, please try again once connected to internet")
                        .setPositiveButton("OK", null)
                        .show()
                } catch (e: Exception) {
                    Toast.makeText(this@FlightPlanActivity, e.message, Toast.LENGTH_LONG).show()
                }
                //             Toast.makeText(FlightPlanActivity.this, "No internet connection", Toast.LENGTH_SHORT).show();
            }
        }
        stitchBtn!!.translationX = 999f
    }

    private fun initCloseOverlayButton() {
        overlayView = findViewById<LinearLayout>(R.id.map_help_overlay)
        val btnCloseOverlay: Button = findViewById<Button>(R.id.confirm_overlay_btn)
        btnCloseOverlay.setOnClickListener { view: View? ->
            overlayView.animate()
                .alpha(0.0f)
                .translationXBy(1000f)
                .setDuration(500)
                .setListener(null)
                .start()
        }
    }
}