import SwiftUI

/// Asked once, when somebody makes their first private room.
struct MakeRadioPrivateDialog: View {
    let onMakePrivate: () -> Void
    let onKeepPublic: () -> Void

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: FirepitSpacing.s) {
                Text(
                    "Your room is private either way. Nobody can read your messages whichever you choose."
                )
                .font(FirepitFont.bodyMedium)
                Text(
                    "What is still public is the radio itself. Right now it broadcasts its name and battery level "
                        + "in the open, and any Meshtastic device nearby can see them. Making it private hides them "
                        + "from ordinary Meshtastic radios — but not from anyone running Firepit, because that key "
                        + "comes with the app."
                )
                .font(FirepitFont.bodyMedium)
                .foregroundStyle(FirepitColors.textSecondary)
                Text(
                    "Say no if you also use this radio on another Meshtastic mesh — making it private would take "
                        + "it off that mesh."
                )
                .font(FirepitFont.bodyMedium)
                .foregroundStyle(FirepitColors.textSecondary)
                Text("You can change this any time in Settings.")
                    .font(FirepitFont.bodySmall)
                    .foregroundStyle(FirepitColors.textSecondary)
                Spacer(minLength: FirepitSpacing.m)
                Button("Yes, make it private", action: onMakePrivate)
                    .buttonStyle(.borderedProminent)
                Button("No, leave it", action: onKeepPublic)
                    .buttonStyle(.bordered)
            }
            .padding(FirepitSpacing.screenMargin)
            .background(FirepitColors.surface)
            .navigationTitle("Make this radio private to Firepit?")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}
