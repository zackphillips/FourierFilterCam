import Foundation
import FourierDSP

/// Number formatting and unit conversion shared by the overlay and readouts.
/// Port of the Android `ui/Format.kt`.
enum Format {
    private static let posix = Locale(identifier: "en_US_POSIX")

    static func number(_ x: Double) -> String {
        if x == 0 { return "0" }
        let a = abs(x)
        let pattern = a >= 100 ? "%.0f" : a >= 10 ? "%.1f" : a >= 1 ? "%.2f" : "%.3f"
        return String(format: pattern, locale: posix, x)
    }

    /// Converts cycles/pixel into `units`, or nil if the camera did not report what is needed.
    static func convert(_ cyclesPerPixel: Double, to units: AxisUnits, scale: PhysicalScale?) -> Double? {
        switch units {
        case .cyclesPerPixel: return cyclesPerPixel
        case .cyclesPerMm: return scale?.cyclesPerMm(cyclesPerPixel)
        case .cyclesPerDegree: return scale?.cyclesPerDegree(cyclesPerPixel)
        }
    }

    /// Inverse of `convert`.
    static func cyclesPerPixel(_ value: Double, from units: AxisUnits, scale: PhysicalScale?) -> Double? {
        switch units {
        case .cyclesPerPixel: return value
        case .cyclesPerMm: return scale?.cyclesPerPixel(cyclesPerMm: value)
        case .cyclesPerDegree:
            guard let perUnit = scale?.cyclesPerDegree(1) else { return nil }
            return value / perUnit
        }
    }

    /// 1-2-5 step giving roughly `targetTicks` ticks over `span`.
    static func niceStep(_ span: Double, targetTicks: Int = 4) -> Double {
        let raw = span / Double(targetTicks)
        let mag = pow(10, floor(log10(raw)))
        let norm = raw / mag
        let step: Double = norm < 1.5 ? 1 : norm < 3.5 ? 2 : norm < 7.5 ? 5 : 10
        return step * mag
    }

    /// One-line readout for a frequency in cycles/pixel.
    static func frequencyReadout(_ f: Frequency, scale: PhysicalScale?) -> String {
        let r = hypot(f.u, f.v)
        var parts = ["|ν| \(number(r)) cy/px"]
        if let s = scale {
            parts.append("\(number(s.cyclesPerMm(r))) cy/mm")
            if let d = s.cyclesPerDegree(r) { parts.append("\(number(d)) cy/°") }
        }
        if r > 0 { parts.append("period \(number(1 / r)) px") }
        return parts.joined(separator: " · ")
    }

    /// Optics summary shown under the spectrum.
    static func opticsReadout(_ scale: PhysicalScale?) -> String? {
        guard let s = scale else { return nil }
        // iOS reports an effective pitch at the stream resolution, so binning is 1.
        let binning = abs(s.binning - 1) < 1e-6 ? "" : " (×\(number(s.binning)) binned)"
        var parts = [
            "pitch \(number(s.pitchMm * 1000)) µm\(binning)",
            "Nyquist \(number(s.nyquistCyclesPerMm)) cy/mm",
        ]
        if let cut = s.diffractionCutoffCyclesPerMm(wavelengthNm: 550) {
            parts.append("diffraction cutoff \(number(cut)) cy/mm @550 nm, f/\(number(s.optics.fNumber ?? 0))")
        }
        return parts.joined(separator: " · ")
    }

    /// Text written into the saved PNG's description.
    static func describe(_ s: UiState) -> String {
        let shape: String
        switch s.spec.shape {
        case .empty: shape = "none"
        case let .box(u, v): shape = "box ±\(number(u)) × ±\(number(v)) cy/px"
        case let .halfBox(u, v): shape = "half box \(number(u)) × ±\(number(v)) cy/px"
        case let .circle(r): shape = "circle r=\(number(r)) cy/px"
        case let .halfCircle(r): shape = "half circle r=\(number(r)) cy/px"
        case let .annulus(outer, width): shape = "annulus \(number(max(outer - width, 0)))–\(number(outer)) cy/px"
        case let .freeform(dabs): shape = "freeform, \(dabs.count) dabs"
        }
        let action = s.spec.passInside ? "pass" : "block"
        return "Fourier Filter Cam: \(action) \(shape); \(s.colorMode.label); \(s.outputMode.label)"
    }
}
