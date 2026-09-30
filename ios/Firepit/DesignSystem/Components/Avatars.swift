import SwiftUI

/// A person, drawn as their 2-character tag on a colour derived from their node number — ported from
/// android/core/designsystem/…/component/Avatars.kt. Colour is decoration only (the name always appears alongside),
/// so a hue collision is cosmetic.
struct IdentityAvatar: View {
    let nodeNum: Int32
    let tag: String?
    var size: CGFloat = FirepitSpacing.avatarSize
    /// Previews a colour other than the chosen one, as the colour picker does.
    var slot: Int?

    @Environment(\.identitySlots) private var identitySlots
    @Environment(\.identityMarks) private var identityMarks

    var body: some View {
        let slot = slot ?? identitySlots[nodeNum]
        let mark = identityMarks[nodeNum]
        let ink = IdentityColors.onColor(for: nodeNum, slot: slot)
        Circle()
            .fill(IdentityColors.color(for: nodeNum, slot: slot))
            .frame(width: size, height: size)
            .overlay {
                if let icon = mark?.icon {
                    Image(icon: icon)
                        .resizable()
                        .scaledToFit()
                        .fontWeight(.medium)
                        .foregroundStyle(ink)
                        .frame(width: size * 0.56, height: size * 0.56)
                } else {
                    let label =
                        [mark?.tag, tag]
                        .compactMap { $0 }
                        .first { !$0.trimmingCharacters(in: .whitespaces).isEmpty } ?? "?"
                    Text(verbatim: label)
                        .foregroundStyle(ink)
                        // Tags from other clients can be 3–4 characters; shrink rather than truncate.
                        .font(.system(size: size * (label.count > 2 ? 0.30 : 0.36), weight: .semibold))
                        .lineLimit(1)
                        .minimumScaleFactor(0.5)
                        .padding(size * 0.08)
                }
            }
            // The name is already read out by the row, so the tag would be noise.
            .accessibilityHidden(true)
    }
}

/// A room, drawn as one of the fixed icons on the outgoing-bubble tint.
struct RoomAvatar: View {
    let icon: RoomIcon
    var size: CGFloat = FirepitSpacing.avatarSize

    var body: some View {
        Circle()
            .fill(FirepitColors.bubbleOut)
            .frame(width: size, height: size)
            .overlay {
                Image(systemName: icon.systemName)
                    .resizable()
                    .scaledToFit()
                    .fontWeight(.medium)
                    .foregroundStyle(FirepitColors.primary)
                    .frame(width: size * 0.5, height: size * 0.5)
            }
            .accessibilityHidden(true)
    }
}

/// Infrastructure nodes share one reserved colour and differ by glyph.
struct InfraAvatar: View {
    let isRouter: Bool
    var size: CGFloat = FirepitSpacing.avatarSize

    var body: some View {
        Circle()
            .fill(FirepitColors.infra)
            .frame(width: size, height: size)
            .overlay {
                Image(systemName: (isRouter ? RoomIcon.flag : RoomIcon.house).systemName)
                    .resizable()
                    .scaledToFit()
                    .fontWeight(.medium)
                    .foregroundStyle(Color(hex: 0xFFFFFF))
                    .frame(width: size * 0.5, height: size * 0.5)
            }
            .accessibilityHidden(true)
    }
}

/// Live marker ring, used on the map and for the connected dot.
struct LiveRing: View {
    var size: CGFloat = 12
    var live = true

    var body: some View {
        Circle()
            .fill(live ? FirepitColors.live : FirepitColors.stale)
            .overlay { Circle().strokeBorder(FirepitColors.surface, lineWidth: 1) }
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

#Preview("Avatars") {
    VStack(spacing: 16) {
        HStack(spacing: 12) {
            IdentityAvatar(nodeNum: 0x1234_5678, tag: "SJ")
            IdentityAvatar(nodeNum: -7, tag: "ABCD")
            IdentityAvatar(nodeNum: 42, tag: nil)
            IdentityAvatar(nodeNum: 9, tag: "BS")
                .environment(\.identityMarks, [9: IdentityMark(icon: .roleBase)])
        }
        HStack(spacing: 12) {
            RoomAvatar(icon: .tent)
            RoomAvatar(icon: .trail)
            InfraAvatar(isRouter: false)
            InfraAvatar(isRouter: true)
            LiveRing()
            LiveRing(live: false)
        }
    }
    .padding()
    .background(FirepitColors.surface)
}
