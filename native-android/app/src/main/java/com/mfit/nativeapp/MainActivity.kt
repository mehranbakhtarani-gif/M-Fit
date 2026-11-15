package com.mfit.nativeapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private enum class MfitTab(val label: String) {
    Health("سلامت"),
    Sport("فعالیت"),
    Profile("پروفایل")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MfitApp()
            }
        }
    }
}

@Composable
private fun MfitApp() {
    var selectedTab by remember { mutableStateOf(MfitTab.Health) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                MfitTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = {
                            when (tab) {
                                MfitTab.Health -> androidx.compose.material3.Icon(
                                    Icons.Default.Favorite, contentDescription = null
                                )
                                MfitTab.Sport -> androidx.compose.material3.Icon(
                                    Icons.Default.DirectionsRun, contentDescription = null
                                )
                                MfitTab.Profile -> androidx.compose.material3.Icon(
                                    Icons.Default.Person, contentDescription = null
                                )
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
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("M FIT", style = MaterialTheme.typography.headlineMedium)
                Text(
                    when (selectedTab) {
                        MfitTab.Health -> "بخش سلامت — در حال آماده‌سازی نسخه Native"
                        MfitTab.Sport -> "بخش فعالیت — موتور GPS در مرحله طراحی"
                        MfitTab.Profile -> "پروفایل — انتقال اطلاعات در مرحله طراحی"
                    },
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}
