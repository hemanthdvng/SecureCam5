package com.securecam.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.securecam.data.repository.EventRepository
import com.securecam.data.repository.SecurityEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CameraViewModel @Inject constructor(
    private val eventRepository: EventRepository
) : ViewModel() {
    
    private val _latestEvent = MutableStateFlow<SecurityEvent?>(null)
    val latestEvent = _latestEvent.asStateFlow()

    init {
        viewModelScope.launch {
            eventRepository.securityEvents.collect { event ->
                _latestEvent.value = event
            }
        }
    }
}

@Composable
fun CameraScreen(
    navController: NavController,
    viewModel: CameraViewModel = hiltViewModel()
) {
    val latestEvent by viewModel.latestEvent.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> hasCameraPermission = granted }
    )

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        
        if (hasCameraPermission) {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }

                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                preview
                            )
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                "Requesting Camera Permission...", 
                color = Color.LightGray,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Dynamic AI Overlay
        latestEvent?.let { event ->
            val pillColor = if (event.confidence > 0.8f) Color(0xFFD32F2F) else Color(0xFF1976D2)
            
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = pillColor.copy(alpha = 0.9f)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 48.dp, start = 16.dp, end = 16.dp)
                    .fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "AI ALERT: ${event.type}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = event.description,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        // Bottom Controls
        Button(
            onClick = { navController.popBackStack() },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .height(56.dp)
                .fillMaxWidth(0.6f)
        ) {
            Text("END STREAM", style = MaterialTheme.typography.titleMedium)
        }
    }
}