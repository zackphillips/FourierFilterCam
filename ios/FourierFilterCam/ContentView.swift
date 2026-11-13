import FourierDSP
import SwiftUI
import UIKit

struct RootView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            switch model.cameraAuthorized {
            case .some(true):
                CameraScreen()
            case .some(false):
                PermissionView()
            case .none:
                ProgressView()
            }
            if let text = model.message {
                Text(text)
                    .font(.callout)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 10))
                    .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.2), value: model.message)
        .onAppear {
            UIApplication.shared.isIdleTimerDisabled = true
            model.start()
        }
    }
}

struct CameraScreen: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        let s = model.state
        ZStack {
            FrameView(display: model.display)
                .ignoresSafeArea()

            if s.showSpectrum, let g = s.geometry {
                SpectrumOverlay(grid: g.grid, scale: s.physicalScale, units: s.axisUnits, cursor: s.cursor) { u, v in
                    model.onSpectrumTouch(u: u, v: v)
                }
                .ignoresSafeArea()
            }

            VStack(spacing: 8) {
                TopBar()
                Spacer()
                BottomBar()
            }
            .padding(8)
        }
        .sheet(isPresented: $model.state.showSettings) {
            SettingsView()
                .environmentObject(model)
                .presentationDetents([.medium, .large])
        }
    }
}

private struct TopBar: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Picker("View", selection: $model.state.showSpectrum) {
                    Text("Image").tag(false)
                    Text("Spectrum").tag(true)
                }
                .pickerStyle(.segmented)
                .frame(width: 190)

                Spacer()

                RoundButton(systemName: "circle.lefthalf.filled", label: "Invert filter") { model.invert() }
                RoundButton(systemName: "trash", label: "Clear filter") { model.clearFilter() }
                RoundButton(systemName: "gearshape", label: "Settings") { model.state.showSettings = true }
            }

            Menu {
                ForEach(FilterType.allCases) { type in
                    Button(type.label) { model.selectFilterType(type) }
                }
            } label: {
                Text("Filter: \(model.state.filterType.label)")
                    .font(.subheadline.weight(.medium))
                    .foregroundColor(.white)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 7)
                    .background(Color.black.opacity(0.5), in: Capsule())
            }
        }
    }
}

private struct BottomBar: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        let s = model.state
        HStack(alignment: .bottom, spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text(filterSummary(s))
                    .font(.footnote.weight(.semibold))
                if let c = s.cursor {
                    Text(Format.frequencyReadout(c, scale: s.physicalScale))
                        .font(.caption2)
                }
                if s.showSpectrum {
                    if let optics = Format.opticsReadout(s.physicalScale) {
                        Text(optics).font(.caption2).opacity(0.8)
                    }
                    if s.geometry != nil, s.cursor == nil {
                        Text("Touch the spectrum to place the filter").font(.caption2).opacity(0.8)
                    }
                }
            }
            .foregroundColor(.white)
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.black.opacity(0.55), in: RoundedRectangle(cornerRadius: 8))

            Button { model.flipCamera() } label: {
                Image(systemName: "arrow.triangle.2.circlepath.camera")
                    .font(.title2)
                    .foregroundColor(.white)
                    .frame(width: 44, height: 44)
            }
            .accessibilityLabel("Switch camera")

            Button { model.capture() } label: {
                ZStack {
                    Circle().fill(Color.white).frame(width: 64, height: 64)
                    Circle().stroke(Color.black.opacity(0.4), lineWidth: 2).frame(width: 56, height: 56)
                }
            }
            .accessibilityLabel("Save filtered image")
        }
    }

    private func filterSummary(_ s: UiState) -> String {
        if s.spec.shape == .empty { return "No filter" }
        let action = s.spec.passInside ? "Passing" : "Blocking"
        return "\(action) inside \(s.filterType.label.lowercased())"
    }
}

private struct RoundButton: View {
    let systemName: String
    let label: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.body.weight(.semibold))
                .foregroundColor(.white)
                .frame(width: 38, height: 38)
                .background(Color.black.opacity(0.5), in: Circle())
        }
        .accessibilityLabel(label)
    }
}

private struct PermissionView: View {
    var body: some View {
        VStack(spacing: 16) {
            Text("Fourier Filter Cam filters the live camera image in the frequency domain. It needs camera access to do that; frames never leave the device.")
                .multilineTextAlignment(.center)
                .foregroundColor(.white)
            Button("Open Settings") {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            }
            .buttonStyle(.borderedProminent)
        }
        .padding(32)
    }
}
