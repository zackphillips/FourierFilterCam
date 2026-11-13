/// Non-owning view of BGRA8 pixels (the iOS camera's native layout).
public struct PixelView {
    public let base: UnsafeRawPointer
    public let width: Int
    public let height: Int
    public let bytesPerRow: Int

    public init(base: UnsafeRawPointer, width: Int, height: Int, bytesPerRow: Int) {
        precondition(bytesPerRow >= width * 4, "bytesPerRow \(bytesPerRow) too small for width \(width)")
        self.base = base
        self.width = width
        self.height = height
        self.bytesPerRow = bytesPerRow
    }
}

/// Owning, tightly packed BGRA8 image. Reused across frames; `resize`
/// reallocates only when the size changes.
public final class PixelImage {
    public private(set) var width: Int
    public private(set) var height: Int
    public var pixels: [UInt8]

    public init(width: Int = 0, height: Int = 0) {
        self.width = width
        self.height = height
        pixels = [UInt8](repeating: 0, count: width * height * 4)
    }

    public var bytesPerRow: Int { width * 4 }

    public func resize(width: Int, height: Int) {
        guard width != self.width || height != self.height else { return }
        self.width = width
        self.height = height
        pixels = [UInt8](repeating: 0, count: width * height * 4)
    }

    public func withView<R>(_ body: (PixelView) throws -> R) rethrows -> R {
        try pixels.withUnsafeBytes { raw in
            try body(PixelView(base: raw.baseAddress!, width: width, height: height, bytesPerRow: width * 4))
        }
    }

    /// Byte offsets of each channel within a BGRA pixel.
    public enum Channel: Int {
        case blue = 0, green = 1, red = 2, alpha = 3
    }

    public subscript(x: Int, y: Int, channel: Channel) -> UInt8 {
        get { pixels[(y * width + x) * 4 + channel.rawValue] }
        set { pixels[(y * width + x) * 4 + channel.rawValue] = newValue }
    }
}

/// Heap float buffer with a stable address, for the engine's working planes.
final class FloatBuffer {
    let count: Int
    let p: UnsafeMutablePointer<Float>

    init(_ count: Int) {
        self.count = count
        p = .allocate(capacity: max(count, 1))
        p.initialize(repeating: 0, count: max(count, 1))
    }

    deinit { p.deallocate() }

    var array: [Float] { Array(UnsafeBufferPointer(start: p, count: count)) }
}
