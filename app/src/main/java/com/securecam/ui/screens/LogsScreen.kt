package com.securecam.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.securecam.data.local.LogDao
import com.securecam.data.local.SecurityLogEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(navController: NavController) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
    val appRole = prefs.getString("app_role", "Camera") ?: "Camera"
    val targetIp = prefs.getString("target_ip", "") ?: ""
    val token = prefs.getString("security_token", "") ?: ""
    
    var logs by remember { mutableStateOf<List<SecurityLogEntity>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedVideoUrl by remember { mutableStateOf<String?>(null) }
    var selectedTabIndex by remember { mutableStateOf(0) }
    val tabs = listOf("All", "Alerts", "Faces", "Normal")
    val coroutineScope = rememberCoroutineScope()

    fun fetchLogs() {
        isLoading = true
        coroutineScope.launch(Dispatchers.IO) {
            try {
                if (appRole == "Viewer" && targetIp.isNotBlank()) {
                    val url = URL("http://$targetIp:8082/api/logs?token=$token")
                    val connection = url.openConnection() as HttpURLConnection
                    connection.connectTimeout = 5000
                    if (connection.responseCode == 200) {
                        val json = connection.inputStream.bufferedReader().readText()
                        val type = object : TypeToken<List<SecurityLogEntity>>() {}.type
                        val remoteLogs: List<SecurityLogEntity> = Gson().fromJson(json, type)
                        withContext(Dispatchers.Main) { logs = remoteLogs.sortedByDescending { it.logTime } }
                    }
                } else {
                    withContext(Dispatchers.Main) { logs = emptyList() }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { logs = emptyList() }
            } finally {
                withContext(Dispatchers.Main) { isLoading = false }
            }
        }
    }

    fun deleteLog(id: Int) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                if (appRole == "Viewer" && targetIp.isNotBlank()) {
                    val url = URL("http://$targetIp:8082/api/logs?token=$token&id=$id")
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "DELETE"
                    connection.responseCode
                    fetchLogs()
                }
            } catch (e: Exception) {}
        }
    }

    LaunchedEffect(Unit) { fetchLogs() }

    val filteredLogs = logs.filter { log ->
        when (selectedTabIndex) {
            1 -> !log.description.contains("Safe", ignoreCase = true) && log.type == "LLM_INSIGHT"
            2 -> log.type == "BIOMETRIC"
            3 -> log.description.contains("Safe", ignoreCase = true)
            else -> true
        }
    }

    if (selectedVideoUrl != null) {
        AlertDialog(
            onDismissRequest = { selectedVideoUrl = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier.fillMaxSize().padding(16.dp),
            text = {
                val exoPlayer = remember { 
                    ExoPlayer.Builder(context).build().apply { 
                        setMediaItem(MediaItem.fromUri(selectedVideoUrl!!))
                        prepare()
                        playWhenReady = true 
                    }
                }
                DisposableEffect(Unit) { onDispose { exoPlayer.release() } }
                AndroidView(factory = { PlayerView(context).apply { player = exoPlayer } }, modifier = Modifier.fillMaxSize())
            },
            confirmButton = { TextButton(onClick = { selectedVideoUrl = null }) { Text("Close Video", color = Color.Red) } }
        )
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Security Vault") }, navigationIcon = { TextButton(onClick = { navController.popBackStack() }) { Text("Back") } }, actions = { IconButton(onClick = { fetchLogs() }) { Text("🔄") } }) }) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = selectedTabIndex) {
                tabs.forEachIndexed { index, title -> Tab(selected = selectedTabIndex == index, onClick = { selectedTabIndex = index }, text = { Text(title) }) }
            }
            if (isLoading) { 
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } 
            } else if (filteredLogs.isEmpty()) { 
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No logs found.", color = Color.Gray) } 
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    items(filteredLogs) { log ->
                        val isSafe = log.description.contains("Safe", ignoreCase = true)
                        val bgColor = if (isSafe) Color(0x224CAF50) else Color(0x22F44336)
                        val timeStr = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault()).format(Date(log.logTime))
                        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), colors = CardDefaults.cardColors(containerColor = bgColor)) {
                            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(timeStr, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(log.description, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                }
                                if (log.videoPath != null) {
                                    IconButton(onClick = { if (appRole == "Viewer") selectedVideoUrl = "http://$targetIp:8082/api/video?file=${log.videoPath}&token=$token" }) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                                    }
                                }
                                IconButton(onClick = { deleteLog(log.id) }) { Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red) }
                            }
                        }
                    }
                }
            }
        }
    }
}