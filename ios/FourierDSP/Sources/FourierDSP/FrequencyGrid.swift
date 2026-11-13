/// Geometry of a centered (fftshifted) spectrum.
///
/// DC sits at pixel (width / 2, height / 2). Frequencies are in cycles per
/// frame pixel, so the grid spans [-0.5, 0.5) on both axes regardless of
/// aspect ratio. Dimensions are even so fftshift and ifftshift are the same
/// quadrant swap.
public struct FrequencyGrid: Equatable, Sendable {
    public let width: Int
    public let height: Int

    public init(width: Int, height: Int) {
        precondition(width > 0 && height > 0, "Grid must be non-empty: \(width)x\(height)")
        precondition(width % 2 == 0 && height % 2 == 0, "Grid dims must be even: \(width)x\(height)")
        self.width = width
        self.height = height
    }

    public var centerX: Int { width / 2 }
    public var centerY: Int { height / 2 }

    /// Horizontal frequency (cycles/pixel) at spectrum column `x`.
    public func u(_ x: Double) -> Double { (x - Double(centerX)) / Double(width) }

    /// Vertical frequency (cycles/pixel) at spectrum row `y`.
    public func v(_ y: Double) -> Double { (y - Double(centerY)) / Double(height) }

    public func x(_ u: Double) -> Double { u * Double(width) + Double(centerX) }

    public func y(_ v: Double) -> Double { v * Double(height) + Double(centerY) }

    /// Grid for an input frame, padded up to sizes `sizing` transforms quickly.
    public static func forFrame(width: Int, height: Int, sizing: FFTSizing = .fiveSmooth) -> FrequencyGrid {
        FrequencyGrid(width: sizing.nextFastEven(width), height: sizing.nextFastEven(height))
    }
}

/// Which DFT lengths a backend handles.
public enum FFTSizing: Sendable {
    /// Even, prime factors 2, 3 and 5 only. What OpenCV (Android) and the
    /// reference DFT use.
    case fiveSmooth

    /// f * 2^n with f in {1, 3, 5, 15} and n >= 4: the lengths
    /// `vDSP_DFT_zop_CreateSetup` accepts. n >= 4 is stricter than the
    /// documented minimum, so every length here is valid for both the
    /// complex and real-input vDSP setups.
    case accelerate

    public func isSupported(_ n: Int) -> Bool {
        guard n > 0, n % 2 == 0 else { return false }
        switch self {
        case .fiveSmooth:
            var m = n
            for p in [2, 3, 5] { while m % p == 0 { m /= p } }
            return m == 1
        case .accelerate:
            var m = n
            var twos = 0
            while m % 2 == 0 { m /= 2; twos += 1 }
            return twos >= 4 && [1, 3, 5, 15].contains(m)
        }
    }

    /// Smallest supported length >= `size`.
    public func nextFastEven(_ size: Int) -> Int {
        precondition(size > 0, "size must be positive: \(size)")
        var n = size % 2 == 0 ? size : size + 1
        while !isSupported(n) { n += 2 }
        return n
    }
}
