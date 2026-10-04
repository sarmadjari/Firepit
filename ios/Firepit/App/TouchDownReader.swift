import SwiftUI
import UIKit
import UIKit.UIGestureRecognizerSubclass

/// Notes where each touch lands in the window without taking it, so the shell knows which side was used last
/// (UX §6.11.5). Ported from the `touched` modifier in android/app/…/ui/SidePanes.kt.
///
/// A SwiftUI tap fails as soon as the finger moves, so panning the map would never count. This recognizer sees every
/// touch begin, reports it, and fails at once: it never holds, delays or cancels the touch for the views under it. A
/// hovering pointer or pencil makes no touches, so hovering never counts.
struct TouchDownReader: UIViewRepresentable {
    let onTouchDown: (CGPoint) -> Void

    func makeUIView(context: Context) -> ReaderView {
        ReaderView(onTouchDown: onTouchDown)
    }

    func updateUIView(_ view: ReaderView, context: Context) {
        view.recognizer.onTouchDown = onTouchDown
    }

    final class ReaderView: UIView {
        let recognizer: TouchDownRecognizer

        init(onTouchDown: @escaping (CGPoint) -> Void) {
            recognizer = TouchDownRecognizer(onTouchDown: onTouchDown)
            super.init(frame: .zero)
            isUserInteractionEnabled = false
        }

        @available(*, unavailable)
        required init?(coder: NSCoder) { nil }

        // On the window, so it sees touches anywhere in it, measured in the window's own space.
        override func didMoveToWindow() {
            super.didMoveToWindow()
            recognizer.view?.removeGestureRecognizer(recognizer)
            window?.addGestureRecognizer(recognizer)
        }
    }
}

final class TouchDownRecognizer: UIGestureRecognizer {
    var onTouchDown: (CGPoint) -> Void

    init(onTouchDown: @escaping (CGPoint) -> Void) {
        self.onTouchDown = onTouchDown
        super.init(target: nil, action: nil)
        cancelsTouchesInView = false
        delaysTouchesBegan = false
        delaysTouchesEnded = false
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
        if let touch = touches.first { onTouchDown(touch.location(in: view)) }
        state = .failed
    }

    override func canPrevent(_ preventedGestureRecognizer: UIGestureRecognizer) -> Bool { false }

    override func canBePrevented(by preventingGestureRecognizer: UIGestureRecognizer) -> Bool { false }
}
