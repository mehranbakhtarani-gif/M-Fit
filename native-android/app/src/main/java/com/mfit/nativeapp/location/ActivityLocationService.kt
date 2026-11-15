package com.mfit.nativeapp.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.android.gms.location.*
import com.mfit.nativeapp.MainActivity
import com.mfit.nativeapp.R
import com.mfit.nativeapp.core.activity.*
import com.mfit.nativeapp.data.ActivityDatabase
import com.mfit.nativeapp.data.ActivitySessionEntity
import com.mfit.nativeapp.data.ActivityTrackPointEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.UUID

class ActivityLocationService : Service() {
    private val databaseDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var database: ActivityDatabase
    private var engine: ActivitySessionEngine? = null
    private var sessionId: String? = null
    private var callback: LocationCallback? = null
    private var activityType: ActivityType = ActivityType.WALK
    private var tickerJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        isActive = true
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        database = ActivityDatabase.get(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTracking(intent)
            ACTION_PAUSE -> pauseTracking()
            ACTION_RESUME -> resumeTracking()
            ACTION_FINISH -> finishTracking()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startTracking(intent: Intent) {
        val rawType = intent.getStringExtra(EXTRA_TYPE) ?: ActivityType.WALK.name
        activityType = runCatching { ActivityType.valueOf(rawType) }.getOrDefault(ActivityType.WALK)
        val id = intent.getStringExtra(EXTRA_SESSION_ID) ?: UUID.randomUUID().toString()
        sessionId = id
        engine = ActivitySessionEngine()
        val now = System.currentTimeMillis()
        engine?.start(activityType, now)
        publishSnapshot()
        startSnapshotTicker()

        val notification = buildNotification("در حال ثبت مسیر")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Serialize Room writes and wait for the session row before accepting route points.
        serviceScope.launch(databaseDispatcher) {
            database.activityDao().insertSession(
                ActivitySessionEntity(
                    id = id,
                    activityType = activityType.name,
                    status = SessionStatus.RUNNING.name,
                    startedAtMillis = now
                )
            )
            withContext(Dispatchers.Main) {
                if (engine?.snapshot(System.currentTimeMillis())?.status == SessionStatus.RUNNING) {
                    requestLocationUpdates()
                }
            }
        }
    }

    private fun requestLocationUpdates() {
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 2_000L
        ).setMinUpdateIntervalMillis(1_000L)
            .setMinUpdateDistanceMeters(1f)
            .build()

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val activeEngine = engine ?: return
                val id = sessionId ?: return
                result.locations.forEach { location ->
                    val decision = activeEngine.addLocation(
                        GeoFix(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            accuracyMeters = location.accuracy,
                            timestampMillis = location.time,
                            altitudeMeters = if (location.hasAltitude()) location.altitude else null
                        )
                    )
                    if (decision.accepted) {
                        val point = activeEngine.snapshot(location.time).points.lastOrNull() ?: return@forEach
                        serviceScope.launch(databaseDispatcher) {
                            database.activityDao().insertPoint(
                                ActivityTrackPointEntity(
                                    sessionId = id,
                                    latitude = point.latitude,
                                    longitude = point.longitude,
                                    timestampMillis = point.timestampMillis,
                                    accuracyMeters = point.accuracyMeters,
                                    segmentDistanceMeters = point.segmentDistanceMeters
                                )
                            )
                        }
                    }
                }
                publishSnapshot()
                updateNotification()
            }
        }
        callback = locationCallback
        try {
            fusedClient.requestLocationUpdates(request, locationCallback, mainLooper)
        } catch (_: SecurityException) {
            stopSelf()
        }
    }

    private fun pauseTracking() {
        val active = engine ?: return
        val now = System.currentTimeMillis()
        if (!active.pause(now)) return
        callback?.let { fusedClient.removeLocationUpdates(it) }
        callback = null
        persistSnapshot(SessionStatus.PAUSED, null)
        publishSnapshot()
        updateNotification("فعالیت متوقف شده")
    }

    private fun resumeTracking() {
        val active = engine ?: return
        if (!active.resume(System.currentTimeMillis())) return
        requestLocationUpdates()
        persistSnapshot(SessionStatus.RUNNING, null)
        publishSnapshot()
        updateNotification("در حال ثبت مسیر")
    }

    private fun finishTracking() {
        val active = engine ?: run { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return }
        callback?.let { fusedClient.removeLocationUpdates(it) }
        callback = null
        val now = System.currentTimeMillis()
        val snapshot = active.finish(now) ?: return
        publishSnapshot(snapshot)
        tickerJob?.cancel()
        tickerJob = null
        val id = sessionId
        if (id != null) {
            serviceScope.launch(databaseDispatcher) {
                database.activityDao().updateSession(
                    id, SessionStatus.FINISHED.name, now, snapshot.elapsedMillis,
                    snapshot.distanceMeters, snapshot.calories
                )
                withContext(Dispatchers.Main) {
                    engine = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        } else {
            engine = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun persistSnapshot(status: SessionStatus, endedAt: Long?) {
        val snapshot = engine?.snapshot(System.currentTimeMillis()) ?: return
        val id = sessionId ?: return
        serviceScope.launch(databaseDispatcher) {
            database.activityDao().updateSession(
                id, status.name, endedAt, snapshot.elapsedMillis,
                snapshot.distanceMeters, snapshot.calories
            )
        }
    }

    private fun startSnapshotTicker() {
        tickerJob?.cancel()
        tickerJob = serviceScope.launch {
            while (engine != null) {
                publishSnapshot()
                delay(1_000L)
            }
        }
    }

    private fun publishSnapshot(snapshot: ActivitySnapshot? = engine?.snapshot(System.currentTimeMillis())) {
        _snapshot.value = snapshot
    }

    private fun updateNotification(message: String? = null) {
        val snapshot = engine?.snapshot(System.currentTimeMillis())
        val text = message ?: snapshot?.let {
            String.format(
                java.util.Locale.US, "%.2f km · %d min",
                it.distanceMeters / 1000.0, it.elapsedMillis / 60_000L
            )
        } ?: "در حال ثبت مسیر"
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(content: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("M FIT")
            .setContentText(content)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "ثبت فعالیت", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        callback?.let { fusedClient.removeLocationUpdates(it) }
        callback = null
        isActive = false
        tickerJob?.cancel()
        tickerJob = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private val _snapshot = MutableStateFlow<ActivitySnapshot?>(null)
        val snapshot: StateFlow<ActivitySnapshot?> = _snapshot.asStateFlow()

        @Volatile var isActive: Boolean = false
            private set
        const val ACTION_START = "com.mfit.nativeapp.location.START"
        const val ACTION_PAUSE = "com.mfit.nativeapp.location.PAUSE"
        const val ACTION_RESUME = "com.mfit.nativeapp.location.RESUME"
        const val ACTION_FINISH = "com.mfit.nativeapp.location.FINISH"
        const val EXTRA_TYPE = "activity_type"
        const val EXTRA_SESSION_ID = "session_id"
        private const val CHANNEL_ID = "mfit_activity_tracking"
        private const val NOTIFICATION_ID = 4102
    }
}
