import Foundation

// MARK: - Lenient decoding
// The web app stored some numbers as strings and vice versa, so every field is read leniently.

struct AnyKey: CodingKey {
    var stringValue: String
    var intValue: Int?
    init(_ s: String) { stringValue = s; intValue = nil }
    init?(stringValue: String) { self.stringValue = stringValue; intValue = nil }
    init?(intValue: Int) { stringValue = String(intValue); self.intValue = intValue }
}

extension KeyedDecodingContainer where Key == AnyKey {
    func str(_ k: String, _ def: String = "") -> String {
        let key = AnyKey(k)
        if let v = try? decodeIfPresent(String.self, forKey: key) { return v }
        if let v = try? decodeIfPresent(Int.self, forKey: key) { return String(v) }
        if let v = try? decodeIfPresent(Double.self, forKey: key) {
            return v == v.rounded() ? String(Int(v)) : String(v)
        }
        if let v = try? decodeIfPresent(Bool.self, forKey: key) { return v ? "true" : "false" }
        return def
    }
    func int(_ k: String) -> Int? {
        let key = AnyKey(k)
        if let v = try? decodeIfPresent(Int.self, forKey: key) { return v }
        if let v = try? decodeIfPresent(Double.self, forKey: key) { return Int(v) }
        if let v = try? decodeIfPresent(String.self, forKey: key) { return Int(v.trimmingCharacters(in: .whitespaces)) }
        return nil
    }
    func dbl(_ k: String) -> Double {
        let key = AnyKey(k)
        if let v = try? decodeIfPresent(Double.self, forKey: key) { return v }
        if let v = try? decodeIfPresent(String.self, forKey: key) { return Double(v) ?? 0 }
        return 0
    }
    func bool(_ k: String) -> Bool { optBool(k) ?? false }
    func optBool(_ k: String) -> Bool? {
        let key = AnyKey(k)
        if let v = try? decodeIfPresent(Bool.self, forKey: key) { return v }
        if let v = try? decodeIfPresent(Int.self, forKey: key) { return v != 0 }
        if let v = try? decodeIfPresent(String.self, forKey: key) { return v == "true" || v == "1" }
        return nil
    }
    func strMap(_ k: String) -> [String: String] {
        guard let sub = try? nestedContainer(keyedBy: AnyKey.self, forKey: AnyKey(k)) else { return [:] }
        var out: [String: String] = [:]
        for key in sub.allKeys { out[key.stringValue] = sub.str(key.stringValue) }
        return out
    }
    func boolMap(_ k: String) -> [String: Bool] {
        guard let sub = try? nestedContainer(keyedBy: AnyKey.self, forKey: AnyKey(k)) else { return [:] }
        var out: [String: Bool] = [:]
        for key in sub.allKeys { out[key.stringValue] = sub.bool(key.stringValue) }
        return out
    }
    func strList(_ k: String) -> [String] {
        if let v = try? decodeIfPresent([String].self, forKey: AnyKey(k)) { return v }
        return []
    }
    func intList(_ k: String) -> [Int] {
        if let v = try? decodeIfPresent([Int].self, forKey: AnyKey(k)) { return v }
        if let v = try? decodeIfPresent([String].self, forKey: AnyKey(k)) { return v.compactMap { Int($0) } }
        return []
    }
}

// MARK: - Profile

struct FTOInfo: Codable, Equatable {
    var name = ""
    var badge = ""
    var initials = ""
    init() {}
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        name = c.str("name"); badge = c.str("badge"); initials = c.str("init")
    }
    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: AnyKey.self)
        try c.encode(name, forKey: AnyKey("name")); try c.encode(badge, forKey: AnyKey("badge")); try c.encode(initials, forKey: AnyKey("init"))
    }
}

struct Trainee: Codable, Identifiable, Equatable, Hashable {
    var id = UUID().uuidString
    var name = ""
    var badge = ""
    var level = "II"
    var phase = "Phase I"
    var start = DateUtil.today()
    var shift = ""
    init() {}
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        id = c.str("id", UUID().uuidString); name = c.str("name"); badge = c.str("badge")
        level = c.str("level", "II"); phase = Phase.normalize(c.str("phase", "Phase I"))
        start = c.str("start"); shift = c.str("shift")
    }
}

struct Settings: Codable, Equatable {
    var carry = true
    init() {}
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        carry = c.optBool("carry") ?? true
    }
}

struct Profile: Codable, Equatable {
    var fto = FTOInfo()
    var agency = "Montpelier Police Department"
    var trainees: [Trainee] = []
    var active: String? = nil
    var settings = Settings()
    init() {}
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        fto = (try? c.decode(FTOInfo.self, forKey: AnyKey("fto"))) ?? FTOInfo()
        let a = c.str("agency"); agency = a.isEmpty ? "Montpelier Police Department" : a
        trainees = (try? c.decode([Trainee].self, forKey: AnyKey("trainees"))) ?? []
        let act = c.str("active"); active = act.isEmpty ? nil : act
        settings = (try? c.decode(Settings.self, forKey: AnyKey("settings"))) ?? Settings()
    }
}

// MARK: - Note

struct Note: Codable, Identifiable, Equatable, Hashable {
    var id = UUID().uuidString
    var tid = ""
    var ts: Double = Date().timeIntervalSince1970 * 1000
    var date = DateUtil.today()
    var time = ""
    var cat: Int? = nil
    var tone = "0"   // "+", "0", "-"
    var text = ""
    var call = ""
    var caseNo = ""
    var rem = ""     // remedial minutes
    init() {}
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        id = c.str("id", UUID().uuidString); tid = c.str("tid"); ts = c.dbl("ts")
        date = c.str("date", DateUtil.today()); time = c.str("time"); cat = c.int("cat")
        tone = c.str("tone", "0"); text = c.str("text"); call = c.str("call"); caseNo = c.str("caseNo"); rem = c.str("rem")
    }
}

// MARK: - DOR

struct AreaNote: Codable, Equatable, Hashable {
    var cat: Int? = nil
    var text = ""
    init() {}
    init(cat: Int?, text: String) { self.cat = cat; self.text = text }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        cat = c.int("cat"); text = c.str("text")
    }
}

struct ManualFlags: Codable, Equatable, Hashable {
    var ratings: [String: Bool] = [:]
    var comments: [String: Bool] = [:]
    var most = false
    var least = false
    var narrative = false
    init() {}
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        ratings = c.boolMap("ratings"); comments = c.boolMap("comments")
        most = c.bool("most"); least = c.bool("least"); narrative = c.bool("narrative")
    }
}

struct DOR: Codable, Identifiable, Equatable, Hashable {
    var id = UUID().uuidString
    var tid = ""
    var date = DateUtil.today()
    var status = "draft"
    var phase = "Phase I"
    var ride = 1
    var shiftHours = ""
    var totalHours = ""
    var fto = ""
    var nonFto = ""
    var assignment = ""
    var attach = false
    var radio = false
    var ungraded = false
    var ratings: [String: String] = [:]
    var comments: [String: String] = [:]
    var nrt: [String: Bool] = [:]
    var rem: [String: Bool] = [:]
    var time: [String: String] = [:]
    var most = AreaNote()
    var least = AreaNote()
    var narrative = ""
    var traineeComments = ""
    var calls = ""
    var mv = ""
    var arrests = ""
    var viols = ""
    var used: [String] = []
    var created: Double = Date().timeIntervalSince1970 * 1000
    var finalAt: Double? = nil
    var ai = false
    var aiCats: [Int] = []
    var manual = ManualFlags()

    var isFinal: Bool { status == "final" }

    init() {}
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        id = c.str("id", UUID().uuidString); tid = c.str("tid"); date = c.str("date", DateUtil.today())
        status = c.str("status", "draft"); phase = Phase.normalize(c.str("phase", "Phase I"))
        ride = c.int("ride") ?? c.int("day") ?? 1
        shiftHours = c.str("shiftHours"); if shiftHours.isEmpty { shiftHours = c.str("shift") }
        totalHours = c.str("totalHours"); fto = c.str("fto"); nonFto = c.str("nonFto"); assignment = c.str("assignment")
        attach = c.bool("attach"); radio = c.bool("radio"); ungraded = c.bool("ungraded")
        ratings = c.strMap("ratings"); comments = c.strMap("comments")
        nrt = c.boolMap("nrt"); rem = c.boolMap("rem"); time = c.strMap("time")
        most = (try? c.decode(AreaNote.self, forKey: AnyKey("most"))) ?? AreaNote()
        least = (try? c.decode(AreaNote.self, forKey: AnyKey("least"))) ?? AreaNote()
        narrative = c.str("narrative"); traineeComments = c.str("traineeComments")
        calls = c.str("calls"); mv = c.str("mv"); arrests = c.str("arrests"); viols = c.str("viols")
        used = c.strList("used"); created = c.dbl("created")
        let fa = c.dbl("finalAt"); finalAt = fa == 0 ? nil : fa
        ai = c.bool("ai"); aiCats = c.intList("aiCats")
        manual = (try? c.decode(ManualFlags.self, forKey: AnyKey("manual"))) ?? ManualFlags()
        // Legacy: an "NRT" rating becomes a 2 with the NRT box ticked.
        for (k, v) in ratings where v == "NRT" { ratings[k] = "2"; nrt[k] = true }
        let legacyRem = c.str("remedial")
        if !legacyRem.isEmpty {
            let mins = c.str("minutes")
            narrative += (narrative.isEmpty ? "" : "\n") + "Remedial: " + legacyRem + (mins.isEmpty ? "" : " (\(mins) min)")
        }
    }

    func rating(_ n: Int) -> String { ratings[String(n)] ?? "NO" }
    func comment(_ n: Int) -> String { comments[String(n)] ?? "" }
    func isRem(_ n: Int) -> Bool { rem[String(n)] ?? false }
    func isNRT(_ n: Int) -> Bool { nrt[String(n)] ?? false }
    func minutes(_ n: Int) -> String { time[String(n)] ?? "" }
}

// MARK: - RTP

struct RTP: Codable, Identifiable, Equatable, Hashable {
    var id = UUID().uuidString
    var tid = ""
    var cat: Int? = nil
    var date = DateUtil.today()
    var week = ""
    var phase = "Phase I"
    var due = ""
    var deficiency = ""
    var assignment = ""
    var completed: Bool? = nil
    var competent: Bool? = nil
    var additional: Bool? = nil
    var comments = ""
    var created: Double = Date().timeIntervalSince1970 * 1000
    init() {}
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        id = c.str("id", UUID().uuidString); tid = c.str("tid"); cat = c.int("cat"); date = c.str("date", DateUtil.today())
        week = c.str("week"); phase = Phase.normalize(c.str("phase", "Phase I")); due = c.str("due")
        deficiency = c.str("deficiency"); assignment = c.str("assignment")
        completed = c.optBool("completed"); competent = c.optBool("competent"); additional = c.optBool("additional")
        comments = c.str("comments"); created = c.dbl("created")
    }
}

// MARK: - Standard Task Checklist progress

struct CheckMark: Codable, Equatable, Hashable {
    var d: String? = nil
    var dm: String? = nil
    var p: String? = nil
    var by: String? = nil
    init() {}
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        func o(_ k: String) -> String? { let s = c.str(k); return s.isEmpty ? nil : s }
        d = o("d"); dm = o("dm"); p = o("p"); by = o("by")
    }
}

struct Checks: Codable, Equatable {
    var tid = ""
    var items: [String: CheckMark] = [:]
    init(tid: String) { self.tid = tid }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        tid = c.str("tid")
        items = (try? c.decode([String: CheckMark].self, forKey: AnyKey("items"))) ?? [:]
    }
}

// MARK: - Whole store / backup file (same shape as the web app's backup)

struct AppData: Codable, Equatable {
    var app = "fto-fieldbook"
    var v = 2
    var profile = Profile()
    var notes: [String: Note] = [:]
    var dors: [String: DOR] = [:]
    var rtps: [String: RTP] = [:]
    var checks: [String: Checks] = [:]
    init() {}
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        profile = (try? c.decode(Profile.self, forKey: AnyKey("profile"))) ?? Profile()
        notes = (try? c.decode([String: Note].self, forKey: AnyKey("notes"))) ?? [:]
        dors = (try? c.decode([String: DOR].self, forKey: AnyKey("dors"))) ?? [:]
        rtps = (try? c.decode([String: RTP].self, forKey: AnyKey("rtps"))) ?? [:]
        checks = (try? c.decode([String: Checks].self, forKey: AnyKey("checks"))) ?? [:]
    }
    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: AnyKey.self)
        try c.encode(app, forKey: AnyKey("app")); try c.encode(v, forKey: AnyKey("v"))
        try c.encode(ISO8601DateFormatter().string(from: Date()), forKey: AnyKey("exported"))
        try c.encode(profile, forKey: AnyKey("profile")); try c.encode(notes, forKey: AnyKey("notes"))
        try c.encode(dors, forKey: AnyKey("dors")); try c.encode(rtps, forKey: AnyKey("rtps")); try c.encode(checks, forKey: AnyKey("checks"))
    }
}

// MARK: - Dates

enum DateUtil {
    static let ymd: DateFormatter = { let f = DateFormatter(); f.calendar = Calendar(identifier: .gregorian); f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = "yyyy-MM-dd"; return f }()
    static func today() -> String { ymd.string(from: Date()) }
    static func date(_ s: String) -> Date? { ymd.date(from: s) }
    static func string(_ d: Date) -> String { ymd.string(from: d) }
    static func us(_ s: String) -> String {
        let p = s.split(separator: "-"); guard p.count == 3 else { return s }
        return "\(p[1])/\(p[2])/\(p[0])"
    }
    static func short(_ s: String) -> String {
        let p = s.split(separator: "-"); guard p.count == 3, let m = Int(p[1]), let d = Int(p[2]) else { return s }
        return "\(m)/\(d)"
    }
    static func pretty(_ s: String) -> String {
        guard let d = date(s) else { return s }
        let f = DateFormatter(); f.dateFormat = "EEE, MMM d"; return f.string(from: d)
    }
    static func nowMillis() -> Double { Date().timeIntervalSince1970 * 1000 }
}
