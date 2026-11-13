import Foundation

public enum ColorMode: String, CaseIterable, Identifiable, Sendable {
    /// Luminance only.
    case gray
    /// Each channel filtered with the same mask (a digital filter).
    case rgb
    /// Each channel filtered with the mask scaled by its wavelength, as a
    /// physical mask in the Fourier plane of a 4f system would act.
    case rgbOptical

    public var id: String { rawValue }

    public var label: String {
        switch self {
        case .gray: return "Grayscale"
        case .rgb: return "Color, digital (same mask per channel)"
        case .rgbOptical: return "Color, optical 4f (mask scales with wavelength)"
        }
    }
}

public enum OutputMode: String, CaseIterable, Identifiable, Sendable {
    /// Real part of the filtered field: what a linear digital filter produces.
    case real
    /// |filtered field|: the intensity-like view a coherent 4f system would show.
    case magnitude

    public var id: String { rawValue }

    public var label: String {
        switch self {
        case .real: return "Real part (digital filter)"
        case .magnitude: return "Magnitude (coherent 4f intensity)"
        }
    }
}

/// Everything the UI can change. Set as a whole; applied on the next frame.
public struct EngineSettings: Equatable, Sendable {
    public var spec: FilterSpec = .passAll
    public var colorMode: ColorMode = .gray
    public var outputMode: OutputMode = .real
    /// Gaussian sigma, in spectrum pixels, used to soften mask edges. 0 disables.
    public var edgeSoftnessPx: Double = 1.5
    /// Hann window before the display spectrum, suppressing the edge-discontinuity cross.
    public var windowSpectrum = true
    public var colormap: Colormap = .inferno

    public init() {}
}

/// Frequency-domain filtering of camera frames. Port of
/// `dsp/.../FourierEngine.kt` without OpenCV.
///
/// Frames come in and go out as BGRA8. The engine owns its working
/// buffers and reuses them; the filter is rebuilt only when the spec,
/// color mode, softness or frame size changes.
///
/// `spectrum` and `filter` must be called from one thread. `settings` and
/// `grid` may be read or written from any thread.
public final class FourierEngine: @unchecked Sendable {
    public let wavelengths: Wavelengths
    public let backend: DFTBackend

    private let lock = NSLock()
    private var _settings = EngineSettings()
    private var _grid: FrequencyGrid?

    public var settings: EngineSettings {
        get { locked { _settings } }
        set { locked { _settings = newValue } }
    }

    /// Padded spectrum grid for the current frame size, or nil before the first frame.
    public var grid: FrequencyGrid? { locked { _grid } }

    // Frame and grid geometry.
    private var frameWidth = 0
    private var frameHeight = 0
    private var gridWidth = 0
    private var gridHeight = 0
    private var dft: DFT2D?
    private var padColumns: [Int] = []
    private var padRows: [Int] = []

    // Working planes.
    private var plane = FloatBuffer(0)
    private var re = FloatBuffer(0)
    private var im = FloatBuffer(0)
    private var shifted = FloatBuffer(0)
    private var hann = FloatBuffer(0)
    private var outPlanes = [FloatBuffer(0), FloatBuffer(0), FloatBuffer(0)]

    // Filter state.
    private var builtSpec: FilterSpec?
    private var builtMode: ColorMode?
    private var builtSoftness = Double.nan
    private var maskBuffer = FloatBuffer(0)
    private var maskScratch = FloatBuffer(0)
    private var maskCentered = FloatBuffer(0)
    private var maskOverlay = FloatBuffer(0)
    private var filters = [FloatBuffer(0), FloatBuffer(0), FloatBuffer(0)]
    private var filterCount = 1

    // Moving averages of the display range, to stop flicker.
    private let spectrumRange = RunningRange(minSpan: 1e-3)
    // Floor of one 8-bit level keeps a flat image flat instead of stretching noise.
    private let imageRange = RunningRange(minSpan: 1)

    public init(wavelengths: Wavelengths = .bayerTypical, backend: DFTBackend = .platformDefault) {
        self.wavelengths = wavelengths
        self.backend = backend
    }

    private func locked<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }

    // MARK: Public API

    /// Log-magnitude spectrum of the frame's luminance, DC centered,
    /// colormapped, blocked regions dimmed. `dst` is resized to the grid.
    public func spectrum(_ src: PixelView, into dst: PixelImage) {
        let s = prepare(width: src.width, height: src.height)
        let gw = gridWidth
        let gh = gridHeight
        let n = gw * gh
        loadPlane(src, channel: nil)
        padPlane()
        let re = self.re.p
        let im = self.im.p
        if s.windowSpectrum {
            let h = hann.p
            for i in 0..<n { re[i] *= h[i] }
        }
        dft!.forward(re: re, im: im)

        let out = shifted.p
        let hw = gw / 2
        let hh = gh / 2
        var lo = Float.infinity
        var hi = -Float.infinity
        for y in 0..<gh {
            let dy = ((y + hh) % gh) * gw
            for x in 0..<gw {
                let i = y * gw + x
                let m = log(1 + (re[i] * re[i] + im[i] * im[i]).squareRoot())
                out[dy + (x + hw) % gw] = m
                if m < lo { lo = m }
                if m > hi { hi = m }
            }
        }
        spectrumRange.update(Double(lo), Double(hi))
        let offset = Float(spectrumRange.lo)
        let k = Float(255 / spectrumRange.span)
        let overlay = maskOverlay.p

        dst.resize(width: gw, height: gh)
        let table = s.colormap.table
        dst.pixels.withUnsafeMutableBufferPointer { px in
            table.withUnsafeBufferPointer { lut in
                for i in 0..<n {
                    let level = Int(ImageOps.saturateU8((out[i] - offset) * overlay[i] * k))
                    px[4 * i] = lut[3 * level + 2]
                    px[4 * i + 1] = lut[3 * level + 1]
                    px[4 * i + 2] = lut[3 * level]
                    px[4 * i + 3] = 255
                }
            }
        }
    }

    /// Filtered frame at the input size.
    public func filter(_ src: PixelView, into dst: PixelImage) {
        let s = prepare(width: src.width, height: src.height)
        let channels = s.colorMode == .gray ? 1 : 3
        for c in 0..<channels {
            loadPlane(src, channel: s.colorMode == .gray ? nil : c)
            filterPlane(filters[filterCount == 1 ? 0 : c], into: outPlanes[c], output: s.outputMode)
        }

        // One range for all channels so the color balance survives.
        let n = frameWidth * frameHeight
        var lo = Float.infinity
        var hi = -Float.infinity
        for c in 0..<channels {
            let p = outPlanes[c].p
            for i in 0..<n {
                if p[i] < lo { lo = p[i] }
                if p[i] > hi { hi = p[i] }
            }
        }
        imageRange.update(Double(lo), Double(hi))
        let scale = Float(255 / imageRange.span)
        let offset = Float(-imageRange.lo) * scale

        dst.resize(width: frameWidth, height: frameHeight)
        let p0 = outPlanes[0].p
        let p1 = outPlanes[channels == 1 ? 0 : 1].p
        let p2 = outPlanes[channels == 1 ? 0 : 2].p
        dst.pixels.withUnsafeMutableBufferPointer { px in
            for i in 0..<n {
                px[4 * i] = ImageOps.saturateU8(p2[i] * scale + offset)
                px[4 * i + 1] = ImageOps.saturateU8(p1[i] * scale + offset)
                px[4 * i + 2] = ImageOps.saturateU8(p0[i] * scale + offset)
                px[4 * i + 3] = 255
            }
        }
    }

    public func spectrum(_ src: PixelImage, into dst: PixelImage) {
        src.withView { spectrum($0, into: dst) }
    }

    public func filter(_ src: PixelImage, into dst: PixelImage) {
        src.withView { filter($0, into: dst) }
    }

    /// Centered-layout transmission of the reference-wavelength mask.
    public func maskCenteredValues() -> [Float] { maskCentered.array }

    /// Float result of the last `filter` call for channel `c` (0 = R), before display scaling.
    func lastFilteredPlane(_ c: Int) -> [Float] { outPlanes[c].array }

    // MARK: Internals

    private func prepare(width w: Int, height h: Int) -> EngineSettings {
        if w != frameWidth || h != frameHeight || dft == nil {
            frameWidth = w
            frameHeight = h
            let g = FrequencyGrid.forFrame(width: w, height: h, sizing: backend.sizing)
            gridWidth = g.width
            gridHeight = g.height
            let n = g.width * g.height
            dft = backend.make(width: g.width, height: g.height)
            padColumns = (w..<g.width).map { ImageOps.reflect101($0, w) }
            padRows = (h..<g.height).map { ImageOps.reflect101($0, h) }
            plane = FloatBuffer(w * h)
            re = FloatBuffer(n)
            im = FloatBuffer(n)
            shifted = FloatBuffer(n)
            hann = FloatBuffer(n)
            ImageOps.hanningWindow(width: g.width, height: g.height, into: hann.p)
            outPlanes = (0..<3).map { _ in FloatBuffer(w * h) }
            maskBuffer = FloatBuffer(n)
            maskScratch = FloatBuffer(n)
            maskCentered = FloatBuffer(n)
            maskOverlay = FloatBuffer(n)
            filters = (0..<3).map { _ in FloatBuffer(n) }
            builtSpec = nil
            spectrumRange.reset()
            imageRange.reset()
            locked { _grid = g }
        }
        let s = settings
        if s.colorMode != builtMode { imageRange.reset() }
        if s.spec != builtSpec || s.colorMode != builtMode || s.edgeSoftnessPx != builtSoftness {
            buildFilter(s)
        }
        return s
    }

    private func buildFilter(_ s: EngineSettings) {
        let g = FrequencyGrid(width: gridWidth, height: gridHeight)
        let n = g.width * g.height
        let scales: [Double]
        if s.colorMode == .rgbOptical {
            let ref = wavelengths.referenceNm
            scales = [wavelengths.redNm / ref, wavelengths.greenNm / ref, wavelengths.blueNm / ref]
        } else {
            scales = [1]
        }
        for (i, scale) in scales.enumerated() {
            MaskRasterizer.rasterize(s.spec, grid: g, into: maskBuffer.p, frequencyScale: scale)
            if s.edgeSoftnessPx > 0 {
                ImageOps.gaussianBlur(maskBuffer.p, tmp: maskScratch.p, width: g.width, height: g.height, sigma: s.edgeSoftnessPx)
            }
            if scale == 1 { maskCentered.p.update(from: maskBuffer.p, count: n) }
            // Centered -> DFT layout (same swap for even dims).
            ImageOps.fftShift(maskBuffer.p, into: filters[i].p, width: g.width, height: g.height)
        }
        filterCount = scales.count

        // Dim blocked regions in the spectrum view instead of hiding them.
        let floor = Self.overlayFloor
        let mc = maskCentered.p
        let mo = maskOverlay.p
        for i in 0..<n { mo[i] = mc[i] * (1 - floor) + floor }

        builtSpec = s.spec
        builtMode = s.colorMode
        builtSoftness = s.edgeSoftnessPx
    }

    /// Loads luminance (channel nil) or channel c (0 = R, 1 = G, 2 = B) into `plane`.
    private func loadPlane(_ src: PixelView, channel: Int?) {
        let w = frameWidth
        let dst = plane.p
        for y in 0..<frameHeight {
            let row = (src.base + y * src.bytesPerRow).assumingMemoryBound(to: UInt8.self)
            let out = dst + y * w
            if let c = channel {
                let offset = 2 - c // BGRA
                for x in 0..<w { out[x] = Float(row[4 * x + offset]) }
            } else {
                // OpenCV RGB2GRAY fixed point: 0.299 R + 0.587 G + 0.114 B, rounded.
                for x in 0..<w {
                    let b = Int(row[4 * x])
                    let g = Int(row[4 * x + 1])
                    let r = Int(row[4 * x + 2])
                    out[x] = Float((r * 4899 + g * 9617 + b * 1868 + 8192) >> 14)
                }
            }
        }
    }

    /// Copies `plane` into the top-left of the grid, mirror-padding the rest, and zeroes `im`.
    private func padPlane() {
        let w = frameWidth
        let h = frameHeight
        let gw = gridWidth
        let src = plane.p
        let dst = re.p
        for y in 0..<gridHeight {
            let sy = y < h ? y : padRows[y - h]
            let srcRow = src + sy * w
            let dstRow = dst + y * gw
            dstRow.update(from: srcRow, count: w)
            for x in w..<gw { dstRow[x] = srcRow[padColumns[x - w]] }
        }
        im.p.update(repeating: 0, count: gw * gridHeight)
    }

    /// Filters `plane` and writes the cropped float result to `out`.
    private func filterPlane(_ filter: FloatBuffer, into out: FloatBuffer, output: OutputMode) {
        padPlane()
        let n = gridWidth * gridHeight
        let re = self.re.p
        let im = self.im.p
        dft!.forward(re: re, im: im)
        let f = filter.p
        for i in 0..<n {
            re[i] *= f[i]
            im[i] *= f[i]
        }
        dft!.inverse(re: re, im: im)

        // A point-symmetric real mask keeps the spectrum Hermitian, so the
        // imaginary part is rounding noise and |re| is the magnitude.
        let symmetric = builtSpec?.isPointSymmetric ?? true
        let scale = 1 / Float(n)
        let w = frameWidth
        let gw = gridWidth
        let dst = out.p
        for y in 0..<frameHeight {
            for x in 0..<w {
                let i = y * gw + x
                let r = re[i] * scale
                switch output {
                case .real:
                    dst[y * w + x] = r
                case .magnitude:
                    if symmetric {
                        dst[y * w + x] = abs(r)
                    } else {
                        let q = im[i] * scale
                        dst[y * w + x] = (r * r + q * q).squareRoot()
                    }
                }
            }
        }
    }

    private static let overlayFloor: Float = 0.25
}

final class RunningRange {
    private let minSpan: Double
    private let alpha: Double
    private(set) var lo = 0.0
    private var hi = 1.0
    private var primed = false

    init(minSpan: Double, alpha: Double = 0.2) {
        self.minSpan = minSpan
        self.alpha = alpha
    }

    var span: Double { max(hi - lo, minSpan) }

    func update(_ newLo: Double, _ newHi: Double) {
        if !primed {
            lo = newLo
            hi = newHi
            primed = true
        } else {
            lo += alpha * (newLo - lo)
            hi += alpha * (newHi - hi)
        }
    }

    func reset() { primed = false }
}
