package com.phillyberk.fourierfiltercamera

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phillyberk.fourierfiltercamera.camera.sensorOptics
import com.phillyberk.fourierfiltercamera.ui.FourierScreen
import com.phillyberk.fourierfiltercamera.ui.ScreenActions
import com.phillyberk.fourierfiltercamera.ui.SettingsSheet
import org.opencv.android.OpenCVLoader

class MainActivity : ComponentActivity() {
    private val vm: FourierViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!OpenCVLoader.initLocal()) Log.e(TAG, "OpenCV native library failed to load")
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { App(vm) }
        }
    }

    private companion object {
        const val TAG = "FourierFilterCam"
    }
}

@Composable
private fun App(vm: FourierViewModel) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    // Full-bleed camera view; the screen applies window insets to its own controls.
    Box(Modifier.fillMaxSize()) {
        if (!granted) {
            PermissionRationale { launcher.launch(Manifest.permission.CAMERA) }
        } else {
            val state by vm.state.collectAsStateWithLifecycle()
            CameraBinding(vm, state.lensFacing, state.resolution)
            FourierScreen(
                state = state,
                renderer = vm.renderer,
                actions = ScreenActions(
                    setShowSpectrum = vm::setShowSpectrum,
                    selectFilterType = vm::selectFilterType,
                    invert = vm::invert,
                    clear = vm::clearFilter,
                    capture = vm::capture,
                    flipCamera = vm::flipCamera,
                    openSettings = { vm.setShowSettings(true) },
                    onSpectrumTouch = vm::onSpectrumTouch,
                ),
            )
            if (state.showSettings) SettingsSheet(state, vm)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.Center).safeDrawingPadding())
    }
}

/**
 * Binds a single ImageAnalysis use case; frames are rendered by the
 * analyzer itself. The activity handles rotation without being recreated,
 * so a display listener keeps the stream's target rotation current.
 */
@Composable
private fun CameraBinding(vm: FourierViewModel, lensFacing: Int, resolution: Resolution) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysis = remember(resolution) { buildAnalysis(resolution, vm) }

    DisposableEffect(analysis, view) {
        val displayManager = context.getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                val display = view.display ?: return
                if (display.displayId == displayId) analysis.targetRotation = display.rotation
            }
        }
        view.display?.let { analysis.targetRotation = it.rotation }
        displayManager.registerDisplayListener(listener, null)
        onDispose { displayManager.unregisterDisplayListener(listener) }
    }

    LaunchedEffect(lensFacing, analysis) {
        val provider = ProcessCameraProvider.awaitInstance(context)
        val camera = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        try {
            provider.unbindAll()
            val bound = provider.bindToLifecycle(lifecycleOwner, camera, analysis)
            vm.onCameraBound(sensorOptics(bound.cameraInfo))
        } catch (e: Exception) {
            Log.e("FourierFilterCam", "Camera bind failed", e)
            vm.onCameraError("Camera unavailable: ${e.message}")
        }
    }
}

private fun buildAnalysis(resolution: Resolution, vm: FourierViewModel): ImageAnalysis {
    val selector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(
            if (resolution.is16x9) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
            else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY,
        )
        .setResolutionStrategy(
            ResolutionStrategy(
                Size(resolution.width, resolution.height),
                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
            ),
        )
        .build()
    return ImageAnalysis.Builder()
        .setResolutionSelector(selector)
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
        .build()
        .also { it.setAnalyzer(vm.analysisExecutor, vm.analyzer) }
}

@Composable
private fun PermissionRationale(onRequest: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Fourier Filter Cam filters the live camera image in the frequency domain. It needs camera access to do that; frames never leave the device.")
            Button(onClick = onRequest) { Text("Allow camera") }
        }
    }
}
