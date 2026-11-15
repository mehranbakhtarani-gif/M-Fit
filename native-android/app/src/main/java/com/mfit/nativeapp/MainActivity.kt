package com.mfit.nativeapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
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
import com.mfit.nativeapp.data.UserProfilePreferences
import com.mfit.nativeapp.data.DailyHealthPreferences
import com.mfit.nativeapp.data.BodyMeasurementPreferences
import com.mfit.nativeapp.data.NativeBackupManager
import com.mfit.nativeapp.data.FastingPreferences
import com.mfit.nativeapp.data.FastingSession
import com.mfit.nativeapp.data.FastingAlarmReceiver
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
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
        setContent { CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { MfitTheme { MfitApp() } } }
    }
}

@Composable
private fun MfitApp() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
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
    var savedWeightKg by remember { mutableStateOf(UserProfilePreferences.getWeightKg(context)) }
    var profileName by remember { mutableStateOf(UserProfilePreferences.getProfileName(context)) }
    var weightHistory by remember { mutableStateOf(BodyMeasurementPreferences.history(context)) }
    var weightInput by remember { mutableStateOf(savedWeightKg?.let { formatWeight(it) } ?: "") }
    val backupExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) coroutineScope.launch {
            runCatching {
                val json = withContext(Dispatchers.IO) { NativeBackupManager.export(context) }
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(json) } ?: error("امکان ذخیره فایل وجود ندارد.") }
            }.onSuccess { Toast.makeText(context, "فایل پشتیبان ذخیره شد.", Toast.LENGTH_LONG).show() }
             .onFailure { Toast.makeText(context, "خطا در پشتیبان‌گیری: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    val backupImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) coroutineScope.launch {
            runCatching {
                val json = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("فایل خوانده نشد.") }
                val message = withContext(Dispatchers.IO) { NativeBackupManager.import(context, json) }
                savedWeightKg = UserProfilePreferences.getWeightKg(context)
                weightInput = savedWeightKg?.let { formatWeight(it) } ?: ""
                weightHistory = BodyMeasurementPreferences.history(context)
                historyRefresh++
                message
            }.onSuccess { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
             .onFailure { Toast.makeText(context, "بازیابی انجام نشد: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

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

    LaunchedEffect(liveSnapshot?.status) {
        if (liveSnapshot?.status == SessionStatus.FINISHED) {
            delay(500L)
            historyRefresh++
        }
    }

    LaunchedEffect(selectedTab, historyRefresh) {
        if (selectedTab == MfitTab.Sport || selectedTab == MfitTab.Profile) {
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
        containerColor = Color.Transparent,
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("M", fontSize = 17.sp, fontWeight = FontWeight.Black, color = MfitPalette.ink, letterSpacing = 2.sp)
                        Text(" FIT", fontSize = 17.sp, fontWeight = FontWeight.Black, color = MfitPalette.green, letterSpacing = 2.sp)
                    }
                    Text(SimpleDateFormat("EEEE، d MMMM", Locale("fa")).format(Date()), fontSize = 10.sp, color = MfitPalette.muted)
                }
                Surface(
                    modifier = Modifier.size(40.dp).clickable { selectedTab = MfitTab.Profile },
                    shape = RoundedCornerShape(50),
                    color = Color(0xFFDDF5EB),
                    shadowElevation = 5.dp,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("M", color = MfitPalette.green, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                    }
                }
            }
        },
        bottomBar = {
            Box(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp, top = 5.dp), contentAlignment = Alignment.Center) {
                Surface(
                    modifier = Modifier.width(330.dp).height(68.dp),
                    shape = RoundedCornerShape(50),
                    color = Color(0xDFFFFFFF),
                    shadowElevation = 14.dp,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White)
                ) {
                    Row(modifier = Modifier.fillMaxSize().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        MfitTab.entries.forEach { tab ->
                            val selected = selectedTab == tab
                            Column(
                                modifier = Modifier.weight(1f).fillMaxSize()
                                    .background(
                                        if (selected) Brush.linearGradient(listOf(Color(0xBFFFFFFF), Color(0x88D8F1E8))) else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)),
                                        RoundedCornerShape(50)
                                    )
                                    .clickable { selectedTab = tab }
                                    .padding(vertical = 5.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                when (tab) {
                                    MfitTab.Health -> androidx.compose.material3.Icon(Icons.Default.Favorite, null, tint = if (selected) MfitPalette.ink else MfitPalette.muted)
                                    MfitTab.Sport -> androidx.compose.material3.Icon(Icons.Default.DirectionsRun, null, tint = if (selected) MfitPalette.ink else MfitPalette.muted)
                                    MfitTab.Profile -> androidx.compose.material3.Icon(Icons.Default.Person, null, tint = if (selected) MfitPalette.ink else MfitPalette.muted)
                                }
                                Text(tab.label, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = if (selected) MfitPalette.ink else MfitPalette.muted)
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Surface(
            modifier = Modifier.fillMaxSize().padding(padding).background(MfitPalette.pageBrush),
            color = Color.Transparent
        ) {
            when (selectedTab) {
                MfitTab.Health -> HealthDashboardScreen()
                MfitTab.Profile -> ProfileScreen(
                    profileName = profileName,
                    activitySessions = sessions,
                    onSaveProfileName = { name ->
                        if (UserProfilePreferences.saveProfileName(context, name)) { profileName = name.trim().take(28); true } else false
                    },
                    onSaveMeasurements = { weight, height, waist ->
                        runCatching {
                            weightHistory = BodyMeasurementPreferences.addMeasurement(context, weight, height, waist)
                            if (weight != null) {
                                UserProfilePreferences.saveWeightKg(context, weight)
                                savedWeightKg = weight
                                weightInput = formatWeight(weight)
                            }
                            true
                        }.getOrDefault(false)
                    },
                    weightInput = weightInput,
                    savedWeightKg = savedWeightKg,
                    weightHistory = weightHistory,
                    onExportBackup = { backupExportLauncher.launch("mfit-backup-${System.currentTimeMillis()}.json") },
                    onImportBackup = { backupImportLauncher.launch(arrayOf("application/json", "text/json", "application/octet-stream")) },
                    onWeightInputChange = { weightInput = it },
                    onSaveWeight = {
                        val parsed = weightInput.trim().replace(',', '.').toDoubleOrNull()
                        if (parsed == null || !UserProfilePreferences.saveWeightKg(context, parsed)) {
                            Toast.makeText(context, "وزن باید عددی بین ۲۰ تا ۲۵۰ کیلوگرم باشد.", Toast.LENGTH_LONG).show()
                        } else {
                            savedWeightKg = parsed
                            weightHistory = BodyMeasurementPreferences.add(context, parsed)
                            weightInput = formatWeight(parsed)
                            Toast.makeText(context, "وزن در پروفایل ذخیره شد.", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
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
                                    Text(if (snapshot.calories > 0) "کالری تخمینی: ${snapshot.calories} kcal" else "کالری این فعالیت در دسترس نیست؛ وزن را پیش از شروع فعالیت در پروفایل ثبت کنید.")
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




private fun formatFastingDuration(millis: Long): String {
    val totalSeconds = millis / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}

@Composable
private fun HealthDashboardScreen() {
    val context = LocalContext.current
    val sensorManager = remember { context.getSystemService(android.content.Context.SENSOR_SERVICE) as SensorManager }
    val stepSensor = remember { sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) }
    var steps by remember { mutableIntStateOf(DailyHealthPreferences.steps(context)) }
    var water by remember { mutableIntStateOf(DailyHealthPreferences.water(context)) }
    var foods by remember { mutableStateOf(DailyHealthPreferences.foods(context)) }
    var fastingSession by remember { mutableStateOf(FastingPreferences.active(context)) }
    var fastingHours by remember { mutableStateOf("16") }
    var fastingMinutes by remember { mutableStateOf("00") }
    var clockNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(fastingSession?.startedAtMillis, fastingSession?.targetEndAtMillis) {
        while (fastingSession != null) {
            clockNow = System.currentTimeMillis()
            delay(1000L)
        }
    }
    var showWeeklyReport by remember { mutableStateOf(false) }
    var showMonthlyReport by remember { mutableStateOf(false) }
    var foodName by remember { mutableStateOf("") }
    var foodCalories by remember { mutableStateOf("") }
    var hasNotificationPermission by remember {
        mutableStateOf(android.os.Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasNotificationPermission = granted
    }
    var hasActivityPermission by remember {
        mutableStateOf(
            android.os.Build.VERSION.SDK_INT < 29 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasActivityPermission = granted
    }

    DisposableEffect(stepSensor, hasActivityPermission) {
        if (stepSensor != null && hasActivityPermission) {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (event.values.isNotEmpty()) {
                        steps = DailyHealthPreferences.recordStepCounter(context, event.values[0].toLong())
                    }
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            sensorManager.registerListener(listener, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)
            onDispose { sensorManager.unregisterListener(listener) }
        } else {
            onDispose { }
        }
    }

    if (showWeeklyReport) {
        WeeklyHealthReportScreen(onBack = { showWeeklyReport = false })
        return
    }
    if (showMonthlyReport) {
        MonthlyHealthReportScreen(onBack = { showMonthlyReport = false })
        return
    }

    val eatenCalories = foods.sumOf { it.calories }
    val stepProgress = (steps / 7000f).coerceIn(0f, 1f)
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("سلام، روزت پرانرژی", style = MaterialTheme.typography.headlineMedium, color = MfitPalette.ink)
        Text("قدم‌های کوچک، تغییرهای بزرگ.", style = MaterialTheme.typography.bodyMedium, color = MfitPalette.muted)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(30.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MfitPalette.glass),
            elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(86.dp)) {
                    CircularProgressIndicator(
                        progress = { stepProgress },
                        modifier = Modifier.fillMaxSize(),
                        strokeWidth = 8.dp,
                        color = MfitPalette.green,
                        trackColor = Color(0xFFDCEEE8)
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${(stepProgress * 100).toInt()}%", fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, color = MfitPalette.ink)
                        Text("امروز", fontSize = 9.sp, color = MfitPalette.muted)
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("پیشرفت روزانه", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MfitPalette.ink)
                    Text("$steps قدم از ۷۰۰۰ قدم هدف", fontSize = 11.sp, color = MfitPalette.muted)
                    Text(if (steps >= 7000) "عالیه! به هدفت رسیدی." else "هر قدم تو را به هدفت نزدیک‌تر می‌کند.", fontSize = 10.sp, color = MfitPalette.green)
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { showWeeklyReport = true }, modifier = Modifier.weight(1f)) { Text("گزارش هفتگی") }
            Button(onClick = { showMonthlyReport = true }, modifier = Modifier.weight(1f)) { Text("گزارش ماهانه") }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(25.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MfitPalette.glass),
            elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 5.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("قدم‌های امروز", style = MaterialTheme.typography.titleLarge, color = MfitPalette.ink)
                Text("$steps قدم", style = MaterialTheme.typography.headlineMedium)
                androidx.compose.material3.LinearProgressIndicator(progress = { stepProgress }, modifier = Modifier.fillMaxWidth())
                Text(
                    if (steps >= 7000) "به هدف ۷۰۰۰ قدم رسیدی."
                    else "تا هدف روزانه ${(7000 - steps).coerceAtLeast(0)} قدم باقی مانده.",
                    style = MaterialTheme.typography.bodySmall
                )
                if (!hasActivityPermission) {
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION) }) {
                        Text("فعال‌سازی شمارش قدم")
                    }
                } else if (stepSensor == null) {
                    Text("حسگر شمارش قدم در این دستگاه در دسترس نیست.", style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = {
                    DailyHealthPreferences.resetSteps(context)
                    steps = 0
                }) { Text("صفر کردن قدم‌های امروز") }
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(25.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MfitPalette.glass),
            elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 5.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("آب مصرفی", style = MaterialTheme.typography.titleLarge, color = MfitPalette.ink)
                Text("$water از ۸ لیوان", style = MaterialTheme.typography.headlineSmall)
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { (water / 8f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { water = DailyHealthPreferences.addWater(context, 1) }) { Text("+ یک لیوان") }
                    Button(onClick = { water = DailyHealthPreferences.addWater(context, -1) }, enabled = water > 0) { Text("−") }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(25.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MfitPalette.glass),
            elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 5.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("تایمر روزه‌داری", style = MaterialTheme.typography.titleLarge, color = MfitPalette.ink)
                if (fastingSession == null) {
                    Text("مدت روزه‌ات را انتخاب کن تا زمان‌سنج شروع شود.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("ساعت", style = MaterialTheme.typography.labelSmall, color = MfitPalette.muted)
                            TextField(value = fastingHours, onValueChange = { fastingHours = it.filter(Char::isDigit).take(2) }, singleLine = true, label = { Text("۰ تا ۴۸") }, shape = RoundedCornerShape(14.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text("دقیقه", style = MaterialTheme.typography.labelSmall, color = MfitPalette.muted)
                            TextField(value = fastingMinutes, onValueChange = { fastingMinutes = it.filter(Char::isDigit).take(2) }, singleLine = true, label = { Text("۰ تا ۵۹") }, shape = RoundedCornerShape(14.dp))
                        }
                    }
                    Button(onClick = {
                        val h = fastingHours.toIntOrNull() ?: -1
                        val m = fastingMinutes.toIntOrNull() ?: -1
                        val total = h * 60 + m
                        if (h in 0..48 && m in 0..59 && total in 1..2880) {
                            fastingSession = FastingPreferences.startForMinutes(context, total)
                            fastingSession?.let { FastingAlarmReceiver.schedule(context, it) }
                        } else {
                            Toast.makeText(context, "مدت روزه باید بین ۱ دقیقه تا ۴۸ ساعت باشد.", Toast.LENGTH_LONG).show()
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("شروع مدت انتخاب‌شده") }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(14, 16, 18).forEach { hours ->
                            Button(onClick = {
                                fastingHours = hours.toString()
                                fastingMinutes = "00"
                                fastingSession = FastingPreferences.start(context, hours)
                                fastingSession?.let { FastingAlarmReceiver.schedule(context, it) }
                            }, modifier = Modifier.weight(1f)) { Text("$hours ساعت") }
                        }
                    }
                    if (!hasNotificationPermission && android.os.Build.VERSION.SDK_INT >= 33) {
                        Button(onClick = { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                            Text("فعال‌سازی اعلان پایان روزه")
                        }
                    }
                } else {
                    val remaining = (fastingSession!!.targetEndAtMillis - clockNow).coerceAtLeast(0L)
                    val elapsed = (clockNow - fastingSession!!.startedAtMillis).coerceAtLeast(0L)
                    Text(
                        if (remaining == 0L) "زمان هدف روزه‌داری به پایان رسیده است."
                        else "زمان باقی‌مانده: ${formatFastingDuration(remaining)}",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text("زمان سپری‌شده: ${formatFastingDuration(elapsed)}", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = {
                        FastingAlarmReceiver.cancel(context)
                        FastingPreferences.stop(context)
                        fastingSession = null
                    }) { Text("پایان روزه‌داری") }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(25.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MfitPalette.glass),
            elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 5.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("کالری دریافتی", style = MaterialTheme.typography.titleLarge, color = MfitPalette.ink)
                Text("$eatenCalories kcal", style = MaterialTheme.typography.headlineSmall)
                TextField(value = foodName, onValueChange = { foodName = it }, modifier = Modifier.fillMaxWidth(), label = { Text("نام غذا") }, singleLine = true)
                TextField(value = foodCalories, onValueChange = { foodCalories = it.filter(Char::isDigit).take(5) }, modifier = Modifier.fillMaxWidth(), label = { Text("کالری (kcal)") }, singleLine = true)
                Button(
                    onClick = {
                        val parsed = foodCalories.toIntOrNull()
                        if (parsed != null && foodName.isNotBlank()) {
                            foods = DailyHealthPreferences.addFood(context, foodName, parsed)
                            foodName = ""
                            foodCalories = ""
                        }
                    },
                    enabled = foodName.isNotBlank() && (foodCalories.toIntOrNull() ?: 0) in 0..10000
                ) { Text("ثبت خوراکی") }
                if (foods.isEmpty()) {
                    Text("هنوز خوراکی ثبت نشده است.", style = MaterialTheme.typography.bodySmall)
                } else {
                    foods.forEach { item ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("${item.name} · ${item.calories} kcal", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { foods = DailyHealthPreferences.removeFood(context, item.id) }) { Text("حذف") }
                        }
                    }
                }
            }
        }
        Text("اطلاعات روزانه روی همین دستگاه ذخیره می‌شوند.", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ProfileScreen(
    profileName: String,
    activitySessions: List<ActivitySessionEntity>,
    onSaveProfileName: (String) -> Boolean,
    onSaveMeasurements: (Double?, Double?, Double?) -> Boolean,
    weightInput: String,
    savedWeightKg: Double?,
    weightHistory: List<com.mfit.nativeapp.data.BodyMeasurement>,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onWeightInputChange: (String) -> Unit,
    onSaveWeight: () -> Unit
) {
    val profileContext = LocalContext.current
    var heightInput by remember { mutableStateOf(BodyMeasurementPreferences.latestHeightCm(profileContext)?.let { formatWeight(it) } ?: "") }
    var waistInput by remember { mutableStateOf(BodyMeasurementPreferences.latestWaistCm(profileContext)?.let { formatWeight(it) } ?: "") }
    var nameInput by remember(profileName) { mutableStateOf(profileName) }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(30.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MfitPalette.glass),
            elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().background(
                    Brush.linearGradient(listOf(Color(0xDFFFFFFF), Color(0x99D8F4E9), Color(0x88D8EFF8)))
                ).padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(66.dp),
                    shape = RoundedCornerShape(23.dp),
                    color = Color(0xDFFFFFFF),
                    shadowElevation = 4.dp,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("M", fontSize = 26.sp, fontWeight = FontWeight.Black, color = MfitPalette.green)
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (profileName.isBlank()) "پروفایل من" else profileName, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = MfitPalette.ink)
                    Text("اطلاعات بدن و پیشرفت سلامتی", fontSize = 11.sp, color = MfitPalette.muted)
                    Text(savedWeightKg?.let { "وزن فعلی: ${formatWeight(it)} کیلوگرم" } ?: "هنوز وزنی ثبت نکرده‌ای", fontSize = 10.sp, color = MfitPalette.green)
                }
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(23.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MfitPalette.glass)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("نام پروفایل", style = MaterialTheme.typography.titleMedium, color = MfitPalette.ink)
                TextField(value = nameInput, onValueChange = { nameInput = it.take(28) }, modifier = Modifier.fillMaxWidth(), label = { Text("نام نمایشی") }, singleLine = true, shape = RoundedCornerShape(15.dp))
                Button(onClick = {
                    if (onSaveProfileName(nameInput)) Toast.makeText(profileContext, "نام پروفایل ذخیره شد.", Toast.LENGTH_SHORT).show()
                    else Toast.makeText(profileContext, "یک نام معتبر وارد کن.", Toast.LENGTH_SHORT).show()
                }, enabled = nameInput.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("ذخیره نام") }
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(23.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MfitPalette.glass)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("پشتیبان‌گیری و انتقال اطلاعات", style = MaterialTheme.typography.titleMedium, color = MfitPalette.ink)
                Text("از اطلاعاتت نسخه پشتیبان بگیر یا نسخه قبلی را بازیابی کن.", style = MaterialTheme.typography.bodySmall, color = MfitPalette.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onExportBackup, modifier = Modifier.weight(1f)) { Text("خروجی اطلاعات") }
                    androidx.compose.material3.OutlinedButton(onClick = onImportBackup, modifier = Modifier.weight(1f)) { Text("بازیابی اطلاعات") }
                }
                Text("بازیابی به‌صورت ادغام انجام می‌شود؛ اطلاعات موجود بازنویسی یا حذف نمی‌شوند.", style = MaterialTheme.typography.bodySmall, color = MfitPalette.muted)
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(23.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MfitPalette.glass)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("اندازه‌گیری بدن", style = MaterialTheme.typography.titleMedium, color = MfitPalette.ink)
                Text("قد، وزن و دور کمر را ثبت کن تا روند تغییرات بدنت را دنبال کنی.", style = MaterialTheme.typography.bodySmall, color = MfitPalette.muted)
                TextField(
                    value = weightInput,
                    onValueChange = onWeightInputChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("وزن (کیلوگرم)") },
                    singleLine = true,
                    shape = RoundedCornerShape(15.dp)
                )
                TextField(value = heightInput, onValueChange = { heightInput = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }.take(6) }, modifier = Modifier.fillMaxWidth(), label = { Text("قد (سانتی‌متر)") }, singleLine = true, shape = RoundedCornerShape(15.dp))
                TextField(value = waistInput, onValueChange = { waistInput = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }.take(6) }, modifier = Modifier.fillMaxWidth(), label = { Text("دور کمر (سانتی‌متر)") }, singleLine = true, shape = RoundedCornerShape(15.dp))
                Button(onClick = {
                    val weight = weightInput.trim().replace(',', '.').toDoubleOrNull()
                    val height = heightInput.trim().replace(',', '.').toDoubleOrNull()
                    val waist = waistInput.trim().replace(',', '.').toDoubleOrNull()
                    if (onSaveMeasurements(weight, height, waist)) {
                        Toast.makeText(profileContext, "اندازه‌گیری‌ها ذخیره شد.", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(profileContext, "مقادیر واردشده معتبر نیستند.", Toast.LENGTH_LONG).show()
                    }
                }, enabled = weightInput.isNotBlank() || heightInput.isNotBlank() || waistInput.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text("ذخیره اندازه‌گیری‌ها")
                }
                Text(
                    savedWeightKg?.let { "وزن ذخیره‌شده: ${formatWeight(it)} کیلوگرم" }
                        ?: "هنوز وزنی ثبت نشده است؛ کالری فعالیت‌ها تا ثبت وزن محاسبه نمی‌شود.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MfitPalette.muted
                )
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(23.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MfitPalette.glass)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("خلاصه فعالیت‌ها", style = MaterialTheme.typography.titleMedium, color = MfitPalette.ink)
                val totalDistance = activitySessions.sumOf { it.distanceMeters }
                val totalCalories = activitySessions.sumOf { it.calories }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("تعداد فعالیت‌ها", color = MfitPalette.muted)
                    Text(activitySessions.size.toString(), color = MfitPalette.ink, fontWeight = FontWeight.Bold)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("مسافت کل", color = MfitPalette.muted)
                    Text(String.format(Locale.US, "%.1f km", totalDistance / 1000.0), color = MfitPalette.ink, fontWeight = FontWeight.Bold)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("کالری ثبت‌شده", color = MfitPalette.muted)
                    Text("$totalCalories kcal", color = MfitPalette.ink, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (weightHistory.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(23.dp),
                colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MfitPalette.glass)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("روند تغییرات وزن", style = MaterialTheme.typography.titleMedium, color = MfitPalette.ink)
                    val weighted = weightHistory.filter { it.weightKg != null }
                    val minWeight = weighted.minOfOrNull { it.weightKg!! }?.toFloat() ?: 0f
                    val maxWeight = weighted.maxOfOrNull { it.weightKg!! }?.toFloat() ?: 0f
                    weightHistory.takeLast(12).forEach { measurement ->
                        val ratio = if (measurement.weightKg == null || maxWeight <= minWeight) 1f else
                            ((measurement.weightKg.toFloat() - minWeight) / (maxWeight - minWeight)).coerceIn(0.05f, 1f)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(java.text.SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date(measurement.recordedAtMillis)), style = MaterialTheme.typography.labelSmall, color = MfitPalette.muted)
                            Text(listOfNotNull(measurement.weightKg?.let { "${formatWeight(it)} kg" }, measurement.heightCm?.let { "${formatWeight(it)} cm قد" }, measurement.waistCm?.let { "${formatWeight(it)} cm کمر" }).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MfitPalette.ink)
                        }
                        if (measurement.weightKg != null) androidx.compose.material3.LinearProgressIndicator(
                            progress = { ratio }, modifier = Modifier.fillMaxWidth(), color = MfitPalette.green, trackColor = Color(0xFFDCEEE8)
                        )
                    }
                }
            }
        }
        Text(
            "وزن جدید از فعالیت بعدی در محاسبه کالری استفاده می‌شود. اطلاعات پروفایل فقط روی همین دستگاه ذخیره می‌شود.",
            style = MaterialTheme.typography.labelSmall,
            color = MfitPalette.muted,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

private fun formatWeight(weightKg: Double): String =
    if (weightKg % 1.0 == 0.0) weightKg.toInt().toString()
    else String.format(Locale.US, "%.1f", weightKg)

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
                "%.2f km  ·  %d دقیقه  ·  %s".format(
                    Locale.US,
                    session.distanceMeters / 1000.0,
                    session.elapsedMillis / 60_000L,
                    if (session.calories > 0) "${session.calories} kcal" else "کالری ثبت نشده"
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
