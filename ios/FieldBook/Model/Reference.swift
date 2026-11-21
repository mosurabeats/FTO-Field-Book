import Foundation

struct SEG: Codable, Identifiable, Hashable {
    let n: Int
    let g: String
    let name: String
    let d: String
    let a1: String
    let a4: String
    let a7: String
    let list: [String]
    let test: Bool
    let l2: Bool
    var id: Int { n }
    var key: String { String(n) }
}

struct ChecklistItem: Codable, Hashable, Identifiable {
    let id: String
    let t: String
    let ph: String?   // "L", "1", "2", "3"; nil = heading row
    let lvl: Int
}

struct ChecklistSection: Codable, Hashable, Identifiable {
    let n: Int
    let t: String
    let items: [ChecklistItem]
    var id: Int { n }
}

enum Ref {
    static let segs: [SEG] = load("segs")
    static let checklist: [ChecklistSection] = load("checklist")
    static let byN: [Int: SEG] = Dictionary(uniqueKeysWithValues: segs.map { ($0.n, $0) })
    static let groups = ["Critical Performance Tasks", "Frequent Performance Tasks", "Knowledge", "Attitude & Relationships", "Appearance"]
    static let subhead: [Int: String] = [25: "Department Policy & Procedure", 27: "Criminal Statutes", 29: "Resources & Alternatives", 31: "Traffic Statutes", 33: "Scope of Practice"]
    static let phaseLabel: [String: String] = ["L": "Limbo", "1": "Phase I", "2": "Phase II", "3": "Phase III"]
    static let tips = "SET THE STAGE · USE QUOTES · USE LISTS · INCLUDE REMEDIAL · FRONT = BACK · USE SEGs · REMEMBER YOUR AUDIENCE · DO NOT PREDICT"
    static let calls = ["MV stop", "Crash", "Alarm", "Domestic", "Welfare check", "Suspicious", "Theft", "Disturbance", "Arrest", "Mental health", "Foot patrol", "Assist"]

    static func segs(for t: Trainee?) -> [SEG] { segs.filter { !($0.l2 && t?.level == "III") } }
    static func name(_ n: Int?) -> String { n.flatMap { byN[$0]?.name } ?? "" }

    private static func load<T: Decodable>(_ name: String) -> T {
        guard let url = Bundle.main.url(forResource: name, withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let v = try? JSONDecoder().decode(T.self, from: data) else { fatalError("Missing resource \(name).json") }
        return v
    }
}

enum Phase {
    static let all = ["Phase I", "Phase II", "Phase III", "Remedial"]
    static let rides: [String: Int] = ["Phase I": 24, "Phase II": 16, "Phase III": 16, "Remedial": 8]
    static func normalize(_ p: String) -> String {
        if all.contains(p) { return p }
        let m = ["Limbo": "Phase I", "Phase 1": "Phase I", "Phase 2": "Phase II", "Phase 3": "Phase III", "Phase 4": "Phase III", "Shadow / Eval": "Phase III", "Extended": "Remedial"]
        return m[p] ?? "Phase I"
    }
    static func label(_ phase: String, _ ride: Int) -> String {
        let wk = Int(ceil(Double(max(ride, 1)) / 4.0))
        let limbo = phase == "Phase I" && ride <= 8 ? " (Limbo)" : ""
        return "\(phase) · Ride \(ride)/\(rides[phase] ?? 16) · Wk \(wk)\(limbo)"
    }
    /// Checklist phase codes due by the trainee's current phase.
    static func due(_ phase: String) -> [String] {
        switch phase { case "Phase I": return ["L", "1"]; case "Phase II": return ["L", "1", "2"]; default: return ["L", "1", "2", "3"] }
    }
}

enum Rating {
    static let numbers = ["1", "2", "3", "4", "5", "6", "7"]
    static func isNum(_ r: String) -> Bool { numbers.contains(r) }
    static func needsNarrative(_ r: String) -> Bool { ["1", "2", "6", "7"].contains(r) }
    static let unratedReason = "Not graded"
}
