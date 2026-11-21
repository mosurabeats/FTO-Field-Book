import Foundation
import ZIPFoundation

/// Fills the department's own FTEP-1 DOR (Level II/III fillable Word form) field by field.
/// Field order in the form: 0 DOR#, 1 date, 2 shift hours, 3 total hours, 4 trainee, 5 FTO, 6 non-FTO,
/// 7/8 attachments yes/no, 9 assignment, 10…489 = 40 SEG rows × 12 cells (1–7, NO, NAR, NRT, REM, TIME),
/// 490/491 most cat/text, 492/493 least cat/text, 494/495 additional narrative yes/no,
/// 496…525 = 15 narrative rows (cat, text), 526 trainee comments, 527/528 radio yes/no, 529–532 calls/MV/arrests/viols.
enum FormFiller {
    enum Value { case text(String), check(Bool) }

    static func values(_ store: Store, _ d: DOR) -> (values: [Int: Value], overflow: [DORLogic.NarrativeRow]) {
        let t = store.trainee(d.tid) ?? Trainee()
        var v: [Int: Value] = [:]
        v[0] = .text(String(d.ride)); v[1] = .text(DateUtil.us(d.date)); v[2] = .text(d.shiftHours); v[3] = .text(d.totalHours)
        v[4] = .text(t.name + (t.badge.isEmpty ? "" : " #" + t.badge)); v[5] = .text(d.fto); v[6] = .text(d.nonFto)
        v[7] = .check(d.attach); v[8] = .check(!d.attach)
        v[9] = .text(d.ungraded && d.assignment.isEmpty ? Rating.unratedReason : d.assignment)
        for n in 1...40 {
            let b = 10 + (n - 1) * 12
            for c in 0..<12 { v[b + c] = .text("") }
            guard let s = Ref.byN[n], !(t.level == "III" && s.l2) else { continue }
            let r = d.rating(n)
            if !d.ungraded { if Rating.isNum(r), let i = Int(r) { v[b + i - 1] = .text("X") } else { v[b + 7] = .text("X") } }
            let hasNarr = !d.comment(n).trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            if hasNarr && (d.ungraded || Rating.isNum(r) || d.isRem(n) || d.isNRT(n)) { v[b + 8] = .text("X") }
            if d.isNRT(n) && !d.ungraded { v[b + 9] = .text("X") }
            if d.isRem(n) { v[b + 10] = .text("X") }
            if !d.minutes(n).isEmpty { v[b + 11] = .text(d.minutes(n)) }
        }
        if d.ungraded {
            v[490] = .text(""); v[491] = .text(""); v[492] = .text(""); v[493] = .text("")
        } else {
            v[490] = .text(d.most.cat.map(String.init) ?? ""); v[491] = .text(d.most.text)
            v[492] = .text(d.least.cat.map(String.init) ?? ""); v[493] = .text(d.least.text)
        }
        let rows = DORLogic.narrativeRows(store, d)
        let over = rows.count > 15
        v[494] = .check(over); v[495] = .check(!over)
        for i in 0..<15 {
            let r = i < rows.count ? rows[i] : nil
            v[496 + i * 2] = .text(r?.cat ?? ""); v[497 + i * 2] = .text(r?.text ?? "")
        }
        v[526] = .text(d.traineeComments); v[527] = .check(d.radio); v[528] = .check(!d.radio)
        v[529] = .text(d.calls); v[530] = .text(d.mv); v[531] = .text(d.arrests); v[532] = .text(d.viols)
        return (v, over ? Array(rows.dropFirst(15)) : [])
    }

    static func xmlEscape(_ s: String) -> String {
        s.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;").replacingOccurrences(of: ">", with: "&gt;")
    }

    private static func find(_ doc: NSString, _ s: String, from: Int, backwards: Bool = false, upTo: Int? = nil) -> Int {
        let range = backwards ? NSRange(location: 0, length: upTo ?? doc.length) : NSRange(location: from, length: doc.length - from)
        return doc.range(of: s, options: backwards ? [.backwards] : [], range: range).location
    }

    static func fill(_ xml: String, _ values: [Int: Value]) -> String {
        let doc = NSMutableString(string: xml)
        var starts: [Int] = []
        var pos = 0
        while true {
            let f = find(doc, "<w:ffData>", from: pos)
            if f == NSNotFound { break }
            starts.append(f); pos = f + 1
        }
        let rPrRegex = try! NSRegularExpression(pattern: "<w:rPr>.*?</w:rPr>", options: [.dotMatchesLineSeparators])
        for k in stride(from: starts.count - 1, through: 0, by: -1) {
            guard let v = values[k] else { continue }
            let s = starts[k]
            let ffEnd = find(doc, "</w:ffData>", from: s)
            guard ffEnd != NSNotFound else { continue }
            let ff = doc.substring(with: NSRange(location: s, length: ffEnd - s))
            if ff.contains("<w:checkBox") {
                guard case .check(let on) = v else { continue }
                var nf = ff.replacingOccurrences(of: "<w:checked[^>]*/>", with: "", options: .regularExpression)
                nf = nf.replacingOccurrences(of: "<w:default w:val=\"[01]\"/>", with: "<w:default w:val=\"\(on ? 1 : 0)\"/>" + (on ? "<w:checked/>" : ""), options: .regularExpression)
                doc.replaceCharacters(in: NSRange(location: s, length: ffEnd - s), with: nf)
                continue
            }
            guard case .text(let text) = v else { continue }
            let sep = find(doc, "w:fldCharType=\"separate\"", from: s)
            guard sep != NSNotFound else { continue }
            let rEnd = find(doc, "</w:r>", from: sep)
            guard rEnd != NSNotFound else { continue }
            let a = rEnd + 6
            let endc = find(doc, "w:fldCharType=\"end\"", from: s)
            guard endc != NSNotFound else { continue }
            let b1 = find(doc, "<w:r>", from: 0, backwards: true, upTo: endc)
            let b2 = find(doc, "<w:r ", from: 0, backwards: true, upTo: endc)
            let b = max(b1 == NSNotFound ? -1 : b1, b2 == NSNotFound ? -1 : b2)
            guard b >= a else { continue }
            let mid = doc.substring(with: NSRange(location: a, length: b - a))
            var rPr = ""
            if let m = rPrRegex.firstMatch(in: mid, range: NSRange(location: 0, length: (mid as NSString).length)) {
                rPr = (mid as NSString).substring(with: m.range)
            } else {
                let c1 = find(doc, "<w:r>", from: 0, backwards: true, upTo: s), c2 = find(doc, "<w:r ", from: 0, backwards: true, upTo: s)
                let begR = max(c1 == NSNotFound ? -1 : c1, c2 == NSNotFound ? -1 : c2)
                if begR >= 0 {
                    let pre = doc.substring(with: NSRange(location: begR, length: s - begR))
                    if let m = rPrRegex.firstMatch(in: pre, range: NSRange(location: 0, length: (pre as NSString).length)) { rPr = (pre as NSString).substring(with: m.range) }
                }
            }
            let body = text.components(separatedBy: "\n").enumerated().map { (i, l) in (i > 0 ? "<w:br/>" : "") + "<w:t xml:space=\"preserve\">\(xmlEscape(l))</w:t>" }.joined()
            doc.replaceCharacters(in: NSRange(location: a, length: b - a), with: "<w:r>\(rPr)\(body)</w:r>")
        }
        return doc as String
    }

    static func appendOverflow(_ xml: String, _ rows: [DORLogic.NarrativeRow], header: String) -> String {
        guard !rows.isEmpty, let at = xml.range(of: "<w:sectPr", options: .backwards) else { return xml }
        func p(_ txt: String, bold: Bool = false) -> String {
            let runs = txt.components(separatedBy: "\n").enumerated().map { (i, l) in (i > 0 ? "<w:br/>" : "") + "<w:t xml:space=\"preserve\">\(xmlEscape(l))</w:t>" }.joined()
            return "<w:p><w:r><w:rPr><w:rFonts w:ascii=\"Arial\" w:hAnsi=\"Arial\" w:cs=\"Arial\"/>\(bold ? "<w:b/>" : "")<w:sz w:val=\"20\"/></w:rPr>\(runs)</w:r></w:p>"
        }
        var x = "<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>" + p(header, bold: true) + p("")
        for r in rows {
            x += p(r.cat.isEmpty ? "General" : "Category #\(r.cat) – \(Ref.name(Int(r.cat)).uppercased())", bold: true) + p(r.text) + p("")
        }
        var out = xml; out.insert(contentsOf: x, at: at.lowerBound); return out
    }

    /// Builds the filled .docx and returns its file URL.
    static func makeDocx(_ store: Store, _ d: DOR) throws -> URL {
        guard let tpl = Bundle.main.url(forResource: "FTEP1", withExtension: "docx") else { throw NSError(domain: "FieldBook", code: 2, userInfo: [NSLocalizedDescriptionKey: "Form template missing"]) }
        let src = try Archive(url: tpl, accessMode: .read)
        let t = store.trainee(d.tid) ?? Trainee()
        let safeName = t.name.replacingOccurrences(of: "[^A-Za-z0-9]+", with: "_", options: .regularExpression)
        let outURL = FileManager.default.temporaryDirectory.appendingPathComponent(String(format: "DOR%02d_%@_%@.docx", d.ride, safeName, d.date))
        try? FileManager.default.removeItem(at: outURL)
        let dst = try Archive(url: outURL, accessMode: .create)
        let (vals, overflow) = values(store, d)
        for entry in src {
            guard entry.type == .file else { continue }
            var data = Data()
            _ = try src.extract(entry, skipCRC32: true) { chunk in data.append(chunk) }
            if entry.path == "word/document.xml", let xml = String(data: data, encoding: .utf8) {
                var filled = fill(xml, vals)
                filled = appendOverflow(filled, overflow, header: "ADDITIONAL NARRATIVE — DOR # \(d.ride) — \(t.name) — \(DateUtil.us(d.date))")
                data = Data(filled.utf8)
            }
            let payload = data
            try dst.addEntry(with: entry.path, type: .file, uncompressedSize: Int64(payload.count), compressionMethod: .deflate) { position, size in
                let start = Int(position)
                return payload.subdata(in: start..<min(start + size, payload.count))
            }
        }
        return outURL
    }
}
