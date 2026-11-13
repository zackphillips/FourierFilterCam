package com.phillyberk.fourierfiltercamera.ui

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.phillyberk.fourierfiltercamera.UiState
import com.phillyberk.fourierfiltercamera.camera.FrameRenderer
import com.phillyberk.fourierfiltercamera.dsp.FilterShape
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

/** How the controls are arranged for the space the window gives us (after insets). */
enum class ControlLayout {
    /** Short windows (landscape phones, short split-screen): actions in a side rail. */
    RAIL,

    /** Narrow windows (portrait phones, narrow split-screen): stacked rows. */
    STACKED,

    /** Tablets, unfolded foldables, desktop windows: one row top and bottom. */
    WIDE,
}

fun controlLayoutFor(width: Dp, height: Dp): ControlLayout = when {
    height < 480.dp && width > height -> ControlLayout.RAIL
    width < 600.dp -> ControlLayout.STACKED
    else -> ControlLayout.WIDE
}

@Composable
fun FourierScreen(state: UiState, renderer: FrameRenderer?, actions: ScreenActions) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (renderer != null) {
            AndroidView(
                factory = { ctx -> SurfaceView(ctx).apply { holder.addCallback(renderer) } },
                modifier = Modifier.fillMaxSize(),
            )
        }

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

        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(8.dp)) {
            when (controlLayoutFor(maxWidth, maxHeight)) {
                ControlLayout.RAIL -> RailLayout(state, actions, maxHeight)
                ControlLayout.STACKED -> StackedLayout(state, actions, maxHeight)
                ControlLayout.WIDE -> WideLayout(state, actions)
            }
        }
    }
}

@Composable
private fun StackedLayout(state: UiState, actions: ScreenActions, height: Dp) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ModeToggle(state, actions, Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterTypeMenu(state.filterType, actions.selectFilterType, Modifier.weight(1f))
            FilterActions(actions)
        }
        Spacer(Modifier.weight(1f))
        Readout(state, maxLines = if (height < 560.dp) 2 else 4, Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            FlipButton(actions)
            Spacer(Modifier.width(24.dp))
            CaptureButton(actions, large = height >= 560.dp)
            // Balances the flip button so the shutter sits on the center line.
            Spacer(Modifier.width(24.dp + 48.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WideLayout(state: UiState, actions: ScreenActions) {
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlowRow(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ModeToggle(state, actions)
                FilterTypeMenu(state.filterType, actions.selectFilterType)
            }
            FilterActions(actions)
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f)) { Readout(state, maxLines = 4, Modifier.widthIn(max = 720.dp)) }
            Spacer(Modifier.width(8.dp))
            FlipButton(actions)
            Spacer(Modifier.width(8.dp))
            CaptureButton(actions, large = true)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RailLayout(state: UiState, actions: ScreenActions, height: Dp) {
    val largeCapture = height >= 400.dp
    // 4 icon buttons (48dp) + capture + 4 gaps of 8dp.
    val railNeeds = 48.dp * 4 + (if (largeCapture) 96.dp else 56.dp) + 32.dp
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ModeToggle(state, actions)
                FilterTypeMenu(state.filterType, actions.selectFilterType)
            }
            Spacer(Modifier.weight(1f))
            Readout(state, maxLines = if (height < 300.dp) 1 else 3, Modifier.widthIn(max = 560.dp))
        }
        Spacer(Modifier.width(8.dp))
        if (height >= railNeeds) {
            Column(
                Modifier.fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { FilterActionButtons(actions) }
                CaptureButton(actions, large = largeCapture)
                FlipButton(actions)
            }
        } else {
            // Very short multi-window panes: the rail scrolls, so lead with the shutter.
            Column(
                Modifier.fillMaxHeight().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CaptureButton(actions, large = false)
                FlipButton(actions)
                FilterActionButtons(actions)
            }
        }
    }
}

@Composable
private fun ModeToggle(state: UiState, actions: ScreenActions, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier) {
        SegmentedButton(
            selected = !state.showSpectrum,
            onClick = { actions.setShowSpectrum(false) },
            shape = SegmentedButtonDefaults.itemShape(0, 2),
        ) { Text("Image", maxLines = 1, overflow = TextOverflow.Ellipsis) }
        SegmentedButton(
            selected = state.showSpectrum,
            onClick = { actions.setShowSpectrum(true) },
            shape = SegmentedButtonDefaults.itemShape(1, 2),
        ) { Text("Spectrum", maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun FilterTypeMenu(selected: FilterType, onSelect: (FilterType) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton(
            onClick = { open = true },
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                .semantics { contentDescription = "Filter type: ${selected.label}" },
        ) {
            Text(selected.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            FilterType.entries.forEach { type ->
                DropdownMenuItem(text = { Text(type.label) }, onClick = { onSelect(type); open = false })
            }
        }
    }
}

@Composable
private fun FilterActions(actions: ScreenActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { FilterActionButtons(actions) }
}

@Composable
private fun FilterActionButtons(actions: ScreenActions) {
    FilledIconButton(onClick = actions.invert) { Icon(Icons.Filled.InvertColors, contentDescription = "Invert filter") }
    FilledIconButton(onClick = actions.clear) { Icon(Icons.Filled.Delete, contentDescription = "Clear filter") }
    FilledIconButton(onClick = actions.openSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
}

@Composable
private fun FlipButton(actions: ScreenActions) {
    IconButton(onClick = actions.flipCamera) {
        Icon(Icons.Filled.Cameraswitch, contentDescription = "Switch camera", tint = Color.White)
    }
}

@Composable
private fun CaptureButton(actions: ScreenActions, large: Boolean) {
    if (large) {
        LargeFloatingActionButton(onClick = actions.capture) {
            Icon(Icons.Filled.PhotoCamera, contentDescription = "Save filtered image", modifier = Modifier.size(36.dp))
        }
    } else {
        FloatingActionButton(onClick = actions.capture) {
            Icon(Icons.Filled.PhotoCamera, contentDescription = "Save filtered image")
        }
    }
}

@Composable
private fun Readout(state: UiState, maxLines: Int, modifier: Modifier = Modifier) {
    val scale = state.physicalScale
    val lines = buildList {
        val passing = if (state.spec.passInside) "Passing" else "Blocking"
        add(if (state.spec.shape == FilterShape.Empty) "No filter" else "$passing inside ${state.filterType.label.lowercase()}")
        state.cursor?.let { (u, v) -> add(frequencyReadout(u, v, scale)) }
        if (state.showSpectrum) {
            opticsReadout(scale)?.let(::add)
            if (state.geometry != null && state.cursor == null) add("Touch the spectrum to place the filter")
        }
    }.take(maxLines)
    Column(
        modifier
            .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        lines.forEachIndexed { i, line ->
            Text(
                line,
                style = if (i == 0) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodySmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
