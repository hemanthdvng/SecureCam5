package com.securecam.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Size
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
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
import com.securecam.core.ai.HybridAIPipeline
import com.securecam.data.repository.EventRepository
import com.securecam.data.repository.SecurityEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import javax.inject.Inject

@HiltViewModel
class CameraViewModel @Inject constructor(
    private val eventRepository: EventRepository,
    val aiPipeline: HybridAIPipeline
) : ViewModel() {
    
    private val _latestEvent = MutableStateFlow<SecurityEvent?>(null)
    val latestEvent = _latestEvent.asStateFlow()
    private var lastAnalyzeTime = 0L

    init {
        viewModelScope.launch {
            eventRepository.securityEvents.collect { event -> _latestEvent.value = event }
        }
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    fun processImageProxy(image: ImageProxy, context: Context) {
        val prefs = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
        val intervalMs = (prefs.getFloat("scan_interval_sec", 5f) * 1000).toLong()

        val currentTimestamp = System.currentTimeMillis()
        if (currentTimestamp - lastAnalyzeTime < intervalMs) {
            image.close()
            return
        }
        lastAnalyzeTime = currentTimestamp

        try {
            val bitmap = image.toBitmap()
            aiPipeline.processFrame(bitmap)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            image.close()
        }
    }
}

@Composable
fun CameraScreen(navController: NavController, viewModel: CameraViewModel = hiltViewModel()) {
    val latestEvent by viewModel.latestEvent.collectAsState()
    var displayEvent by remember { mutableStateOf<SecurityEvent?>(null) }
    
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> hasCameraPermission = granted }
    )

    // LIFECYCLE FIX: Start engine exactly when camera opens, kill it when camera closes
    DisposableEffect(lifecycleOwner) {
        viewModel.aiPipeline.start()
        onDispose {
            viewModel.aiPipeline.stop()
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(latestEvent) {
        if (latestEvent != null) {
            displayEvent = latestEvent
            delay(5000)
            displayEvent = null
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCameraPermission) {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    }

                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        val analyzerExecutor = Executors.newSingleThreadExecutor()
                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                            .setTargetResolution(Size(512, 512)) 
                            .build()
                            .also {
                                it.setAnalyzer(analyzerExecutor) { imageProxy ->
                                    viewModel.processImageProxy(imageProxy, ctx)
                                }
                            }

                        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
                        } catch (e: Exception) { e.printStackTrace() }
                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        displayEvent?.let { event ->
            val pillColor = if (event.confidence > 0.8f) Color(0xFFD32F2F) else Color(0xFF1976D2)
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = pillColor.copy(alpha = 0.9f)),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp, start = 16.dp, end = 16.dp).fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "AI ALERT: ${event.type}", color = Color.White, style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = event.description, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Button(
            onClick = { navController.popBackStack() },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp).height(56.dp).fillMaxWidth(0.6f)
        ) {
            Text("END STREAM", style = MaterialTheme.typography.titleMedium)
        }
    }
}