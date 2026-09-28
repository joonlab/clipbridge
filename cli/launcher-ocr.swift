import Vision; import AppKit
let url = URL(fileURLWithPath: CommandLine.arguments[1])
let img = NSImage(contentsOf: url)!; var r = CGRect(origin: .zero, size: img.size)
let cg = img.cgImage(forProposedRect: &r, context: nil, hints: nil)!
let W = Double(cg.width), H = Double(cg.height)
let req = VNRecognizeTextRequest(); req.recognitionLanguages = ["ko-KR","en-US"]; req.recognitionLevel = .accurate
try! VNImageRequestHandler(cgImage: cg).perform([req])
for o in req.results ?? [] { if let t = o.topCandidates(1).first { let b = o.boundingBox
  print("\(t.string)\t\(Int(b.midX*W))\t\(Int((1-b.midY)*H))") } }
