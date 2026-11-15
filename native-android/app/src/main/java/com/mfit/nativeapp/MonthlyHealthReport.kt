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

private data class MonthlyDay(
    val date: LocalDate,
    val steps: Int,
    val water: Int,
    val foodCalories: Int,
    val sessions: Int,
    val distance: Double
)

@Composable
internal fun MonthlyHealthReportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val today = remember { LocalDate.now() }
    val firstDay = remember { today.withDayOfMonth(1) }
    var rows by remember { mutableStateOf<List<MonthlyDay>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val zone = ZoneId.systemDefault()
        val start = firstDay.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val sessions = withContext(Dispatchers.IO) {
            ActivityDatabase.get(context).activityDao().getSessionsBetween(start, end)
        }
        rows = (0L..today.dayOfMonth.toLong() - 1L).map { offset ->
            val day = firstDay.plusDays(offset)
            val key = day.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val until = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val dailySessions = sessions.filter { it.startedAtMillis >= from && it.startedAtMillis < until && it.status == "FINISHED" }
            MonthlyDay(
                day,
                DailyHealthPreferences.steps(context, key),
                DailyHealthPreferences.water(context, key),
                DailyHealthPreferences.foods(context, key).sumOf { it.calories },
                dailySessions.size,
                dailySessions.sumOf { it.distanceMeters }
            )
        }
        loading = false
    }

    val stepTotal = rows.sumOf { it.steps }
    val waterTotal = rows.sumOf { it.water }
    val foodTotal = rows.sumOf { it.foodCalories }
    val activityTotal = rows.sumOf { it.sessions }
    val distanceTotal = rows.sumOf { it.distance }
    val maxSteps = rows.maxOfOrNull { it.steps }?.coerceAtLeast(1) ?: 1

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("گزارش ماهانه", style = MaterialTheme.typography.headlineMedium)
            Button(onClick = onBack) { Text("بازگشت") }
        }
        if (loading) Text("در حال آماده‌سازی گزارش...")
        else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(today.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())), style = MaterialTheme.typography.titleLarge)
                    Text("مجموع قدم‌ها: $stepTotal")
                    Text("میانگین قدم روزانه: ${if (rows.isEmpty()) 0 else stepTotal / rows.size}")
                    Text("آب ثبت‌شده: $waterTotal لیوان")
                    Text("کالری غذای ثبت‌شده: $foodTotal kcal")
                    Text("فعالیت‌های پایان‌یافته: $activityTotal")
                    Text("مسافت فعالیت‌ها: %.2f km".format(Locale.US, distanceTotal / 1000.0))
                }
            }
            Text("روند روزانه قدم‌ها", style = MaterialTheme.typography.titleLarge)
            rows.forEach { day ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(day.date.format(DateTimeFormatter.ofPattern("MM/dd", Locale.getDefault())))
                        Text("${day.steps} قدم")
                        LinearProgressIndicator(
                            progress = { (day.steps.toFloat() / maxSteps).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("آب ${day.water} لیوان · غذا ${day.foodCalories} kcal · فعالیت ${day.sessions} · %.2f km".format(Locale.US, day.distance / 1000.0))
                    }
                }
            }
            Text("این گزارش فقط داده‌هایی را نشان می‌دهد که در نسخه Native ثبت شده‌اند؛ داده‌های قدیمی PWA تا زمان واردکردن فایل پشتیبان در این جمع‌ها لحاظ نمی‌شوند.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
