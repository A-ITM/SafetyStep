// Local-only macOS export: ordered clips, cropped browser chrome, timed Korean captions.
import AVFoundation
import AppKit
import CoreText
import Foundation

struct Segment: Decodable { let path: String; let start: Double; let duration: Double; let chapter: String }
struct Caption: Decodable { let start: Double; let end: Double; let chapter: String; let text: String }
struct Edit: Decodable { let segments: [Segment]; let captions: [Caption]; let output: String }
let edit=try JSONDecoder().decode(Edit.self,from:Data(contentsOf:URL(fileURLWithPath:CommandLine.arguments[1])))
precondition(!FileManager.default.fileExists(atPath:edit.output), "Choose a new output path")
precondition(!edit.segments.isEmpty)
let total=edit.segments.reduce(0.0) { $0+$1.duration }
for s in edit.segments { precondition(s.start>=0 && s.duration>0) }
for c in edit.captions { precondition(c.start>=0 && c.end>c.start && c.end<=total+0.01) }
let composition=AVMutableComposition()
let video=composition.addMutableTrack(withMediaType:.video,preferredTrackID:kCMPersistentTrackID_Invalid)!
var cursor=CMTime.zero
for segment in edit.segments {
 let asset=AVURLAsset(url:URL(fileURLWithPath:segment.path));let track=asset.tracks(withMediaType:.video)[0]
 precondition(track.naturalSize.width==2560 && track.naturalSize.height==1410, "Expected the dedicated 2560×1410 browser window")
 precondition(segment.start+segment.duration<=asset.duration.seconds, "Trim exceeds source duration")
 try video.insertTimeRange(CMTimeRange(start:CMTime(seconds:segment.start,preferredTimescale:600),duration:CMTime(seconds:segment.duration,preferredTimescale:600)),of:track,at:cursor)
 cursor=CMTimeAdd(cursor,CMTime(seconds:segment.duration,preferredTimescale:600))
}
let vc=AVMutableVideoComposition();vc.renderSize=CGSize(width:2560,height:1440);vc.frameDuration=CMTime(value:1,timescale:30)
let instruction=AVMutableVideoCompositionInstruction();instruction.timeRange=CMTimeRange(start:.zero,duration:cursor)
let layerInstruction=AVMutableVideoCompositionLayerInstruction(assetTrack:video)
// Remove browser chrome, retain all 1288px of the app/console, and add a separate caption band.
layerInstruction.setTransform(CGAffineTransform(translationX:0,y:-122),at:.zero)
instruction.layerInstructions=[layerInstruction];vc.instructions=[instruction]
let root=CALayer();root.frame=CGRect(x:0,y:0,width:2560,height:1440);root.backgroundColor=NSColor(calibratedRed:0.035,green:0.058,blue:0.10,alpha:1).cgColor
let videoLayer=CALayer();videoLayer.frame=root.bounds;root.addSublayer(videoLayer)
let band=CALayer();band.frame=CGRect(x:0,y:0,width:2560,height:152);band.backgroundColor=NSColor(calibratedRed:0.035,green:0.058,blue:0.10,alpha:1).cgColor;root.addSublayer(band)
let border=CALayer();border.frame=CGRect(x:48,y:150,width:2464,height:2);border.backgroundColor=NSColor(calibratedRed:0.2,green:0.3,blue:0.43,alpha:1).cgColor;root.addSublayer(border)
func textLayer(_ text:String,frame:CGRect,size:CGFloat,color:NSColor,weight:NSFont.Weight)->CALayer {
 let width=Int(frame.width), height=Int(frame.height)
 let ctx=CGContext(data:nil,width:width,height:height,bitsPerComponent:8,bytesPerRow:width*4,space:CGColorSpaceCreateDeviceRGB(),bitmapInfo:CGImageAlphaInfo.premultipliedLast.rawValue)!
 let font=CTFontCreateWithName("AppleSDGothicNeo-SemiBold" as CFString,size,nil)
 let attrs:[NSAttributedString.Key:Any]=[NSAttributedString.Key(kCTFontAttributeName as String):font,NSAttributedString.Key(kCTForegroundColorAttributeName as String):color.cgColor]
 let line=CTLineCreateWithAttributedString(NSAttributedString(string:text,attributes:attrs))
 precondition(CTLineGetTypographicBounds(line,nil,nil,nil)<=frame.width, "Caption does not fit")
 ctx.textPosition=CGPoint(x:0,y:(frame.height-size)/2+6);CTLineDraw(line,ctx)
 let l=CALayer();l.frame=frame;l.contents=ctx.makeImage()!;l.contentsScale=1;l.opacity=0
 return l
}
func show(_ layer:CALayer,start:Double,end:Double) {
 let anim=CAKeyframeAnimation(keyPath:"opacity");anim.values=[0,1,1,0];anim.keyTimes=[0,0.001,0.999,1];anim.beginTime=AVCoreAnimationBeginTimeAtZero+start;anim.duration=end-start;anim.isRemovedOnCompletion=false;anim.fillMode = .both;layer.add(anim,forKey:"subtitle")
 root.addSublayer(layer)
}
for c in edit.captions {
 let title=textLayer(c.chapter,frame:CGRect(x:64,y:90,width:2420,height:40),size:27,color:NSColor(calibratedRed:0.5,green:0.72,blue:1,alpha:1),weight:.semibold)
 let body=textLayer(c.text,frame:CGRect(x:64,y:22,width:2420,height:62),size:42,color:.white,weight:.semibold)
 show(title,start:c.start,end:c.end);show(body,start:c.start,end:c.end)
}
vc.animationTool=AVVideoCompositionCoreAnimationTool(postProcessingAsVideoLayer:videoLayer,in:root)
let export=AVAssetExportSession(asset:composition,presetName:AVAssetExportPresetHighestQuality)!
export.outputURL=URL(fileURLWithPath:edit.output);export.outputFileType = .mp4;export.videoComposition=vc;export.shouldOptimizeForNetworkUse=true
let done=DispatchSemaphore(value:0);export.exportAsynchronously{done.signal()};done.wait()
if export.status != .completed {fatalError(String(describing:export.error))}
print("Exported \(cursor.seconds) seconds / 2560x1440 / 30 fps")
