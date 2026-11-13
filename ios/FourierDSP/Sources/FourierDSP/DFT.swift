import Foundation
#if canImport(Accelerate)
import Accelerate
#endif

/// In-place 2D complex DFT over split real/imaginary planes, row-major.
///
/// `forward` uses exp(-2 pi i k n / N). `inverse` uses the positive exponent
/// and is unscaled: forward then inverse multiplies by width * height.
/// Instances own scratch buffers and are not thread-safe.
public protocol DFT2D: AnyObject {
    var width: Int { get }
    var height: Int { get }
    func forward(re: UnsafeMutablePointer<Float>, im: UnsafeMutablePointer<Float>)
    func inverse(re: UnsafeMutablePointer<Float>, im: UnsafeMutablePointer<Float>)
}

/// A DFT implementation plus the lengths it supports.
public struct DFTBackend: Sendable {
    public let name: String
    public let sizing: FFTSizing
    private let factory: @Sendable (Int, Int) -> DFT2D

    public init(name: String, sizing: FFTSizing, factory: @escaping @Sendable (Int, Int) -> DFT2D) {
        self.name = name
        self.sizing = sizing
        self.factory = factory
    }

    public func make(width: Int, height: Int) -> DFT2D { factory(width, height) }

    /// Portable mixed-radix DFT in double precision. Slow; for tests and Linux.
    public static let reference = DFTBackend(name: "reference", sizing: .fiveSmooth) {
        ReferenceDFT2D(width: $0, height: $1)
    }

    #if canImport(Accelerate)
    /// vDSP row/column DFTs with a transpose between passes.
    public static let accelerate = DFTBackend(name: "accelerate", sizing: .accelerate) {
        AccelerateDFT2D(width: $0, height: $1)
    }

    public static let platformDefault = accelerate
    #else
    public static let platformDefault = reference
    #endif
}

// MARK: - Reference

final class ReferenceDFT2D: DFT2D {
    let width: Int
    let height: Int
    private var bufRe: [Double]
    private var bufIm: [Double]

    init(width: Int, height: Int) {
        self.width = width
        self.height = height
        bufRe = [Double](repeating: 0, count: max(width, height))
        bufIm = bufRe
    }

    func forward(re: UnsafeMutablePointer<Float>, im: UnsafeMutablePointer<Float>) {
        transform(re: re, im: im, sign: -1)
    }

    func inverse(re: UnsafeMutablePointer<Float>, im: UnsafeMutablePointer<Float>) {
        transform(re: re, im: im, sign: 1)
    }

    private func transform(re: UnsafeMutablePointer<Float>, im: UnsafeMutablePointer<Float>, sign: Double) {
        let w = width
        let h = height
        var rowRe = [Double](repeating: 0, count: w)
        var rowIm = rowRe
        for y in 0..<h {
            for x in 0..<w { rowRe[x] = Double(re[y * w + x]); rowIm[x] = Double(im[y * w + x]) }
            fft1d(&rowRe, &rowIm, sign: sign)
            for x in 0..<w { re[y * w + x] = Float(rowRe[x]); im[y * w + x] = Float(rowIm[x]) }
        }
        var colRe = [Double](repeating: 0, count: h)
        var colIm = colRe
        for x in 0..<w {
            for y in 0..<h { colRe[y] = Double(re[y * w + x]); colIm[y] = Double(im[y * w + x]) }
            fft1d(&colRe, &colIm, sign: sign)
            for y in 0..<h { re[y * w + x] = Float(colRe[y]); im[y * w + x] = Float(colIm[y]) }
        }
    }
}

/// Recursive mixed-radix DFT. Factors of 2, 3 and 5 are split off; any
/// remaining factor falls back to a direct O(n^2) DFT.
func fft1d(_ re: inout [Double], _ im: inout [Double], sign: Double) {
    let n = re.count
    if n <= 1 { return }
    let p = [2, 3, 5].first { n % $0 == 0 } ?? n
    if p == n {
        var outRe = [Double](repeating: 0, count: n)
        var outIm = outRe
        for k in 0..<n {
            var sr = 0.0
            var si = 0.0
            for j in 0..<n {
                let a = sign * 2 * Double.pi * Double((j * k) % n) / Double(n)
                let c = cos(a)
                let s = sin(a)
                sr += re[j] * c - im[j] * s
                si += re[j] * s + im[j] * c
            }
            outRe[k] = sr
            outIm[k] = si
        }
        re = outRe
        im = outIm
        return
    }
    let m = n / p
    // Decimation in time: sub-sequence r holds x[j * p + r].
    var subRe = [[Double]](repeating: [Double](repeating: 0, count: m), count: p)
    var subIm = subRe
    for r in 0..<p {
        for j in 0..<m {
            subRe[r][j] = re[j * p + r]
            subIm[r][j] = im[j * p + r]
        }
        fft1d(&subRe[r], &subIm[r], sign: sign)
    }
    for q in 0..<m {
        for s in 0..<p {
            let k = q + m * s
            var sr = 0.0
            var si = 0.0
            for r in 0..<p {
                let a = sign * 2 * Double.pi * Double((r * k) % n) / Double(n)
                let c = cos(a)
                let sn = sin(a)
                sr += subRe[r][q] * c - subIm[r][q] * sn
                si += subRe[r][q] * sn + subIm[r][q] * c
            }
            re[k] = sr
            im[k] = si
        }
    }
}

// MARK: - Accelerate

#if canImport(Accelerate)
final class AccelerateDFT2D: DFT2D {
    let width: Int
    let height: Int
    private let rowForward: vDSP_DFT_Setup
    private let rowInverse: vDSP_DFT_Setup
    private let colForward: vDSP_DFT_Setup
    private let colInverse: vDSP_DFT_Setup
    private let aRe: UnsafeMutablePointer<Float>
    private let aIm: UnsafeMutablePointer<Float>
    private let bRe: UnsafeMutablePointer<Float>
    private let bIm: UnsafeMutablePointer<Float>

    init(width: Int, height: Int) {
        precondition(FFTSizing.accelerate.isSupported(width) && FFTSizing.accelerate.isSupported(height),
                     "vDSP cannot transform \(width)x\(height)")
        self.width = width
        self.height = height
        guard
            let rf = vDSP_DFT_zop_CreateSetup(nil, vDSP_Length(width), vDSP_DFT_Direction.FORWARD),
            let ri = vDSP_DFT_zop_CreateSetup(rf, vDSP_Length(width), vDSP_DFT_Direction.INVERSE),
            let cf = vDSP_DFT_zop_CreateSetup(nil, vDSP_Length(height), vDSP_DFT_Direction.FORWARD),
            let ci = vDSP_DFT_zop_CreateSetup(cf, vDSP_Length(height), vDSP_DFT_Direction.INVERSE)
        else {
            fatalError("vDSP_DFT_zop_CreateSetup failed for \(width)x\(height)")
        }
        rowForward = rf
        rowInverse = ri
        colForward = cf
        colInverse = ci
        let n = width * height
        aRe = .allocate(capacity: n)
        aIm = .allocate(capacity: n)
        bRe = .allocate(capacity: n)
        bIm = .allocate(capacity: n)
    }

    deinit {
        vDSP_DFT_DestroySetup(rowForward)
        vDSP_DFT_DestroySetup(rowInverse)
        vDSP_DFT_DestroySetup(colForward)
        vDSP_DFT_DestroySetup(colInverse)
        aRe.deallocate()
        aIm.deallocate()
        bRe.deallocate()
        bIm.deallocate()
    }

    func forward(re: UnsafeMutablePointer<Float>, im: UnsafeMutablePointer<Float>) {
        transform(re: re, im: im, rows: rowForward, cols: colForward)
    }

    func inverse(re: UnsafeMutablePointer<Float>, im: UnsafeMutablePointer<Float>) {
        transform(re: re, im: im, rows: rowInverse, cols: colInverse)
    }

    private func transform(re: UnsafeMutablePointer<Float>, im: UnsafeMutablePointer<Float>, rows: vDSP_DFT_Setup, cols: vDSP_DFT_Setup) {
        let w = width
        let h = height
        // Rows: (re, im) -> a, h x w.
        for y in 0..<h {
            vDSP_DFT_Execute(rows, re + y * w, im + y * w, aRe + y * w, aIm + y * w)
        }
        // a (h x w) -> b (w x h). vDSP_mtrans takes C's rows, then C's columns.
        vDSP_mtrans(aRe, 1, bRe, 1, vDSP_Length(w), vDSP_Length(h))
        vDSP_mtrans(aIm, 1, bIm, 1, vDSP_Length(w), vDSP_Length(h))
        // Columns are now rows of b: b -> a, w x h.
        for x in 0..<w {
            vDSP_DFT_Execute(cols, bRe + x * h, bIm + x * h, aRe + x * h, aIm + x * h)
        }
        // a (w x h) -> (re, im) (h x w).
        vDSP_mtrans(aRe, 1, re, 1, vDSP_Length(h), vDSP_Length(w))
        vDSP_mtrans(aIm, 1, im, 1, vDSP_Length(h), vDSP_Length(w))
    }
}
#endif
