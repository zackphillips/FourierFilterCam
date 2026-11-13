import XCTest
@testable import FourierDSP

final class GeometryTests: XCTestCase {
    func testNextFastEvenPicksSmallEven235Numbers() {
        let s = FFTSizing.fiveSmooth
        XCTAssertEqual(s.nextFastEven(1), 2)
        XCTAssertEqual(s.nextFastEven(481), 486)
        XCTAssertEqual(s.nextFastEven(720), 720)
        XCTAssertEqual(s.nextFastEven(1080), 1080)
        XCTAssertEqual(s.nextFastEven(1920), 1920)
        XCTAssertEqual(s.nextFastEven(482), 486)
    }

    func testAccelerateSizingPadsToVdspLengths() {
        let s = FFTSizing.accelerate
        XCTAssertEqual(s.nextFastEven(1), 16)
        XCTAssertEqual(s.nextFastEven(480), 480) // 15 * 32
        XCTAssertEqual(s.nextFastEven(640), 640) // 5 * 128
        XCTAssertEqual(s.nextFastEven(720), 768) // 720 = 45 * 16 is not allowed
        XCTAssertEqual(s.nextFastEven(1080), 1280)
        XCTAssertEqual(s.nextFastEven(1280), 1280)
        XCTAssertEqual(s.nextFastEven(1920), 1920)
        XCTAssertEqual(s.nextFastEven(101), 128)
    }

    func testGridMapsCenterToDcAndEdgeToNyquist() {
        let g = FrequencyGrid(width: 640, height: 480)
        XCTAssertEqual(g.u(320), 0)
        XCTAssertEqual(g.v(240), 0)
        XCTAssertEqual(g.u(0), -0.5)
        XCTAssertEqual(g.v(0), -0.5)
        XCTAssertEqual(g.x(g.u(100)), 100, accuracy: 1e-9)
    }

    func testCircleAreaMatchesPiRSquared() {
        let g = FrequencyGrid(width: 400, height: 300)
        let r = 0.2
        let out = MaskRasterizer.rasterize(FilterSpec(shape: .circle(radius: r), passInside: true), grid: g)
        let expected = Double.pi * r * r * Double(g.width * g.height)
        XCTAssertEqual(Double(out.reduce(0, +)), expected, accuracy: expected * 0.01)
    }

    func testBlockingInvertsTransmission() {
        let g = FrequencyGrid(width: 64, height: 64)
        let pass = MaskRasterizer.rasterize(FilterSpec(shape: .box(halfU: 0.1, halfV: 0.2), passInside: true), grid: g)
        let block = MaskRasterizer.rasterize(FilterSpec(shape: .box(halfU: 0.1, halfV: 0.2), passInside: false), grid: g)
        for i in pass.indices { XCTAssertEqual(pass[i] + block[i], 1) }
    }

    func testFrequencyScaleShrinksTheShapeForLongerWavelengths() {
        let g = FrequencyGrid(width: 400, height: 400)
        let spec = FilterSpec(shape: .circle(radius: 0.2), passInside: true)
        let ref = MaskRasterizer.rasterize(spec, grid: g, frequencyScale: 1)
        let red = MaskRasterizer.rasterize(spec, grid: g, frequencyScale: 600.0 / 530.0)
        let ratio = Double(red.reduce(0, +) / ref.reduce(0, +))
        let expected = (530.0 / 600.0) * (530.0 / 600.0)
        XCTAssertEqual(ratio, expected, accuracy: 0.01)
    }

    func testFreeformBoundingBoxPaintingMatchesBruteForce() {
        let g = FrequencyGrid(width: 120, height: 80)
        let shape = FilterShape.freeform([
            Dab(u: 0.1, v: -0.05, radius: 0.03),
            Dab(u: -0.31, v: 0.2, radius: 0.05),
            Dab(u: 0.49, v: 0.49, radius: 0.04),
        ])
        for pass in [true, false] {
            for s in [1.0, 1.13, 0.88] {
                let spec = FilterSpec(shape: shape, passInside: pass)
                let fast = MaskRasterizer.rasterize(spec, grid: g, frequencyScale: s)
                for y in 0..<g.height {
                    for x in 0..<g.width {
                        let want = spec.transmission(u: g.u(Double(x)) * s, v: g.v(Double(y)) * s)
                        XCTAssertEqual(fast[y * g.width + x], want, "x=\(x) y=\(y) s=\(s) pass=\(pass)")
                    }
                }
            }
        }
    }

    func testHalfShapesAreNotPointSymmetric() {
        XCTAssertFalse(FilterShape.halfBox(halfU: 0.1, halfV: 0.1).isPointSymmetric)
        XCTAssertFalse(FilterShape.halfCircle(radius: 0.1).isPointSymmetric)
        XCTAssertTrue(FilterShape.annulus(outer: 0.2, width: 0.05).isPointSymmetric)
        XCTAssertTrue(FilterShape.freeform([]).isPointSymmetric)
        XCTAssertFalse(FilterShape.freeform([Dab(u: 0.1, v: 0, radius: 0.01)]).isPointSymmetric)
    }

    func testPhysicalScaleUsesTheUncroppedAxis() {
        let optics = SensorOptics(pixelPitchMm: 0.001, activeWidthPx: 4000, activeHeightPx: 3000, focalLengthMm: 5, fNumber: 1.8)
        let p = PhysicalScale(optics: optics, bufferWidth: 1280, bufferHeight: 720)
        XCTAssertEqual(p.binning, 3.125, accuracy: 1e-12)
        XCTAssertEqual(p.pitchMm, 0.003125, accuracy: 1e-12)
        XCTAssertEqual(p.nyquistCyclesPerMm, 160, accuracy: 1e-9)
        XCTAssertEqual(p.cyclesPerMm(0.1), 32, accuracy: 1e-9)
        XCTAssertEqual(p.cyclesPerDegree(0.1)!, 160 * Double.pi / 180, accuracy: 1e-9)
        XCTAssertEqual(p.diffractionCutoffCyclesPerMm(wavelengthNm: 550)!, 1010.10, accuracy: 0.01)
        XCTAssertEqual(p.cyclesPerPixel(cyclesPerMm: p.cyclesPerMm(0.1)), 0.1, accuracy: 1e-12)
    }

    func testReflect101MatchesOpenCv() {
        // OpenCV borderInterpolate(i, 5, BORDER_REFLECT_101) for i = 5...9.
        XCTAssertEqual((5...9).map { ImageOps.reflect101($0, 5) }, [3, 2, 1, 0, 1])
        XCTAssertEqual(ImageOps.reflect101(-2, 5), 2)
        XCTAssertEqual(ImageOps.reflect101(7, 1), 0)
    }
}
