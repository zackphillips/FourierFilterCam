import FourierDSP
import SwiftUI

/// Touch surface and annotations drawn over the spectrum: frequency axes in
/// the chosen units, the diffraction cutoff when it falls inside the
/// sampled band, and a crosshair at the last touch.
struct SpectrumOverlay: View {
    let grid: FrequencyGrid
    let scale: PhysicalScale?
    let units: AxisUnits
    let cursor: Frequency?
    let onTouch: (Double, Double) -> Void

    var body: some View {
        GeometryReader { geo in
            let rect = fitRect(width: grid.width, height: grid.height, in: geo.size)
            Canvas { context, _ in draw(in: context, rect: rect) }
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { value in
                            if let f = frequency(at: value.location, rect: rect) { onTouch(f.u, f.v) }
                        }
                )
        }
    }

    private func frequency(at p: CGPoint, rect: CGRect) -> Frequency? {
        guard rect.contains(p) else { return nil }
        let x = Double((p.x - rect.minX) / rect.width) * Double(grid.width)
        let y = Double((p.y - rect.minY) / rect.height) * Double(grid.height)
        return Frequency(u: grid.u(x), v: grid.v(y))
    }

    private func draw(in context: GraphicsContext, rect: CGRect) {
        func px(_ u: Double) -> CGFloat { rect.minX + CGFloat(grid.x(u) / Double(grid.width)) * rect.width }
        func py(_ v: Double) -> CGFloat { rect.minY + CGFloat(grid.y(v) / Double(grid.height)) * rect.height }
        let x0 = px(0)
        let y0 = py(0)

        var text = context
        text.addFilter(.shadow(color: .black, radius: 1.5))

        // Fall back to cycles/pixel when the camera did not report what the units need.
        let shown = Format.convert(0.5, to: units, scale: scale) != nil ? units : .cyclesPerPixel
        let nyquist = Format.convert(0.5, to: shown, scale: scale) ?? 0.5
        let step = Format.niceStep(2 * nyquist)

        var axes = Path()
        axes.move(to: CGPoint(x: rect.minX, y: y0))
        axes.addLine(to: CGPoint(x: rect.maxX, y: y0))
        axes.move(to: CGPoint(x: x0, y: rect.minY))
        axes.addLine(to: CGPoint(x: x0, y: rect.maxY))
        context.stroke(axes, with: .color(.white.opacity(0.35)), lineWidth: 1)

        let first = Int(ceil(-nyquist / step))
        let last = Int(floor(nyquist / step))
        if first <= last {
            var ticks = Path()
            for k in first...last where k != 0 {
                let value = Double(k) * step
                guard let f = Format.cyclesPerPixel(value, from: shown, scale: scale) else { continue }
                let x = px(f)
                let y = py(f)
                ticks.move(to: CGPoint(x: x, y: y0 - 4))
                ticks.addLine(to: CGPoint(x: x, y: y0 + 4))
                ticks.move(to: CGPoint(x: x0 - 4, y: y))
                ticks.addLine(to: CGPoint(x: x0 + 4, y: y))
                let label = Text(Format.number(value)).font(.caption2).foregroundColor(.white)
                text.draw(label, at: CGPoint(x: x + 2, y: y0 + 6), anchor: .topLeading)
                text.draw(label, at: CGPoint(x: x0 + 6, y: y), anchor: .leading)
            }
            context.stroke(ticks, with: .color(.white), lineWidth: 1.5)
        }
        text.draw(
            Text(shown.short).font(.caption).foregroundColor(.white),
            at: CGPoint(x: rect.maxX - 8, y: y0 - 6),
            anchor: .bottomTrailing
        )

        if let s = scale, let cutMm = s.diffractionCutoffCyclesPerMm(wavelengthNm: 550) {
            let cut = s.cyclesPerPixel(cyclesPerMm: cutMm)
            if cut < 0.5 {
                let rx = CGFloat(cut) * rect.width
                let ry = CGFloat(cut) * rect.height
                let oval = Path(ellipseIn: CGRect(x: x0 - rx, y: y0 - ry, width: 2 * rx, height: 2 * ry))
                context.stroke(oval, with: .color(.white.opacity(0.6)), style: StrokeStyle(lineWidth: 1.5, dash: [6, 5]))
            }
        }

        if let c = cursor {
            let p = CGPoint(x: px(c.u), y: py(c.v))
            let arm: CGFloat = 10
            var cross = Path()
            cross.move(to: CGPoint(x: p.x - arm, y: p.y))
            cross.addLine(to: CGPoint(x: p.x + arm, y: p.y))
            cross.move(to: CGPoint(x: p.x, y: p.y - arm))
            cross.addLine(to: CGPoint(x: p.x, y: p.y + arm))
            context.stroke(cross, with: .color(.cyan), lineWidth: 1.5)
        }
    }
}
