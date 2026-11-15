package com.mfit.nativeapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mfit.nativeapp.data.ActivityDatabase
import com.mfit.nativeapp.data.DailyHealthPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class DailyReport(
    val date: LocalDate,
    val steps: Int,
    val water: Int,
    val foodCalories: Int,
    val activityCount: Int,
    val distanceMeters: Double
)

@Composable
internal fun WeeklyHealthReportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val today = remember { LocalDate.now() }
    var report by remember { mutableStateOf<List<DailyReport>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val days = (6L downTo 0L).map { today.minusDays(it) }
        val zone = ZoneId.systemDefault()
        val start = days.first().atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val sessions = withContext(Dispatchers.IO) {
            ActivityDatabase.get(context).activityDao().getSessionsBetween(start, end)
        }
        report = days.map { day ->
            val key = day.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val daySessions = sessions.filter { it.startedAtMillis >= dayStart && it.startedAtMillis < dayEnd }
            DailyReport(
                date = day,
                steps = DailyHealthPreferences.steps(context, key),
                water = DailyHealthPreferences.water(context, key),
                foodCalories = DailyHealthPreferences.foods(context, key).sumOf { it.calories },
                activityCount = daySessions.count { it.status == "FINISHED" },
                distanceMeters = daySessions.filter { it.status == "FINISHED" }.sumOf { it.distanceMeters }
            )
        }
        loading = false
    }

    val maxSteps = report.maxOfOrNull { it.steps }?.coerceAtLeast(1) ?: 1
    val totalActivities = report.sumOf { it.activityCount }
    val totalDistance = report.sumOf { it.distanceMeters }
    val totalWater = report.sumOf { it.water }
    val averageSteps = if (report.isEmpty()) 0 else report.sumOf { it.steps } / report.size

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("گزارش هفتگی", style = MaterialTheme.typography.headlineMedium)
            Button(onClick = onBack) { Text("بازگشت") }
        }
        if (loading) {
            Text("در حال آماده‌سازی گزارش...")
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("خلاصه ۷ روز اخیر", style = MaterialTheme.typography.titleLarge)
                    Text("میانگین قدم روزانه: $averageSteps")
                    Text("مجموع آب ثبت‌شده: $totalWater لیوان")
                    Text("فعالیت‌های پایان‌یافته: $totalActivities")
                    Text("مسافت ثبت‌شده: %.2f کیلومتر".format(Locale.US, totalDistance / 1000.0))
                }
            }
            Text("روند قدم‌ها", style = MaterialTheme.typography.titleLarge)
            report.forEach { day ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(day.date.format(DateTimeFormatter.ofPattern("EEE  MM/dd", Locale.getDefault())))
                        Text("${day.steps} قدم")
                        LinearProgressIndicator(
                            progress = { (day.steps.toFloat() / maxSteps).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("آب: ${day.water} لیوان · کالری غذای ثبت‌شده: ${day.foodCalories} kcal")
                        Text("فعالیت پایان‌یافته: ${day.activityCount} · مسافت: %.2f km".format(Locale.US, day.distanceMeters / 1000.0))
                    }
                }
            }
            Text(
                "روزهایی که داده‌ای ثبت نشده، صفر نمایش داده می‌شوند. قدم‌های تاریخی از زمان نصب این نسخه به بعد ذخیره می‌شوند؛ اطلاعات گذشته‌ای که قبلاً ذخیره نشده قابل بازیابی نیست.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
