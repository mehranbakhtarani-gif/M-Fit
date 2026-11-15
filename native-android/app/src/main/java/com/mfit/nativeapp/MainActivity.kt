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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
                        Text("نوع فعالیت: ${activityType.name}", modifier = Modifier.padding(vertical = 8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { activityType = ActivityType.WALK }, enabled = trackingState == UiTrackingState.IDLE || trackingState == UiTrackingState.FINISHED) { Text("پیاده‌روی") }
                            Button(onClick = { activityType = ActivityType.RUN }, enabled = trackingState == UiTrackingState.IDLE || trackingState == UiTrackingState.FINISHED) { Text("دویدن") }
                            Button(onClick = { activityType = ActivityType.RIDE }, enabled = trackingState == UiTrackingState.IDLE || trackingState == UiTrackingState.FINISHED) { Text("دوچرخه") }
                        }
                        Text(
                            when (trackingState) {
                                UiTrackingState.IDLE -> "آماده شروع"
                                UiTrackingState.RUNNING -> "در حال ثبت مسیر در پس‌زمینه"
                                UiTrackingState.PAUSED -> "فعالیت متوقف شده"
                                UiTrackingState.FINISHED -> "فعالیت پایان یافته"
                            },
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { startTracking() }, enabled = trackingState == UiTrackingState.IDLE || trackingState == UiTrackingState.FINISHED) { Text("شروع") }
                            Button(
                                onClick = {
                                    val wasRunning = trackingState == UiTrackingState.RUNNING
                                    sendServiceAction(if (wasRunning) ActivityLocationService.ACTION_PAUSE else ActivityLocationService.ACTION_RESUME)
                                    trackingState = if (wasRunning) UiTrackingState.PAUSED else UiTrackingState.RUNNING
                                },
                                enabled = trackingState == UiTrackingState.RUNNING || trackingState == UiTrackingState.PAUSED
                            ) { Text(if (trackingState == UiTrackingState.RUNNING) "مکث" else "ادامه") }
                            Button(
                                onClick = {
                                    sendServiceAction(ActivityLocationService.ACTION_FINISH)
                                    trackingState = UiTrackingState.FINISHED
                                    historyRefresh++
                                },
                                enabled = trackingState == UiTrackingState.RUNNING || trackingState == UiTrackingState.PAUSED
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
                                    selectedPoints.firstOrNull()?.let { first ->
                                        Text("شروع مسیر: %.5f, %.5f".format(Locale.US, first.latitude, first.longitude))
                                    }
                                    selectedPoints.lastOrNull()?.let { last ->
                                        Text("آخرین نقطه: %.5f, %.5f".format(Locale.US, last.latitude, last.longitude))
                                    }
                                    Text("نقشه و بازپخش تصویری مسیر در مرحله بعد اضافه می‌شود.", style = MaterialTheme.typography.bodySmall)
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
