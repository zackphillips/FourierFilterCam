import Foundation

/// Optical parameters of a camera module.
///
/// On Android these come straight from Camera2. iOS does not report sensor
/// size, so the app derives an effective pitch at the stream resolution
/// (focal length / focal length in pixels) and passes the stream size as
/// the active array, which makes `PhysicalScale.binning` 1.
public struct SensorOptics: Equatable, Sendable {
    public var pixelPitchMm: Double
    public var activeWidthPx: Int
    public var activeHeightPx: Int
    public var focalLengthMm: Double?
    public var fNumber: Double?

    public init(pixelPitchMm: Double, activeWidthPx: Int, activeHeightPx: Int, focalLengthMm: Double?, fNumber: Double?) {
        self.pixelPitchMm = pixelPitchMm
        self.activeWidthPx = activeWidthPx
        self.activeHeightPx = activeHeightPx
        self.focalLengthMm = focalLengthMm
        self.fNumber = fNumber
    }
}

/// Converts frame-pixel frequencies to physical units for a stream of
/// `bufferWidth` x `bufferHeight` frames in sensor orientation.
///
/// The stream is assumed to be a centered crop of the active array, scaled
/// uniformly, so the scale is set by the axis that is not cropped.
public struct PhysicalScale: Equatable, Sendable {
    public let optics: SensorOptics

    /// Native pixels per frame pixel.
    public let binning: Double

    /// Effective pixel pitch of the analyzed frame at the sensor, in mm.
    public let pitchMm: Double

    public init(optics: SensorOptics, bufferWidth: Int, bufferHeight: Int) {
        self.optics = optics
        binning = min(
            Double(optics.activeWidthPx) / Double(bufferWidth),
            Double(optics.activeHeightPx) / Double(bufferHeight)
        )
        pitchMm = optics.pixelPitchMm * binning
    }

    public var nyquistCyclesPerMm: Double { 0.5 / pitchMm }

    /// Spatial frequency at the sensor plane.
    public func cyclesPerMm(_ cyclesPerPixel: Double) -> Double { cyclesPerPixel / pitchMm }

    /// Angular frequency in object space. One frame pixel subtends pitch / f radians.
    public func cyclesPerDegree(_ cyclesPerPixel: Double) -> Double? {
        optics.focalLengthMm.map { f in cyclesPerPixel * f / pitchMm * Double.pi / 180 }
    }

    public func cyclesPerPixel(cyclesPerMm: Double) -> Double { cyclesPerMm * pitchMm }

    /// Incoherent (OTF) diffraction cutoff 1 / (lambda N) at the sensor.
    public func diffractionCutoffCyclesPerMm(wavelengthNm: Double) -> Double? {
        optics.fNumber.map { n in 1 / (wavelengthNm * 1e-6 * n) }
    }
}

/// Effective center wavelengths of the color channels, typical for a Bayer
/// CMOS sensor behind an IR-cut filter (+/- 20 nm between sensors).
public struct Wavelengths: Equatable, Sendable {
    public var redNm: Double
    public var greenNm: Double
    public var blueNm: Double

    public init(redNm: Double, greenNm: Double, blueNm: Double) {
        self.redNm = redNm
        self.greenNm = greenNm
        self.blueNm = blueNm
    }

    /// Reference wavelength the user draws the mask at.
    public var referenceNm: Double { greenNm }

    public static let bayerTypical = Wavelengths(redNm: 600, greenNm: 530, blueNm: 465)
}
