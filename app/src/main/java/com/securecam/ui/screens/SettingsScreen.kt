package com.securecam.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val aiPipeline: HybridAIPipeline
) : ViewModel() {
    val isLlmEnabled = repository.isLlmEnabled.stateIn(viewModelScope, SharingStarted.Lazily, true)
    
    var isImporting by mutableStateOf(false)
        private set

    var currentModelName by mutableStateOf("None")
    var useGpu by mutableStateOf(false)

    fun loadPrefs(context: Context) {
        val prefs = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
        currentModelName = prefs.getString("selected_model", "None") ?: "None"
        useGpu = prefs.getBoolean("use_gpu", false)
    }

    fun setGpu(context: Context, enabled: Boolean) {
        useGpu = enabled
        context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("use_gpu", enabled).apply()
        aiPipeline.reinitialize()
    }

    fun toggleLlm(enabled: Boolean) {
        viewModelScope.launch { repository.setLlmEnabled(enabled) }
    }

    fun importModel(uri: Uri, context: Context) {
        isImporting = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Dynamically get the real file name
                var fileName = "custom_model.litertlm"
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index != -1) fileName = cursor.getString(index)
                    }
                }

                val destFile = File(context.filesDir, fileName)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                
                context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
                    .edit().putString("selected_model", fileName).apply()
                
                currentModelName = fileName
                aiPipeline.reinitialize()
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Model $fileName Loaded!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Import Failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                isImporting = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController, viewModel: SettingsViewModel = hiltViewModel()) {
    val llmEnabled by viewModel.isLlmEnabled.collectAsState()
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
    
    var scanInterval by remember { mutableStateOf(prefs.getFloat("scan_interval_sec", 1f)) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importModel(it, context) }
    }

    LaunchedEffect(Unit) {
        viewModel.loadPrefs(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { TextButton(onClick = { navController.popBackStack() }) { Text("Back") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            
            Text("AI Engine Preferences", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Enable LLM Security Engine", style = MaterialTheme.typography.bodyLarge)
                    Text("Current model: ${viewModel.currentModelName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = llmEnabled, onCheckedChange = { viewModel.toggleLlm(it) })
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Use GPU Acceleration", style = MaterialTheme.typography.bodyLarge)
                    Text("Warning: Turn off if the camera crashes!", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Switch(checked = viewModel.useGpu, onCheckedChange = { viewModel.setGpu(context, it) })
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))
            
            Text("Performance & Battery", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            
            Text("Analyze 1 frame every: ${scanInterval.roundToInt()} seconds", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = scanInterval,
                onValueChange = { scanInterval = it },
                onValueChangeFinished = { prefs.edit().putFloat("scan_interval_sec", scanInterval).apply() },
                valueRange = 1f..60f,
                steps = 58 
            )

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))

            Text("Dynamic Model Loading", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Select any .litertlm or .bin model from your phone's storage to use as the AI brain.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(16.dp))
            
            Button(
                onClick = { filePicker.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !viewModel.isImporting
            ) {
                Text(if (viewModel.isImporting) "Loading Model (Please Wait)..." else "Select New Model")
            }
        }
    }
}