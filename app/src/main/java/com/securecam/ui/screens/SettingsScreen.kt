package com.securecam.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.securecam.core.ai.HybridAIPipeline
import com.securecam.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt
import javax.inject.Inject
import java.util.UUID

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val aiPipeline: HybridAIPipeline
) : ViewModel() {
    val isLlmEnabled = repository.isLlmEnabled.stateIn(viewModelScope, SharingStarted.Lazily, true)
    var isImporting by mutableStateOf(false)
        private set
    var currentModelName by mutableStateOf("None")

    fun loadPrefs(context: Context) {
        val prefs = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
        currentModelName = prefs.getString("selected_model", "None") ?: "None"
    }

    fun toggleLlm(enabled: Boolean) { viewModelScope.launch { repository.setLlmEnabled(enabled) } }

    fun importModel(uri: Uri, context: Context) {
        isImporting = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var fileName = "custom_model.litertlm"
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) fileName = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME) ?: 0)
                }
                val destFile = File(context.filesDir, fileName)
                context.contentResolver.openInputStream(uri)?.use { input -> destFile.outputStream().use { output -> input.copyTo(output) } }
                context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE).edit().putString("selected_model", fileName).apply()
                currentModelName = fileName
            } catch (e: Exception) {} finally { isImporting = false }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController, viewModel: SettingsViewModel = hiltViewModel()) {
    val llmEnabled by viewModel.isLlmEnabled.collectAsState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val prefs = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
    
    var securityToken by remember { mutableStateOf(prefs.getString("security_token", "") ?: "") }
    if (securityToken.isBlank()) {
        securityToken = UUID.randomUUID().toString().substring(0, 8)
        prefs.edit().putString("security_token", securityToken).apply()
    }
    
    var viewerMode by remember { mutableStateOf(prefs.getString("viewer_mode", "Firebase") ?: "Firebase") }
    var targetIp by remember { mutableStateOf(prefs.getString("target_ip", "") ?: "") }

    var scanInterval by remember { mutableStateOf(prefs.getFloat("scan_interval_sec", 5f)) }
    var confidenceThreshold by remember { mutableStateOf(prefs.getFloat("confidence_threshold", 0.85f)) }
    var debugMode by remember { mutableStateOf(prefs.getBoolean("debug_mode", true)) }
    var popupNotifications by remember { mutableStateOf(prefs.getBoolean("enable_notifications", true)) }
    
    var fbDbUrl by remember { mutableStateOf(prefs.getString("fb_db_url", "") ?: "") }
    var fbApiKey by remember { mutableStateOf(prefs.getString("fb_api_key", "") ?: "") }
    var fbAppId by remember { mutableStateOf(prefs.getString("fb_app_id", "") ?: "") }

    LaunchedEffect(Unit) { viewModel.loadPrefs(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { TextButton(onClick = { navController.popBackStack() }) { Text("Back") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            
            Text("Connection Mode", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                FilterChip(
                    selected = viewerMode == "Firebase",
                    onClick = { viewerMode = "Firebase"; prefs.edit().putString("viewer_mode", "Firebase").apply() },
                    label = { Text("Firebase (Cloud)") }
                )
                FilterChip(
                    selected = viewerMode == "Local WiFi",
                    onClick = { viewerMode = "Local WiFi"; prefs.edit().putString("viewer_mode", "Local WiFi").apply() },
                    label = { Text("Local WiFi (Off-Grid)") }
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = securityToken,
                onValueChange = { securityToken = it; prefs.edit().putString("security_token", it).apply() },
                label = { Text("Master Token (Required for Both Modes)") },
                trailingIcon = { IconButton(onClick = { clipboardManager.setText(AnnotatedString(securityToken)) }) { Text("📋") } },
                modifier = Modifier.fillMaxWidth()
            )
            
            if (viewerMode == "Local WiFi") {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = targetIp,
                    onValueChange = { targetIp = it; prefs.edit().putString("target_ip", it).apply() },
                    label = { Text("Camera IP Address (e.g. 192.168.1.5)") },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(24.dp))
                Text("Firebase Credentials", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = fbDbUrl, onValueChange = { fbDbUrl = it; prefs.edit().putString("fb_db_url", it).apply() }, label = { Text("Database URL") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = fbApiKey, onValueChange = { fbApiKey = it; prefs.edit().putString("fb_api_key", it).apply() }, label = { Text("API Key") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = fbAppId, onValueChange = { fbAppId = it; prefs.edit().putString("fb_app_id", it).apply() }, label = { Text("App ID") }, modifier = Modifier.fillMaxWidth())
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))

            Text("Device Alerts & Settings", style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Popup Notifications", style = MaterialTheme.typography.bodyLarge)
                    Text("Show Android banner when app closed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = popupNotifications, onCheckedChange = { popupNotifications = it; prefs.edit().putBoolean("enable_notifications", it).apply() })
            }
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Enable LLM Security Engine", style = MaterialTheme.typography.bodyLarge)
                }
                Switch(checked = llmEnabled, onCheckedChange = { viewModel.toggleLlm(it) })
            }
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Verbose Debug Mode", style = MaterialTheme.typography.bodyLarge)
                    Text("Show safe scans in UI log", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = debugMode, onCheckedChange = { debugMode = it; prefs.edit().putBoolean("debug_mode", it).apply() })
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))
            
            Text("AI Tuning", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Analyze 1 frame every: ${scanInterval.roundToInt()} seconds", style = MaterialTheme.typography.bodyMedium)
            Slider(value = scanInterval, onValueChange = { scanInterval = it }, onValueChangeFinished = { prefs.edit().putFloat("scan_interval_sec", scanInterval).apply() }, valueRange = 1f..60f, steps = 58)

            Spacer(modifier = Modifier.height(16.dp))
            Text("Alert Confidence Threshold: ${(confidenceThreshold * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium)
            Slider(value = confidenceThreshold, onValueChange = { confidenceThreshold = it }, onValueChangeFinished = { prefs.edit().putFloat("confidence_threshold", confidenceThreshold).apply() }, valueRange = 0.0f..1.0f, steps = 100)

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}