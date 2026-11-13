import Foundation
import XCTest
@testable import FourierDSP

final class DFTTests: XCTestCase {
    /// Direct O(n^2) 1D DFT as ground truth.
    func naive(_ re: [Double], _ im: [Double], sign: Double) -> ([Double], [Double]) {
        let n = re.count
        var outRe = [Double](repeating: 0, count: n)
        var outIm = outRe
        for k in 0..<n {
            for j in 0..<n {
                let a = sign * 2 * Double.pi * Double(j * k) / Double(n)
                outRe[k] += re[j] * cos(a) - im[j] * sin(a)
                outIm[k] += re[j] * sin(a) + im[j] * cos(a)
            }
        }
        return (outRe, outIm)
    }

    func testMixedRadixMatchesNaive() {
        var rng = SystemRandomNumberGenerator()
        for n in [2, 6, 7, 10, 14, 30, 48, 120] {
            let re = (0..<n).map { _ in Double.random(in: -1...1, using: &rng) }
            let im = (0..<n).map { _ in Double.random(in: -1...1, using: &rng) }
            var r = re
            var i = im
            fft1d(&r, &i, sign: -1)
            let (wr, wi) = naive(re, im, sign: -1)
            for k in 0..<n {
                XCTAssertEqual(r[k], wr[k], accuracy: 1e-9, "n=\(n) k=\(k)")
                XCTAssertEqual(i[k], wi[k], accuracy: 1e-9, "n=\(n) k=\(k)")
            }
        }
    }

    func testReferenceRoundTrip() {
        let w = 30
        let h = 20
        let dft = DFTBackend.reference.make(width: w, height: h)
        var re = (0..<(w * h)).map { Float(($0 * 37) % 101) }
        var im = [Float](repeating: 0, count: w * h)
        let original = re
        re.withUnsafeMutableBufferPointer { r in
            im.withUnsafeMutableBufferPointer { i in
                dft.forward(re: r.baseAddress!, im: i.baseAddress!)
                dft.inverse(re: r.baseAddress!, im: i.baseAddress!)
            }
        }
        for k in re.indices { XCTAssertEqual(re[k] / Float(w * h), original[k], accuracy: 1e-3) }
    }

    #if canImport(Accelerate)
    func testAccelerateMatchesReference() {
        for (w, h) in [(16, 16), (48, 80), (240, 80), (160, 96)] {
            var rng = SystemRandomNumberGenerator()
            let n = w * h
            let srcRe = (0..<n).map { _ in Float.random(in: -1...1, using: &rng) }
            let srcIm = (0..<n).map { _ in Float.random(in: -1...1, using: &rng) }
            var aRe = srcRe, aIm = srcIm, bRe = srcRe, bIm = srcIm
            let fast = DFTBackend.accelerate.make(width: w, height: h)
            let ref = DFTBackend.reference.make(width: w, height: h)
            aRe.withUnsafeMutableBufferPointer { r in
                aIm.withUnsafeMutableBufferPointer { i in fast.forward(re: r.baseAddress!, im: i.baseAddress!) }
            }
            bRe.withUnsafeMutableBufferPointer { r in
                bIm.withUnsafeMutableBufferPointer { i in ref.forward(re: r.baseAddress!, im: i.baseAddress!) }
            }
            var worst: Float = 0
            for k in 0..<n { worst = max(worst, abs(aRe[k] - bRe[k]), abs(aIm[k] - bIm[k])) }
            XCTAssertLessThan(worst, 1e-2, "\(w)x\(h) forward")

            aRe.withUnsafeMutableBufferPointer { r in
                aIm.withUnsafeMutableBufferPointer { i in fast.inverse(re: r.baseAddress!, im: i.baseAddress!) }
            }
            worst = 0
            for k in 0..<n {
                worst = max(worst, abs(aRe[k] / Float(n) - srcRe[k]), abs(aIm[k] / Float(n) - srcIm[k]))
            }
            XCTAssertLessThan(worst, 1e-4, "\(w)x\(h) round trip")
        }
    }
    #endif
}
