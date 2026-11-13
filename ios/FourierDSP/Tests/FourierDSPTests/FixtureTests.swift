import Foundation
import XCTest
@testable import FourierDSP

/// Checks this port against `fixtures/dsp.json`, which the Android DSP
/// module writes from Kotlin and desktop OpenCV (see FixtureExportTest.kt).
final class FixtureTests: XCTestCase {
    static let fixtures: [String: Any] = {
        // ios/FourierDSP/Tests/FourierDSPTests/FixtureTests.swift -> repo root
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { url.deleteLastPathComponent() }
        url.appendPathComponent("fixtures/dsp.json")
        guard let data = try? Data(contentsOf: url),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { fatalError("Cannot read \(url.path)") }
        return json
    }()

    func num(_ a: Any?) -> Double {
        if let d = a as? Double { return d }
        if let i = a as? Int { return Double(i) }
        if let n = a as? NSNumber { return n.doubleValue }
        fatalError("Not a number: \(String(describing: a))")
    }

    func optNum(_ a: Any?) -> Double? {
        if a == nil || a is NSNull { return nil }
        return num(a)
    }

    func int(_ a: Any?) -> Int { Int(num(a)) }

    func spec(_ json: [String: Any]) -> FilterSpec {
        let pass = json["passInside"] as! Bool
        let s = json["shape"] as! [String: Any]
        let shape: FilterShape
        switch s["type"] as! String {
        case "empty": shape = .empty
        case "box": shape = .box(halfU: num(s["halfU"]), halfV: num(s["halfV"]))
        case "halfBox": shape = .halfBox(halfU: num(s["halfU"]), halfV: num(s["halfV"]))
        case "circle": shape = .circle(radius: num(s["radius"]))
        case "halfCircle": shape = .halfCircle(radius: num(s["radius"]))
        case "annulus": shape = .annulus(outer: num(s["outer"]), width: num(s["width"]))
        case "freeform":
            let dabs = (s["dabs"] as! [[Any]]).map { Dab(u: num($0[0]), v: num($0[1]), radius: num($0[2])) }
            shape = .freeform(dabs)
        case let other: fatalError("Unknown shape \(other)")
        }
        return FilterSpec(shape: shape, passInside: pass)
    }

    func testNextFastEvenMatchesKotlin() {
        for pair in Self.fixtures["nextFastEven"] as! [[Any]] {
            XCTAssertEqual(FFTSizing.fiveSmooth.nextFastEven(int(pair[0])), int(pair[1]), "size \(pair[0])")
        }
    }

    func testMasksMatchKotlin() {
        let cases = Self.fixtures["masks"] as! [[String: Any]]
        XCTAssertGreaterThan(cases.count, 0)
        for c in cases {
            let g = FrequencyGrid(width: int(c["width"]), height: int(c["height"]))
            let s = num(c["scale"])
            let mask = MaskRasterizer.rasterize(spec(c["spec"] as! [String: Any]), grid: g, frequencyScale: s)
            var want: [Float] = []
            var value: Float = 0
            for run in c["runs"] as! [Any] {
                want.append(contentsOf: repeatElement(value, count: int(run)))
                value = 1 - value
            }
            XCTAssertEqual(want.count, mask.count)
            let mismatches = zip(mask, want).filter { $0 != $1 }.count
            XCTAssertEqual(mismatches, 0, "\(c["name"]!) \(g.width)x\(g.height) scale \(s)")
        }
    }

    func testPhysicalScaleMatchesKotlin() {
        for c in Self.fixtures["physicalScale"] as! [[String: Any]] {
            let o = SensorOptics(
                pixelPitchMm: num(c["pixelPitchMm"]),
                activeWidthPx: int(c["activeWidthPx"]),
                activeHeightPx: int(c["activeHeightPx"]),
                focalLengthMm: optNum(c["focalLengthMm"]),
                fNumber: optNum(c["fNumber"])
            )
            let p = PhysicalScale(optics: o, bufferWidth: int(c["bufferWidth"]), bufferHeight: int(c["bufferHeight"]))
            XCTAssertEqual(p.binning, num(c["binning"]), accuracy: 1e-12)
            XCTAssertEqual(p.pitchMm, num(c["pitchMm"]), accuracy: 1e-15)
            XCTAssertEqual(p.cyclesPerMm(0.1), num(c["cyclesPerMmAt0.1"]), accuracy: 1e-9)
            if let d = optNum(c["cyclesPerDegreeAt0.1"]) {
                XCTAssertEqual(p.cyclesPerDegree(0.1)!, d, accuracy: 1e-9)
            } else {
                XCTAssertNil(p.cyclesPerDegree(0.1))
            }
            if let cut = optNum(c["cutoff550"]) {
                XCTAssertEqual(p.diffractionCutoffCyclesPerMm(wavelengthNm: 550)!, cut, accuracy: 1e-6)
            } else {
                XCTAssertNil(p.diffractionCutoffCyclesPerMm(wavelengthNm: 550))
            }
        }
    }

    func testColormapsMatchOpenCv() {
        let maps = Self.fixtures["colormaps"] as! [String: Any]
        for map in Colormap.allCases {
            let want = (maps[map.rawValue] as! [Any]).map { UInt8(int($0)) }
            XCTAssertEqual(map.table, want, map.rawValue)
        }
    }

    func testHannWindowMatchesOpenCv() {
        let h = Self.fixtures["hann"] as! [String: Any]
        let w = int(h["width"])
        let ht = int(h["height"])
        let want = (h["values"] as! [Any]).map { Float(num($0)) }
        var got = [Float](repeating: 0, count: w * ht)
        got.withUnsafeMutableBufferPointer { ImageOps.hanningWindow(width: w, height: ht, into: $0.baseAddress!) }
        for i in want.indices { XCTAssertEqual(got[i], want[i], accuracy: 1e-6, "index \(i)") }
    }

    func testGaussianBlurMatchesOpenCv() {
        let b = Self.fixtures["blurredCircle"] as! [String: Any]
        let g = FrequencyGrid(width: int(b["width"]), height: int(b["height"]))
        var mask = MaskRasterizer.rasterize(FilterSpec(shape: .circle(radius: num(b["radius"])), passInside: true), grid: g)
        var tmp = [Float](repeating: 0, count: mask.count)
        mask.withUnsafeMutableBufferPointer { m in
            tmp.withUnsafeMutableBufferPointer { t in
                ImageOps.gaussianBlur(m.baseAddress!, tmp: t.baseAddress!, width: g.width, height: g.height, sigma: num(b["sigma"]))
            }
        }
        let want = (b["values"] as! [Any]).map { Float(num($0)) }
        var worst: Float = 0
        for i in want.indices { worst = max(worst, abs(mask[i] - want[i])) }
        XCTAssertLessThan(worst, 1e-5)
    }
}
