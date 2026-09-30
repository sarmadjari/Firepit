import Foundation
import Testing

func bytes(_ values: UInt8...) -> Data {
    Data(values)
}

func repeatedBytes(count: Int, value: UInt8) -> Data {
    Data(repeating: value, count: count)
}

func incrementingBytes(count: Int) -> Data {
    Data((0..<count).map { UInt8(truncatingIfNeeded: $0) })
}

func hex(_ data: Data) -> String {
    data.map { String(format: "%02x", $0) }.joined()
}

struct SeededRandom {
    private var state: UInt64

    init(_ seed: UInt64) {
        state = seed
    }

    mutating func nextUInt32() -> UInt32 {
        state = state &* 6364136223846793005 &+ 1442695040888963407
        return UInt32(truncatingIfNeeded: state >> 32)
    }

    mutating func nextInt32() -> Int32 {
        Int32(bitPattern: nextUInt32())
    }

    mutating func nextInt(in range: Range<Int>) -> Int {
        let width = UInt32(range.upperBound - range.lowerBound)
        return range.lowerBound + Int(nextUInt32() % width)
    }
}

func expectFailureMessage(_ body: () throws -> Void) -> String {
    do {
        try body()
        Issue.record("expected this block to throw")
        return ""
    } catch {
        return String(describing: error)
    }
}
