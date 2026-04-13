package com.securecam.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.google.gson.reflect.TypeToken
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.tasks.await
import com.securecam.core.ai.BiometricEngine
import com.securecam.core.ai.HybridAIPipeline
import com.securecam.core.ai.RegisteredFace
import com.securecam.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val aiPipeline: HybridAIPipeline
) : ViewModel() {
    val isLlmEnabled = repository.isLlmEnabled.stateIn(viewModelScope, SharingStarted.Lazily, true)
    val isFaceRecogEnabled = repository.isFaceRecogEnabled.stateIn(viewModelScope, SharingStarted.Lazily, true)
    
    var isImporting by mutableStateOf(false)
        private set
    var currentModelName by mutableStateOf("None")

    private val _registeredFaces = MutableStateFlow<List<RegisteredFace>>(emptyList())
    val registeredFaces = _registeredFaces.asStateFlow()

    fun loadPrefs(context: Context) {
        val prefs = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
        currentModelName = prefs.getString("selected_model", "None") ?: "None"
        
        val json = prefs.getString("authorized_faces", "[]") ?: "[]"
        val type = object : TypeToken<List<RegisteredFace>>() {}.type
        _registeredFaces.value = Gson().fromJson(json, type) ?: emptyList()
    }

    fun toggleLlm(enabled: Boolean) { viewModelScope.launch { repository.setLlmEnabled(enabled) } }
    fun toggleFaceRecog(enabled: Boolean) { viewModelScope.launch { repository.setFaceRecogEnabled(enabled) } }

    fun removeFace(context: Context, id: String) {
        val updated = _registeredFaces.value.filter { it.id != id }
        _registeredFaces.value = updated
        val prefs = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("authorized_faces", Gson().toJson(updated)).apply()
    }

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
                withContext(Dispatchers.Main) { Toast.makeText(context, "Model $fileName Loaded!", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(context, "Import Failed: ${e.message}", Toast.LENGTH_LONG).show() }
            } finally { isImporting = false }
        }
    }

    fun processFaceRegistration(uri: Uri, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { Toast.makeText(context, "Scanning for face...", Toast.LENGTH_SHORT).show() }
            
            try {
                var bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, _, _ ->
                        decoder.isMutableRequired = true
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                } else {
                    MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                }

                // Scale down to avoid OOM
                if (bmp.width > 1500 || bmp.height > 1500) {
                    val scale = 1500f / maxOf(bmp.width, bmp.height)
                    bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
                }

                val inputImage = InputImage.fromBitmap(bmp, 0)
                val options = FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build()
                val detector = FaceDetection.getClient(options)
                val facesList = detector.process(inputImage).await()

                if (facesList.isEmpty()) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "No face found. Try a clearer photo.", Toast.LENGTH_LONG).show() }
                    return@launch
                }

                val bounds = facesList.first().boundingBox
                val size = maxOf(bounds.width(), bounds.height())
                var left = bounds.centerX() - size / 2
                var top = bounds.centerY() - size / 2
                
                left = left.coerceAtLeast(0)
                top = top.coerceAtLeast(0)
                val w = minOf(size, bmp.width - left)
                val h = minOf(size, bmp.height - top)

                val croppedBmp = Bitmap.createBitmap(bmp, left, top, w, h)

                val biometricEngine = BiometricEngine(context)
                biometricEngine.initialize()
                val embedding = biometricEngine.getFaceEmbedding(croppedBmp)
                biometricEngine.close()

                if (embedding != null) {
                    val newFace = RegisteredFace(UUID.randomUUID().toString(), "Face ${registeredFaces.value.size + 1}", embedding)
                    val updatedList = registeredFaces.value + newFace
                    
                    val prefs = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
                    prefs.edit().putString("authorized_faces", Gson().toJson(updatedList)).apply()
                    _registeredFaces.value = updatedList
                    
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Face added successfully!", Toast.LENGTH_SHORT).show() }
                } else {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Could not extract features.", Toast.LENGTH_SHORT).show() }
                }
            } catch(e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController, viewModel: SettingsViewModel = hiltViewModel()) {
    val llmEnabled by viewModel.isLlmEnabled.collectAsState()
    val faceRecogEnabled by viewModel.isFaceRecogEnabled.collectAsState()
    val faces by viewModel.registeredFaces.collectAsState()
    
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
    var aiBackend by remember { mutableStateOf(prefs.getString("ai_backend", "CPU") ?: "CPU") }
    var fbDbUrl by remember { mutableStateOf(prefs.getString("fb_db_url", "") ?: "") }
    var fbApiKey by remember { mutableStateOf(prefs.getString("fb_api_key", "") ?: "") }
    var fbAppId by remember { mutableStateOf(prefs.getString("fb_app_id", "") ?: "") }
    var sysPrompt by remember { mutableStateOf(prefs.getString("prompt_sys", "You are a security camera AI assistant. Provide brief, factual security observations.") ?: "") }
    var usrPrompt by remember { mutableStateOf(prefs.getString("prompt_usr", "Describe what you see in this camera frame from a security perspective.") ?: "") }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> 
        uri?.let { viewModel.processFaceRegistration(it, context) }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> 
        uri?.let { viewModel.importModel(it, context) } 
    }
    
    LaunchedEffect(Unit) { viewModel.loadPrefs(context) }

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
            if (viewerMode == "Local WiFi") {
                Text("Camera & Viewer must be on the same network. Install Tailscale VPN on both devices to access the camera securely from anywhere in the world. This keeps your stream direct and private without needing third-party cloud servers.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Spacer(modifier = Modifier.height(8.dp))
            }
            OutlinedTextField(value = targetIp, onValueChange = { targetIp = it; prefs.edit().putString("target_ip", it).apply() }, label = { Text(if (viewerMode == "Local WiFi") "Camera IP Address (e.g. 192.168.1.5 or Tailscale IP)" else "IP Field Disabled (Firebase Active)") }, enabled = viewerMode == "Local WiFi", modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = securityToken, onValueChange = { securityToken = it; prefs.edit().putString("security_token", it).apply() }, label = { Text("Master Token (Must match EXACTLY in Viewer App to connect)") }, trailingIcon = { IconButton(onClick = { clipboardManager.setText(AnnotatedString(securityToken)) }) { Text("📋") } }, modifier = Modifier.fillMaxWidth())
            
            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))

            Text("Local Biometric Vault", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Upload any photo containing your face. The AI will automatically detect the face, crop it, and store its vector offline.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Spacer(modifier = Modifier.height(12.dp))
            
            Button(
                onClick = { photoPicker.launch("image/*") }, 
                modifier = Modifier.fillMaxWidth()
            ) { 
                Text("📸 Upload Face Photo") 
            }
            
            if (faces.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                faces.forEach { face ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).background(Color(0xFF2C2C2C), RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(face.name, modifier = Modifier.weight(1f), color = Color.White)
                        IconButton(onClick = { viewModel.removeFace(context, face.id) }) {
                            Text("🗑️", color = Color.Red)
                        }
                    }
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
                Column(modifier = Modifier.weight(1f)) { 
                    Text("Enable LLM Security Engine", style = MaterialTheme.typography.bodyLarge) 
                    Text("Current model: ${viewModel.currentModelName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = llmEnabled, onCheckedChange = { viewModel.toggleLlm(it) })
            }
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) { 
                    Text("Enable Face Recognition", style = MaterialTheme.typography.bodyLarge) 
                    Text("Load biometric engine to bypass alerts for known faces", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = faceRecogEnabled, onCheckedChange = { viewModel.toggleFaceRecog(it) })
            }
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) { Text("Verbose Debug Mode", style = MaterialTheme.typography.bodyLarge) }
                Switch(checked = debugMode, onCheckedChange = { debugMode = it; prefs.edit().putBoolean("debug_mode", it).apply() })
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            Text("Hardware Acceleration", style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("CPU", "GPU", "NPU").forEach { backend ->
                    Button(onClick = { aiBackend = backend; prefs.edit().putString("ai_backend", backend).apply() }, colors = ButtonDefaults.buttonColors(containerColor = if (aiBackend == backend) MaterialTheme.colorScheme.primary else Color.DarkGray), modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) { Text(backend) }
                }
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

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))
            Text("Custom AI Prompts", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = sysPrompt, onValueChange = { sysPrompt = it; prefs.edit().putString("prompt_sys", it).apply() }, label = { Text("System Prompt") }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = usrPrompt, onValueChange = { usrPrompt = it; prefs.edit().putString("prompt_usr", it).apply() }, label = { Text("User Prompt") }, modifier = Modifier.fillMaxWidth())

            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = { filePicker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth(), enabled = !viewModel.isImporting) { Text(if (viewModel.isImporting) "Loading Model..." else "Select New Model") }
            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}