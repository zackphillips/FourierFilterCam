package com.phillyberk.fourierfiltercamera.ui

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.phillyberk.fourierfiltercamera.UiState
import com.phillyberk.fourierfiltercamera.camera.FrameRenderer
import com.phillyberk.fourierfiltercamera.dsp.FilterType

data class ScreenActions(
    val setShowSpectrum: (Boolean) -> Unit,
    val selectFilterType: (FilterType) -> Unit,
    val invert: () -> Unit,
    val clear: () -> Unit,
    val capture: () -> Unit,
    val flipCamera: () -> Unit,
    val openSettings: () -> Unit,
    val onSpectrumTouch: (Double, Double) -> Unit,
)

@Composable
fun FourierScreen(state: UiState, renderer: FrameRenderer, actions: ScreenActions) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx -> SurfaceView(ctx).apply { holder.addCallback(renderer) } },
            modifier = Modifier.fillMaxSize(),
        )

        val geometry = state.geometry
        if (state.showSpectrum && geometry != null) {
            SpectrumOverlay(
                grid = geometry.grid,
                scale = state.physicalScale,
                units = state.axisUnits,
                cursor = state.cursor,
                onTouch = actions.onSpectrumTouch,
            )
        }

        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(8.dp)) {
            TopBar(state, actions)
            Spacer(Modifier.weight(1f))
            BottomBar(state, actions)
        }
    }
}

@Composable
private fun TopBar(state: UiState, actions: ScreenActions) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SingleChoiceSegmentedButtonRow(Modifier.width(240.dp)) {
            SegmentedButton(
                selected = !state.showSpectrum,
                onClick = { actions.setShowSpectrum(false) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text("Image") }
            SegmentedButton(
                selected = state.showSpectrum,
                onClick = { actions.setShowSpectrum(true) },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text("Spectrum") }
        }
        FilterTypeMenu(state.filterType, actions.selectFilterType)
        Spacer(Modifier.weight(1f))
        FilledIconButton(onClick = actions.invert) { Icon(Icons.Filled.InvertColors, contentDescription = "Invert filter") }
        FilledIconButton(onClick = actions.clear) { Icon(Icons.Filled.Delete, contentDescription = "Clear filter") }
        FilledIconButton(onClick = actions.openSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
    }
}

@Composable
private fun FilterTypeMenu(selected: FilterType, onSelect: (FilterType) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { open = true },
            modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(20.dp)),
        ) { Text("Filter: ${selected.label}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            FilterType.entries.forEach { type ->
                DropdownMenuItem(text = { Text(type.label) }, onClick = { onSelect(type); open = false })
            }
        }
    }
}

@Composable
private fun BottomBar(state: UiState, actions: ScreenActions) {
    val scale = state.physicalScale
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(
            Modifier
                .weight(1f)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            val passing = if (state.spec.passInside) "Passing" else "Blocking"
            Text(
                if (state.spec.shape == com.phillyberk.fourierfiltercamera.dsp.FilterShape.Empty) "No filter" else "$passing inside ${state.filterType.label.lowercase()}",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
            )
            state.cursor?.let { (u, v) ->
                Text(frequencyReadout(u, v, scale), style = MaterialTheme.typography.bodySmall, color = Color.White)
            }
            if (state.showSpectrum) {
                opticsReadout(scale)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f)) }
                if (state.geometry != null && state.cursor == null) {
                    Text("Touch the spectrum to place the filter", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        IconButton(onClick = actions.flipCamera) {
            Icon(Icons.Filled.Cameraswitch, contentDescription = "Switch camera", tint = Color.White)
        }
        Spacer(Modifier.width(8.dp))
        LargeFloatingActionButton(onClick = actions.capture) {
            Icon(Icons.Filled.PhotoCamera, contentDescription = "Save filtered image", modifier = Modifier.size(36.dp))
        }
    }
}
