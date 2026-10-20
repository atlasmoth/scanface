// Run from the repository root: swift iosApp/Tools/GenerateAppIcon.swift
// Mirrors androidApp/src/main/res/drawable/ic_launcher.xml.
import AppKit
import ImageIO
import UniformTypeIdentifiers

let edge = 1024
let context = CGContext(
    data: nil, width: edge, height: edge, bitsPerComponent: 8, bytesPerRow: 0,
    space: CGColorSpace(name: CGColorSpace.sRGB)!,
    bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
)!
context.setFillColor(CGColor(srgbRed: 36/255, green: 92/255, blue: 69/255, alpha: 1))
context.fill(CGRect(x: 0, y: 0, width: edge, height: edge))
context.translateBy(x: 0, y: CGFloat(edge))
context.scaleBy(x: CGFloat(edge)/108, y: -CGFloat(edge)/108)
context.setStrokeColor(CGColor(srgbRed: 246/255, green: 247/255, blue: 242/255, alpha: 1))
context.setLineWidth(4)
context.setLineCap(.round)
context.move(to: CGPoint(x: 33, y: 50))
context.addCurve(to: CGPoint(x: 75, y: 50), control1: CGPoint(x: 33, y: 19), control2: CGPoint(x: 75, y: 19))
context.move(to: CGPoint(x: 33, y: 60))
context.addCurve(to: CGPoint(x: 75, y: 60), control1: CGPoint(x: 33, y: 91), control2: CGPoint(x: 75, y: 91))
context.move(to: CGPoint(x: 41, y: 54))
context.addLine(to: CGPoint(x: 44, y: 54))
context.move(to: CGPoint(x: 64, y: 54))
context.addLine(to: CGPoint(x: 67, y: 54))
context.move(to: CGPoint(x: 51, y: 65))
context.addQuadCurve(to: CGPoint(x: 57, y: 65), control: CGPoint(x: 54, y: 68))
context.strokePath()
let output = URL(fileURLWithPath: "iosApp/ScanFace/Assets.xcassets/AppIcon.appiconset/AppIcon.png")
try FileManager.default.createDirectory(at: output.deletingLastPathComponent(), withIntermediateDirectories: true)
let destination = CGImageDestinationCreateWithURL(output as CFURL, UTType.png.identifier as CFString, 1, nil)!
CGImageDestinationAddImage(destination, context.makeImage()!, nil)
precondition(CGImageDestinationFinalize(destination))
