/// One step along a traced path.
public struct TraceHop: Hashable, Sendable {
    public var nodeNum: Int32
    /// Signal into this hop, or nil when the responder did not report one.
    public var snr: Float?

    public init(nodeNum: Int32, snr: Float?) {
        self.nodeNum = nodeNum
        self.snr = snr
    }
}

/// A traceroute reply, turned into something displayable.
///
/// The route and SNR lists are parallel but the firmware does not guarantee they
/// are the same length, so hops are paired defensively.
public struct TraceRouteResult: Hashable, Sendable {
    public var target: Int32
    public var towards: [TraceHop]
    public var back: [TraceHop]

    public init(target: Int32, towards: [TraceHop], back: [TraceHop]) {
        self.target = target
        self.towards = towards
        self.back = back
    }

    /// True when the target answered directly, with nobody relaying.
    public var isDirect: Bool {
        towards.isEmpty
    }

    /// Hops out to the target, counting the target itself.
    public var hopsOut: Int {
        towards.count + 1
    }

    /// The firmware sends SNR in quarter-decibels to keep it an integer.
    public static let snrScale: Float = 4

    public static func from(
        target: Int32,
        route: [Int32],
        snrTowards: [Int32],
        routeBack: [Int32],
        snrBack: [Int32]
    ) -> TraceRouteResult {
        TraceRouteResult(
            target: target,
            towards: pair(route: route, snr: snrTowards),
            back: pair(route: routeBack, snr: snrBack)
        )
    }

    private static func pair(route: [Int32], snr: [Int32]) -> [TraceHop] {
        route.enumerated().map { index, nodeNum in
            let reading = snr.indices.contains(index) ? snr[index] : nil
            let value = reading == unknownSnr ? nil : reading.map { Float($0) / snrScale }
            return TraceHop(nodeNum: nodeNum, snr: value)
        }
    }

    /// What the firmware sends when a hop reported no usable signal reading.
    private static let unknownSnr: Int32 = -128
}
