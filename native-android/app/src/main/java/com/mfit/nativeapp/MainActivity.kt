package com.mfit.nativeapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Slider
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mfit.nativeapp.core.activity.ActivityType
import com.mfit.nativeapp.data.ActivityDatabase
import com.mfit.nativeapp.data.ActivitySessionEntity
import com.mfit.nativeapp.data.ActivityTrackPointEntity
import com.mfit.nativeapp.location.ActivityLocationService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mfit.nativeapp.core.activity.SessionStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private enum class MfitTab(val label: String) {
    Health("سلامت"), Sport("فعالیت"), Profile("پروفایل")
}

private enum class UiTrackingState { IDLE, RUNNING, PAUSED, FINISHED }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { MfitApp() } }
    }
}

@Composable
private fun MfitApp() {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(MfitTab.Health) }
    var trackingState by remember { mutableStateOf(UiTrackingState.IDLE) }
    val liveSnapshot by ActivityLocationService.snapshot.collectAsStateWithLifecycle()
    val effectiveTrackingState = when (liveSnapshot?.status) {
        SessionStatus.RUNNING -> UiTrackingState.RUNNING
        SessionStatus.PAUSED -> UiTrackingState.PAUSED
        SessionStatus.FINISHED -> UiTrackingState.FINISHED
        else -> trackingState
    }
    var activityType by remember { mutableStateOf(ActivityType.WALK) }
    var recoveredNotice by remember { mutableStateOf<String?>(null) }
    var historyRefresh by remember { mutableIntStateOf(0) }
    var sessions by remember { mutableStateOf<List<ActivitySessionEntity>>(emptyList()) }
    var selectedSessionId by remember { mutableStateOf<String?>(null) }
    var selectedPoints by remember { mutableStateOf<List<ActivityTrackPointEntity>>(emptyList()) }

    LaunchedEffect(Unit) {
        val dao = ActivityDatabase.get(context).activityDao()
        val unfinished = withContext(Dispatchers.IO) { dao.findUnfinishedSession() }
        if (unfinished != null && ActivityLocationService.isActive) {
            activityType = runCatching { ActivityType.valueOf(unfinished.activityType) }.getOrDefault(ActivityType.WALK)
            trackingState = if (unfinished.status == "PAUSED") UiTrackingState.PAUSED else UiTrackingState.RUNNING
        } else if (unfinished != null) {
            withContext(Dispatchers.IO) { dao.markUnfinishedSessionsInterrupted() }
            recoveredNotice = "یک فعالیت ناتمام پیدا شد؛ مسیرهای ذخیره‌شده حفظ شدند و وضعیت آن «ناتمام» ثبت شد."
            historyRefresh++
        }
    }

    LaunchedEffect(selectedTab, historyRefresh) {
        if (selectedTab == MfitTab.Sport) {
            sessions = withContext(Dispatchers.IO) {
                ActivityDatabase.get(context).activityDao().getSessions()
            }
        }
    }

    LaunchedEffect(selectedSessionId) {
        val id = selectedSessionId ?: return@LaunchedEffect
        selectedPoints = withContext(Dispatchers.IO) {
            ActivityDatabase.get(context).activityDao().getPoints(id)
        }
    }

    fun sendServiceAction(action: String, type: ActivityType? = null) {
        val intent = Intent(context, ActivityLocationService::class.java).setAction(action)
        if (type != null) {
            intent.putExtra(ActivityLocationService.EXTRA_TYPE, type.name)
            intent.putExtra(ActivityLocationService.EXTRA_SESSION_ID, UUID.randomUUID().toString())
            ContextCompat.startForegroundService(context, intent)
        } else {
            context.startService(intent)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val fineGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (fineGranted) {
            sendServiceAction(ActivityLocationService.ACTION_START, activityType)
            trackingState = UiTrackingState.RUNNING
        } else {
            Toast.makeText(context, "برای ثبت دقیق مسیر، دسترسی موقعیت مکانی دقیق لازم است.", Toast.LENGTH_LONG).show()
        }
    }

    fun startTracking() {
        val fineGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (fineGranted) {
            sendServiceAction(ActivityLocationService.ACTION_START, activityType)
            trackingState = UiTrackingState.RUNNING
        } else {
            val permissions = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                MfitTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = {
                            when (tab) {
                                MfitTab.Health -> androidx.compose.material3.Icon(Icons.Default.Favorite, null)
                                MfitTab.Sport -> androidx.compose.material3.Icon(Icons.Default.DirectionsRun, null)
                                MfitTab.Profile -> androidx.compose.material3.Icon(Icons.Default.Person, null)
                            }
                        },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { padding ->
        Surface(
            modifier = Modifier.fillMaxSize().padding(padding),
            color = MaterialTheme.colorScheme.background
        ) {
            when (selectedTab) {
                MfitTab.Health -> PlaceholderScreen("بخش سلامت — در حال آماده‌سازی نسخه Native")
                MfitTab.Profile -> PlaceholderScreen("پروفایل — انتقال اطلاعات در مرحله طراحی")
                MfitTab.Sport -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 20.dp)
                ) {
                    item {
                        Text("ثبت فعالیت", style = MaterialTheme.typography.headlineMedium)
                        recoveredNotice?.let {
                            Text(it, modifier = Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                        }
                        Text("نوع فعالیت: ${liveSnapshot?.type?.let { type -> when (type) { ActivityType.WALK -> "پیاده‌روی"; ActivityType.RUN -> "دویدن"; ActivityType.RIDE -> "دوچرخه" } } ?: activityType.name}", modifier = Modifier.padding(vertical = 8.dp))
                        liveSnapshot?.let { snapshot ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("زمان: ${snapshot.elapsedMillis / 3_600_000L}:%02d:%02d".format((snapshot.elapsedMillis / 60_000L) % 60L, (snapshot.elapsedMillis / 1_000L) % 60L), style = MaterialTheme.typography.titleMedium)
                                    Text("مسافت: %.2f کیلومتر".format(Locale.US, snapshot.distanceMeters / 1000.0))
                                    Text("سرعت فعلی: %.1f km/h  ·  میانگین: %.1f km/h".format(Locale.US, snapshot.currentSpeedMetersPerSecond * 3.6, snapshot.averageSpeedMetersPerSecond * 3.6))
                                    Text("کالری تخمینی: ${snapshot.calories} kcal")
                                    Text("نقاط GPS: ${snapshot.points.size}", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { activityType = ActivityType.WALK }, enabled = effectiveTrackingState == UiTrackingState.IDLE || effectiveTrackingState == UiTrackingState.FINISHED) { Text("پیاده‌روی") }
                            Button(onClick = { activityType = ActivityType.RUN }, enabled = effectiveTrackingState == UiTrackingState.IDLE || effectiveTrackingState == UiTrackingState.FINISHED) { Text("دویدن") }
                            Button(onClick = { activityType = ActivityType.RIDE }, enabled = effectiveTrackingState == UiTrackingState.IDLE || effectiveTrackingState == UiTrackingState.FINISHED) { Text("دوچرخه") }
                        }
                        Text(
                            when (effectiveTrackingState) {
                                UiTrackingState.IDLE -> "آماده شروع"
                                UiTrackingState.RUNNING -> "در حال ثبت مسیر در پس‌زمینه"
                                UiTrackingState.PAUSED -> "فعالیت متوقف شده"
                                UiTrackingState.FINISHED -> "فعالیت پایان یافته"
                            },
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { startTracking() }, enabled = effectiveTrackingState == UiTrackingState.IDLE || effectiveTrackingState == UiTrackingState.FINISHED) { Text("شروع") }
                            Button(
                                onClick = {
                                    val wasRunning = effectiveTrackingState == UiTrackingState.RUNNING
                                    sendServiceAction(if (wasRunning) ActivityLocationService.ACTION_PAUSE else ActivityLocationService.ACTION_RESUME)
                                    trackingState = if (wasRunning) UiTrackingState.PAUSED else UiTrackingState.RUNNING
                                },
                                enabled = effectiveTrackingState == UiTrackingState.RUNNING || effectiveTrackingState == UiTrackingState.PAUSED
                            ) { Text(if (effectiveTrackingState == UiTrackingState.RUNNING) "مکث" else "ادامه") }
                            Button(
                                onClick = {
                                    sendServiceAction(ActivityLocationService.ACTION_FINISH)
                                    trackingState = UiTrackingState.FINISHED
                                    historyRefresh++
                                },
                                enabled = effectiveTrackingState == UiTrackingState.RUNNING || effectiveTrackingState == UiTrackingState.PAUSED
                            ) { Text("پایان") }
                        }
                        Text(
                            "برای ادامه ثبت GPS هنگام خاموش بودن صفحه، اعلان دائمی فعالیت نمایش داده می‌شود.",
                            modifier = Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("تاریخچه فعالیت‌ها", style = MaterialTheme.typography.titleLarge)
                            Button(onClick = { historyRefresh++ }) { Text("بروزرسانی") }
                        }
                    }
                    if (sessions.isEmpty()) {
                        item { Text("هنوز فعالیتی ذخیره نشده است.", style = MaterialTheme.typography.bodyMedium) }
                    }
                    items(sessions, key = { it.id }) { session ->
                        ActivityHistoryCard(
                            session = session,
                            onClick = { selectedSessionId = session.id }
                        )
                    }
                    if (selectedSessionId != null) {
                        item {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("جزئیات مسیر", style = MaterialTheme.typography.titleMedium)
                                    Text("نقاط GPS ذخیره‌شده: ${selectedPoints.size}")
                                    ActivityRouteMap(points = selectedPoints)
                                    Button(onClick = { selectedSessionId = null }) { Text("بستن جزئیات") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityHistoryCard(session: ActivitySessionEntity, onClick: () -> Unit) {
    val date = remember(session.startedAtMillis) {
        SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.getDefault()).format(Date(session.startedAtMillis))
    }
    val type = when (session.activityType) {
        "WALK" -> "پیاده‌روی"
        "RUN" -> "دویدن"
        "RIDE" -> "دوچرخه"
        else -> session.activityType
    }
    val status = when (session.status) {
        "FINISHED" -> "پایان‌یافته"
        "INTERRUPTED" -> "ناتمام (بر اثر بسته‌شدن برنامه)"
        "PAUSED" -> "متوقف‌شده"
        "RUNNING" -> "در حال اجرا"
        else -> session.status
    }
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("$type · $date", style = MaterialTheme.typography.titleSmall)
            Text(status, style = MaterialTheme.typography.bodySmall)
            Text(
                "%.2f km  ·  %d دقیقه  ·  %d kcal".format(
                    Locale.US,
                    session.distanceMeters / 1000.0,
                    session.elapsedMillis / 60_000L,
                    session.calories
                ),
                style = MaterialTheme.typography.bodyMedium
            )
            Text("برای مشاهده جزئیات مسیر لمس کنید", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ActivityRouteMap(points: List<ActivityTrackPointEntity>) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var progress by remember(points.size, points.firstOrNull()?.timestampMillis) {
        mutableFloatStateOf(1f)
    }
    var hasCentered by remember(points.size, points.firstOrNull()?.timestampMillis) {
        mutableStateOf(false)
    }
    val visibleCount = if (points.isEmpty()) 0 else
        (1 + ((points.size - 1) * progress).toInt()).coerceIn(1, points.size)
    val visiblePoints = points.take(visibleCount)

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView?.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView?.onPause()
            mapView?.onDetach()
            mapView = null
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (points.isEmpty()) {
            Text("برای این فعالیت نقطه GPS ذخیره نشده است.")
        } else {
            AndroidView(
                modifier = Modifier.fillMaxWidth().height(260.dp),
                factory = { ctx ->
                    Configuration.getInstance().userAgentValue = ctx.packageName
                    MapView(ctx).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        setBuiltInZoomControls(true)
                        controller.setZoom(15.0)
                        mapView = this
                        onResume()
                    }
                },
                update = { map ->
                    map.overlays.clear()
                    val route = visiblePoints.map { GeoPoint(it.latitude, it.longitude) }
                    if (route.size >= 2) {
                        val line = Polyline(map).apply {
                            setPoints(route)
                            outlinePaint.color = android.graphics.Color.rgb(35, 116, 220)
                            outlinePaint.strokeWidth = 9f
                            outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                            outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                        }
                        map.overlays.add(line)
                    }
                    if (route.isNotEmpty()) {
                        val start = Marker(map).apply {
                            position = route.first()
                            title = "شروع مسیر"
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        }
                        map.overlays.add(start)
                        if (route.size > 1) {
                            val current = Marker(map).apply {
                                position = route.last()
                                title = if (visibleCount == points.size) "پایان مسیر" else "موقعیت در بازپخش"
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            }
                            map.overlays.add(current)
                        }
                        if (!hasCentered) {
                            if (route.size == 1) {
                                map.controller.setZoom(16.0)
                                map.controller.setCenter(route.first())
                            } else {
                                val north = route.maxOf { it.latitude }
                                val south = route.minOf { it.latitude }
                                val east = route.maxOf { it.longitude }
                                val west = route.minOf { it.longitude }
                                map.zoomToBoundingBox(BoundingBox(north, east, south, west), true, 64)
                            }
                            hasCentered = true
                        }
                    }
                    map.invalidate()
                }
            )
            Text("بازپخش مسیر")
            Slider(
                value = progress,
                onValueChange = { progress = it },
                valueRange = 0f..1f,
                enabled = points.size > 1
            )
            Text(
                "$visibleCount از ${points.size} نقطه نمایش داده می‌شود",
                style = MaterialTheme.typography.labelSmall
            )
            Text("نقشه © OpenStreetMap contributors", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun PlaceholderScreen(message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("M FIT", style = MaterialTheme.typography.headlineMedium)
        Text(message, modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyLarge)
    }
}
