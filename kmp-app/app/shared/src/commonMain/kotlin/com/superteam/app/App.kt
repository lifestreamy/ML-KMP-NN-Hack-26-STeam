package com.superteam.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.superteam.app.di.fakeAppModule
import com.superteam.app.di.networkAppModule
import com.superteam.app.presentation.components.ConnectionStatusBanner
import com.superteam.app.presentation.result.ResultRoot
import com.superteam.app.presentation.upload.UploadRoot
import org.koin.compose.KoinApplication

enum class Screen { UPLOAD, RESULT }
const val USE_FAKE_API = false

@Composable
fun App() {
    KoinApplication(application = { modules(if (USE_FAKE_API) fakeAppModule else networkAppModule) }) {
        var currentScreen by remember { mutableStateOf(Screen.UPLOAD) }
        var selectedTaskId by remember { mutableStateOf<String?>(null) }

        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxSize()) {
                    when (currentScreen) {
                        Screen.UPLOAD -> UploadRoot(onNavigateToResult = { selectedTaskId = it; currentScreen = Screen.RESULT })
                        Screen.RESULT -> ResultRoot(taskId = selectedTaskId ?: "unknown", onNavigateBack = { currentScreen = Screen.UPLOAD })
                    }

                    if (!USE_FAKE_API) {
                        Box(modifier = Modifier.align(Alignment.TopEnd)) {
                            ConnectionStatusBanner()
                        }
                    }
                }
            }
        }
    }
}