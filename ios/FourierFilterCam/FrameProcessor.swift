import AVFoundation
import CoreGraphics
import FourierDSP
import ImageIO
import simd

/// What the processor knows about the active camera from AVCaptureDevice.
struct DeviceOptics {
    let generation: Int
    let fNumber: Double?
    /// Horizontal field of view of the active format, sensor (landscape) orientation.
    let horizontalFovDeg: Double?
}

/// Turns BGRA camera frames into either the filtered image or its
/// spectrum. Runs on `queue`; buffers are reused across frames.
final class FrameProcessor: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate, @unchecked Sendable {
    let queue = DispatchQueue(label: "fourier.frames", qos: .userInteractive)
    let engine = FourierEngine()

    // Set once before the session starts; called on `queue`.
    var onFrame: ((CGImage) -> Void)?
    var onGeometry: ((FrameGeometry) -> Void)?
    var onOptics: ((SensorOptics?) -> Void)?
    var onCapture: ((CGImage) -> Void)?

    private let lock = NSLock()
    private var _showSpectrum = false
    private var _captureRequested = false
    private var _device: DeviceOptics?

    // Confined to `queue`.
    private let displayImage = PixelImage()
    private let captureImage = PixelImage()
    private var lastGeometry: FrameGeometry?
    private var opticsGeneration = -1
    private var opticsAttempts = 0

    var showSpectrum: Bool {
        get { lock.lock(); defer { lock.unlock() }; return _showSpectrum }
        set { lock.lock(); _showSpectrum = newValue; lock.unlock() }
    }

    func requestCapture() {
        lock.lock()
        _captureRequested = true
        lock.unlock()
    }

    func setDevice(_ device: DeviceOptics) {
        lock.lock()
        _device = device
        lock.unlock()
    }

    private func snapshot() -> (showSpectrum: Bool, capture: Bool, device: DeviceOptics?) {
        lock.lock()
        defer { lock.unlock() }
        let capture = _captureRequested
        _captureRequested = false
        return (_showSpectrum, capture, _device)
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(pixelBuffer) else { return }

        let width = CVPixelBufferGetWidth(pixelBuffer)
        let height = CVPixelBufferGetHeight(pixelBuffer)
        let view = PixelView(
            base: UnsafeRawPointer(base),
            width: width,
            height: height,
            bytesPerRow: CVPixelBufferGetBytesPerRow(pixelBuffer)
        )
        let s = snapshot()

        if s.showSpectrum {
            engine.spectrum(view, into: displayImage)
        } else {
            engine.filter(view, into: displayImage)
        }

        // iPhone sensors are landscape; buffers arrive rotated to portrait.
        let sensorWidth = max(width, height)
        let sensorHeight = min(width, height)
        if let grid = engine.grid {
            let g = FrameGeometry(grid: grid, frameWidth: width, frameHeight: height,
                                  bufferWidth: sensorWidth, bufferHeight: sensorHeight)
            if g != lastGeometry {
                lastGeometry = g
                onGeometry?(g)
            }
        }

        if let device = s.device, device.generation != opticsGeneration {
            // EXIF can be missing on the first frames after a switch; retry briefly.
            let optics = Self.resolveOptics(sampleBuffer, device: device, sensorWidth: sensorWidth, sensorHeight: sensorHeight)
            opticsAttempts += 1
            if optics != nil || opticsAttempts >= 30 {
                opticsGeneration = device.generation
                opticsAttempts = 0
                onOptics?(optics)
            }
        }

        if s.capture {
            let source: PixelImage
            if s.showSpectrum {
                engine.filter(view, into: captureImage)
                source = captureImage
            } else {
                source = displayImage
            }
            if let image = Self.makeImage(source) { onCapture?(image) }
        }

        if let image = Self.makeImage(displayImage) { onFrame?(image) }
    }

    /// Effective pixel pitch at the stream resolution from focal length in mm
    /// (frame EXIF) over focal length in pixels (intrinsics, else field of
    /// view). iOS does not expose the physical sensor size.
    static func resolveOptics(
        _ sampleBuffer: CMSampleBuffer,
        device: DeviceOptics,
        sensorWidth: Int,
        sensorHeight: Int
    ) -> SensorOptics? {
        let attachments = CMCopyDictionaryOfAttachments(
            allocator: kCFAllocatorDefault,
            target: sampleBuffer,
            attachmentMode: kCMAttachmentMode_ShouldPropagate
        ) as? [String: Any]
        let exif = attachments?[kCGImagePropertyExifDictionary as String] as? [String: Any]
        let focalMm = (exif?[kCGImagePropertyExifFocalLength as String] as? NSNumber)?.doubleValue
        let exifF = (exif?[kCGImagePropertyExifFNumber as String] as? NSNumber)?.doubleValue

        var intrinsicFx: Double?
        if let attachment = CMGetAttachment(sampleBuffer, key: kCMSampleBufferAttachmentKey_CameraIntrinsicMatrix, attachmentModeOut: nil),
           let data = attachment as? Data,
           data.count >= MemoryLayout<matrix_float3x3>.size {
            let m = data.withUnsafeBytes { $0.loadUnaligned(as: matrix_float3x3.self) }
            intrinsicFx = Double(m.columns.0.x)
        }
        let fovFx = device.horizontalFovDeg.map { Double(sensorWidth) / 2 / tan($0 * Double.pi / 360) }

        // Intrinsics may be relative to the full sensor rather than the
        // stream; fall back to the field of view when they disagree.
        var fx = intrinsicFx ?? fovFx
        if let a = intrinsicFx, let b = fovFx, abs(a - b) / b > 0.25 { fx = b }

        guard let f = focalMm, f > 0, let px = fx, px > 0 else { return nil }
        let pitch = f / px
        guard pitch > 0.0003, pitch < 0.03 else { return nil } // 0.3 to 30 um
        return SensorOptics(
            pixelPitchMm: pitch,
            activeWidthPx: sensorWidth,
            activeHeightPx: sensorHeight,
            focalLengthMm: f,
            fNumber: device.fNumber ?? exifF
        )
    }

    static let colorSpace = CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB()

    /// BGRA bytes -> CGImage (copies the pixels).
    static func makeImage(_ img: PixelImage) -> CGImage? {
        guard img.width > 0, img.height > 0 else { return nil }
        guard let provider = CGDataProvider(data: Data(img.pixels) as CFData) else { return nil }
        return CGImage(
            width: img.width,
            height: img.height,
            bitsPerComponent: 8,
            bitsPerPixel: 32,
            bytesPerRow: img.bytesPerRow,
            space: colorSpace,
            bitmapInfo: CGBitmapInfo(rawValue: CGBitmapInfo.byteOrder32Little.rawValue | CGImageAlphaInfo.noneSkipFirst.rawValue),
            provider: provider,
            decode: nil,
            shouldInterpolate: true,
            intent: .defaultIntent
        )
    }
}
