import Foundation

/// Small image primitives that match the OpenCV calls the Android engine
/// makes, so both platforms produce the same masks and windows.
enum ImageOps {
    /// OpenCV BORDER_REFLECT_101 index: ... 2 1 | 0 1 2 ... n-1 | n-2 ...
    @inline(__always)
    static func reflect101(_ i: Int, _ n: Int) -> Int {
        if n == 1 { return 0 }
        var i = i
        while i < 0 || i >= n {
            if i < 0 { i = -i }
            if i >= n { i = 2 * (n - 1) - i }
        }
        return i
    }

    /// Kernel `Imgproc.GaussianBlur` picks for a CV_32F image with ksize (0, 0):
    /// size round(8 sigma + 1) forced odd.
    static func gaussianKernel(sigma: Double) -> [Float] {
        let size = Int((sigma * 8 + 1).rounded(.toNearestOrEven)) | 1
        let center = Double(size - 1) * 0.5
        let scale = -0.5 / (sigma * sigma)
        var k = [Float](repeating: 0, count: size)
        var sum = 0.0
        for i in 0..<size {
            let x = Double(i) - center
            k[i] = Float(exp(scale * x * x))
            sum += Double(k[i])
        }
        let inv = 1 / sum
        for i in 0..<size { k[i] = Float(Double(k[i]) * inv) }
        return k
    }

    /// Separable Gaussian blur in place, BORDER_REFLECT_101. `tmp` must hold width * height.
    static func gaussianBlur(
        _ data: UnsafeMutablePointer<Float>,
        tmp: UnsafeMutablePointer<Float>,
        width w: Int,
        height h: Int,
        sigma: Double
    ) {
        let kernel = gaussianKernel(sigma: sigma)
        let n = kernel.count
        let r = n / 2
        var line = [Float](repeating: 0, count: max(w, h) + 2 * r)
        kernel.withUnsafeBufferPointer { kp in
            let k = kp.baseAddress!
            line.withUnsafeMutableBufferPointer { lp in
                let l = lp.baseAddress!
                for y in 0..<h {
                    let row = data + y * w
                    for i in 0..<(w + 2 * r) { l[i] = row[reflect101(i - r, w)] }
                    let out = tmp + y * w
                    for x in 0..<w {
                        var acc: Float = 0
                        for j in 0..<n { acc += k[j] * l[x + j] }
                        out[x] = acc
                    }
                }
                for x in 0..<w {
                    for i in 0..<(h + 2 * r) { l[i] = tmp[reflect101(i - r, h) * w + x] }
                    for y in 0..<h {
                        var acc: Float = 0
                        for j in 0..<n { acc += k[j] * l[y + j] }
                        data[y * w + x] = acc
                    }
                }
            }
        }
    }

    /// `Imgproc.createHanningWindow`: sqrt of the outer product of two
    /// periodic-endpoint Hann windows, row-major.
    static func hanningWindow(width: Int, height: Int, into out: UnsafeMutablePointer<Float>) {
        let c0 = 2 * Double.pi / Double(width - 1)
        let c1 = 2 * Double.pi / Double(height - 1)
        var wc = [Double](repeating: 0, count: width)
        for j in 0..<width { wc[j] = 0.5 * (1 - cos(c0 * Double(j))) }
        for i in 0..<height {
            let wr = 0.5 * (1 - cos(c1 * Double(i)))
            for j in 0..<width { out[i * width + j] = Float(wr * wc[j]).squareRoot() }
        }
    }

    /// Quadrant swap. With even dimensions fftshift and ifftshift coincide.
    static func fftShift(_ src: UnsafePointer<Float>, into dst: UnsafeMutablePointer<Float>, width w: Int, height h: Int) {
        let hw = w / 2
        let hh = h / 2
        for y in 0..<h {
            let dy = ((y + hh) % h) * w
            let s = src + y * w
            for x in 0..<w { dst[dy + (x + hw) % w] = s[x] }
        }
    }

    /// OpenCV saturate_cast<uchar> of a float: round half to even, clamp.
    @inline(__always)
    static func saturateU8(_ v: Float) -> UInt8 {
        let r = v.rounded(.toNearestOrEven)
        if !(r > 0) { return 0 }
        if r >= 255 { return 255 }
        return UInt8(r)
    }
}
