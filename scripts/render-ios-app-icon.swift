#!/usr/bin/env swift
// Renders the iOS app icon (1024 px) from the Firepit Android launcher icon, so both platforms show the same mark.
// Source of truth, in this repository: design/brand/firepit-icon.svg as drawn by
// android/app/src/main/res/drawable/ic_launcher_{background,foreground}.xml — ember gradient ground, cream flame with
// an even-odd hollow, and the pit rim; the launcher shifts the artwork up 18 units so it sits visually centred.
// The tinted variant is the one-colour mark (design/brand/firepit-mark.svg, the adaptive icon's monochrome layer).
// Run from the repo root: swift scripts/render-ios-app-icon.swift
import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

let size = 1024
let outDir = URL(fileURLWithPath: "ios/Firepit/Assets.xcassets/AppIcon.appiconset")

func srgb(_ hex: UInt32, alpha: CGFloat = 1) -> CGColor {
    CGColor(srgbRed: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255, alpha: alpha)
}

enum Ground { case ember, transparent }

struct Variant {
    let file: String
    let ground: Ground
    let ink: CGColor
}

/// Maps the SVG's 512-unit, y-down viewBox onto the whole bitmap, with the launcher's 18-unit upward shift.
func mapSvgCanvas(_ ctx: CGContext) {
    let side = CGFloat(size)
    ctx.translateBy(x: 0, y: side)
    ctx.scaleBy(x: side / 512, y: -side / 512)
    ctx.translateBy(x: 0, y: -18)
}

/// "M 262 122 C … Z M 256 248 C … Z", filled even-odd so the ground burns through the hollow.
func flamePath() -> CGPath {
    let path = CGMutablePath()
    func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: x, y: y) }
    path.move(to: p(262, 122))
    path.addCurve(to: p(336, 268), control1: p(306, 174), control2: p(336, 216))
    path.addCurve(to: p(256, 356), control1: p(336, 320), control2: p(300, 356))
    path.addCurve(to: p(176, 270), control1: p(212, 356), control2: p(176, 320))
    path.addCurve(to: p(216, 182), control1: p(176, 236), control2: p(192, 206))
    path.addCurve(to: p(240, 252), control1: p(214, 214), control2: p(222, 238))
    path.addCurve(to: p(262, 122), control1: p(258, 224), control2: p(266, 172))
    path.closeSubpath()
    path.move(to: p(256, 248))
    path.addCurve(to: p(291, 311), control1: p(276, 274), control2: p(291, 289))
    path.addCurve(to: p(256, 344), control1: p(291, 332), control2: p(276, 344))
    path.addCurve(to: p(221, 311), control1: p(236, 344), control2: p(221, 332))
    path.addCurve(to: p(256, 248), control1: p(221, 289), control2: p(236, 274))
    path.closeSubpath()
    return path
}

/// "M 170 384 Q 256 416 342 384", stroked 22 wide with round caps.
func rimPath() -> CGPath {
    let path = CGMutablePath()
    path.move(to: CGPoint(x: 170, y: 384))
    path.addQuadCurve(to: CGPoint(x: 342, y: 384), control: CGPoint(x: 256, y: 416))
    return path
}

func render(_ variant: Variant) throws {
    let alpha: CGImageAlphaInfo = variant.ground == .transparent ? .premultipliedLast : .noneSkipLast
    guard let space = CGColorSpace(name: CGColorSpace.sRGB),
          let ctx = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0,
                              space: space, bitmapInfo: alpha.rawValue)
    else { throw CocoaError(.fileWriteUnknown) }
    ctx.setShouldAntialias(true)
    ctx.interpolationQuality = .high

    if variant.ground == .ember {
        // Vertical gradient, #E2611A at the top to #A8350A at the bottom (CoreGraphics is y-up).
        let colors = [srgb(0xE2611A), srgb(0xA8350A)] as CFArray
        guard let gradient = CGGradient(colorsSpace: space, colors: colors, locations: [0, 1]) else {
            throw CocoaError(.fileWriteUnknown)
        }
        ctx.drawLinearGradient(gradient, start: CGPoint(x: 0, y: size), end: .zero, options: [])
    }

    mapSvgCanvas(ctx)
    ctx.addPath(flamePath())
    ctx.setFillColor(variant.ink)
    ctx.fillPath(using: .evenOdd)
    ctx.addPath(rimPath())
    ctx.setStrokeColor(variant.ink)
    ctx.setLineWidth(22)
    ctx.setLineCap(.round)
    ctx.strokePath()

    let url = outDir.appendingPathComponent(variant.file)
    guard let image = ctx.makeImage(),
          let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil)
    else { throw CocoaError(.fileWriteUnknown) }
    CGImageDestinationAddImage(destination, image, nil)
    guard CGImageDestinationFinalize(destination) else { throw CocoaError(.fileWriteUnknown) }
    print("wrote \(url.path)")
}

let cream = srgb(0xFFF4EC)
// Android has no separate dark launcher icon, so dark mode shows the same ember icon.
try render(Variant(file: "AppIcon.png", ground: .ember, ink: cream))
try render(Variant(file: "AppIcon-Dark.png", ground: .ember, ink: cream))
// iOS tints the luminance of a grayscale icon; the mark goes white on a transparent ground.
try render(Variant(file: "AppIcon-Tinted.png", ground: .transparent, ink: srgb(0xFFFFFF)))
