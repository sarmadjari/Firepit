import FirepitData
import FirepitModel
import Foundation
@preconcurrency import UserNotifications
import os

private nonisolated let notifyLog = Logger(subsystem: "com.getfirepit.app", category: "FirepitNotify")

/// Raises a notification for messages that arrive while nobody is reading them. Ported from
/// android/app/…/notifications/MessageNotifier.kt.
///
/// Deliberately quiet: muted rooms, the conversation currently on screen, and anything already seen produce nothing.
final class MessageNotifier: NSObject, UNUserNotificationCenterDelegate {
    private nonisolated static let categoryId = "com.getfirepit.app.message"
    private nonisolated static let channelKey = "channel"
    private nonisolated static let peerKey = "peer"

    private let mesh: MeshRepository
    private let channelState: ChannelStateDao
    private let presence: ChatPresence
    private let notificationPreferences: NotificationPreferences
    private let rooms: RoomRepository
    private let router: AppRouter
    private var job: Task<Void, Never>?

    init(
        mesh: MeshRepository,
        channelState: ChannelStateDao,
        presence: ChatPresence,
        notificationPreferences: NotificationPreferences,
        rooms: RoomRepository,
        router: AppRouter
    ) {
        self.mesh = mesh
        self.channelState = channelState
        self.presence = presence
        self.notificationPreferences = notificationPreferences
        self.rooms = rooms
        self.router = router
    }

    /// Call once at launch: a notification tapped while the app was closed is delivered to the delegate set here.
    func start() {
        let center = UNUserNotificationCenter.current()
        center.delegate = self
        // Whatever the lock screen hides, this is all a passer-by sees: iOS shows the placeholder instead of the
        // body when previews are off, which is the phone owner's own setting and no app can override it.
        center.setNotificationCategories([
            UNNotificationCategory(
                identifier: Self.categoryId,
                actions: [],
                intentIdentifiers: [],
                hiddenPreviewsBodyPlaceholder: String(localized: "New message"),
                options: []
            )
        ])
        guard job == nil else { return }
        let messages = mesh.incomingMessages.subscribe()
        job = Task { [weak self] in
            for await message in messages {
                await self?.notifyIfUnseen(message)
            }
        }
    }

    /// Asks once, when connecting a radio makes messages possible; Android asks at the same moment.
    func requestAuthorization() async {
        _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
    }

    private func notifyIfUnseen(_ message: ChatMessage) async {
        if presence.isWatching(channel: message.channel) { return }
        if await firstValue(channelState.observeMuted())?.contains(message.channel) == true { return }

        let cardName = await firstValue(rooms.observePersonCards())?[message.fromNodeNum]?.name
        let nodeName = await firstValue(mesh.observeNodes())?.first { $0.nodeNum == message.fromNodeNum }?.displayName
        let name =
            [cardName, nodeName].compactMap { $0 }.first { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
            ?? String(localized: "Unknown node")
        let room = mesh.channels.value.first { $0.index == message.channel }?.displayName

        // Who, where and what only when asked for: a notification is read by whoever is looking at the phone, and
        // copied to notification history and to anything allowed to mirror notifications.
        let showText = notificationPreferences.showText
        let content = UNMutableNotificationContent()
        if showText {
            content.title = room.map { String(localized: "\(name) in \($0)") } ?? name
            content.body = message.text
        } else {
            content.title = String(localized: "Firepit")
            content.body = String(localized: "New message")
        }
        content.sound = .default
        content.categoryIdentifier = Self.categoryId
        // One notification per conversation, replaced as it moves on. A direct message opens the person, anything
        // else its room.
        let conversation = message.isDirect ? "direct-\(message.fromNodeNum)" : "channel-\(message.channel)"
        content.threadIdentifier = conversation
        content.userInfo =
            message.isDirect ? [Self.peerKey: Int(message.fromNodeNum)] : [Self.channelKey: message.channel]

        // Checked rather than assumed: a denied permission is a standing state, not an error, and pretending
        // otherwise would leave someone believing they were being alerted to messages they never saw.
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        guard settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional else {
            notifyLog.info("notification suppressed: not authorised")
            return
        }
        let request = UNNotificationRequest(identifier: conversation, content: content, trigger: nil)
        do {
            try await UNUserNotificationCenter.current().add(request)
        } catch {
            notifyLog.warning("could not post a notification")
        }
    }

    // MARK: UNUserNotificationCenterDelegate

    /// Shown while the app is open too, as on Android — unless that conversation is on screen, which never gets here.
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse
    ) async {
        let info = response.notification.request.content.userInfo
        if let peer = info[Self.peerKey] as? Int {
            await MainActor.run { router.openDirect(Int32(truncatingIfNeeded: peer)) }
        } else if let channel = info[Self.channelKey] as? Int {
            await MainActor.run { router.open(channel: channel) }
        }
    }
}

/// The first value a stream produces, like Kotlin's `Flow.first()`.
private func firstValue<Element: Sendable>(_ stream: AsyncStream<Element>) async -> Element? {
    for await value in stream {
        return value
    }
    return nil
}
