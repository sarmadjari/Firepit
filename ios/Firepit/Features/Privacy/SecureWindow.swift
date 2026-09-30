import Observation
import SwiftUI
import UIKit

/// Who needs the window kept out of screen recordings and the app-switcher snapshot right now. Ported from
/// android/app/…/privacy/SecureWindow.kt.
///
/// More than one thing asks — the setting, and the invite screen, which always does — and the cover is one bit, so
/// whoever finishes last is the one allowed to lift it.
///
/// Android sets FLAG_SECURE, which also blanks screenshots. iOS has no switch for screenshots, so a held window is
/// covered wherever iOS lets an app act: while the scene is being recorded, mirrored or AirPlayed, and whenever it is
/// not active, which is when the system takes the app-switcher snapshot.
@Observable
final class SecureWindow {
    private var holders: Set<String> = []

    var isHeld: Bool { !holders.isEmpty }

    func hold(_ holder: String) {
        holders.insert(holder)
    }

    func release(_ holder: String) {
        holders.remove(holder)
    }
}

extension View {
    /// Covers this window, per `SecureWindow`, when the system could capture it. Put at the root.
    func secureWindowCover(_ secureWindow: SecureWindow) -> some View {
        modifier(SecureWindowCover(secureWindow: secureWindow))
    }

    /// Holds the window secure while this view is on screen, as Android's invite screen does.
    func holdsSecureWindow(_ secureWindow: SecureWindow, as holder: String) -> some View {
        onAppear { secureWindow.hold(holder) }
            .onDisappear { secureWindow.release(holder) }
    }
}

private struct SecureWindowCover: ViewModifier {
    let secureWindow: SecureWindow

    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.isSceneCaptured) private var isSceneCaptured

    func body(content: Content) -> some View {
        let covered = secureWindow.isHeld && (scenePhase != .active || isSceneCaptured)
        content
            .overlay {
                if covered {
                    PrivacyCover()
                        .transition(.opacity)
                }
            }
            .animation(.easeOut(duration: 0.15), value: covered)
    }
}

/// What a captured or backgrounded window shows instead of the conversation: the mark on the page colour.
private struct PrivacyCover: View {
    var body: some View {
        ZStack {
            FirepitColors.surface
            FirepitMark()
                .fill(FirepitColors.primary)
                .frame(width: 72, height: 72)
        }
        .ignoresSafeArea()
        .accessibilityElement()
        .accessibilityLabel(Text("Firepit is hidden while the screen is shared"))
    }
}

/// Shown where a feature cannot work without a permission. Ported from android/app/…/permissions/PermissionNeeded.kt.
///
/// Says what the permission is for and offers the only route back, since a second in-app prompt does nothing once the
/// system has recorded a denial.
struct PermissionNeeded: View {
    let title: String
    let message: String

    @Environment(\.openURL) private var openURL

    var body: some View {
        ContentUnavailableView {
            Text(verbatim: title)
                .font(FirepitFont.titleMedium)
        } description: {
            Text(verbatim: message)
                .font(FirepitFont.bodyMedium)
                .foregroundStyle(FirepitColors.textSecondary)
        } actions: {
            Button("Open Settings") {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    openURL(url)
                }
            }
            .buttonStyle(.borderedProminent)
        }
    }
}
