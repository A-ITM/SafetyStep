// Local post-production: actual SDK speech + the original synthetic response fixtures.
import AVFoundation
import AppKit
import CoreText
import Foundation

struct Hold: Decodable { let source: Double; let duration: Double }
struct Cue: Decodable {
    let path: String; let start: Double; let duration: Double
    let role: String; let text: String; let label: String
}
struct AudioEdit: Decodable {
    let input: String; let output: String; let holds: [Hold]; let cues: [Cue]
}
let edit = try JSONDecoder().decode(AudioEdit.self, from: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1])))
precondition(!FileManager.default.fileExists(atPath: edit.output), "Choose a new output file")
let source = AVURLAsset(url: URL(fileURLWithPath: edit.input))
let sourceVideo = source.tracks(withMediaType: .video)[0]
let composition = AVMutableComposition()
let video = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid)!
let audio = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid)!
func time(_ seconds: Double) -> CMTime { CMTime(seconds: seconds, preferredTimescale: 60000) }
var cursor = CMTime.zero
var previous = 0.0
for hold in edit.holds {
    precondition(hold.source >= previous && hold.duration > 0 && hold.source < source.duration.seconds)
    let length = time(hold.source - previous)
    try video.insertTimeRange(CMTimeRange(start: time(previous), duration: length), of: sourceVideo, at: cursor)
    cursor = CMTimeAdd(cursor, length)
    // Repeat a genuine frame at the conversation boundary. Do not redraw app UI.
    let frame = CMTime(value: 1, timescale: 30)
    try video.insertTimeRange(CMTimeRange(start: time(hold.source), duration: frame), of: sourceVideo, at: cursor)
    video.scaleTimeRange(CMTimeRange(start: cursor, duration: frame), toDuration: time(hold.duration))
    cursor = CMTimeAdd(cursor, time(hold.duration))
    previous = hold.source
}
let tail = CMTimeSubtract(source.duration, time(previous))
try video.insertTimeRange(CMTimeRange(start: time(previous), duration: tail), of: sourceVideo, at: cursor)
cursor = CMTimeAdd(cursor, tail)
var lastAudioEnd = 0.0
for cue in edit.cues {
    precondition(cue.start >= lastAudioEnd && cue.start + cue.duration <= cursor.seconds)
    let asset = AVURLAsset(url: URL(fileURLWithPath: cue.path))
    let track = asset.tracks(withMediaType: .audio)[0]
    try audio.insertTimeRange(CMTimeRange(start: .zero, duration: time(cue.duration)), of: track, at: time(cue.start))
    lastAudioEnd = cue.start + cue.duration
}
let vc = AVMutableVideoComposition()
vc.renderSize = sourceVideo.naturalSize
vc.frameDuration = CMTime(value: 1, timescale: 30)
let instruction = AVMutableVideoCompositionInstruction()
instruction.timeRange = CMTimeRange(start: .zero, duration: cursor)
instruction.layerInstructions = [AVMutableVideoCompositionLayerInstruction(assetTrack: video)]
vc.instructions = [instruction]
let root = CALayer(); root.frame = CGRect(origin: .zero, size: vc.renderSize)
let videoLayer = CALayer(); videoLayer.frame = root.bounds; root.addSublayer(videoLayer)
let background = NSColor(calibratedRed: 0.035, green: 0.058, blue: 0.10, alpha: 1)
func textLayer(_ string: String, frame: CGRect, size: CGFloat, color: NSColor) -> CALayer {
    let width = Int(frame.width), height = Int(frame.height)
    let ctx = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8,
                        bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(),
                        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    let font = CTFontCreateWithName("AppleSDGothicNeo-SemiBold" as CFString, size, nil)
    let attrs: [NSAttributedString.Key: Any] = [
        NSAttributedString.Key(kCTFontAttributeName as String): font,
        NSAttributedString.Key(kCTForegroundColorAttributeName as String): color.cgColor,
    ]
    let line = CTLineCreateWithAttributedString(NSAttributedString(string: string, attributes: attrs))
    precondition(CTLineGetTypographicBounds(line, nil, nil, nil) <= frame.width)
    ctx.textPosition = CGPoint(x: 0, y: (frame.height - size) / 2 + 6)
    CTLineDraw(line, ctx)
    let layer = CALayer(); layer.frame = frame; layer.contents = ctx.makeImage(); layer.opacity = 0
    return layer
}
func show(_ layer: CALayer, cue: Cue) {
    layer.opacity = 0
    let animation = CAKeyframeAnimation(keyPath: "opacity")
    animation.values = [0, 1, 1, 0]; animation.keyTimes = [0, 0.001, 0.999, 1]
    animation.beginTime = AVCoreAnimationBeginTimeAtZero + cue.start
    animation.duration = cue.duration
    animation.isRemovedOnCompletion = false; animation.fillMode = .both
    layer.add(animation, forKey: "speaker"); root.addSublayer(layer)
}
for cue in edit.cues {
    let color = cue.role == "driver"
        ? NSColor(calibratedRed: 0.46, green: 0.88, blue: 0.73, alpha: 1)
        : NSColor(calibratedRed: 0.5, green: 0.72, blue: 1, alpha: 1)
    show(textLayer(cue.label, frame: CGRect(x: 2220, y: 90, width: 300, height: 40), size: 27, color: color), cue: cue)
    if cue.role == "driver" {
        let panel = CALayer(); panel.frame = CGRect(x: 0, y: 0, width: vc.renderSize.width, height: 88)
        panel.backgroundColor = background.cgColor; show(panel, cue: cue)
        show(textLayer("“" + cue.text + "”", frame: CGRect(x: 64, y: 22, width: 2420, height: 62), size: 42, color: .white), cue: cue)
    }
}
vc.animationTool = AVVideoCompositionCoreAnimationTool(postProcessingAsVideoLayer: videoLayer, in: root)
let exporter = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetHighestQuality)!
exporter.outputURL = URL(fileURLWithPath: edit.output); exporter.outputFileType = .mp4
exporter.videoComposition = vc; exporter.shouldOptimizeForNetworkUse = true
let done = DispatchSemaphore(value: 0)
exporter.exportAsynchronously { done.signal() }; done.wait()
precondition(exporter.status == .completed, String(describing: exporter.error))
print("Exported \(cursor.seconds)s with \(edit.cues.count) voice cues")
