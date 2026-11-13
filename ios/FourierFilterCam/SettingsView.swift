import FourierDSP
import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section("Analysis resolution") {
                    Picker("Resolution", selection: $model.state.resolution) {
                        ForEach(Resolution.allCases) { Text($0.label).tag($0) }
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                }

                Section("Color") {
                    Picker("Color", selection: $model.state.colorMode) {
                        ForEach(ColorMode.allCases) { Text($0.label).tag($0) }
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                }

                Section("Output") {
                    Picker("Output", selection: $model.state.outputMode) {
                        ForEach(OutputMode.allCases) { Text($0.label).tag($0) }
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                }

                Section {
                    Picker("Axes", selection: $model.state.axisUnits) {
                        ForEach(AxisUnits.allCases) { Text($0.label).tag($0) }
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                } header: {
                    Text("Spectrum axes")
                } footer: {
                    if model.state.optics == nil {
                        Text("This camera has not reported its focal length, so axes stay in cycles/pixel.")
                    }
                }

                Section("Spectrum colormap") {
                    Picker("Colormap", selection: $model.state.colormap) {
                        ForEach(Colormap.allCases) { Text($0.label).tag($0) }
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                }

                Section("Spectrum display") {
                    Toggle("Hann window (hides edge cross)", isOn: $model.state.windowSpectrum)
                }

                Section {
                    Slider(value: $model.state.edgeSoftnessPx, in: 0...5)
                } header: {
                    Text("Mask edge softness: \(String(format: "%.1f", model.state.edgeSoftnessPx)) px")
                } footer: {
                    Text("A soft edge trades a little sharpness at the cutoff for much less ringing.")
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}
