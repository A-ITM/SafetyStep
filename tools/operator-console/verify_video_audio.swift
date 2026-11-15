import AVFoundation
import AppKit
import Vision
import Foundation
struct Cue:Decodable {let path:String;let start:Double;let duration:Double;let role:String;let text:String;let label:String}
struct Edit:Decodable {let output:String;let cues:[Cue]}
let file=URL(fileURLWithPath:CommandLine.arguments[1]);let edit=try JSONDecoder().decode(Edit.self,from:Data(contentsOf:file));let dir=file.deletingLastPathComponent().appendingPathComponent("audio-video-qa",isDirectory:true);try FileManager.default.createDirectory(at:dir,withIntermediateDirectories:true)
let asset=AVURLAsset(url:URL(fileURLWithPath:edit.output));let vt=asset.tracks(withMediaType:.video)[0];let ats=asset.tracks(withMediaType:.audio);precondition(ats.count==1)
let gen=AVAssetImageGenerator(asset:asset);gen.requestedTimeToleranceBefore = .zero;gen.requestedTimeToleranceAfter = .zero
var frames:[[String:Any]]=[]
for (i,c) in edit.cues.enumerated(){
 let t=c.start+c.duration/2;let cg=try gen.copyCGImage(at:CMTime(seconds:t,preferredTimescale:60000),actualTime:nil);let name=String(format:"%02d-%06.2f.png",i+1,t)
 try NSBitmapImageRep(cgImage:cg).representation(using:.png,properties:[:])!.write(to:dir.appendingPathComponent(name))
 let crop=cg.cropping(to:CGRect(x:0,y:1288,width:2560,height:152))!;let r=VNRecognizeTextRequest();r.recognitionLevel = .accurate;r.recognitionLanguages=["ko-KR","en-US"];try VNImageRequestHandler(cgImage:crop).perform([r]);let texts=(r.results ?? []).compactMap{$0.topCandidates(1).first?.string}
 frames.append(["frame":name,"second":t,"role":c.role,"text":c.text,"recognized":texts]);print(name,texts.joined(separator:" | "))
}
let reader=try AVAssetReader(asset:asset)
let output=AVAssetReaderTrackOutput(track:ats[0],outputSettings:[AVFormatIDKey:kAudioFormatLinearPCM,AVSampleRateKey:48000,AVNumberOfChannelsKey:1,AVLinearPCMBitDepthKey:16,AVLinearPCMIsFloatKey:false,AVLinearPCMIsBigEndianKey:false]);reader.add(output);precondition(reader.startReading())
var pcm=Data(count:Int(ceil(asset.duration.seconds*48000))*2)
while let buffer=output.copyNextSampleBuffer(){
 guard let block=CMSampleBufferGetDataBuffer(buffer) else{continue}
 let length=CMBlockBufferGetDataLength(block);var bytes=Data(count:length);let status=bytes.withUnsafeMutableBytes{p in CMBlockBufferCopyDataBytes(block,atOffset:0,dataLength:length,destination:p.baseAddress!)};precondition(status==kCMBlockBufferNoErr)
 let start=Int((CMSampleBufferGetPresentationTimeStamp(buffer).seconds*48000).rounded())*2
 if start>=0 && start<pcm.count{let end=min(start+length,pcm.count);pcm.replaceSubrange(start..<end,with:bytes.prefix(end-start))}
}
precondition(reader.status == .completed,String(describing:reader.error))
try pcm.write(to:dir.appendingPathComponent("decoded-48k.s16le"))
let qa:[String:Any]=["duration":asset.duration.seconds,"width":vt.naturalSize.width,"height":vt.naturalSize.height,"fps":vt.nominalFrameRate,"audio_tracks":ats.count,"audio_duration":ats[0].timeRange.duration.seconds,"frames":frames]
try JSONSerialization.data(withJSONObject:qa,options:[.prettyPrinted,.sortedKeys]).write(to:dir.appendingPathComponent("qa.json"))
print("PASS audio decode",pcm.count,"bytes")
