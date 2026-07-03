package com.superteam.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.superteam.app.di.fakeAppModule
import com.superteam.app.di.networkAppModule
import com.superteam.app.presentation.result.ResultRoot
import com.superteam.app.presentation.upload.UploadRoot
import org.koin.compose.KoinApplication

enum class Screen { UPLOAD, RESULT }

const val USE_FAKE_API = false

@Composable
fun App() {
    KoinApplication(application = {
        modules(if (USE_FAKE_API) fakeAppModule else networkAppModule)
    }) {
        var currentScreen by remember { mutableStateOf(Screen.UPLOAD) }
        var selectedTaskId by remember { mutableStateOf<String?>(null) }

        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                when (currentScreen) {
                    Screen.UPLOAD -> UploadRoot(
                        onNavigateToResult = { taskId ->
                            selectedTaskId = taskId
                            currentScreen = Screen.RESULT
                        }
                    )
                    Screen.RESULT -> ResultRoot(
                        taskId = selectedTaskId ?: "unknown",
                        onNavigateBack = {
                            currentScreen = Screen.UPLOAD
                        }
                    )
                }
            }
        }
    }
}