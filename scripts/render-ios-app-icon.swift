#!/usr/bin/env swift
// Renders the iOS app icon from design/artwork/icon-app.svg, the artwork both apps use. Android draws the same file as
// vectors (android/app/src/main/res/drawable/ic_launcher_{background,foreground}.xml); after changing the artwork,
// update those and run this.
//
// Light and dark are the artwork itself: Android has no dark launcher icon, so dark mode shows the same one. Tinted is
// the artwork's white shapes alone on a clear ground, which iOS tints; like Android's themed icon it keeps the rim, so
// the icon does not change shape between modes.
//
// Reads only the SVG that Affinity Designer exports — groups with matrix transforms, rects and paths, solid and
// gradient fills — and stops on anything else rather than drawing it wrong.
// Run from the repo root: swift scripts/render-ios-app-icon.swift
import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

let artwork = URL(fileURLWithPath: "design/artwork/icon-app.svg")
let outDir = URL(fileURLWithPath: "ios/Firepit/Assets.xcassets/AppIcon.appiconset")
let size = 1024

struct Unsupported: Error, CustomStringConvertible {
    let description: String
    init(_ description: String) { self.description = description }
}

// MARK: - Reading the SVG

enum Paint {
    case none
    case color(CGColor)
    case gradient(id: String)
}

struct Shape {
    let path: CGPath
    let transform: CGAffineTransform
    let paint: Paint
    let evenOdd: Bool
}

struct Gradient {
    enum Geometry {
        case linear(start: CGPoint, end: CGPoint)
        case radial(center: CGPoint, radius: CGFloat, focus: CGPoint)
    }
    var geometry: Geometry
    var transform: CGAffineTransform
    var boundingBoxUnits: Bool
    var stops: [(offset: CGFloat, color: CGColor)] = []
}

func srgb(_ hex: UInt32, alpha: CGFloat = 1) -> CGColor {
    CGColor(
        srgbRed: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
        blue: CGFloat(hex & 0xFF) / 255, alpha: alpha)
}

func number(_ text: String?, default fallback: CGFloat? = nil) throws -> CGFloat {
    guard let text = text?.trimmingCharacters(in: .whitespaces), !text.isEmpty else {
        if let fallback { return fallback }
        throw Unsupported("missing number")
    }
    guard let value = Double(text) else { throw Unsupported("not a plain number: \(text)") }
    return CGFloat(value)
}

/// `style="a:b;c:d"` merged over the presentation attributes, as SVG's cascade has it.
func properties(_ attributes: [String: String]) -> [String: String] {
    var merged = attributes
    for declaration in (attributes["style"] ?? "").split(separator: ";") {
        let pair = declaration.split(separator: ":", maxSplits: 1).map { $0.trimmingCharacters(in: .whitespaces) }
        if pair.count == 2 { merged[pair[0]] = pair[1] }
    }
    return merged
}

func color(_ text: String) throws -> CGColor {
    var hex = text.trimmingCharacters(in: .whitespaces)
    guard hex.hasPrefix("#") else { throw Unsupported("colour \(text)") }
    hex.removeFirst()
    if hex.count == 3 { hex = hex.map { "\($0)\($0)" }.joined() }
    guard hex.count == 6, let value = UInt32(hex, radix: 16) else { throw Unsupported("colour \(text)") }
    return srgb(value)
}

func paint(_ text: String) throws -> Paint {
    if text == "none" { return .none }
    if text.hasPrefix("url(#"), text.hasSuffix(")") {
        return .gradient(id: String(text.dropFirst(5).dropLast()))
    }
    return .color(try color(text))
}

/// `matrix(a,b,c,d,e,f)`, `translate(x[,y])` and `scale(x[,y])`, applied in order.
func transform(_ text: String?) throws -> CGAffineTransform {
    guard let text else { return .identity }
    var result = CGAffineTransform.identity
    let pattern = try NSRegularExpression(pattern: #"(\w+)\s*\(([^)]*)\)"#)
    for match in pattern.matches(in: text, range: NSRange(text.startIndex..., in: text)) {
        let name = String(text[Range(match.range(at: 1), in: text)!])
        let values = try text[Range(match.range(at: 2), in: text)!]
            .split(whereSeparator: { $0 == "," || $0 == " " }).map { try number(String($0)) }
        let step: CGAffineTransform
        switch (name, values.count) {
        case ("matrix", 6):
            step = CGAffineTransform(
                a: values[0], b: values[1], c: values[2], d: values[3], tx: values[4], ty: values[5])
        case ("translate", 1), ("translate", 2):
            step = CGAffineTransform(translationX: values[0], y: values.count == 2 ? values[1] : 0)
        case ("scale", 1), ("scale", 2):
            step = CGAffineTransform(scaleX: values[0], y: values.count == 2 ? values[1] : values[0])
        default:
            throw Unsupported("transform \(name)")
        }
        result = step.concatenating(result)
    }
    return result
}

/// Path data: M L H V C S Q T Z, absolute and relative. Arcs are not part of what Affinity exports.
func pathData(_ data: String) throws -> CGPath {
    let tokens = try NSRegularExpression(pattern: #"[A-Za-z]|[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?"#)
        .matches(in: data, range: NSRange(data.startIndex..., in: data))
        .map { String(data[Range($0.range, in: data)!]) }
    let path = CGMutablePath()
    var index = 0
    var command: Character = "M"
    var current = CGPoint.zero
    var start = CGPoint.zero
    var lastControl: CGPoint?
    var lastCommand: Character = "M"

    func next() throws -> CGFloat {
        guard index < tokens.count, let value = Double(tokens[index]) else { throw Unsupported("path data \(data)") }
        index += 1
        return CGFloat(value)
    }
    func point(_ relative: Bool) throws -> CGPoint {
        let x = try next()
        let y = try next()
        return relative ? CGPoint(x: current.x + x, y: current.y + y) : CGPoint(x: x, y: y)
    }
    func reflected() -> CGPoint {
        guard let lastControl else { return current }
        return CGPoint(x: 2 * current.x - lastControl.x, y: 2 * current.y - lastControl.y)
    }

    while index < tokens.count {
        let before = index
        if let letter = tokens[index].first, letter.isLetter {
            command = letter
            index += 1
        }
        let relative = command.isLowercase
        var control: CGPoint?
        switch command.uppercased() {
        case "M":
            current = try point(relative)
            start = current
            path.move(to: current)
            // Further pairs after a move are lines.
            command = relative ? "l" : "L"
        case "L":
            current = try point(relative)
            path.addLine(to: current)
        case "H":
            let x = try next()
            current = CGPoint(x: relative ? current.x + x : x, y: current.y)
            path.addLine(to: current)
        case "V":
            let y = try next()
            current = CGPoint(x: current.x, y: relative ? current.y + y : y)
            path.addLine(to: current)
        case "C", "S":
            let first =
                command.uppercased() == "C" ? try point(relative) : ("CS".contains(lastCommand) ? reflected() : current)
            let second = try point(relative)
            let end = try point(relative)
            path.addCurve(to: end, control1: first, control2: second)
            control = second
            current = end
        case "Q", "T":
            let mid =
                command.uppercased() == "Q" ? try point(relative) : ("QT".contains(lastCommand) ? reflected() : current)
            let end = try point(relative)
            path.addQuadCurve(to: end, control: mid)
            control = mid
            current = end
        case "Z":
            path.closeSubpath()
            current = start
        default:
            throw Unsupported("path command \(command)")
        }
        // Numbers straight after a close have no command to belong to; stop rather than loop on them.
        guard index > before else { throw Unsupported("path data \(data)") }
        lastControl = control
        lastCommand = Character(command.uppercased())
    }
    return path
}

final class Reader: NSObject, XMLParserDelegate {
    struct Context {
        var transform: CGAffineTransform
        var fill: String
        var fillRule: String
        /// Inside <defs>, <clipPath> and the like, which are referenced rather than drawn.
        var hidden: Bool
    }

    var canvas = CGRect.zero
    var shapes: [Shape] = []
    var gradients: [String: Gradient] = [:]
    var failure: Error?
    private var stack = [Context(transform: .identity, fill: "#000", fillRule: "nonzero", hidden: false)]
    private var openGradient: String?
    /// Clip paths that are a single rect, by id. Affinity clips everything to the artboard, which the bitmap's own
    /// edges already do; any other clip is refused.
    private var clipRects: [String: CGRect] = [:]
    private var openClip: String?

    func parser(
        _ parser: XMLParser, didStartElement element: String, namespaceURI: String?, qualifiedName: String?,
        attributes: [String: String] = [:]
    ) {
        do {
            try start(element, properties(attributes))
        } catch {
            failure = error
            parser.abortParsing()
        }
    }

    func parser(_ parser: XMLParser, didEndElement element: String, namespaceURI: String?, qualifiedName: String?) {
        if element == "linearGradient" || element == "radialGradient" { openGradient = nil }
        if element == "clipPath" { openClip = nil }
        stack.removeLast()
    }

    private func start(_ element: String, _ props: [String: String]) throws {
        let parent = stack.last!
        var context = parent
        context.transform = try transform(props["transform"]).concatenating(parent.transform)
        if let fill = props["fill"] { context.fill = fill }
        if let rule = props["fill-rule"] { context.fillRule = rule }
        if ["defs", "clipPath", "mask", "symbol", "pattern"].contains(element) { context.hidden = true }
        stack.append(context)

        if let stroke = props["stroke"], stroke != "none" {
            throw Unsupported("strokes: outline them in the artwork (Layer > Expand Stroke)")
        }
        for key in ["opacity", "fill-opacity"] where props[key].map({ Double($0) != 1 }) ?? false {
            throw Unsupported("\(key) below 1")
        }
        if let clip = props["clip-path"] {
            let id = clip.hasPrefix("url(#") ? String(clip.dropFirst(5).dropLast()) : clip
            guard clipRects[id] == canvas else { throw Unsupported("a clip-path other than the artboard") }
        }
        switch element {
        case "svg":
            let box = try (props["viewBox"] ?? "").split(whereSeparator: { $0 == " " || $0 == "," })
                .map { try number(String($0)) }
            guard box.count == 4 else { throw Unsupported("svg without a viewBox") }
            canvas = CGRect(x: box[0], y: box[1], width: box[2], height: box[3])
        case "clipPath":
            openClip = props["id"]
        case "rect":
            let rect = CGRect(
                x: try number(props["x"], default: 0), y: try number(props["y"], default: 0),
                width: try number(props["width"]), height: try number(props["height"]))
            guard props["rx"] == nil, props["ry"] == nil else { throw Unsupported("rounded rect") }
            if let clip = openClip {
                // Kept only while the clip is one untransformed rect; a second shape makes it something else.
                clipRects[clip] = clipRects[clip] == nil && props["transform"] == nil ? rect : .null
            } else if !context.hidden {
                try add(CGPath(rect: rect, transform: nil), context)
            }
        case "path" where openClip != nil:
            clipRects[openClip!] = .null
        case "path" where !context.hidden:
            try add(try pathData(props["d"] ?? ""), context)
        case "linearGradient", "radialGradient":
            guard let id = props["id"] else { throw Unsupported("gradient without an id") }
            let geometry: Gradient.Geometry
            if element == "linearGradient" {
                geometry = .linear(
                    start: CGPoint(x: try number(props["x1"], default: 0), y: try number(props["y1"], default: 0)),
                    end: CGPoint(x: try number(props["x2"], default: 1), y: try number(props["y2"], default: 0)))
            } else {
                let center = CGPoint(x: try number(props["cx"], default: 0.5), y: try number(props["cy"], default: 0.5))
                geometry = .radial(
                    center: center, radius: try number(props["r"], default: 0.5),
                    focus: CGPoint(
                        x: try number(props["fx"], default: center.x), y: try number(props["fy"], default: center.y)))
            }
            guard props["xlink:href"] == nil, props["href"] == nil else { throw Unsupported("inherited gradient") }
            guard (props["spreadMethod"] ?? "pad") == "pad" else { throw Unsupported("gradient spreadMethod") }
            gradients[id] = Gradient(
                geometry: geometry, transform: try transform(props["gradientTransform"]),
                boundingBoxUnits: (props["gradientUnits"] ?? "objectBoundingBox") == "objectBoundingBox")
            openGradient = id
        case "stop":
            guard let id = openGradient else { return }
            let opacity = try number(props["stop-opacity"], default: 1)
            let base = try color(props["stop-color"] ?? "#000")
            gradients[id]?.stops.append((try number(props["offset"], default: 0), base.copy(alpha: opacity)!))
        case "circle", "ellipse", "line", "polyline", "polygon", "text", "image", "use":
            if !context.hidden { throw Unsupported("<\(element)>") }
        default:
            break
        }
    }

    private func add(_ path: CGPath, _ context: Context) throws {
        shapes.append(
            Shape(
                path: path, transform: context.transform, paint: try paint(context.fill),
                evenOdd: context.fillRule == "evenodd"))
    }
}

// MARK: - Drawing

enum Variant {
    /// The artwork as drawn.
    case full
    /// Every solid shape in white, and nothing else, on a clear ground.
    case shapesOnly
}

func render(_ reader: Reader, _ variant: Variant, to file: String) throws {
    // Drawn at 16 bits a channel and rounded to 8 below: CoreGraphics dithers a gradient drawn straight into 8 bits,
    // which makes the PNG four times the size for a difference nobody can see at icon sizes.
    let fine = CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue).union(.byteOrder16Little)
    guard let space = CGColorSpace(name: CGColorSpace.sRGB),
        let ctx = CGContext(
            data: nil, width: size, height: size, bitsPerComponent: 16, bytesPerRow: size * 8, space: space,
            bitmapInfo: fine.rawValue)
    else { throw Unsupported("bitmap context") }
    ctx.setShouldAntialias(true)
    ctx.interpolationQuality = .high
    // SVG units, y down, onto the bitmap.
    ctx.translateBy(x: 0, y: CGFloat(size))
    ctx.scaleBy(x: CGFloat(size) / reader.canvas.width, y: -CGFloat(size) / reader.canvas.height)
    ctx.translateBy(x: -reader.canvas.minX, y: -reader.canvas.minY)

    for shape in reader.shapes {
        ctx.saveGState()
        ctx.concatenate(shape.transform)
        // The current path is not part of the saved state, so a shape that is skipped would otherwise leave its
        // outline behind for the next one to fill or clip with.
        ctx.beginPath()
        ctx.addPath(shape.path)
        let rule: CGPathFillRule = shape.evenOdd ? .evenOdd : .winding
        switch (shape.paint, variant) {
        case (.none, _), (.gradient, .shapesOnly):
            break
        case (.color(let color), .full):
            ctx.setFillColor(color)
            ctx.fillPath(using: rule)
        case (.color, .shapesOnly):
            ctx.setFillColor(srgb(0xFFFFFF))
            ctx.fillPath(using: rule)
        case (.gradient(let id), .full):
            guard let gradient = reader.gradients[id], !gradient.stops.isEmpty,
                let shading = CGGradient(
                    colorsSpace: space, colors: gradient.stops.map(\.color) as CFArray,
                    locations: gradient.stops.map(\.offset))
            else { throw Unsupported("gradient \(id)") }
            let box = shape.path.boundingBoxOfPath
            ctx.clip(using: rule)
            if gradient.boundingBoxUnits {
                ctx.concatenate(CGAffineTransform(a: box.width, b: 0, c: 0, d: box.height, tx: box.minX, ty: box.minY))
            }
            ctx.concatenate(gradient.transform)
            let pad: CGGradientDrawingOptions = [.drawsBeforeStartLocation, .drawsAfterEndLocation]
            switch gradient.geometry {
            case .linear(let start, let end):
                ctx.drawLinearGradient(shading, start: start, end: end, options: pad)
            case .radial(let center, let radius, let focus):
                ctx.drawRadialGradient(
                    shading, startCenter: focus, startRadius: 0, endCenter: center, endRadius: radius, options: pad)
            }
        }
        ctx.restoreGState()
    }

    let opaque = variant == .full
    guard let wide = ctx.data?.assumingMemoryBound(to: UInt16.self) else { throw Unsupported("bitmap data") }
    var narrow = [UInt8](repeating: 0, count: size * size * 4)
    for index in narrow.indices {
        // The icon is opaque everywhere the ground is drawn; iOS wants no alpha channel there at all.
        narrow[index] = opaque && index % 4 == 3 ? 255 : UInt8((UInt32(UInt16(littleEndian: wide[index])) + 128) / 257)
    }
    let image = try narrow.withUnsafeMutableBytes { bytes -> CGImage in
        guard
            let output = CGContext(
                data: bytes.baseAddress, width: size, height: size, bitsPerComponent: 8, bytesPerRow: size * 4,
                space: space, bitmapInfo: (opaque ? CGImageAlphaInfo.noneSkipLast : .premultipliedLast).rawValue),
            let image = output.makeImage()
        else { throw Unsupported("8-bit image") }
        return image
    }

    let url = outDir.appendingPathComponent(file)
    guard
        let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil)
    else { throw Unsupported("PNG encoder") }
    CGImageDestinationAddImage(destination, image, nil)
    guard CGImageDestinationFinalize(destination) else { throw Unsupported("writing \(url.path)") }
    print("wrote \(url.path)")
}

let reader = Reader()
guard let parser = XMLParser(contentsOf: artwork) else {
    FileHandle.standardError.write("cannot read \(artwork.path); run from the repo root\n".data(using: .utf8)!)
    exit(1)
}
parser.delegate = reader
guard parser.parse(), reader.failure == nil, !reader.canvas.isEmpty else {
    let reason = reader.failure.map { "\($0)" } ?? parser.parserError.map { "\($0)" } ?? "no viewBox"
    FileHandle.standardError.write("\(artwork.path): unsupported SVG: \(reason)\n".data(using: .utf8)!)
    exit(1)
}
try render(reader, .full, to: "AppIcon.png")
try render(reader, .full, to: "AppIcon-Dark.png")
try render(reader, .shapesOnly, to: "AppIcon-Tinted.png")
