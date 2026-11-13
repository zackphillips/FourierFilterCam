import AVFoundation

/// Owns the capture session. All session work happens on `sessionQueue`;
/// frames go to `FrameProcessor` on its own queue.
///
/// Uses the physical wide-angle camera, not the dual/triple virtual
/// devices, which switch lenses with zoom and focus distance and would
/// change pitch and f-number under the user. Video stabilization is off
/// because it crops the frame, which would invalidate the field of view.
final class CameraController: @unchecked Sendable {
    let session = AVCaptureSession()
    var onError: ((String) -> Void)?

    private let sessionQueue = DispatchQueue(label: "fourier.session")
    private let videoOutput = AVCaptureVideoDataOutput()
    private let processor: FrameProcessor
    private var input: AVCaptureDeviceInput?
    private var generation = 0

    init(processor: FrameProcessor) {
        self.processor = processor
    }

    func configure(position: AVCaptureDevice.Position, preset: AVCaptureSession.Preset) {
        sessionQueue.async { self.reconfigure(position: position, preset: preset) }
    }

    func stop() {
        sessionQueue.async {
            if self.session.isRunning { self.session.stopRunning() }
        }
    }

    private func reconfigure(position: AVCaptureDevice.Position, preset: AVCaptureSession.Preset) {
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: position) else {
            onError?("No \(position == .front ? "front" : "back") camera")
            return
        }

        session.beginConfiguration()
        if let input {
            session.removeInput(input)
            self.input = nil
        }
        do {
            let newInput = try AVCaptureDeviceInput(device: device)
            guard session.canAddInput(newInput) else {
                session.commitConfiguration()
                onError?("Camera unavailable")
                return
            }
            session.addInput(newInput)
            input = newInput
        } catch {
            session.commitConfiguration()
            onError?("Camera unavailable: \(error.localizedDescription)")
            return
        }

        session.sessionPreset = session.canSetSessionPreset(preset) ? preset : .high

        if !session.outputs.contains(videoOutput) {
            videoOutput.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
            videoOutput.alwaysDiscardsLateVideoFrames = true
            videoOutput.setSampleBufferDelegate(processor, queue: processor.queue)
            if session.canAddOutput(videoOutput) { session.addOutput(videoOutput) }
        }

        if let connection = videoOutput.connection(with: .video) {
            // The UI is portrait-only, so rotate buffers to portrait once here.
            if connection.isVideoRotationAngleSupported(90) { connection.videoRotationAngle = 90 }
            if connection.isVideoStabilizationSupported { connection.preferredVideoStabilizationMode = .off }
            if connection.isVideoMirroringSupported {
                connection.automaticallyAdjustsVideoMirroring = false
                connection.isVideoMirrored = position == .front
            }
            if connection.isCameraIntrinsicMatrixDeliverySupported {
                connection.isCameraIntrinsicMatrixDeliveryEnabled = true
            }
        }
        session.commitConfiguration()

        generation += 1
        let aperture = Double(device.lensAperture)
        let fov = Double(device.activeFormat.videoFieldOfView)
        processor.setDevice(DeviceOptics(
            generation: generation,
            fNumber: aperture > 0 ? aperture : nil,
            horizontalFovDeg: fov > 0 ? fov : nil
        ))

        if !session.isRunning { session.startRunning() }
    }
}
