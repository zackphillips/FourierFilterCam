package com.phillyberk.fourierfiltercamera.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.phillyberk.fourierfiltercamera.AxisUnits
import com.phillyberk.fourierfiltercamera.FourierViewModel
import com.phillyberk.fourierfiltercamera.Resolution
import com.phillyberk.fourierfiltercamera.UiState
import com.phillyberk.fourierfiltercamera.dsp.ColorMode
import com.phillyberk.fourierfiltercamera.dsp.OutputMode
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(state: UiState, vm: FourierViewModel) {
    ModalBottomSheet(onDismissRequest = { vm.setShowSettings(false) }) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Section("Analysis resolution")
            Resolution.entries.forEach { RadioRow(it.label, state.resolution == it) { vm.setResolution(it) } }

            Section("Color")
            ColorMode.entries.forEach { RadioRow(it.label, state.colorMode == it) { vm.setColorMode(it) } }

            Section("Output")
            OutputMode.entries.forEach { RadioRow(it.label, state.outputMode == it) { vm.setOutputMode(it) } }

            Section("Spectrum axes")
            AxisUnits.entries.forEach { RadioRow(it.label, state.axisUnits == it) { vm.setAxisUnits(it) } }
            if (state.optics == null) {
                Text(
                    "This camera does not report its sensor size, so axes stay in cycles/pixel.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Section("Spectrum colormap")
            Colormap.entries.forEach { RadioRow(it.label, state.colormap == it) { vm.setColormap(it) } }

            Section("Spectrum display")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Hann window (hides edge cross)", Modifier.weight(1f))
                Switch(checked = state.windowSpectrum, onCheckedChange = vm::setWindowSpectrum)
            }

            Section("Mask edge softness: ${String.format(Locale.US, "%.1f", state.edgeSoftnessPx)} px")
            Slider(value = state.edgeSoftnessPx, onValueChange = vm::setEdgeSoftness, valueRange = 0f..5f)
            Text(
                "A soft edge trades a little sharpness at the cutoff for much less ringing.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.padding(vertical = 16.dp)) {}
        }
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 12.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 8.dp))
    }
}
