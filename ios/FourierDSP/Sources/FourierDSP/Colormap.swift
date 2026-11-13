import Foundation

/// Spectrum colormaps. Tables are OpenCV's, so spectra look the same as on Android.
public enum Colormap: String, CaseIterable, Identifiable, Sendable {
    case inferno, viridis, magma, turbo, jet

    public var id: String { rawValue }

    public var label: String {
        switch self {
        case .inferno: return "Inferno"
        case .viridis: return "Viridis"
        case .magma: return "Magma"
        case .turbo: return "Turbo"
        case .jet: return "Jet (classic)"
        }
    }

    /// 256 RGB triples, 768 bytes.
    public var table: [UInt8] { Self.tables[self]! }

    private static let tables: [Colormap: [UInt8]] = {
        func decode(_ s: String) -> [UInt8] {
            let bytes = [UInt8](Data(base64Encoded: s)!)
            precondition(bytes.count == 768)
            return bytes
        }
        return [
            .inferno: decode(ColormapTables.inferno),
            .viridis: decode(ColormapTables.viridis),
            .magma: decode(ColormapTables.magma),
            .turbo: decode(ColormapTables.turbo),
            .jet: decode(ColormapTables.jet),
        ]
    }()
}
