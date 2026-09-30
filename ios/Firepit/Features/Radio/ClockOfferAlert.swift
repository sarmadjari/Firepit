import FirepitData
import FirepitModel
import SwiftUI

/// Asks before writing the phone's time to a radio whose clock is wrong. Ported from
/// android/app/…/ClockOfferDialog.kt.
///
/// The radio stamps every message it hands over, so a wrong clock puts today's conversation under yesterday.
/// Correcting it changes the device, which is the user's to decide.
struct ClockOfferAlert: ViewModifier {
    let nodeClock: NodeClock

    @State private var offer: ClockOffer?

    func body(content: Content) -> some View {
        content
            .task {
                for await next in nodeClock.offer.subscribe() {
                    offer = next
                }
            }
            .alert(
                "This radio's clock is wrong",
                isPresented: Binding(get: { offer != nil }, set: { shown in if !shown { nodeClock.dismiss() } }),
                presenting: offer
            ) { _ in
                Button("Set from phone") {
                    Task { await nodeClock.sync() }
                }
                Button("Leave it", role: .cancel) {
                    nodeClock.dismiss()
                }
            } message: { offer in
                Text(Self.explain(offer))
            }
    }

    static func explain(_ offer: ClockOffer) -> String {
        let drift = describe(offer.drift)
        let first =
            offer.behind
            ? String(
                localized: """
                    It is \(drift) behind this phone, so messages it passes on can appear under the wrong day.
                    """)
            : String(
                localized: """
                    It is \(drift) ahead of this phone, so messages it passes on can appear under the wrong day.
                    """)
        let second = String(
            localized: """
                Setting it from the phone costs no airtime and does not touch the mesh. \
                A radio with its own GPS will keep the better time it already has.
                """)
        return first + "\n\n" + second
    }

    /// The largest whole unit, rounded down: "3 days", "1 hour", "under a minute".
    static func describe(_ drift: Duration) -> String {
        if drift < .seconds(60) {
            return String(localized: "under a minute")
        }
        return drift.formatted(
            .units(
                allowed: [.days, .hours, .minutes], width: .wide, maximumUnitCount: 1,
                fractionalPart: .hide(rounded: .down)))
    }
}

extension View {
    func clockOfferAlert(_ nodeClock: NodeClock) -> some View {
        modifier(ClockOfferAlert(nodeClock: nodeClock))
    }
}
