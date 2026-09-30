#if DEBUG
    import FirepitData
    import FirepitModel
    import FirepitProtocol
    import FirepitProtos
    import FirepitTransport
    import Foundation
    import SwiftProtobuf

    /// Debug builds only: a pretend radio for `-demo`. It carries the demo world's snapshot and answers what the app
    /// sends the way firmware does for the parts the app relies on — queue acceptance, the implicit ack for
    /// broadcasts, the recipient's ack for direct messages, and the admin reads and writes used when rooms change.
    nonisolated final class DemoRadio: RadioLinking {
        let state = CurrentValue(LinkState.disconnected)
        let inbound = Broadcast<FromRadio>()

        private let snapshot = Mutex<RadioSnapshot?>(nil)

        func publish(_ snapshot: RadioSnapshot) {
            self.snapshot.withLock { $0 = snapshot }
            state.set(.ready(snapshot))
        }

        func send(_ message: ToRadio) async throws {
            guard case .packet(let packet)? = message.payloadVariant,
                case .decoded(let data)? = packet.payloadVariant
            else { return }
            if data.portnum == .adminApp {
                handleAdmin(packet: packet, data: data)
                return
            }
            var queue = QueueStatus()
            queue.res = 0
            queue.free = 16
            queue.meshPacketID = packet.id
            var accepted = FromRadio()
            accepted.queueStatus = queue
            inbound.send(accepted)
            guard packet.wantAck else { return }
            let direct = Int32(bitPattern: packet.to) != broadcastNodeNum
            let from = direct ? Int32(bitPattern: packet.to) : DemoWorld.me
            Task { [weak self] in
                try? await Task.sleep(for: .milliseconds(direct ? 1_400 : 700))
                self?.acknowledge(requestId: packet.id, from: from)
            }
        }

        private func acknowledge(requestId: UInt32, from: Int32) {
            var routing = Routing()
            routing.errorReason = .none
            var data = DataMessage()
            data.portnum = .routingApp
            data.requestID = requestId
            data.payload = (try? routing.serializedData()) ?? Data()
            push(data, from: from)
        }

        private func handleAdmin(packet: MeshPacket, data: DataMessage) {
            guard let admin = try? AdminMessage(serializedBytes: data.payload) else { return }
            if admin.getConfigRequest == .sessionkeyConfig {
                var reply = AdminMessage()
                reply.sessionPasskey = Data(repeating: 7, count: 8)
                sendReply(to: packet, admin: reply)
                return
            }
            if case .setChannel(let channel)? = admin.payloadVariant {
                snapshot.withLock { current in
                    current?.channels[channel.index] = channel
                }
                acknowledge(requestId: packet.id, from: DemoWorld.me)
                return
            }
            if admin.getChannelRequest != 0 {
                var reply = AdminMessage()
                let index = Int32(admin.getChannelRequest) - 1
                if let channel = snapshot.withLock({ $0?.channels[index] }) {
                    reply.getChannelResponse = channel
                }
                sendReply(to: packet, admin: reply)
                return
            }
            acknowledge(requestId: packet.id, from: DemoWorld.me)
        }

        private func sendReply(to packet: MeshPacket, admin: AdminMessage) {
            var data = DataMessage()
            data.portnum = .adminApp
            data.requestID = packet.id
            data.payload = (try? admin.serializedData()) ?? Data()
            push(data, from: DemoWorld.me)
        }

        private func push(_ data: DataMessage, from: Int32) {
            var packet = MeshPacket()
            packet.from = UInt32(bitPattern: from)
            packet.to = UInt32(bitPattern: DemoWorld.me)
            packet.id = UInt32.random(in: 1...UInt32.max)
            packet.rxTime = UInt32(Date.now.timeIntervalSince1970)
            packet.decoded = data
            var message = FromRadio()
            message.packet = packet
            inbound.send(message)
        }
    }
#endif
