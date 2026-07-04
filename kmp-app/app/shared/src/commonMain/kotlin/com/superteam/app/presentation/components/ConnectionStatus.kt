package com.superteam.app.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.superteam.app.data.NetworkHealthRepository
import org.koin.compose.koinInject

@Composable
fun ConnectionStatusBanner(healthRepo: NetworkHealthRepository = koinInject()) {
    val isKtorConnected by healthRepo.isKtorConnected.collectAsState()
    val isPythonConnected by healthRepo.isPythonConnected.collectAsState()

    Row(
        modifier = Modifier.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
       ) {
        StatusIndicator(label = "Ktor Gateway", isConnected = isKtorConnected)
        Spacer(modifier = Modifier.width(16.dp))
        StatusIndicator(label = "Python ML", isConnected = isPythonConnected)
    }
}

@Composable
private fun StatusIndicator(label: String, isConnected: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(
                    color = if (isConnected) Color.Green else Color.Red,
                    shape = CircleShape
                           )
           )
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = label, style = MaterialTheme.typography.bodySmall)
    }
}