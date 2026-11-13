import CoreGraphics
import QuartzCore
import SwiftUI
import UIKit

/// Hands processed frames to a layer on the main thread. If the main thread
/// falls behind, only the newest frame is shown; older ones are dropped
/// rather than queued.
final class FrameDisplay: @unchecked Sendable {
    /// Set and read on the main thread only.
    weak var layer: CALayer?

    private let lock = NSLock()
    private var pending: CGImage?
    private var scheduled = false

    func show(_ image: CGImage) {
        lock.lock()
        pending = image
        let needsDispatch = !scheduled
        scheduled = true
        lock.unlock()
        guard needsDispatch else { return }

        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.lock.lock()
            let next = self.pending
            self.pending = nil
            self.scheduled = false
            self.lock.unlock()
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            self.layer?.contents = next
            CATransaction.commit()
        }
    }
}

/// Full-bleed view showing the latest frame, aspect-fit like `fitRect`.
struct FrameView: UIViewRepresentable {
    let display: FrameDisplay

    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.backgroundColor = .black
        view.layer.contentsGravity = .resizeAspect
        display.layer = view.layer
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {}
}

/// Largest rect with the source aspect ratio that fits, centered, in `size`.
/// Matches CALayer's `.resizeAspect`, so touches map onto the displayed image.
func fitRect(width: Int, height: Int, in size: CGSize) -> CGRect {
    let scale = min(size.width / CGFloat(width), size.height / CGFloat(height))
    let w = CGFloat(width) * scale
    let h = CGFloat(height) * scale
    return CGRect(x: (size.width - w) / 2, y: (size.height - h) / 2, width: w, height: h)
}
