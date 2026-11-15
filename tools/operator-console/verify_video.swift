import AVFoundation
import AppKit
import Vision
struct Caption:Decodable{let start:Double;let end:Double;let chapter:String;let text:String}
struct Edit:Decodable{let captions:[Caption];let output:String}
let input=URL(fileURLWithPath:CommandLine.arguments[1]);let edit=try JSONDecoder().decode(Edit.self,from:Data(contentsOf:input))
let dir=input.deletingLastPathComponent().appendingPathComponent("final-video-qa",isDirectory:true);try FileManager.default.createDirectory(at:dir,withIntermediateDirectories:true)
let asset=AVURLAsset(url:URL(fileURLWithPath:edit.output));let generator=AVAssetImageGenerator(asset:asset);generator.appliesPreferredTrackTransform=true;generator.requestedTimeToleranceBefore = .zero;generator.requestedTimeToleranceAfter = .zero
var results:[[String:Any]]=[]
for (i,c) in edit.captions.enumerated(){
 let second=(c.start+c.end)/2
 let frame=try generator.copyCGImage(at:CMTime(seconds:second,preferredTimescale:600),actualTime:nil)
 let name=String(format:"%02d-%06.2f.png",i+1,second)
 try NSBitmapImageRep(cgImage:frame).representation(using:.png,properties:[:])!.write(to:dir.appendingPathComponent(name))
 let strip=frame.cropping(to:CGRect(x:0,y:1288,width:2560,height:152))!
 let req=VNRecognizeTextRequest();req.recognitionLevel = .accurate;req.recognitionLanguages=["ko-KR","en-US"];req.usesLanguageCorrection=false
 try VNImageRequestHandler(cgImage:strip).perform([req])
 let lines=(req.results ?? []).compactMap{$0.topCandidates(1).first?.string}
 results.append(["frame":name,"second":second,"expected":c.text,"recognized":lines])
 print(name,lines.joined(separator:" | "))
}
let track=asset.tracks(withMediaType:.video)[0]
let report:[String:Any]=["duration":asset.duration.seconds,"width":track.naturalSize.width,"height":track.naturalSize.height,"fps":track.nominalFrameRate,"audio_tracks":asset.tracks(withMediaType:.audio).count,"captions":results]
try JSONSerialization.data(withJSONObject:report,options:[.prettyPrinted,.sortedKeys]).write(to:dir.appendingPathComponent("qa.json"))
