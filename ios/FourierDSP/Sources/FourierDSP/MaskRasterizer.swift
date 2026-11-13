import Foundation

public enum MaskRasterizer {
    /// Writes the 0/1 transmission of `spec` into `out` (row-major,
    /// width * height) in centered layout.
    ///
    /// `frequencyScale` evaluates the shape at (u * s, v * s). A fixed mask in
    /// the Fourier plane of a 4f system passes frequency nu at position
    /// lambda * f * nu, so the filter at wavelength lambda_c for a mask drawn
    /// at lambda_ref is H_ref(nu * lambda_c / lambda_ref).
    public static func rasterize(
        _ spec: FilterSpec,
        grid: FrequencyGrid,
        into out: UnsafeMutablePointer<Float>,
        frequencyScale s: Double = 1
    ) {
        precondition(s > 0, "frequencyScale must be positive")
        let inside: Float = spec.passInside ? 1 : 0
        let outside: Float = 1 - inside
        let w = grid.width
        let h = grid.height

        if case let .freeform(dabs) = spec.shape {
            // Paint each dab only inside its bounding box.
            out.update(repeating: outside, count: w * h)
            for dab in dabs {
                let x0 = max(0, Int(floor(grid.x((dab.u - dab.radius) / s))))
                let x1 = min(w - 1, Int(ceil(grid.x((dab.u + dab.radius) / s))))
                let y0 = max(0, Int(floor(grid.y((dab.v - dab.radius) / s))))
                let y1 = min(h - 1, Int(ceil(grid.y((dab.v + dab.radius) / s))))
                guard x0 <= x1, y0 <= y1 else { continue }
                let r2 = dab.radius * dab.radius
                for y in y0...y1 {
                    let dv = grid.v(Double(y)) * s - dab.v
                    let row = out + y * w
                    for x in x0...x1 {
                        let du = grid.u(Double(x)) * s - dab.u
                        if du * du + dv * dv <= r2 { row[x] = inside }
                    }
                }
            }
            return
        }

        let shape = spec.shape
        for y in 0..<h {
            let v = grid.v(Double(y)) * s
            let row = out + y * w
            for x in 0..<w {
                let u = grid.u(Double(x)) * s
                row[x] = shape.contains(u: u, v: v) ? inside : outside
            }
        }
    }

    /// Array convenience for tests and callers that do not hold raw buffers.
    public static func rasterize(_ spec: FilterSpec, grid: FrequencyGrid, frequencyScale s: Double = 1) -> [Float] {
        var out = [Float](repeating: 0, count: grid.width * grid.height)
        out.withUnsafeMutableBufferPointer { rasterize(spec, grid: grid, into: $0.baseAddress!, frequencyScale: s) }
        return out
    }
}
