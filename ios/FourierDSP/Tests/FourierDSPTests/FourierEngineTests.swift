import Foundation
import XCTest
@testable import FourierDSP

/// Port of the Android engine tests on synthetic gratings. 240 x 80 is a
/// valid length for every backend (240 = 15 * 16, 80 = 5 * 16), and every
/// grating has a whole number of cycles per frame, so there is no padding
/// and no leakage. Each test runs on every available backend.
final class FourierEngineTests: XCTestCase {
    static let W = 240
    static let H = 80

    var backends: [DFTBackend] {
        #if canImport(Accelerate)
        return [.reference, .accelerate]
        #else
        return [.reference]
        #endif
    }

    /// BGRA frame with value(x, y) in every color channel.
    func frame(width: Int = FourierEngineTests.W, height: Int = FourierEngineTests.H, _ value: (Int, Int) -> Double) -> PixelImage {
        let img = PixelImage(width: width, height: height)
        for y in 0..<height {
            for x in 0..<width {
                let v = UInt8(max(0, min(255, value(x, y).rounded())))
                img[x, y, .blue] = v
                img[x, y, .green] = v
                img[x, y, .red] = v
                img[x, y, .alpha] = 255
            }
        }
        return img
    }

    /// Grating with `cycles` periods across the frame width.
    func grating(_ cycles: Int, _ amplitude: Double) -> (Int, Int) -> Double {
        { x, _ in amplitude * cos(2 * Double.pi * Double(cycles) * Double(x) / Double(Self.W)) }
    }

    func channel(_ img: PixelImage, _ c: PixelImage.Channel) -> [Double] {
        var out: [Double] = []
        out.reserveCapacity(img.width * img.height)
        for y in 0..<img.height { for x in 0..<img.width { out.append(Double(img[x, y, c])) } }
        return out
    }

    func std(_ v: [Double]) -> Double {
        let mean = v.reduce(0, +) / Double(v.count)
        return (v.map { ($0 - mean) * ($0 - mean) }.reduce(0, +) / Double(v.count)).squareRoot()
    }

    func engine(_ backend: DFTBackend, _ configure: (inout EngineSettings) -> Void = { _ in }) -> FourierEngine {
        let e = FourierEngine(backend: backend)
        var s = EngineSettings()
        s.edgeSoftnessPx = 0
        configure(&s)
        e.settings = s
        return e
    }

    func testPassAllReproducesTheInput() {
        let input = frame { x, y in Double((x * 255 / (Self.W - 1) + y) % 256) }
        for b in backends {
            let out = PixelImage()
            engine(b).filter(input, into: out)
            let err = zip(channel(input, .red), channel(out, .red)).map { abs($0 - $1) }.max()!
            XCTAssertLessThanOrEqual(err, 1, "\(b.name): max error \(err)")
        }
    }

    func testLowPassCircleRemovesTheFineGrating() {
        let coarse = grating(6, 60)
        let fine = grating(60, 60)
        let input = frame { x, y in 128 + coarse(x, y) + fine(x, y) }
        let expected = channel(frame { x, y in 128 + coarse(x, y) }, .red)
        // Output is range-normalized; normalize the expectation the same way.
        let lo = expected.min()!
        let hi = expected.max()!
        let want = expected.map { ($0 - lo) / (hi - lo) * 255 }
        for b in backends {
            // 6/240 = 0.025 cy/px passes, 60/240 = 0.25 cy/px does not.
            let e = engine(b) { $0.spec = FilterSpec(shape: .circle(radius: 0.1), passInside: true) }
            let out = PixelImage()
            e.filter(input, into: out)
            let err = zip(channel(out, .red), want).map { abs($0 - $1) }.max()!
            XCTAssertLessThanOrEqual(err, 3, "\(b.name): max error \(err)")
        }
    }

    func testSpectrumPeakLandsAtTheGratingFrequency() {
        let input = frame { x, y in 128 + self.grating(24, 100)(x, y) }
        for b in backends {
            let e = engine(b) { $0.windowSpectrum = false }
            let out = PixelImage()
            e.spectrum(input, into: out)
            let g = e.grid!
            XCTAssertEqual(out.width, Self.W)
            XCTAssertEqual(out.height, Self.H)
            // Red of inferno rises monotonically with magnitude.
            var best = -1
            var bestVal = -1
            for x in 0..<out.width where x != g.centerX {
                let v = Int(out[x, g.centerY, .red])
                if v > bestVal { bestVal = v; best = x }
            }
            XCTAssertEqual(abs(best - g.centerX), 24, b.name)
        }
    }

    func testOpticalColorModeCutsRedBeforeGreen() {
        // 36/240 = 0.15 cy/px. Circle 0.16 at 530 nm passes green and blue;
        // red sees 0.16 * 530/600 = 0.141 and loses it.
        let input = frame { x, y in 128 + self.grating(36, 100)(x, y) }
        for b in backends {
            let e = engine(b) {
                $0.colorMode = .rgbOptical
                $0.spec = FilterSpec(shape: .circle(radius: 0.16), passInside: true)
            }
            let out = PixelImage()
            e.filter(input, into: out)
            let red = std(channel(out, .red))
            let green = std(channel(out, .green))
            let blue = std(channel(out, .blue))
            XCTAssertLessThan(red, 1, "\(b.name): red std \(red) should be ~0")
            XCTAssertGreaterThan(green, 50, "\(b.name): green std \(green)")
            XCTAssertGreaterThan(blue, 50, "\(b.name): blue std \(blue)")
        }
    }

    func testDigitalColorModeTreatsChannelsAlike() {
        let input = frame { x, y in 128 + self.grating(36, 100)(x, y) }
        for b in backends {
            let e = engine(b) {
                $0.colorMode = .rgb
                $0.spec = FilterSpec(shape: .circle(radius: 0.16), passInside: true)
            }
            let out = PixelImage()
            e.filter(input, into: out)
            XCTAssertEqual(std(channel(out, .green)), std(channel(out, .red)), accuracy: 0.5, b.name)
        }
    }

    func testSingleSidebandFilterGivesFlatMagnitudeAndOscillatingRealPart() {
        // Pass only the +f lobe of cos(2 pi f x), leaving exp(i 2 pi f x) / 2.
        let f = 20.0 / Double(Self.W)
        let spec = FilterSpec(shape: .freeform([Dab(u: f, v: 0, radius: 0.02)]), passInside: true)
        let input = frame { x, y in 128 + self.grating(20, 100)(x, y) }
        for b in backends {
            let out = PixelImage()
            let real = engine(b) { $0.spec = spec; $0.outputMode = .real }
            real.filter(input, into: out)
            let mag = engine(b) { $0.spec = spec; $0.outputMode = .magnitude }
            mag.filter(input, into: out)

            // The +f lobe of 100 cos() carries amplitude 50.
            let r = real.lastFilteredPlane(0)
            XCTAssertEqual(Double(r.max()!), 50, accuracy: 1, b.name)
            XCTAssertEqual(Double(r.min()!), -50, accuracy: 1, b.name)
            let m = mag.lastFilteredPlane(0)
            XCTAssertEqual(Double(m.min()!), 50, accuracy: 1, b.name)
            XCTAssertEqual(Double(m.max()!), 50, accuracy: 1, b.name)
        }
    }

    func testOddFrameSizesArePaddedAndCroppedBack() {
        let input = PixelImage(width: 101, height: 77)
        for i in stride(from: 0, to: input.pixels.count, by: 4) {
            input.pixels[i] = 30
            input.pixels[i + 1] = 20
            input.pixels[i + 2] = 10
            input.pixels[i + 3] = 255
        }
        for b in backends {
            let e = engine(b)
            let out = PixelImage()
            e.filter(input, into: out)
            XCTAssertEqual(out.width, 101)
            XCTAssertEqual(out.height, 77)
            let expected = b.sizing == .fiveSmooth
                ? FrequencyGrid(width: 108, height: 80)
                : FrequencyGrid(width: 128, height: 80)
            XCTAssertEqual(e.grid, expected, b.name)
        }
    }

    func testStridedInputIsReadRowByRow() {
        // Camera buffers often pad rows; a 16-byte pad per row must not shear the image.
        let src = frame { x, y in Double((x * 3 + y * 7) % 256) }
        let bpr = Self.W * 4 + 16
        var strided = [UInt8](repeating: 0, count: bpr * Self.H)
        for y in 0..<Self.H {
            for i in 0..<(Self.W * 4) { strided[y * bpr + i] = src.pixels[y * Self.W * 4 + i] }
        }
        let e = engine(.reference)
        let a = PixelImage()
        let b = PixelImage()
        e.filter(src, into: a)
        let e2 = engine(.reference)
        strided.withUnsafeBytes { raw in
            e2.filter(PixelView(base: raw.baseAddress!, width: Self.W, height: Self.H, bytesPerRow: bpr), into: b)
        }
        XCTAssertEqual(a.pixels, b.pixels)
    }
}
