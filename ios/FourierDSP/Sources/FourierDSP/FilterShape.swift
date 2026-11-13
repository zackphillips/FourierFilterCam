import Foundation

/// One disk of a freeform mask, in cycles/pixel.
public struct Dab: Equatable, Sendable {
    public var u: Double
    public var v: Double
    public var radius: Double

    public init(u: Double, v: Double, radius: Double) {
        self.u = u
        self.v = v
        self.radius = radius
    }
}

/// A region of frequency space, in cycles/pixel with DC at the origin.
/// Whether the region passes or blocks is decided by `FilterSpec`.
///
/// Mirrors `dsp/.../FilterShape.kt`; `fixtures/dsp.json` keeps the two in step.
public enum FilterShape: Equatable, Sendable {
    case empty
    /// Rectangle centered on DC.
    case box(halfU: Double, halfV: Double)
    /// Right half (u >= 0) of a DC-centered rectangle. A single-sideband filter.
    case halfBox(halfU: Double, halfV: Double)
    /// Disk centered on DC: a circular pupil when passing, a DC stop when blocking.
    case circle(radius: Double)
    /// Left half (u <= 0) of a DC-centered disk.
    case halfCircle(radius: Double)
    /// Ring centered on DC with outer radius `outer` and radial `width`.
    case annulus(outer: Double, width: Double)
    /// Union of disks painted with a finger.
    case freeform([Dab])

    /// True when the region is point-symmetric about DC, so a real input
    /// stays real after filtering.
    public var isPointSymmetric: Bool {
        switch self {
        case .halfBox, .halfCircle: return false
        case .freeform(let dabs): return dabs.isEmpty
        default: return true
        }
    }

    public func contains(u: Double, v: Double) -> Bool {
        switch self {
        case .empty:
            return false
        case let .box(halfU, halfV):
            return abs(u) <= halfU && abs(v) <= halfV
        case let .halfBox(halfU, halfV):
            return u >= 0 && u <= halfU && abs(v) <= halfV
        case let .circle(radius):
            return hypot(u, v) <= radius
        case let .halfCircle(radius):
            return u <= 0 && hypot(u, v) <= radius
        case let .annulus(outer, width):
            let r = hypot(u, v)
            return r <= outer && r >= max(outer - width, 0)
        case let .freeform(dabs):
            return dabs.contains { hypot(u - $0.u, v - $0.v) <= $0.radius }
        }
    }
}

/// A complete filter: a region plus whether the region passes or blocks.
public struct FilterSpec: Equatable, Sendable {
    public var shape: FilterShape
    public var passInside: Bool

    public init(shape: FilterShape, passInside: Bool) {
        self.shape = shape
        self.passInside = passInside
    }

    public var isPointSymmetric: Bool { shape.isPointSymmetric }

    public func transmission(u: Double, v: Double) -> Float {
        shape.contains(u: u, v: v) == passInside ? 1 : 0
    }

    public static let passAll = FilterSpec(shape: .empty, passInside: false)
}

/// The shape families offered in the UI.
public enum FilterType: String, CaseIterable, Identifiable, Sendable {
    case freeform, box, halfBox, circle, halfCircle, annulus

    public var id: String { rawValue }

    public var label: String {
        switch self {
        case .freeform: return "Freeform"
        case .box: return "Box"
        case .halfBox: return "Half box"
        case .circle: return "Circle"
        case .halfCircle: return "Half circle"
        case .annulus: return "Annulus"
        }
    }

    public var passInsideByDefault: Bool { self != .freeform }

    /// Shape whose boundary passes through the touched frequency (u, v).
    public func shapeThrough(u: Double, v: Double, annulusWidth: Double) -> FilterShape {
        let r = hypot(u, v)
        switch self {
        case .freeform: return .freeform([])
        case .box: return .box(halfU: abs(u), halfV: abs(v))
        case .halfBox: return .halfBox(halfU: abs(u), halfV: abs(v))
        case .circle: return .circle(radius: r)
        case .halfCircle: return .halfCircle(radius: r)
        case .annulus: return .annulus(outer: r, width: annulusWidth)
        }
    }
}
