import AVFoundation
import FourierDSP
import SwiftUI

enum Resolution: String, CaseIterable, Identifiable {
    case vga, hd, fhd

    var id: String { rawValue }

    var label: String {
        switch self {
        case .vga: return "640 × 480"
        case .hd: return "1280 × 720"
        case .fhd: return "1920 × 1080"
        }
    }

    var preset: AVCaptureSession.Preset {
        switch self {
        case .vga: return .vga640x480
        case .hd: return .hd1280x720
        case .fhd: return .hd1920x1080
        }
    }
}

enum AxisUnits: String, CaseIterable, Identifiable {
    case cyclesPerPixel, cyclesPerMm, cyclesPerDegree

    var id: String { rawValue }

    var label: String {
        switch self {
        case .cyclesPerPixel: return "cycles/pixel"
        case .cyclesPerMm: return "cycles/mm (sensor)"
        case .cyclesPerDegree: return "cycles/degree (field)"
        }
    }

    var short: String {
        switch self {
        case .cyclesPerPixel: return "cy/px"
        case .cyclesPerMm: return "cy/mm"
        case .cyclesPerDegree: return "cy/°"
        }
    }
}

/// A spatial frequency in cycles/pixel.
struct Frequency: Equatable {
    var u: Double
    var v: Double
}

/// Frame geometry reported back to the UI for touch mapping and units.
struct FrameGeometry: Equatable {
    let grid: FrequencyGrid
    let frameWidth: Int
    let frameHeight: Int
    /// Buffer size in sensor (landscape) orientation, before display rotation.
    let bufferWidth: Int
    let bufferHeight: Int
}

struct UiState: Equatable {
    var showSpectrum = false
    var filterType = FilterType.circle
    var spec = FilterSpec.passAll
    var colorMode = ColorMode.gray
    var outputMode = OutputMode.real
    var resolution = Resolution.hd
    var position = AVCaptureDevice.Position.back
    var axisUnits = AxisUnits.cyclesPerMm
    var colormap = Colormap.inferno
    var windowSpectrum = true
    var edgeSoftnessPx = 1.5
    /// Last touched frequency, for the readout.
    var cursor: Frequency?
    var geometry: FrameGeometry?
    var optics: SensorOptics?
    var showSettings = false

    var physicalScale: PhysicalScale? {
        guard let o = optics, let g = geometry else { return nil }
        return PhysicalScale(optics: o, bufferWidth: g.bufferWidth, bufferHeight: g.bufferHeight)
    }

    var engineSettings: EngineSettings {
        var s = EngineSettings()
        s.spec = spec
        s.colorMode = colorMode
        s.outputMode = outputMode
        s.edgeSoftnessPx = edgeSoftnessPx
        s.windowSpectrum = windowSpectrum
        s.colormap = colormap
        return s
    }
}

@MainActor
final class AppModel: ObservableObject {
    @Published var state = UiState() {
        didSet { stateChanged(from: oldValue) }
    }

    /// nil until the camera permission is known.
    @Published private(set) var cameraAuthorized: Bool?
    @Published private(set) var message: String?

    let display = FrameDisplay()
    private let processor = FrameProcessor()
    private let camera: CameraController
    private var messageTask: Task<Void, Never>?

    /// Freeform brush radius, cycles/pixel.
    static let brushRadius = 0.02
    /// Radial width of the annulus, cycles/pixel.
    static let annulusWidth = 0.02
    static let maxDabs = 4000
    /// Where shape filters start when picked, cycles/pixel.
    static let defaultTouch = Frequency(u: 0.08, v: 0.06)

    init() {
        camera = CameraController(processor: processor)
        processor.engine.settings = state.engineSettings
        let display = self.display
        processor.onFrame = { image in display.show(image) }
        processor.onGeometry = { [weak self] g in
            Task { @MainActor in self?.state.geometry = g }
        }
        processor.onOptics = { [weak self] o in
            Task { @MainActor in self?.state.optics = o }
        }
        processor.onCapture = { [weak self] image in
            Task { @MainActor in self?.save(image) }
        }
        camera.onError = { [weak self] text in
            Task { @MainActor in self?.show(text) }
        }
    }

    func start() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            cameraAuthorized = true
            configureCamera()
        case .notDetermined:
            Task {
                let granted = await AVCaptureDevice.requestAccess(for: .video)
                cameraAuthorized = granted
                if granted { configureCamera() }
            }
        default:
            cameraAuthorized = false
        }
    }

    private func configureCamera() {
        camera.configure(position: state.position, preset: state.resolution.preset)
    }

    private func stateChanged(from old: UiState) {
        let settings = state.engineSettings
        if settings != old.engineSettings { processor.engine.settings = settings }
        if state.showSpectrum != old.showSpectrum { processor.showSpectrum = state.showSpectrum }
        if cameraAuthorized == true, state.position != old.position || state.resolution != old.resolution {
            configureCamera()
        }
    }

    // MARK: Actions

    func selectFilterType(_ type: FilterType) {
        var s = state
        let t = Self.defaultTouch
        s.filterType = type
        s.spec = FilterSpec(shape: type.shapeThrough(u: t.u, v: t.v, annulusWidth: Self.annulusWidth), passInside: type.passInsideByDefault)
        s.cursor = type == .freeform ? nil : t
        state = s
    }

    func invert() {
        state.spec = FilterSpec(shape: state.spec.shape, passInside: !state.spec.passInside)
    }

    func clearFilter() {
        var s = state
        s.spec = FilterSpec(shape: s.filterType == .freeform ? .freeform([]) : .empty, passInside: false)
        s.cursor = nil
        state = s
    }

    /// A touch on the spectrum at frequency (u, v) cycles/pixel.
    func onSpectrumTouch(u: Double, v: Double) {
        var s = state
        if s.filterType == .freeform {
            var dabs: [Dab] = []
            var passInside = FilterType.freeform.passInsideByDefault
            if case let .freeform(current) = s.spec.shape {
                dabs = current
                passInside = s.spec.passInside
            }
            // Skip dabs that would mostly overlap the previous one.
            if let last = dabs.last, hypot(u - last.u, v - last.v) < Self.brushRadius / 3 { return }
            if dabs.count >= Self.maxDabs { return }
            dabs.append(Dab(u: u, v: v, radius: Self.brushRadius))
            s.spec = FilterSpec(shape: .freeform(dabs), passInside: passInside)
        } else {
            s.spec = FilterSpec(shape: s.filterType.shapeThrough(u: u, v: v, annulusWidth: Self.annulusWidth), passInside: s.spec.passInside)
        }
        s.cursor = Frequency(u: u, v: v)
        state = s
    }

    func flipCamera() {
        var s = state
        s.position = s.position == .back ? .front : .back
        s.optics = nil
        state = s
    }

    func capture() { processor.requestCapture() }

    private func save(_ image: CGImage) {
        let description = Format.describe(state)
        Task {
            do {
                try await CaptureSaver.save(image, description: description)
                show("Saved to Photos")
            } catch {
                show("Could not save: \(error.localizedDescription)")
            }
        }
    }

    func show(_ text: String) {
        message = text
        messageTask?.cancel()
        messageTask = Task {
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            if !Task.isCancelled { message = nil }
        }
    }
}
