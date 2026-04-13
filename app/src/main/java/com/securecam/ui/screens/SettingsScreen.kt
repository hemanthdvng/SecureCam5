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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
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
import com.securecam.core.ai.RegisteredFace
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.PrintWriter
import java.net.Socket
import kotlin.math.roundToInt
import java.util.UUID
import javax.inject.Inject

// CRITICAL FIX: Removed complex dependencies to fix the Unresolved Reference compiler errors
@HiltViewModel
class SettingsViewModel @Inject constructor() : ViewModel() {
    
    var isImporting by mutableStateOf(false)
        private set
    var isDownloading by mutableStateOf(false)
        private set
    var currentModelName by mutableStateOf("None")

    var draftCroppedBitmap by mutableStateOf<Bitmap?>(null)
        private set
    var draftFaceName by mutableStateOf("")

    private val _registeredFaces = MutableStateFlow<List<RegisteredFace>>(emptyList())
    val registeredFaces = _registeredFaces.asStateFlow()

    fun loadPrefs(context: Context) {
        val prefs = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
        currentModelName = prefs.getString("selected_model", "None") ?: "None"
        
        val json = prefs.getString("authorized_faces", "[]") ?: "[]"
        try {
            val type = object : TypeToken<List<RegisteredFace>>() {}.type
            _registeredFaces.value = Gson().fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            prefs.edit().remove("authorized_faces").apply()
            _registeredFaces.value = emptyList()
        }
    }

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

    fun downloadCloudModel(context: Context) {
        isDownloading = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { Toast.makeText(context, "Downloading Cloud Model (This may take a while)...", Toast.LENGTH_LONG).show() }
                val urlStr = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
                val url = java.net.URL(urlStr)
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connect()

                if (connection.responseCode == java.net.HttpURLConnection.HTTP_OK) {
                    val fileName = "gemma-4-E2B-it.litertlm"
                    val destFile = File(context.filesDir, fileName)
                    connection.inputStream.use { input ->
                        destFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE).edit().putString("selected_model", fileName).apply()
                    currentModelName = fileName
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Cloud Model Downloaded & Loaded!", Toast.LENGTH_LONG).show() }
                } else {
                    throw Exception("HTTP ${connection.responseCode}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(context, "Download Failed: ${e.message}", Toast.LENGTH_LONG).show() }
            } finally {
                isDownloading = false
            }
        }
    }

    fun syncToCamera(context: Context, ip: String, token: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val prefs = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
                val socket = Socket(ip, 8081)
                val out = PrintWriter(socket.getOutputStream(), true)
                out.println(token)
                
                val syncData = mapOf(
                    "type" to "SYNC_SETTINGS",
                    "scan_interval_sec" to prefs.getFloat("scan_interval_sec", 5f).toDouble(),
                    "confidence_threshold" to prefs.getFloat("confidence_threshold", 0.60f).toDouble(),
                    "prompt_sys" to prefs.getString("prompt_sys", ""),
                    "prompt_usr" to prefs.getString("prompt_usr", ""),
                    "llm_enabled" to prefs.getBoolean("llm_enabled", true),
                    "face_recog_enabled" to prefs.getBoolean("face_recog_enabled", false)
                )
                out.println(Gson().toJson(syncData))
                socket.close()
                withContext(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onError(e.message ?: "Connection Refused") }
            }
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
                } else { MediaStore.Images.Media.getBitmap(context.contentResolver, uri) }

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

                withContext(Dispatchers.Main) {
                    draftCroppedBitmap = croppedBmp
                    draftFaceName = "Face ${_registeredFaces.value.size + 1}"
                }
            } catch(e: Exception) {}
        }
    }

    fun confirmFaceRegistration(context: Context) {
        val bmp = draftCroppedBitmap ?: return
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { Toast.makeText(context, "Saving...", Toast.LENGTH_SHORT).show() }
            try {
                val biometricEngine = BiometricEngine(context)
                biometricEngine.initialize()
                val embedding = biometricEngine.getFaceEmbedding(bmp)
                biometricEngine.close()

                if (embedding != null) {
                    val newFace = RegisteredFace(UUID.randomUUID().toString(), draftFaceName, embedding)
                    val updatedList = _registeredFaces.value + newFace
                    val prefs = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
                    prefs.edit().putString("authorized_faces", Gson().toJson(updatedList)).apply()
                    _registeredFaces.value = updatedList
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Face added!", Toast.LENGTH_SHORT).show(); draftCroppedBitmap = null }
                }
            } catch(e: Exception) {}
        }
    }
    fun cancelFaceRegistration() { draftCroppedBitmap = null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController, viewModel: SettingsViewModel = hiltViewModel()) {
    val faces by viewModel.registeredFaces.collectAsState()
    
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val prefs = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
    
    var securityToken by remember { mutableStateOf(prefs.getString("security_token", "").takeIf { !it.isNullOrBlank() } ?: UUID.randomUUID().toString().substring(0, 8).also { prefs.edit().putString("security_token", it).apply() }) }
    
    var viewerMode by remember { mutableStateOf(prefs.getString("viewer_mode", "Local WiFi") ?: "Local WiFi") }
    var menuExpanded by remember { mutableStateOf(false) }
    
    var appRole by remember { mutableStateOf(prefs.getString("app_role", "Camera") ?: "Camera") }
    var roleExpanded by remember { mutableStateOf(false) }
    
    var targetIp by remember { mutableStateOf(prefs.getString("target_ip", "") ?: "") }
    var fbDbUrl by remember { mutableStateOf(prefs.getString("fb_db_url", "") ?: "") }
    var fbApiKey by remember { mutableStateOf(prefs.getString("fb_api_key", "") ?: "") }
    var fbAppId by remember { mutableStateOf(prefs.getString("fb_app_id", "") ?: "") }
    
    var scanInterval by remember { mutableStateOf(prefs.getFloat("scan_interval_sec", 5f).coerceIn(1f, 60f)) }
    var confidenceThreshold by remember { mutableStateOf(prefs.getFloat("confidence_threshold", 0.60f).coerceIn(0.0f, 1.0f)) }
    var debugMode by remember { mutableStateOf(prefs.getBoolean("debug_mode", false)) }
    var popupNotifications by remember { mutableStateOf(prefs.getBoolean("enable_notifications", true)) }
    
    // CRITICAL FIX: The Settings UI now directly reads and writes the AI toggles to SharedPreferences
    var llmEnabled by remember { mutableStateOf(prefs.getBoolean("llm_enabled", true)) }
    var faceRecogEnabled by remember { mutableStateOf(prefs.getBoolean("face_recog_enabled", false)) }
    
    var sysPrompt by remember { mutableStateOf(prefs.getString("prompt_sys", "You are an AI security camera. Answer the user's prompt based ONLY on the image provided.") ?: "") }
    var usrPrompt by remember { mutableStateOf(prefs.getString("prompt_usr", "Report if you see any clock.") ?: "") }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { viewModel.processFaceRegistration(it, context) } }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { viewModel.importModel(it, context) } }

    LaunchedEffect(Unit) { viewModel.loadPrefs(context) }

    if (viewModel.draftCroppedBitmap != null) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelFaceRegistration() },
            title = { Text("Confirm & Name Face") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Image(bitmap = viewModel.draftCroppedBitmap!!.asImageBitmap(), contentDescription = "Cropped Face", modifier = Modifier.size(150.dp).clip(CircleShape))
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(value = viewModel.draftFaceName, onValueChange = { viewModel.draftFaceName = it }, label = { Text("Person's Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { Button(onClick = { viewModel.confirmFaceRegistration(context) }) { Text("Save Face") } },
            dismissButton = { TextButton(onClick = { viewModel.cancelFaceRegistration() }) { Text("Cancel") } }
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { TextButton(onClick = { navController.popBackStack() }) { Text("Back") } }) }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            
            Text("Device Role", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            ExposedDropdownMenuBox(expanded = roleExpanded, onExpandedChange = { roleExpanded = !roleExpanded }) {
                OutlinedTextField(
                    value = appRole, onValueChange = {}, readOnly = true, label = { Text("Use this device as:") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = roleExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(), colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                )
                ExposedDropdownMenu(expanded = roleExpanded, onDismissRequest = { roleExpanded = false }) {
                    listOf("Camera", "Viewer").forEach { mode ->
                        DropdownMenuItem(text = { Text(mode) }, onClick = { appRole = mode; prefs.edit().putString("app_role", mode).apply(); roleExpanded = false })
                    }
                }
            }
            
            if (appRole == "Viewer") {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { 
                        viewModel.syncToCamera(context, targetIp, securityToken, 
                            onSuccess = { Toast.makeText(context, "Settings Synced!", Toast.LENGTH_SHORT).show() },
                            onError = { Toast.makeText(context, "Sync Failed: $it", Toast.LENGTH_LONG).show() }
                        ) 
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF009688)),
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text("📡 PUSH SETTINGS TO CAMERA", fontWeight = FontWeight.Bold)
                }
                Text("Make sure the Camera device is running before pushing settings.", style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(top = 4.dp))
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))

            Text("Connection & Networking", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            ExposedDropdownMenuBox(expanded = menuExpanded, onExpandedChange = { menuExpanded = !menuExpanded }) {
                OutlinedTextField(
                    value = viewerMode, onValueChange = {}, readOnly = true, label = { Text("Routing Protocol") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(), colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                )
                ExposedDropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    listOf("Local WiFi", "Firebase").forEach { mode ->
                        DropdownMenuItem(text = { Text(mode) }, onClick = { viewerMode = mode; prefs.edit().putString("viewer_mode", mode).apply(); menuExpanded = false })
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = targetIp, onValueChange = { targetIp = it; prefs.edit().putString("target_ip", it).apply() }, label = { Text("Camera IP Address (Required for WiFi)") }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = securityToken, onValueChange = { securityToken = it; prefs.edit().putString("security_token", it).apply() }, label = { Text("Master Auth Token") }, trailingIcon = { IconButton(onClick = { clipboardManager.setText(AnnotatedString(securityToken)) }) { Text("📋") } }, modifier = Modifier.fillMaxWidth())
            
            if (viewerMode == "Local WiFi") {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Camera & Viewer must be on the same network. Install Tailscale VPN on both devices to access the camera securely from anywhere in the world.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            } else if (viewerMode == "Firebase") {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = fbDbUrl, onValueChange = { fbDbUrl = it; prefs.edit().putString("fb_db_url", it).apply() }, label = { Text("Database URL") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = fbApiKey, onValueChange = { fbApiKey = it; prefs.edit().putString("fb_api_key", it).apply() }, label = { Text("API Key") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = fbAppId, onValueChange = { fbAppId = it; prefs.edit().putString("fb_app_id", it).apply() }, label = { Text("App ID") }, modifier = Modifier.fillMaxWidth())
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))

            Text("Local Biometric Vault", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Upload a photo. The AI will auto-crop the face. Faces are NOT synced remotely.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = { photoPicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) { Text("📸 Upload Face Photo") }
            if (faces.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                faces.forEach { face ->
                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).background(Color(0xFF2C2C2C), RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(face.name, modifier = Modifier.weight(1f), color = Color.White)
                        IconButton(onClick = { viewModel.removeFace(context, face.id) }) { Text("🗑️", color = Color.Red) }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))

            Text("Device Alerts & AI Tuning", style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) { Text("Popup Notifications", style = MaterialTheme.typography.bodyLarge) }
                Switch(checked = popupNotifications, onCheckedChange = { popupNotifications = it; prefs.edit().putBoolean("enable_notifications", it).apply() })
            }
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) { Text("Enable LLM Security Engine", style = MaterialTheme.typography.bodyLarge) }
                Switch(checked = llmEnabled, onCheckedChange = { llmEnabled = it; prefs.edit().putBoolean("llm_enabled", it).apply() })
            }
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) { Text("Enable Face Recognition", style = MaterialTheme.typography.bodyLarge) }
                Switch(checked = faceRecogEnabled, onCheckedChange = { faceRecogEnabled = it; prefs.edit().putBoolean("face_recog_enabled", it).apply() })
            }
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) { Text("Verbose Debug Mode", style = MaterialTheme.typography.bodyLarge) }
                Switch(checked = debugMode, onCheckedChange = { debugMode = it; prefs.edit().putBoolean("debug_mode", it).apply() })
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            Text("Analyze 1 frame every: ${scanInterval.roundToInt()} seconds", style = MaterialTheme.typography.bodyMedium)
            Slider(value = scanInterval, onValueChange = { scanInterval = it }, onValueChangeFinished = { prefs.edit().putFloat("scan_interval_sec", scanInterval).apply() }, valueRange = 1f..60f)
            
            Spacer(modifier = Modifier.height(24.dp))
            Text("Custom AI Prompts", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = sysPrompt, onValueChange = { sysPrompt = it; prefs.edit().putString("prompt_sys", it).apply() }, label = { Text("System Prompt (Rules & Behavior)") }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = usrPrompt, onValueChange = { usrPrompt = it; prefs.edit().putString("prompt_usr", it).apply() }, label = { Text("User Prompt (Custom Trigger)") }, modifier = Modifier.fillMaxWidth())

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))
            
            Text("Local LLM Model", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Current model loaded: ${viewModel.currentModelName}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = { filePicker.launch(arrayOf("*/*")) }, 
                modifier = Modifier.fillMaxWidth(), 
                enabled = !viewModel.isImporting && !viewModel.isDownloading,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) { 
                Text(if (viewModel.isImporting) "Loading Local Model..." else "📁 Import Model from Device (.litertlm)") 
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { viewModel.downloadCloudModel(context) }, 
                modifier = Modifier.fillMaxWidth(), 
                enabled = !viewModel.isImporting && !viewModel.isDownloading,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
            ) { 
                Text(if (viewModel.isDownloading) "Downloading Cloud Model (Please Wait)..." else "☁️ Download Gemma-4-E2B Cloud Model") 
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}