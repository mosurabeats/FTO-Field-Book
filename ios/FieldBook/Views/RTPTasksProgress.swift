import SwiftUI
import Charts

// MARK: - Remedial Training Worksheet

struct RTPEditorView: View {
    @EnvironmentObject var store: Store
    @Environment(\.pal) var pal
    @Environment(\.dismiss) var dismiss
    let rtpID: String
    @State private var drafting = false
    @State private var aiError: String? = nil
    @State private var confirmDelete = false
    @State private var followUp: String? = nil

    private var r: Binding<RTP> { store.rtpBinding(rtpID) }

    var body: some View {
        let rtp = r.wrappedValue
        let done = rtp.cat.map { DORLogic.rtpCompleted(store, tid: rtp.tid, cat: $0) } ?? 0
        return ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                HStack { Spacer(); if rtp.completed == true { Text("COMPLETED").font(.system(size: 13, weight: .bold)).foregroundStyle(pal.pos) } else { Tag(text: "OPEN") } }
                Card(title: "Remedial Training Worksheet") {
                    Labeled(label: "SEG / area") {
                        Menu {
                            ForEach(Ref.segs(for: store.trainee(rtp.tid))) { s in Button("\(s.n). \(s.name)") { r.wrappedValue.cat = s.n } }
                        } label: {
                            HStack { Text(rtp.cat.map { "\($0). \(Ref.name($0))" } ?? "— select —").foregroundStyle(rtp.cat == nil ? pal.dim : pal.tx); Spacer(); Image(systemName: "chevron.up.chevron.down").foregroundStyle(pal.mu) }.fieldBox()
                        }
                    }
                    if rtp.cat != nil {
                        Text("\(done) completed RTP\(done == 1 ? "" : "s") in this SEG" + (done >= 3 ? " — NRT may be applied if still deficient" : " · \(3 - done) more before NRT is allowed"))
                            .font(.system(size: 12.5)).foregroundStyle(pal.mu)
                    }
                    HStack(spacing: 10) {
                        Labeled(label: "Date") { DatePicker("", selection: dateBinding(\.date), displayedComponents: .date).labelsHidden() }
                        Labeled(label: "Training week #") { TextField("", text: r.week).keyboardType(.numberPad).fieldBox() }.frame(width: 130)
                    }
                    Labeled(label: "Complete assignment by") {
                        DatePicker("", selection: Binding(get: { DateUtil.date(rtp.due) ?? Date().addingTimeInterval(7 * 86400) }, set: { r.wrappedValue.due = DateUtil.string($0) }), displayedComponents: .date).labelsHidden()
                    }
                }
                Card(title: "Performance deficiencies") {
                    Text("Define the problem specifically with examples. Describe the training already conducted.").font(.system(size: 12.5)).foregroundStyle(pal.mu)
                    NoteEditor(text: r.deficiency, minHeight: 130)
                }
                Card(title: "Training assignment") {
                    Text("Specific, measurable goals, e.g. \"practice self-initiated car stops daily for 4 shifts: at least 4 F.I.s and 2 citations per shift.\"").font(.system(size: 12.5)).foregroundStyle(pal.mu)
                    NoteEditor(text: r.assignment, minHeight: 130)
                    if ClaudeAPI.hasKey {
                        Button { draft() } label: { HStack { if drafting { ProgressView().tint(pal.aiTx) }; Text(drafting ? "Drafting…" : "✦ Draft deficiency & assignment with Claude") } }
                            .buttonStyle(AIButtonStyle()).disabled(drafting || rtp.cat == nil)
                        if let e = aiError { Text(e).font(.system(size: 13)).foregroundStyle(pal.neg) }
                    }
                }
                Card(title: "Assignment completion") {
                    yesNo("Satisfactorily completed the training plan?", \.completed)
                    yesNo("Now performing at a competent level?", \.competent)
                    yesNo("Additional assignment given?", \.additional)
                    Labeled(label: "Comments") { NoteEditor(text: r.comments, placeholder: "If not satisfactorily completed, specific recommendations are required", minHeight: 80) }
                    if rtp.completed == false || rtp.additional == true {
                        Button("Create follow-up RTP") {
                            var n = RTP(); n.tid = rtp.tid; n.cat = rtp.cat; n.phase = rtp.phase
                            n.deficiency = "Follow-up to RTP of \(DateUtil.us(rtp.date)).\n" + rtp.comments
                            store.data.rtps[n.id] = n; followUp = n.id
                        }.buttonStyle(SecondaryButtonStyle())
                    }
                }
                InfoBox(title: "Hand-off", lines: ["The originating FTO must pass this assignment on to the trainee's next FTO so follow-up can be tracked."], danger: false)
                HStack(spacing: 10) {
                    ShareLink(item: DORLogic.rtpText(store, rtp)) { Text("Share worksheet") }.buttonStyle(PrimaryButtonStyle())
                    Button("Delete", role: .destructive) { confirmDelete = true }.buttonStyle(SecondaryButtonStyle(danger: true)).frame(width: 110)
                }
            }.padding(14)
        }
        .scrollDismissesKeyboard(.interactively)
        .screen(pal, store: store)
        .navigationDestination(item: $followUp) { id in RTPEditorView(rtpID: id) }
        .confirmationDialog("Delete this RTP?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete", role: .destructive) { store.data.rtps[rtpID] = nil; dismiss() }
        }
    }

    private func dateBinding(_ kp: WritableKeyPath<RTP, String>) -> Binding<Date> {
        Binding(get: { DateUtil.date(r.wrappedValue[keyPath: kp]) ?? Date() }, set: { r.wrappedValue[keyPath: kp] = DateUtil.string($0) })
    }

    private func yesNo(_ label: String, _ kp: WritableKeyPath<RTP, Bool?>) -> some View {
        HStack {
            Text(label).font(.system(size: 14)).foregroundStyle(pal.tx)
            Spacer()
            Picker("", selection: Binding(get: { r.wrappedValue[keyPath: kp].map { $0 ? 1 : 0 } ?? -1 }, set: { r.wrappedValue[keyPath: kp] = $0 == -1 ? nil : $0 == 1 })) {
                Text("—").tag(-1); Text("Yes").tag(1); Text("No").tag(0)
            }.pickerStyle(.segmented).frame(width: 150)
        }
    }

    private func draft() {
        drafting = true; aiError = nil
        Task {
            do { try await Drafting.draftRTP(store, rtpID: rtpID); await MainActor.run { drafting = false } }
            catch { await MainActor.run { drafting = false; aiError = error.localizedDescription } }
        }
    }
}

// MARK: - Standard Task Checklist

struct TasksView: View {
    @EnvironmentObject var store: Store
    @Environment(\.pal) var pal
    @State private var filter = "due"
    @State private var q = ""
    @State private var expanded: Set<Int> = []

    var body: some View {
        Group {
            if let t = store.trainee { content(t) } else { Onboarding() }
        }
        .screen(pal, store: store)
    }

    private func phaseColor(_ p: String) -> Color {
        switch p { case "L": return Color(hex: "E5C400"); case "1": return Color(hex: "E5483D"); case "2": return Color(hex: "F39A1C"); default: return Color(hex: "6FBF3F") }
    }

    private func content(_ t: Trainee) -> some View {
        let ck = store.checks(for: t.id).items
        let due = Phase.due(t.phase)
        let all = Ref.checklist.flatMap { $0.items.filter { $0.ph != nil } }
        func done(_ i: ChecklistItem) -> Bool { ck[i.id]?.p != nil }
        func touched(_ i: ChecklistItem) -> Bool { let m = ck[i.id]; return m?.d != nil || m?.dm != nil || m?.p != nil }
        func keep(_ i: ChecklistItem) -> Bool {
            guard let ph = i.ph else { return q.isEmpty }
            if !q.isEmpty && !i.t.localizedCaseInsensitiveContains(q) { return false }
            switch filter { case "due": return due.contains(ph) && !done(i); case "open": return !touched(i); case "all": return true; default: return ph == filter }
        }
        return ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Card(title: "Standard Task Checklist") {
                    Text("Tap Disc, Demo or Perf to stamp today's date. Colours match the phase codes in your manual.").font(.system(size: 12.5)).foregroundStyle(pal.mu)
                    ForEach(["L", "1", "2", "3"], id: \.self) { p in
                        let items = all.filter { $0.ph == p }
                        let d = items.filter(done).count
                        HStack {
                            Circle().fill(phaseColor(p)).frame(width: 9, height: 9)
                            Text(Ref.phaseLabel[p] ?? p).font(.system(size: 13)).foregroundStyle(pal.tx).frame(width: 72, alignment: .leading)
                            ProgressView(value: Double(d), total: Double(max(items.count, 1))).tint(pal.pos)
                            Text("\(d)/\(items.count)").font(Fonts.mono(12, .regular)).foregroundStyle(pal.mu).frame(width: 60, alignment: .trailing)
                        }
                    }
                }
                TextField("Search tasks (DUI, pursuit, juvenile)", text: $q).fieldBox()
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach([("due", "Due by \(t.phase)"), ("open", "Not started"), ("all", "All"), ("L", "Limbo"), ("1", "Phase I"), ("2", "Phase II"), ("3", "Phase III")], id: \.0) { k, l in
                            Chip(text: l, on: filter == k) { filter = k }
                        }
                    }
                }
                ForEach(Ref.checklist) { sec in
                    let items = sec.items.filter(keep)
                    let real = sec.items.filter { $0.ph != nil }
                    if items.contains(where: { $0.ph != nil }) {
                        DisclosureGroup(isExpanded: Binding(get: { expanded.contains(sec.n) || !q.isEmpty }, set: { on in if on { expanded.insert(sec.n) } else { expanded.remove(sec.n) } })) {
                            VStack(spacing: 0) {
                                ForEach(items) { i in
                                    if let ph = i.ph { CheckRow(item: i, color: phaseColor(ph), mark: ck[i.id] ?? CheckMark()) { k in toggle(t, i, k) } }
                                    else { Text(i.t.uppercased()).font(.system(size: 12, weight: .bold)).foregroundStyle(pal.mu).frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 8) }
                                }
                            }
                        } label: {
                            HStack {
                                Text("\(sec.n)").font(Fonts.mono(13)).foregroundStyle(pal.hi2).frame(width: 24, alignment: .leading)
                                Text(sec.t).fontWeight(.semibold).foregroundStyle(pal.tx)
                                Spacer()
                                Text("\(real.filter(done).count)/\(real.count)").font(Fonts.mono(12, .regular)).foregroundStyle(pal.mu)
                            }
                        }
                        .tint(pal.mu)
                        .padding(12).background(pal.p1, in: RoundedRectangle(cornerRadius: 14))
                        .overlay(RoundedRectangle(cornerRadius: 14).stroke(pal.line, lineWidth: 1))
                    }
                }
                ShareLink(item: DORLogic.checklistText(t, store.checks(for: t.id))) { Text("Share checklist") }.buttonStyle(SecondaryButtonStyle())
            }.padding(14)
        }
    }

    private func toggle(_ t: Trainee, _ i: ChecklistItem, _ k: String) {
        var c = store.checks(for: t.id)
        var m = c.items[i.id] ?? CheckMark()
        let today = DateUtil.today()
        switch k {
        case "d": m.d = m.d == nil ? today : nil
        case "dm": m.dm = m.dm == nil ? today : nil
        default: m.p = m.p == nil ? today : nil
        }
        if !store.profile.fto.initials.isEmpty { m.by = store.profile.fto.initials }
        c.items[i.id] = m
        store.data.checks[t.id] = c
        UISelectionFeedbackGenerator().selectionChanged()
    }
}

struct CheckRow: View {
    @Environment(\.pal) var pal
    let item: ChecklistItem
    let color: Color
    let mark: CheckMark
    let tap: (String) -> Void
    var body: some View {
        HStack(spacing: 8) {
            Circle().fill(color).frame(width: 8, height: 8)
            Text(item.t).font(.system(size: item.lvl == 0 ? 14 : 13)).foregroundStyle(item.lvl == 0 ? pal.tx : pal.tx2)
                .padding(.leading, CGFloat(item.lvl) * 12).frame(maxWidth: .infinity, alignment: .leading)
            stamp("Disc", mark.d, "d"); stamp("Demo", mark.dm, "dm"); stamp("Perf", mark.p, "p")
        }
        .padding(.vertical, 7)
        .overlay(alignment: .bottom) { Rectangle().fill(pal.line).frame(height: 1) }
    }
    private func stamp(_ l: String, _ v: String?, _ k: String) -> some View {
        Button { tap(k) } label: {
            VStack(spacing: 0) {
                Text(l).font(.system(size: 10.5, weight: .bold))
                if let v { Text(DateUtil.short(v)).font(.system(size: 9.5)) }
            }
            .frame(width: 46, height: 38)
            .foregroundStyle(v == nil ? pal.dim : pal.pos)
            .background(v == nil ? pal.p2 : pal.pos.opacity(0.16), in: RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(v == nil ? pal.line : pal.pos, lineWidth: 1))
        }.buttonStyle(.plain)
    }
}

// MARK: - Progress

struct ProgressTab: View {
    @EnvironmentObject var store: Store
    @Environment(\.pal) var pal
    @State private var weekKey: String? = nil
    @State private var summary: String? = nil
    @State private var working = false
    @State private var aiError: String? = nil

    var body: some View {
        Group {
            if let t = store.trainee { content(t) } else { Onboarding() }
        }
        .screen(pal, store: store)
    }

    struct Trend: Identifiable { let seg: SEG; let vals: [(Int, Int)]; let last: Int?; let avg3: Double?; let nrt: Bool; let rem: Int; var id: Int { seg.n } }

    private func trends(_ t: Trainee, _ ds: [DOR]) -> [Trend] {
        Ref.segs(for: t).map { s in
            let vals: [(Int, Int)] = ds.enumerated().compactMap { i, d in Int(d.rating(s.n)).map { (i, $0) } }
            let last3 = vals.suffix(3).map(\.1)
            return Trend(seg: s, vals: vals, last: vals.last?.1, avg3: last3.isEmpty ? nil : Double(last3.reduce(0, +)) / Double(last3.count),
                         nrt: ds.contains { $0.isNRT(s.n) }, rem: ds.filter { $0.isRem(s.n) }.count)
        }
    }

    private func content(_ t: Trainee) -> some View {
        let all = store.dors(for: t.id)
        let graded = all.filter { !$0.ungraded }
        let tr = trends(t, graded)
        let watch = tr.filter { ($0.avg3 ?? 9) < 4 || $0.nrt }
        let mins = all.reduce(0) { $0 + $1.time.values.reduce(0) { $0 + (Int($1) ?? 0) } }
        let weeks = DORLogic.weeks(store, tid: t.id)
        let wk = weeks.first { $0.key == weekKey } ?? weeks.last
        return ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                if all.isEmpty {
                    Text("Progress shows up once you have a DOR or two.").foregroundStyle(pal.mu).frame(maxWidth: .infinity).padding(.vertical, 40)
                } else {
                    HStack(spacing: 8) {
                        stat("\(all.count)", "DORs"); stat("\(store.notes(for: t.id).count)", "notes"); stat(String(format: "%.1f", Double(mins) / 60), "hrs remedial")
                    }
                    Card(title: "Watch list (last 3 avg < 4 or NRT)") {
                        if watch.isEmpty { Text("Nothing trending below acceptable.").foregroundStyle(pal.pos) }
                        ForEach(watch) { trendRow($0) }
                    }
                    if let wk {
                        Card(title: "Weekly report data") {
                            Picker("Week", selection: Binding(get: { wk.key }, set: { weekKey = $0 })) {
                                ForEach(weeks, id: \.key) { w in Text("\(w.key) (\(w.dors.count) DORs)").tag(w.key) }
                            }.pickerStyle(.menu).tint(pal.tx)
                            let txt = DORLogic.weekText(t, wk.dors)
                            Text(txt).font(Fonts.mono(11, .regular)).foregroundStyle(Color(hex: "111111")).padding(10)
                                .frame(maxWidth: .infinity, alignment: .leading).background(Color(hex: "FBFAF6"), in: RoundedRectangle(cornerRadius: 8))
                            HStack(spacing: 10) {
                                Button("Copy") { UIPasteboard.general.string = txt }.buttonStyle(SecondaryButtonStyle())
                                if ClaudeAPI.hasKey { Button("✦ Weekly comments") { run(t, wk.dors, weekly: true) }.buttonStyle(AIButtonStyle()).disabled(working) }
                            }
                        }
                    }
                    if ClaudeAPI.hasKey {
                        Card(title: "Phase evaluation") {
                            Text("Claude reads every DOR, RTP and note for this trainee's current phase and drafts the Phase Evaluation: strengths, weaknesses, remedial training and its effect, recommended follow-up, and whether the trainee is performing at the level of a solo patrol officer.")
                                .font(.system(size: 12.5)).foregroundStyle(pal.mu)
                            Button { run(t, all.filter { $0.phase == t.phase }, weekly: false) } label: { HStack { if working { ProgressView().tint(pal.aiTx) }; Text("✦ Draft phase evaluation") } }
                                .buttonStyle(AIButtonStyle()).disabled(working)
                        }
                    }
                    if let e = aiError { Text(e).foregroundStyle(pal.neg) }
                    if let s = summary {
                        Card(title: "✦ Draft — review & edit before you use it") {
                            Text(s).font(.system(size: 14.5)).foregroundStyle(pal.tx).textSelection(.enabled)
                            Button("Copy") { UIPasteboard.general.string = s }.buttonStyle(SecondaryButtonStyle())
                        }
                    }
                    Card(title: "All SEGs · \(graded.count) graded DORs") { ForEach(tr) { trendRow($0) } }
                }
            }.padding(14)
        }
    }

    private func stat(_ v: String, _ l: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(v).font(Fonts.mono(22, .bold)).foregroundStyle(pal.tx)
            Text(l).font(.system(size: 12)).foregroundStyle(pal.mu)
        }
        .padding(12).frame(maxWidth: .infinity, alignment: .leading)
        .background(pal.p1, in: RoundedRectangle(cornerRadius: 12)).overlay(RoundedRectangle(cornerRadius: 12).stroke(pal.line, lineWidth: 1))
    }

    private func trendRow(_ tr: Trend) -> some View {
        HStack(spacing: 10) {
            Text("\(tr.seg.n)").font(Fonts.mono(11.5, .regular)).foregroundStyle(pal.dim).frame(width: 22, alignment: .leading)
            HStack(spacing: 4) {
                Text(tr.seg.name).font(.system(size: 13.5)).foregroundStyle(pal.tx).lineLimit(1)
                if tr.rem > 0 { Tag(text: "REM×\(tr.rem)", style: .quiet) }
                if tr.nrt { Tag(text: "NRT", style: .danger) }
            }
            Spacer(minLength: 4)
            Chart {
                RuleMark(y: .value("Standard", 4)).foregroundStyle(pal.dim.opacity(0.6)).lineStyle(StrokeStyle(lineWidth: 1, dash: [2, 3]))
                ForEach(tr.vals, id: \.0) { p in
                    LineMark(x: .value("DOR", p.0), y: .value("Rating", p.1)).foregroundStyle((tr.last ?? 4) < 4 ? pal.neg : pal.acc)
                    PointMark(x: .value("DOR", p.0), y: .value("Rating", p.1)).symbolSize(14).foregroundStyle(p.1 < 4 ? pal.neg : p.1 > 5 ? pal.pos : pal.tx2)
                }
            }
            .chartYScale(domain: 1...7).chartXAxis(.hidden).chartYAxis(.hidden).frame(width: 96, height: 28)
            RatingPill(r: tr.last.map(String.init) ?? "—").scaleEffect(0.85)
        }
        .padding(.vertical, 4)
    }

    private func run(_ t: Trainee, _ ds: [DOR], weekly: Bool) {
        working = true; aiError = nil
        Task {
            do {
                let s = try await Drafting.summary(store, trainee: t, dors: ds, weekly: weekly)
                await MainActor.run { summary = s; working = false }
            } catch { await MainActor.run { aiError = error.localizedDescription; working = false } }
        }
    }
}
