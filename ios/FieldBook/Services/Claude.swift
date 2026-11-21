import Foundation

/// Talks to the Claude API with the user's own key (kept in the Keychain).
enum ClaudeAPI {
    static let keyAccount = "anthropicKey"
    static var key: String? { Keychain.get(keyAccount) }
    static var hasKey: Bool { !(key ?? "").isEmpty }
    static var model: String { UserDefaults.standard.string(forKey: "claudeModel") ?? "claude-sonnet-5-5" }

    enum Failure: LocalizedError {
        case noKey, badKey, busy, network, server(String), badJSON
        var errorDescription: String? {
            switch self {
            case .noKey: return "Add your API key under Trainee → Claude drafting."
            case .badKey: return "Your API key was rejected. Check it under Trainee → Claude drafting."
            case .busy: return "Claude is busy right now. Try again in a minute."
            case .network: return "No connection. Try again when you have signal."
            case .server(let s): return "Claude couldn't draft this: \(s.prefix(160))"
            case .badJSON: return "Claude's answer couldn't be read. Try again."
            }
        }
    }

    static func complete(_ prompt: String, maxTokens: Int = 4096) async throws -> String {
        guard let key = key, !key.isEmpty else { throw Failure.noKey }
        var req = URLRequest(url: URL(string: "https://api.anthropic.com/v1/messages")!)
        req.httpMethod = "POST"
        req.timeoutInterval = 120
        req.setValue("application/json", forHTTPHeaderField: "content-type")
        req.setValue(key, forHTTPHeaderField: "x-api-key")
        req.setValue("2023-06-01", forHTTPHeaderField: "anthropic-version")
        let body: [String: Any] = ["model": model, "max_tokens": maxTokens, "messages": [["role": "user", "content": prompt]]]
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        let data: Data, resp: URLResponse
        do { (data, resp) = try await URLSession.shared.data(for: req) } catch { throw Failure.network }
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        if code == 401 || code == 403 { throw Failure.badKey }
        if code == 429 || code == 529 { throw Failure.busy }
        guard code == 200 else { throw Failure.server(String(data: data, encoding: .utf8) ?? "HTTP \(code)") }
        guard let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let content = obj["content"] as? [[String: Any]] else { throw Failure.badJSON }
        return content.compactMap { ($0["type"] as? String) == "text" ? $0["text"] as? String : nil }.joined()
    }

    static func completeJSON(_ prompt: String) async throws -> [String: Any] {
        let text = try await complete(prompt)
        guard let s = text.firstIndex(of: "{"), let e = text.lastIndex(of: "}") else { throw Failure.badJSON }
        let slice = String(text[s...e])
        guard let d = slice.data(using: .utf8), let obj = try? JSONSerialization.jsonObject(with: d) as? [String: Any] else { throw Failure.badJSON }
        return obj
    }
}

// MARK: - Prompts and applying the answers

enum Drafting {
    static let voice = "Write in the FIRST PERSON as the FTO (\"I observed\", \"I instructed\", \"I corrected\"). Refer to the trainee only as \"Trainee\" or \"the trainee\": never by name."

    static func program(_ store: Store, _ t: Trainee) -> String {
        "the \(store.profile.agency.isEmpty ? "police department" : store.profile.agency) Field Training & Evaluation Program (Vermont Criminal Justice Council, Level \(t.level) officer)"
    }

    static func segBrief(_ t: Trainee) -> String {
        Ref.segs(for: t).map { "#\($0.n) \($0.name): \($0.d) | 1=\($0.a1) | 4=\($0.a4) | 7=\($0.a7)" }.joined(separator: "\n")
    }

    static func notesBlock(_ ns: [Note]) -> String {
        ns.map { "- tone \($0.tone) | SEG \($0.cat.map(String.init) ?? "untagged") | \($0.call) \($0.caseNo.isEmpty ? "" : "#" + $0.caseNo) | rem \($0.rem.isEmpty ? "0" : $0.rem) | \($0.text)" }.joined(separator: "\n")
    }

    /// JSON-safe optional (nil becomes null instead of crashing the serializer).
    static func nn<T>(_ v: T?) -> Any { v.map { $0 as Any } ?? NSNull() }

    static func json(_ v: Any) -> String {
        (try? JSONSerialization.data(withJSONObject: v, options: [.sortedKeys])).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
    }

    /// Drafts a DOR from the shift's notes. Ratings and text the FTO set by hand are kept.
    static func draftDOR(_ store: Store, dorID: String) async throws -> Int {
        guard var d = store.data.dors[dorID], let t = store.trainee(d.tid) else { return 0 }
        let ns = Array(store.notes(for: d.tid).filter { $0.date == d.date }.reversed())
        guard !ns.isEmpty else { throw ClaudeAPI.Failure.server("There are no notes on this date to draft from.") }
        let segKeys = Set(Ref.segs(for: t).map(\.key))
        var fixedR: [String: String] = [:]
        for (k, on) in d.manual.ratings where on { if let r = d.ratings[k] { fixedR[k] = r } }
        var fixedC: [String: String] = [:]
        for (k, on) in d.manual.comments where on { let c = d.comments[k] ?? ""; if !c.trimmingCharacters(in: .whitespaces).isEmpty { fixedC[k] = c } }
        let keepMost = d.manual.most && d.most.cat != nil, keepLeast = d.manual.least
        let keepNarr = d.manual.narrative && !d.narrative.trimmingCharacters(in: .whitespaces).isEmpty
        let prompt: String
        if d.ungraded {
            prompt = """
            You help a Field Training Officer write a Daily Observation Report (FTEP-1 DOR) for \(program(store, t)). This shift is NOT GRADED (for example a ride with a non-FTO officer, or an orientation shift): nothing is evaluated. Do not give ratings, scores, NRT, or most/least acceptable areas, and do not judge performance against the standard.
            \(voice)
            STYLE: concise, factual, past tense. One or two short sentences per item; no filler. Never write about the notes or the documentation themselves. Never state clock times or imply a time sequence unless a note's own text gives it. Cite call type/case # when given. Never invent facts.
            SEG categories (use only to label an item with the best-fitting category #): \(Ref.segs(for: t).map { "#\($0.n) \($0.name)" }.joined(separator: "; "))
            \(d.phase), DOR #\(d.ride).
            \(fixedC.isEmpty ? "" : "SEG narratives the FTO has written (final, do not return them): \(json(Array(fixedC.keys)))")\(keepNarr ? " The FTO has written the general narrative: return \"narrative\" as an empty string." : "")
            FTO field notes for the \(d.date) shift, in no guaranteed order (tone: + did well, - needs work, 0 observed; "rem" = remedial training minutes):
            \(notesBlock(ns))
            Task: turn the notes into the shift narrative. Put each observation that clearly belongs to a SEG under that SEG # (what I showed, discussed or had the trainee do, and how the trainee did). Put orientation activity and anything without a clear SEG in "narrative", one item per line. Report remedial minutes only where a note records remedial training.
            Return ONLY JSON: {"comments":{"<seg#>":"..."},"rem":{"<seg#>":<minutes>},"narrative":"..."}
            """
        } else {
            let prev = DORLogic.previous(store, tid: d.tid, before: d.date)
            prompt = """
            You help a Field Training Officer complete a Daily Observation Report (FTEP-1 DOR) for \(program(store, t)).
            Rating scale: 1 totally unacceptable; 2 a little better than 1; 3 close to 4 but not yet minimally acceptable; 4 minimum acceptable, able to work solo in a safe, skillful, productive and professional manner; 5-6 above acceptable; 7 superior; "NO" not observed.
            Narrative documentation is REQUIRED for ratings of 1, 2, 6, 7 and any remedial training (REM). Never assign NRT.
            DOR writing standards: SET THE STAGE, USE QUOTES (only quotes present in the notes), USE LISTS, INCLUDE REMEDIAL, FRONT = BACK (ratings must match the narrative), USE SEG language, REMEMBER YOUR AUDIENCE (supervisor, trainee, possibly a review board), DO NOT PREDICT future performance.
            \(voice)
            Standardized Evaluation Guidelines (category: description | 1 | 4 | 7):
            \(segBrief(t))

            \(d.phase), DOR #\(d.ride)\(d.phase == "Phase I" && d.ride <= 8 ? " (limbo/orientation weeks)" : "").
            \(prev.map { "Previous DOR ratings: \(json($0.ratings))" } ?? "This is the first DOR.")
            RATINGS THE FTO HAS SET (final, do not change them; every narrative you write for these SEGs must support exactly this rating): \(json(fixedR))
            NARRATIVES THE FTO HAS WRITTEN (final, do not rewrite or return them): \(json(Array(fixedC.keys)))
            \(keepMost ? "The FTO has already written the most acceptable area: do not return \"most\"." : "")\(keepLeast ? " The FTO has already written the least acceptable area: do not return \"least\"." : "")\(keepNarr ? " The FTO has written the general narrative: do not return \"narrative\"." : "")

            FTO field notes for the \(d.date) shift, in no guaranteed order (tone: + did well, - needs work, 0 observed; "rem" = remedial training minutes the FTO logged). Notes are often written after the fact, so they carry no reliable times:
            \(notesBlock(ns))

            Task: For each SEG the notes give evidence for (assign untagged notes to the best-fitting SEG; one note may support several), give a rating anchored to the SEG language (use the FTO's rating where one is set) and write the narrative from the note: objective, behaviour-based, past tense, first person, cite call type/case # when given, and say what I corrected and how the trainee responded if the note says so. Never invent facts. Ratings of 1, 2, 6, 7 must be justified by the specific behaviour in the note.
            STYLE: concise. One or two short sentences per SEG; no filler, no restating the SEG definition, no hedging. Never write about the notes or the documentation themselves (no "I did not document", "no notes were taken", "not observed in notes", "it should be noted").
            SEGs with an FTO-set rating but NO note that relates to them: do not explain the gap. If the rating is below 4, the narrative is exactly: "Trainee did not meet the minimum acceptable standard." If the rating is 6 or 7, it is exactly: "Trainee exceeded the minimum acceptable standard." For any other rating, return no narrative for that SEG.
            Only report remedial minutes ("rem") for SEGs where the notes explicitly record remedial training. Pick the most acceptable area (SEG # + the specific incident, one or two sentences). Give a least acceptable area ONLY if a final rating (yours or the FTO's) is below 4, in one or two sentences; otherwise return null for it. Put general observations that fit no SEG in "narrative", at most three sentences, or return an empty string if there are none. Never state clock times or imply a time sequence (no "at 0942", "first", "later in the shift") unless a note's own text gives it.
            Return ONLY JSON: {"ratings":{"<seg#>":"1".."7"|"NO"},"comments":{"<seg#>":"..."},"rem":{"<seg#>":<minutes>},"most":{"cat":<seg#>,"text":"..."},"least":{"cat":<seg#>,"text":"..."}|null,"narrative":"..."}
            """
        }
        let out = try await ClaudeAPI.completeJSON(prompt)
        // Re-read in case the FTO edited while Claude was working.
        d = store.data.dors[dorID] ?? d
        var aiCats: [Int] = []
        func key(_ any: Any?) -> String? { if let i = any as? Int { return String(i) }; if let s = any as? String { return s }; if let n = any as? NSNumber { return n.stringValue }; return nil }
        if !d.ungraded, let r = out["ratings"] as? [String: Any] {
            for (k, v) in r { if segKeys.contains(k), let s = key(v), Rating.isNum(s) || s == "NO", d.manual.ratings[k] != true { d.ratings[k] = s; aiCats.append(Int(k)!) } }
        }
        for k in segKeys where fixedC[k] == nil { d.comments[k] = "" }
        if let c = out["comments"] as? [String: Any] {
            for (k, v) in c { if segKeys.contains(k), fixedC[k] == nil, let s = v as? String, !s.isEmpty { d.comments[k] = s; if !aiCats.contains(Int(k)!) { aiCats.append(Int(k)!) } } }
        }
        if let rm = out["rem"] as? [String: Any] {
            for (k, v) in rm { if segKeys.contains(k), let m = (v as? NSNumber)?.intValue ?? Int(key(v) ?? ""), m > 0, (d.time[k] ?? "").isEmpty { d.rem[k] = true; d.time[k] = String(m) } }
        }
        if !d.ungraded {
            if !keepMost, let m = out["most"] as? [String: Any], let c = key(m["cat"]), segKeys.contains(c) { d.most = AreaNote(cat: Int(c), text: (m["text"] as? String) ?? "") }
            if !keepLeast {
                if let l = out["least"] as? [String: Any], let c = key(l["cat"]), segKeys.contains(c) { d.least = AreaNote(cat: Int(c), text: (l["text"] as? String) ?? "") }
                else if !DORLogic.failing(d) { d.least = AreaNote() }
            }
        }
        if !keepNarr { d.narrative = ((out["narrative"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines) }
        d.ai = true; d.aiCats = aiCats
        for n in ns where !d.used.contains(n.id) { d.used.append(n.id) }
        let final = d
        await MainActor.run { store.data.dors[dorID] = final }
        return aiCats.count
    }

    static func draftRTP(_ store: Store, rtpID: String) async throws {
        guard var r = store.data.rtps[rtpID], let cat = r.cat, let s = Ref.byN[cat], let t = store.trainee(r.tid) else { return }
        let ds = store.dors(for: r.tid).filter { $0.date <= r.date }.suffix(6).map { ["date": $0.date, "rating": $0.rating(cat), "rem": $0.isRem(cat), "time": $0.minutes(cat), "narrative": $0.comment(cat)] as [String: Any] }
        let ns = store.notes(for: r.tid).filter { $0.cat == cat }.prefix(25).map { "\($0.date) \($0.tone) \($0.text)" }
        let prior = store.rtps(for: r.tid).filter { $0.cat == cat && $0.id != r.id }.map { ["date": $0.date, "assignment": $0.assignment, "completed": nn($0.completed)] }
        let prompt = """
        You help a Field Training Officer in \(program(store, t)) write a Remedial Training Worksheet for SEG #\(s.n) \(s.name).
        SEG: \(s.d) | 1=\(s.a1) | 4 (acceptable)=\(s.a4)
        Recent DOR entries for this SEG: \(json(Array(ds)))
        FTO notes for this SEG: \(ns.joined(separator: " || "))
        Prior RTPs in this SEG: \(json(prior))
        Existing draft deficiency text: \(r.deficiency.isEmpty ? "(none)" : r.deficiency)
        Write two plain-text sections. \(voice) "deficiency": define the problem specifically with dated examples from the data, measured against the SEG's acceptable standard, and describe the training already conducted. "assignment": specific, measurable training assignments with clear performance goals and a time frame (e.g. "practice self-initiated car stops daily for 4 shifts: at least 4 F.I.s and 2 citations per shift"), and state that the trainee is expected to perform at a satisfactory level in this area. Be concise and direct: short sentences, no filler, never write about the notes or documentation themselves. Use only facts in the data. Return ONLY JSON {"deficiency":"...","assignment":"..."}
        """
        let out = try await ClaudeAPI.completeJSON(prompt)
        r = store.data.rtps[rtpID] ?? r
        if let v = out["deficiency"] as? String { r.deficiency = v }
        if let v = out["assignment"] as? String { r.assignment = v }
        let final = r
        await MainActor.run { store.data.rtps[rtpID] = final }
    }

    static func summary(_ store: Store, trainee t: Trainee, dors ds: [DOR], weekly: Bool) async throws -> String {
        let compact = ds.map { ["date": $0.date, "ride": $0.ride, "ungraded": $0.ungraded, "ratings": $0.ratings, "rem": $0.rem, "time": $0.time, "nrt": $0.nrt,
                                "most": ["cat": nn($0.most.cat), "text": $0.most.text], "least": ["cat": nn($0.least.cat), "text": $0.least.text], "narr": $0.comments] as [String: Any] }
        let rt = store.rtps(for: t.id).map { ["cat": nn($0.cat), "date": $0.date, "assignment": $0.assignment, "completed": nn($0.completed), "competent": nn($0.competent)] }
        let ck = store.checks(for: t.id).items
        let due = Phase.due(t.phase)
        let missing = Ref.checklist.flatMap { $0.items.filter { ($0.ph.map(due.contains) ?? false) && ck[$0.id]?.p == nil }.map(\.t) }.prefix(40)
        let ask = weekly
            ? "Write the \"COMMENTS REGARDING STRENGTHS, WEAKNESSES AND PROGRESS\" section of the FTEP Supervisor Weekly Report: overall performance measured by the SEGs, specific strengths and weaknesses with examples, whether progress is increasing, decreasing or unchanged, and remedial training provided with its effect and any further training needed. Under 250 words."
            : "Write a Phase Evaluation Form summary for \(t.phase): 1) strengths with specific examples; 2) weaknesses with specific examples; 3) where remedial training was given or special testing done, a brief description of the process and result; 4) recommendations for follow-up training for the next FTO/phase; 5) a clear statement of whether the trainee IS or IS NOT currently performing at the level of a solo patrol officer, based only on the record. Under 400 words."
        let prompt = """
        You help a Field Training Officer in \(program(store, t)). The trainee is currently in \(t.phase).
        \(voice)
        SEGs: \(Ref.segs(for: t).map { "#\($0.n) \($0.name)" }.joined(separator: "; ")). Scale: 4 = minimum acceptable for solo work. REM = remedial training given (TIME minutes). NRT = not responding to training. Ungraded DORs carry narrative only.
        DORs: \(json(compact))
        Remedial Training Plans: \(json(rt))
        Standard Task Checklist items due by this phase not yet performed: \(missing.isEmpty ? "none" : missing.joined(separator: "; "))
        \(ask)
        Plain text, no markdown symbols, first person as the FTO, past tense, concise (short sentences, no filler, never comment on what was or wasn't documented), only facts in the data, do not predict future performance.
        """
        return try await ClaudeAPI.complete(prompt).trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
