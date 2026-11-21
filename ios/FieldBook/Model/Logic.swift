import Foundation

/// DOR rules from the Montpelier / VCJTC FTEP manuals, ported from the web app.
enum DORLogic {
    static func nextRide(_ store: Store, tid: String, phase: String, date: String, excluding: String? = nil) -> Int {
        store.dors(for: tid).filter { $0.phase == phase && $0.date <= date && $0.id != excluding }.count + 1
    }

    /// Last graded DOR before a date (ungraded shifts are never used to carry ratings forward).
    static func previous(_ store: Store, tid: String, before date: String) -> DOR? {
        store.dors(for: tid).filter { $0.date < date && !$0.ungraded }.last
    }

    static func noteLine(_ n: Note) -> String {
        var s = ""
        if !n.call.isEmpty { s = n.call + (n.caseNo.isEmpty ? "" : " #\(n.caseNo)") + " – " }
        else if !n.caseNo.isEmpty { s = "#\(n.caseNo) – " }
        s += n.text
        if !n.rem.isEmpty { s += " [remedial \(n.rem) min]" }
        return s.trimmingCharacters(in: .whitespaces)
    }

    static func failing(_ d: DOR) -> Bool { d.ratings.values.contains { Rating.isNum($0) && (Int($0) ?? 4) < 4 } }

    @discardableResult
    static func build(_ store: Store, trainee t: Trainee, date: String) -> String {
        if let existing = store.dors(for: t.id).first(where: { $0.date == date }) { return existing.id }
        var d = DOR()
        d.tid = t.id; d.date = date; d.phase = t.phase
        d.ride = nextRide(store, tid: t.id, phase: t.phase, date: date)
        d.shiftHours = t.shift; d.fto = store.profile.fto.name
        let prev = previous(store, tid: t.id, before: date)
        for s in Ref.segs(for: t) {
            let pr = prev?.rating(s.n) ?? "NO"
            d.ratings[s.key] = (store.profile.settings.carry && Rating.isNum(pr)) ? pr : "NO"
        }
        _ = pullNotes(store, into: &d)
        store.data.dors[d.id] = d
        return d.id
    }

    /// Copies the shift's not-yet-used notes into the DOR as a starting point.
    static func pullNotes(_ store: Store, into d: inout DOR) -> Int {
        let ns = store.notes(for: d.tid).filter { $0.date == d.date && !d.used.contains($0.id) }.reversed()
        var pos: [Int: [Note]] = [:], neg: [Int: [Note]] = [:]
        for n in ns {
            d.used.append(n.id)
            if let k = n.cat {
                let key = String(k)
                let cur = d.comments[key] ?? ""
                d.comments[key] = (cur.isEmpty ? "" : cur + "\n") + noteLine(n)
                if !d.ungraded && !Rating.isNum(d.ratings[key] ?? "NO") { d.ratings[key] = n.tone == "-" ? "3" : "4" }
                if let m = Int(n.rem), m > 0 { d.rem[key] = true; d.time[key] = String((Int(d.time[key] ?? "") ?? 0) + m) }
                if n.tone == "+" { pos[k, default: []].append(n) }
                if n.tone == "-" { neg[k, default: []].append(n) }
            } else {
                d.narrative = (d.narrative.isEmpty ? "" : d.narrative + "\n") + noteLine(n)
            }
        }
        if !d.ungraded {
            if d.most.cat == nil, let p = pos.max(by: { $0.value.count < $1.value.count }) {
                d.most = AreaNote(cat: p.key, text: p.value.map(noteLine).joined(separator: " "))
            }
            if d.least.cat == nil, let q = neg.max(by: { $0.value.count < $1.value.count }) {
                d.least = AreaNote(cat: q.key, text: q.value.map(noteLine).joined(separator: " "))
            }
        }
        let day = store.notes(for: d.tid).filter { $0.date == d.date }
        let callKeys = Set(day.filter { !$0.call.isEmpty || !$0.caseNo.isEmpty }.map { $0.caseNo.isEmpty ? $0.call + "@" + $0.id : $0.caseNo })
        let mvKeys = Set(day.filter { $0.call.range(of: "mv|stop|traffic", options: [.regularExpression, .caseInsensitive]) != nil }.map { $0.caseNo.isEmpty ? $0.id : $0.caseNo })
        let arrests = Set(day.filter { $0.call.range(of: "arrest", options: .caseInsensitive) != nil }.map { $0.caseNo.isEmpty ? $0.id : $0.caseNo })
        if d.calls.isEmpty && !callKeys.isEmpty { d.calls = String(callKeys.count) }
        if d.mv.isEmpty && !mvKeys.isEmpty { d.mv = String(mvKeys.count) }
        if d.arrests.isEmpty && !arrests.isEmpty { d.arrests = String(arrests.count) }
        return ns.count
    }

    static func rtpCompleted(_ store: Store, tid: String, cat: Int, on date: String? = nil) -> Int {
        store.data.rtps.values.filter { $0.tid == tid && $0.cat == cat && $0.completed == true && (date == nil || $0.date <= date!) }.count
    }
    static func rtpCount(_ store: Store, tid: String, cat: Int) -> Int {
        store.data.rtps.values.filter { $0.tid == tid && $0.cat == cat }.count
    }

    struct Issues { var errors: [String] = []; var warnings: [String] = [] }

    static func validate(_ store: Store, _ d: DOR) -> Issues {
        var v = Issues()
        let t = store.trainee(d.tid)
        let segs = Ref.segs(for: t)
        if d.ungraded {
            let any = !d.narrative.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || d.comments.values.contains { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
            if !any { v.errors.append("An ungraded DOR still needs a narrative") }
            for s in segs {
                if d.isRem(s.n) && d.comment(s.n).trimmingCharacters(in: .whitespaces).isEmpty { v.errors.append("#\(s.n) \(s.name): REM needs narrative") }
                if d.isRem(s.n) && d.minutes(s.n).isEmpty { v.warnings.append("#\(s.n): REM checked — add TIME (minutes)") }
            }
            if d.totalHours.isEmpty { v.warnings.append("Total hours worked is blank") }
            return v
        }
        for s in segs {
            let r = d.rating(s.n), c = d.comment(s.n).trimmingCharacters(in: .whitespacesAndNewlines)
            if (Rating.needsNarrative(r) || d.isRem(s.n) || d.isNRT(s.n)) && c.isEmpty {
                v.errors.append("#\(s.n) \(s.name): \(d.isNRT(s.n) ? "NRT" : d.isRem(s.n) ? "REM" : "rated \(r)") needs narrative")
            }
            if d.isNRT(s.n) {
                let k = rtpCompleted(store, tid: d.tid, cat: s.n, on: d.date)
                if k < 3 { v.errors.append("#\(s.n): NRT needs 3 completed RTPs in this SEG (\(k) logged)") }
                if !["1", "2"].contains(r) { v.warnings.append("#\(s.n): NRT score should be a 1 or 2") }
            }
            if d.isRem(s.n) && d.minutes(s.n).isEmpty { v.warnings.append("#\(s.n): REM checked — add TIME (minutes)") }
        }
        if d.most.cat == nil || d.most.text.trimmingCharacters(in: .whitespaces).isEmpty { v.errors.append("Most acceptable area + specific incident is mandatory") }
        let f = failing(d)
        if f && (d.least.cat == nil || d.least.text.trimmingCharacters(in: .whitespaces).isEmpty) { v.errors.append("A failing grade was given: least acceptable area + incident is mandatory") }
        if !f && (d.least.cat != nil || !d.least.text.trimmingCharacters(in: .whitespaces).isEmpty) { v.warnings.append("All ratings are passing: least acceptable should be left blank") }
        if d.totalHours.isEmpty { v.warnings.append("Total hours worked is blank") }
        if !d.nonFto.trimmingCharacters(in: .whitespaces).isEmpty { v.warnings.append("A non-FTO is listed: per the FTEP manual a non-FTO ride gets no evaluation — tick Not graded") }
        return v
    }

    struct NarrativeRow { let cat: String; let text: String }

    static func narrativeRows(_ store: Store, _ d: DOR) -> [NarrativeRow] {
        var rows: [NarrativeRow] = []
        for s in Ref.segs(for: store.trainee(d.tid)) {
            let c = d.comment(s.n).trimmingCharacters(in: .whitespacesAndNewlines)
            let r = d.rating(s.n)
            guard !c.isEmpty, d.ungraded || Rating.isNum(r) || d.isRem(s.n) || d.isNRT(s.n) else { continue }
            var txt = c.replacingOccurrences(of: "\\s*\\n+\\s*", with: " ", options: .regularExpression)
            if d.isRem(s.n), !d.minutes(s.n).isEmpty, c.range(of: "remedial", options: .caseInsensitive) == nil { txt += " Remedial training: \(d.minutes(s.n)) min." }
            rows.append(NarrativeRow(cat: s.key, text: txt))
        }
        for line in d.narrative.components(separatedBy: "\n") {
            let l = line.trimmingCharacters(in: .whitespaces)
            if !l.isEmpty { rows.append(NarrativeRow(cat: "", text: l)) }
        }
        return rows
    }

    // MARK: - Weekly report

    static func weeks(_ store: Store, tid: String) -> [(key: String, dors: [DOR])] {
        var order: [String] = []; var map: [String: [DOR]] = [:]
        for d in store.dors(for: tid) {
            let k = "\(d.phase) · Week \(Int(ceil(Double(max(d.ride, 1)) / 4.0)))"
            if map[k] == nil { order.append(k) }
            map[k, default: []].append(d)
        }
        return order.map { ($0, map[$0]!) }
    }

    static func weekText(_ t: Trainee, _ ds: [DOR]) -> String {
        guard let first = ds.first, let last = ds.last else { return "" }
        func sum(_ kp: KeyPath<DOR, String>) -> Int { ds.reduce(0) { $0 + (Int($1[keyPath: kp]) ?? 0) } }
        var mins: [Int: Int] = [:]; var rem: [String] = []
        for d in ds { for (k, on) in d.rem where on { if let n = Int(k) { mins[n, default: 0] += Int(d.time[k] ?? "") ?? 0; rem.append("\(DateUtil.short(d.date)) #\(n) \(Ref.name(n))\((d.time[k] ?? "").isEmpty ? "" : " (\(d.time[k]!) min)")") } } }
        var L = ["FTEP SUPERVISOR WEEKLY REPORT — DATA", "Recruit officer: \(t.name)\(t.badge.isEmpty ? "" : " #" + t.badge)",
                 "Training week: \(first.phase), week \(Int(ceil(Double(max(first.ride, 1)) / 4.0))) · \(DateUtil.us(first.date)) to \(DateUtil.us(last.date)) · \(ds.count) DORs", "",
                 "REMEDIAL TRAINING:"]
        L += rem.isEmpty ? ["None this week"] : rem
        L += ["", "MOST ACCEPTABLE:"] + ds.map { "\(DateUtil.short($0.date)): \($0.most.cat.map { "#\($0) \(Ref.name($0))" } ?? ($0.ungraded ? "— (not graded)" : "—"))" }
        L += ["", "LEAST ACCEPTABLE:"] + ds.map { "\(DateUtil.short($0.date)): \($0.least.cat.map { "#\($0) \(Ref.name($0))" } ?? "— (all passing)")" }
        L += ["", "DOCUMENTED TRAINING MINUTES:"] + (mins.isEmpty ? ["None"] : mins.keys.sorted().map { "#\($0) \(Ref.name($0)): \(mins[$0]!)" })
        L += ["", "STATS:", "Calls for Service: \(sum(\.calls))", "MV Stops: \(sum(\.mv))", "Arrests: \(sum(\.arrests))", "Violations: \(sum(\.viols))"]
        return L.joined(separator: "\n")
    }

    // MARK: - Plain-text DOR

    static func text(_ store: Store, _ d: DOR) -> String {
        let t = store.trainee(d.tid) ?? Trainee(); let p = store.profile
        let line = String(repeating: "─", count: 40)
        var L = ["DOR # \(d.ride)   DAILY OBSERVATION REPORT – LEVEL II and LEVEL III"]
        if !p.agency.isEmpty { L.append(p.agency) }
        L += [line, "Date: \(DateUtil.us(d.date))   Shift: \(d.shiftHours.isEmpty ? "—" : d.shiftHours)   Total hrs: \(d.totalHours.isEmpty ? "—" : d.totalHours)",
              "Trainee: \(t.name)\(t.badge.isEmpty ? "" : " #" + t.badge)",
              "FTO: \(d.fto)\(d.nonFto.isEmpty ? "" : "   Non-FTO: " + d.nonFto)", Phase.label(d.phase, d.ride)]
        if !d.assignment.isEmpty { L.append("Assignment: \(d.assignment)") }
        L += [line, d.ungraded ? "NOT GRADED" : "#  CATEGORY                       RATE REM TIME NRT"]
        if !d.ungraded {
            for g in Ref.groups {
                let ss = Ref.segs(for: t).filter { $0.g == g }; if ss.isEmpty { continue }
                L.append(g.uppercased())
                for s in ss {
                    let nm = (s.name + String(repeating: ".", count: 30)).prefix(30)
                    L.append("\(String(format: "%2d", s.n)) \(nm) \(d.rating(s.n).padding(toLength: 3, withPad: " ", startingAt: 0)) \(d.isRem(s.n) ? "X" : " ")  \(d.minutes(s.n).padding(toLength: 4, withPad: " ", startingAt: 0)) \(d.isNRT(s.n) ? "X" : "")")
                }
            }
            L += [line, "MOST ACCEPTABLE: \(d.most.cat.map { "#\($0) \(Ref.name($0))" } ?? "—")"]
            if !d.most.text.isEmpty { L.append(d.most.text) }
            L += ["", "LEAST ACCEPTABLE: \(d.least.cat.map { "#\($0) \(Ref.name($0))" } ?? "— (all ratings passing)")"]
            if !d.least.text.isEmpty { L.append(d.least.text) }
            L.append("")
        }
        L.append("DOCUMENTATION OF PERFORMANCE AND COMMENTS")
        for r in narrativeRows(store, d) { L.append("\(r.cat.isEmpty ? "  -" : "#" + r.cat)  \(r.text)") }
        L.append("")
        if !d.traineeComments.isEmpty { L += ["TRAINEE COMMENTS:", d.traineeComments, ""] }
        L.append("SHIFT DATA  Calls: \(d.calls.isEmpty ? "0" : d.calls)  MV stops: \(d.mv.isEmpty ? "0" : d.mv)  Arrests: \(d.arrests.isEmpty ? "0" : d.arrests)  Viols: \(d.viols.isEmpty ? "0" : d.viols)  Radio history: \(d.radio ? "Yes" : "No")")
        L += [line, "Trainee Signature ____________________ Date ______", "FTO / Non-FTO Signature ______________ Date ______"]
        return L.joined(separator: "\n")
    }

    static func rtpText(_ store: Store, _ r: RTP) -> String {
        let t = store.trainee(r.tid) ?? Trainee()
        func yn(_ v: Bool?) -> String { v == true ? "Yes [X]  No [ ]" : v == false ? "Yes [ ]  No [X]" : "Yes [ ]  No [ ]" }
        return ["FIELD TRAINING AND EVALUATION PROGRAM — REMEDIAL TRAINING WORKSHEET", store.profile.agency, "",
                "Trainee: \(t.name)\(t.badge.isEmpty ? "" : " #" + t.badge)    Date: \(DateUtil.us(r.date))    Training Week #: \(r.week)",
                "Area: \(r.cat.map { "#\($0) \(Ref.name($0))" } ?? "")", "Complete assignment by: \(DateUtil.us(r.due))", "",
                "PERFORMANCE DEFICIENCIES:", r.deficiency, "", "TRAINING ASSIGNMENT:", r.assignment, "",
                "Trainee Signature: ____________________   FTO Signature: ____________________", "",
                "ASSIGNMENT COMPLETION:",
                "1. Has the trainee satisfactorily completed the training plan?  \(yn(r.completed))",
                "2. Is the trainee now performing at a competent level?  \(yn(r.competent))",
                "3. Has an additional assignment been given?  \(yn(r.additional))", "",
                "COMMENTS:", r.comments, "",
                "Trainee Signature: ____________________  Date: ________", "FTO Signature: ________________________  Date: ________",
                "FTEP Supervisor Signature: _____________  Date: ________"].joined(separator: "\n")
    }

    static func checklistText(_ t: Trainee, _ c: Checks) -> String {
        var L = ["STANDARD TASK CHECKLIST — \(t.name)", "Legend: D=Discussed  Dm=Demonstrated  P=Performed (dates)", ""]
        for s in Ref.checklist {
            L.append("\(s.n). \(s.t.uppercased())")
            for i in s.items {
                guard let ph = i.ph else { L.append("   " + i.t); continue }
                let m = c.items[i.id] ?? CheckMark()
                func f(_ v: String?) -> String { v.map(DateUtil.us) ?? "—" }
                L.append("\(String(repeating: "  ", count: i.lvl + 1))[\(Ref.phaseLabel[ph] ?? ph)] \(i.t) — D:\(f(m.d)) Dm:\(f(m.dm)) P:\(f(m.p))")
            }
            L.append("")
        }
        return L.joined(separator: "\n")
    }
}
