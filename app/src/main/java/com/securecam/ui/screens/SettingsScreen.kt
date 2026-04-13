package com.securecam.ui.screens

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.google.gson.Gson
import com.securecam.core.ai.BiometricEngine
import com.securecam.core.ai.HybridAIPipeline
import com.securecam.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val aiPipeline: HybridAIPipeline
) : ViewModel() {
    val isLlmEnabled = repository.isLlmEnabled.stateIn(viewModelScope, SharingStarted.Lazily, true)
    
    fun toggleLlm(enabled: Boolean) { viewModelScope.launch { repository.setLlmEnabled(enabled) } }

    fun processFaceRegistration(uri: Uri, context: Context, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val biometricEngine = BiometricEngine(context)
            biometricEngine.initialize()
            try {
                val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri))
                } else {
                    MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                }
                
                val copyBmp = bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                val embedding = biometricEngine.getFaceEmbedding(copyBmp)
                
                if (embedding != null) {
                    val prefs = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
                    prefs.edit().putString("authorized_face_vector", Gson().toJson(embedding)).apply()
                    withContext(Dispatchers.Main) { 
                        Toast.makeText(context, "Face Registered Successfully!", Toast.LENGTH_LONG).show() 
                        onComplete(true)
                    }
                } else {
                    withContext(Dispatchers.Main) { 
                        Toast.makeText(context, "Could not extract face data.", Toast.LENGTH_LONG).show() 
                        onComplete(false)
                    }
                }
            } catch(e: Exception) {
                withContext(Dispatchers.Main) { onComplete(false) }
            } finally {
                biometricEngine.close()
            }
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
    
    var securityToken by remember { 
        mutableStateOf(
            prefs.getString("security_token", "").takeIf { !it.isNullOrBlank() } ?: UUID.randomUUID().toString().substring(0, 8).also {
                prefs.edit().putString("security_token", it).apply()
            }
        )
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
    
    var hasRegisteredFace by remember { mutableStateOf((prefs.getString("authorized_face_vector", "") ?: "").isNotBlank()) }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> 
        uri?.let { 
            Toast.makeText(context, "Processing Biometrics...", Toast.LENGTH_SHORT).show()
            viewModel.processFaceRegistration(it, context) { success ->
                hasRegisteredFace = success
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { TextButton(onClick = { navController.popBackStack() }) { Text("Back") } }) }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            
            Text("Connection Mode & Endpoint", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewerMode = "Firebase"; prefs.edit().putString("viewer_mode", "Firebase").apply() }, colors = ButtonDefaults.buttonColors(containerColor = if (viewerMode == "Firebase") MaterialTheme.colorScheme.primary else Color.DarkGray), modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) { Text("☁️ Firebase") }
                Button(onClick = { viewerMode = "Local WiFi"; prefs.edit().putString("viewer_mode", "Local WiFi").apply() }, colors = ButtonDefaults.buttonColors(containerColor = if (viewerMode == "Local WiFi") MaterialTheme.colorScheme.primary else Color.DarkGray), modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) { Text("🏠 Local WiFi") }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = targetIp, onValueChange = { targetIp = it; prefs.edit().putString("target_ip", it).apply() }, label = { Text(if (viewerMode == "Local WiFi") "Camera IP Address (e.g. 192.168.1.5)" else "IP Field Disabled (Firebase Active)") }, enabled = viewerMode == "Local WiFi", modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = securityToken, onValueChange = { securityToken = it; prefs.edit().putString("security_token", it).apply() }, label = { Text("Master Token (Required for Both Modes)") }, trailingIcon = { IconButton(onClick = { clipboardManager.setText(AnnotatedString(securityToken)) }) { Text("📋") } }, modifier = Modifier.fillMaxWidth())
            
            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))

            // --- NATIVE BIOMETRIC REGISTRATION ---
            Text("Local Biometric Vault", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Upload a clear portrait of authorized personnel. The AI will extract their facial vector and store it entirely offline. If this face is detected, alarms are disabled.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Spacer(modifier = Modifier.height(12.dp))
            
            Button(
                onClick = { photoPicker.launch("image/*") }, 
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = if (hasRegisteredFace) Color(0xFF388E3C) else MaterialTheme.colorScheme.primary)
            ) { 
                Text(if (hasRegisteredFace) "✅ Authorized Face Registered (Tap to Replace)" else "📸 Upload Face Photo") 
            }
            if (hasRegisteredFace) {
                TextButton(onClick = { 
                    prefs.edit().remove("authorized_face_vector").apply()
                    hasRegisteredFace = false
                }, modifier = Modifier.align(Alignment.End)) {
                    Text("Clear Biometrics", color = Color.Red)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))

            Text("Firebase Credentials", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = fbDbUrl, onValueChange = { fbDbUrl = it; prefs.edit().putString("fb_db_url", it).apply() }, label = { Text("Database URL") }, modifier = Modifier.fillMaxWidth(), enabled = viewerMode == "Firebase")
            OutlinedTextField(value = fbApiKey, onValueChange = { fbApiKey = it; prefs.edit().putString("fb_api_key", it).apply() }, label = { Text("API Key") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), enabled = viewerMode == "Firebase")
            OutlinedTextField(value = fbAppId, onValueChange = { fbAppId = it; prefs.edit().putString("fb_app_id", it).apply() }, label = { Text("App ID") }, modifier = Modifier.fillMaxWidth(), enabled = viewerMode == "Firebase")

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))
            Text("Device Alerts & Settings", style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) { Text("Popup Notifications", style = MaterialTheme.typography.bodyLarge) }
                Switch(checked = popupNotifications, onCheckedChange = { popupNotifications = it; prefs.edit().putBoolean("enable_notifications", it).apply() })
            }
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) { Text("Enable LLM Security Engine", style = MaterialTheme.typography.bodyLarge) }
                Switch(checked = llmEnabled, onCheckedChange = { viewModel.toggleLlm(it) })
            }
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) { Text("Verbose Debug Mode", style = MaterialTheme.typography.bodyLarge) }
                Switch(checked = debugMode, onCheckedChange = { debugMode = it; prefs.edit().putBoolean("debug_mode", it).apply() })
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))
            Text("AI Tuning & Polling", style = MaterialTheme.typography.titleMedium)
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