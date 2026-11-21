import SwiftUI

// MARK: - DOR list

struct DORListView: View {
    @EnvironmentObject var store: Store
    @Environment(\.pal) var pal
    @State private var date = Date()
    @State private var open: Route? = nil

    var body: some View {
        Group {
            if let t = store.trainee { content(t) } else { Onboarding() }
        }
        .screen(pal, store: store)
        .navigationDestination(item: $open) { r in
            switch r { case .dor(let id): DOREditorView(dorID: id); case .rtp(let id): RTPEditorView(rtpID: id) }
        }
    }

    private func content(_ t: Trainee) -> some View {
        let ds = store.dors(for: t.id).reversed()
        let rt = store.rtps(for: t.id)
        let day = DateUtil.string(date)
        let exists = store.dors(for: t.id).contains { $0.date == day }
        return ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                backupNudge(t)
                Card(title: "Daily Observation Report") {
                    HStack {
                        DatePicker("", selection: $date, displayedComponents: .date).labelsHidden()
                        Spacer()
                        Button(exists ? "Open DOR" : "Build DOR") { open = .dor(DORLogic.build(store, trainee: t, date: day)) }
                            .buttonStyle(PrimaryButtonStyle()).frame(width: 150)
                    }
                    let n = store.notes(for: t.id).filter { $0.date == day }.count
                    let ride = exists ? (store.dors(for: t.id).first { $0.date == day }?.ride ?? 1) : DORLogic.nextRide(store, tid: t.id, phase: t.phase, date: day)
                    Text("\(n) note\(n == 1 ? "" : "s") on this date · \(Phase.label(t.phase, ride))").font(.system(size: 13)).foregroundStyle(pal.mu)
                }
                Card(title: "Remedial Training Plans") {
                    if rt.isEmpty {
                        Text("None yet. Turning on REM for a SEG offers one. You need 3 completed RTPs in a SEG before NRT can be given.").font(.system(size: 13)).foregroundStyle(pal.mu)
                    }
                    ForEach(rt.prefix(8)) { r in
                        Button { open = .rtp(r.id) } label: { RTPRow(rtp: r) }.buttonStyle(.plain)
                    }
                    Button("＋ New RTP") { var r = RTP(); r.tid = t.id; r.phase = t.phase; store.data.rtps[r.id] = r; open = .rtp(r.id) }
                        .buttonStyle(SecondaryButtonStyle())
                }
                if ds.isEmpty { Text("No DORs yet.").foregroundStyle(pal.mu).frame(maxWidth: .infinity).padding(.vertical, 20) }
                ForEach(Array(ds)) { d in
                    Button { open = .dor(d.id) } label: { DORRow(dor: d) }.buttonStyle(.plain)
                }
            }.padding(14)
        }
    }

    @ViewBuilder private func backupNudge(_ t: Trainee) -> some View {
        let lb = UserDefaults.standard.string(forKey: "lastBackup")
        let stale = lb.flatMap(DateUtil.date).map { Date().timeIntervalSince($0) > 7 * 86400 } ?? true
        if stale && store.dors(for: t.id).count >= 3 {
            InfoBox(title: "Back up your records", lines: ["Last backup: \(lb.map(DateUtil.us) ?? "never"). Trainee → Export backup saves a copy to Files."], danger: false)
        }
    }
}

struct DORRow: View {
    @EnvironmentObject var store: Store
    @Environment(\.pal) var pal
    let dor: DOR
    var body: some View {
        let nums = dor.ratings.values.compactMap { Rating.isNum($0) ? Int($0) : nil }
        let avg = nums.isEmpty || dor.ungraded ? nil : Double(nums.reduce(0, +)) / Double(nums.count)
        let lo = dor.ungraded ? 0 : nums.filter { $0 < 4 }.count
        let rem = dor.rem.values.filter { $0 }.count, nrt = dor.nrt.values.filter { $0 }.count
        let v = DORLogic.validate(store, dor)
        HStack(spacing: 12) {
            RatingPill(r: avg.map { String(format: "%.1f", $0) } ?? "–")
            VStack(alignment: .leading, spacing: 2) {
                Text("DOR \(dor.ride) · \(DateUtil.pretty(dor.date))").font(.system(size: 15, weight: .bold)).foregroundStyle(pal.tx)
                Text([dor.phase, dor.ungraded ? "not graded" : nil, lo > 0 ? "\(lo) below 4" : nil, rem > 0 ? "\(rem) REM" : nil, nrt > 0 ? "\(nrt) NRT" : nil].compactMap { $0 }.joined(separator: " · "))
                    .font(Fonts.mono(12, .regular)).foregroundStyle(pal.mu)
            }
            Spacer()
            if dor.isFinal { Text("FINAL").font(.system(size: 12, weight: .bold)).foregroundStyle(pal.pos) }
            else if !v.errors.isEmpty { Tag(text: "\(v.errors.count)", style: .danger) }
            else { Tag(text: "DRAFT") }
        }
        .padding(12).background(pal.p1, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(pal.line, lineWidth: 1))
    }
}

struct RTPRow: View {
    @Environment(\.pal) var pal
    let rtp: RTP
    var body: some View {
        HStack(spacing: 10) {
            Text(rtp.cat.map(String.init) ?? "–").font(Fonts.mono(13)).foregroundStyle(pal.hi2).frame(width: 24)
            VStack(alignment: .leading, spacing: 1) {
                Text(rtp.cat.map { Ref.name($0) } ?? "Remedial").foregroundStyle(pal.tx)
                Text(DateUtil.short(rtp.date) + (rtp.due.isEmpty ? "" : " · due " + DateUtil.short(rtp.due))).font(.system(size: 12)).foregroundStyle(pal.mu)
            }
            Spacer()
            if rtp.completed == true { Text("DONE").font(.system(size: 12, weight: .bold)).foregroundStyle(pal.pos) }
            else if rtp.completed == false { Tag(text: "NOT MET", style: .danger) }
            else { Tag(text: "OPEN") }
        }.padding(.vertical, 6)
    }
}

// MARK: - DOR editor

struct RateTarget: Identifiable, Equatable { let n: Int; var id: Int { n } }

struct DOREditorView: View {
    @EnvironmentObject var store: Store
    @Environment(\.pal) var pal
    @Environment(\.dismiss) var dismiss
    let dorID: String
    @State private var rate: RateTarget? = nil
    @State private var addSEG = false
    @State private var showExport = false
    @State private var confirmDelete = false
    @State private var drafting = false
    @State private var aiError: String? = nil
    @State private var toast: String? = nil
    @State private var openRTP: String? = nil

    private var d: Binding<DOR> { store.dorBinding(dorID) }

    var body: some View {
        Group {
            if store.data.dors[dorID] != nil { editor } else { Text("This DOR was deleted.").foregroundStyle(pal.mu) }
        }
        .screen(pal, store: store)
        .sheet(item: $rate) { r in RatingSheet(dorID: dorID, startN: r.n, openRTP: { id in rate = nil; openRTP = id }) }
        .sheet(isPresented: $addSEG) { SEGPicker(allowNone: false) { n in if let n { DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { rate = RateTarget(n: n) } } } }
        .sheet(isPresented: $showExport) { ExportSheet(dorID: dorID) }
        .navigationDestination(item: $openRTP) { id in RTPEditorView(rtpID: id) }
        .confirmationDialog("Delete this DOR? Your notes are kept.", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete DOR", role: .destructive) { store.data.dors[dorID] = nil; dismiss() }
        }
        .overlay(alignment: .bottom) { ToastView(text: $toast) }
    }

    private var editor: some View {
        let dor = d.wrappedValue
        let locked = dor.isFinal
        let issues = DORLogic.validate(store, dor)
        return ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                HStack { Spacer(); if locked { Text("FINAL · locked").font(.system(size: 13, weight: .bold)).foregroundStyle(pal.pos) } else { Tag(text: "DRAFT") } }
                DORHeaderCard(d: d, locked: locked)
                if !locked { draftCard(dor) }
                if !locked && !issues.errors.isEmpty { InfoBox(title: "Required before final", lines: issues.errors) }
                if !locked && !issues.warnings.isEmpty { InfoBox(title: "Check", lines: issues.warnings, danger: false) }
                if dor.ai && !locked { InfoBox(title: "✦ Claude drafted the items marked ✦", lines: ["Confirm each one against what you saw."], danger: false) }
                if dor.ungraded { ungradedCard(dor, locked: locked) } else { ratingsCard(dor); AreaCards(d: d, locked: locked) }
                NarrativeCard(d: d, locked: locked)
                ShiftDataCard(d: d, locked: locked)
                HStack(spacing: 10) {
                    Button("Export FTEP-1") { showExport = true }.buttonStyle(PrimaryButtonStyle())
                    if locked { Button("Reopen") { d.wrappedValue.status = "draft" }.buttonStyle(SecondaryButtonStyle()) }
                    else {
                        Button("Mark final") {
                            if issues.errors.isEmpty { d.wrappedValue.status = "final"; d.wrappedValue.finalAt = DateUtil.nowMillis(); toast = "DOR marked final" }
                            else { toast = "Fix the items in red first" }
                        }.buttonStyle(SecondaryButtonStyle())
                    }
                }
                Button("Delete this DOR", role: .destructive) { confirmDelete = true }.buttonStyle(SecondaryButtonStyle(danger: true))
            }.padding(14)
        }
        .scrollDismissesKeyboard(.interactively)
    }

    @ViewBuilder private func draftCard(_ dor: DOR) -> some View {
        let newNotes = store.notes(for: dor.tid).filter { $0.date == dor.date && !dor.used.contains($0.id) }.count
        Card {
            if newNotes > 0 {
                HStack {
                    Text("\(newNotes) new note\(newNotes > 1 ? "s" : "") for this date since the DOR was built.").font(.system(size: 13.5)).foregroundStyle(pal.tx)
                    Spacer()
                    Button("Pull in") { var x = d.wrappedValue; let n = DORLogic.pullNotes(store, into: &x); d.wrappedValue = x; toast = "Pulled in \(n) note\(n == 1 ? "" : "s")" }
                        .buttonStyle(SecondaryButtonStyle()).frame(width: 96)
                }
            }
            if ClaudeAPI.hasKey {
                Button { runDraft() } label: {
                    HStack { if drafting { ProgressView().tint(pal.aiTx) }; Text(drafting ? "Reading notes against the SEGs…" : "✦ Draft \(dor.ungraded ? "narrative" : "ratings & narrative") with Claude") }
                }.buttonStyle(AIButtonStyle()).disabled(drafting)
                Text(dor.ungraded ? "Writes a short narrative from this shift's notes, with SEG numbers where they fit. Nothing is graded. Anything you typed yourself is kept."
                     : "Suggests ratings and documentation from this shift's notes. It never sets NRT. Ratings and narratives you set yourself are kept on a redraft. Every ✦ is a suggestion until you check it.")
                    .font(.system(size: 12.5)).foregroundStyle(pal.mu)
                if let e = aiError { Text(e).font(.system(size: 13)).foregroundStyle(pal.neg) }
            } else {
                Text("Add your Claude API key under Trainee → Claude drafting to draft DORs from your notes.").font(.system(size: 13)).foregroundStyle(pal.mu)
            }
        }
    }

    private func runDraft() {
        drafting = true; aiError = nil
        Task {
            do {
                let n = try await Drafting.draftDOR(store, dorID: dorID)
                await MainActor.run { drafting = false; toast = "Claude drafted \(n) SEGs — check each ✦" }
            } catch {
                await MainActor.run { drafting = false; aiError = error.localizedDescription }
            }
        }
    }

    private func ratingsCard(_ dor: DOR) -> some View {
        let t = store.trainee(dor.tid)
        let prev = DORLogic.previous(store, tid: dor.tid, before: dor.date)
        return VStack(alignment: .leading, spacing: 0) {
            ForEach(Ref.groups, id: \.self) { g in
                let ss = Ref.segs(for: t).filter { $0.g == g }
                if !ss.isEmpty {
                    Text(g.uppercased()).font(.system(size: 12, weight: .bold)).kerning(0.7).foregroundStyle(pal.hi2).padding(.top, 16).padding(.bottom, 4)
                    ForEach(ss) { s in
                        if let sh = Ref.subhead[s.n] { Text(sh).font(.system(size: 12.5, weight: .semibold)).foregroundStyle(pal.mu).padding(.top, 8) }
                        Button { rate = RateTarget(n: s.n) } label: { SEGRow(seg: s, dor: dor, prev: prev?.rating(s.n)) }.buttonStyle(.plain)
                        Divider().overlay(pal.line)
                    }
                }
            }
        }
        .padding(.horizontal, 12).padding(.bottom, 8)
        .background(pal.p1, in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(pal.line, lineWidth: 1))
    }

    private func ungradedCard(_ dor: DOR, locked: Bool) -> some View {
        let segs = Ref.segs(for: store.trainee(dor.tid)).filter { !dor.comment($0.n).trimmingCharacters(in: .whitespaces).isEmpty || dor.isRem($0.n) }
        return Card(title: "Not graded") {
            Text("This DOR is narrative only: the rating boxes on the form stay blank. Write the narrative below, and add a SEG narrative wherever you want a category # on the form.")
                .font(.system(size: 13)).foregroundStyle(pal.mu)
            ForEach(segs) { s in
                Button { rate = RateTarget(n: s.n) } label: { SEGRow(seg: s, dor: dor, prev: nil, showPill: false) }.buttonStyle(.plain)
            }
            if !locked { Button("＋ Narrative for a SEG") { addSEG = true }.buttonStyle(SecondaryButtonStyle()) }
        }
    }
}

struct SEGRow: View {
    @Environment(\.pal) var pal
    let seg: SEG
    let dor: DOR
    let prev: String?
    var showPill = true
    var body: some View {
        let r = dor.rating(seg.n), c = dor.comment(seg.n)
        let missing = !dor.ungraded && (Rating.needsNarrative(r) || dor.isRem(seg.n) || dor.isNRT(seg.n)) && c.trimmingCharacters(in: .whitespaces).isEmpty
        HStack(spacing: 10) {
            Text("\(seg.n)").font(Fonts.mono(12, .regular)).foregroundStyle(pal.dim).frame(width: 22, alignment: .leading)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 4) {
                    Text(seg.name).font(.system(size: 15)).foregroundStyle(pal.tx).multilineTextAlignment(.leading)
                    if missing { Tag(text: "narrative", style: .danger) }
                    if dor.isRem(seg.n) { Tag(text: "REM" + (dor.minutes(seg.n).isEmpty ? "" : " \(dor.minutes(seg.n))m"), style: .quiet) }
                    if dor.isNRT(seg.n) && !dor.ungraded { Tag(text: "NRT", style: .danger) }
                    if dor.aiCats.contains(seg.n) { Tag(text: "✦", style: .info) }
                }
                if !c.isEmpty { Text(c.components(separatedBy: "\n").first ?? "").font(.system(size: 12.5)).foregroundStyle(pal.mu).lineLimit(1) }
                else if let p = prev, showPill { Text("prev \(p)").font(Fonts.mono(11, .regular)).foregroundStyle(pal.dim) }
            }
            Spacer(minLength: 4)
            if showPill { RatingPill(r: r) } else { Image(systemName: "chevron.right").foregroundStyle(pal.dim) }
        }
        .padding(.vertical, 10).contentShape(Rectangle())
    }
}

struct DORHeaderCard: View {
    @EnvironmentObject var store: Store
    @Environment(\.pal) var pal
    @Binding var d: DOR
    let locked: Bool
    var body: some View {
        Card(title: "DOR # \(d.ride) · \(DateUtil.pretty(d.date))") {
            Text(Phase.label(d.phase, d.ride)).font(.system(size: 13)).foregroundStyle(pal.mu)
            HStack(spacing: 10) {
                Labeled(label: "Phase") {
                    Picker("Phase", selection: Binding(get: { d.phase }, set: { p in d.phase = p; d.ride = DORLogic.nextRide(store, tid: d.tid, phase: p, date: d.date, excluding: d.id) })) {
                        ForEach(Phase.all, id: \.self) { Text($0).tag($0) }
                    }.pickerStyle(.menu).tint(pal.tx).frame(maxWidth: .infinity, alignment: .leading).fieldBox()
                }
                Labeled(label: "DOR / ride #") {
                    TextField("#", value: $d.ride, format: .number).keyboardType(.numberPad).fieldBox()
                }.frame(width: 120)
            }
            HStack(spacing: 10) {
                Labeled(label: "Shift hours") { TextField("0600–1600", text: $d.shiftHours).fieldBox() }
                Labeled(label: "Total hrs worked") { TextField("", text: $d.totalHours).keyboardType(.decimalPad).fieldBox() }.frame(width: 140)
            }
            HStack(spacing: 10) {
                Labeled(label: "FTO") { TextField("", text: $d.fto).fieldBox() }
                Labeled(label: "Non-FTO (if any)") { TextField("", text: $d.nonFto).fieldBox() }
            }
            Labeled(label: "Assignment or reason for no evaluation") { TextField("e.g. Day patrol, Car 12", text: $d.assignment).fieldBox() }
            Toggle("Attachments", isOn: $d.attach).tint(pal.hi)
            Toggle(isOn: Binding(get: { d.ungraded }, set: { on in
                d.ungraded = on
                if on && d.assignment.isEmpty { d.assignment = d.nonFto.isEmpty ? Rating.unratedReason : "Not graded – ride with non-FTO \(d.nonFto)" }
                if !on && d.assignment.hasPrefix("Not graded") { d.assignment = "" }
            })) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Not graded").fontWeight(.semibold)
                    Text("Narrative only: no ratings, NRT or most/least. For a ride with a non-FTO or any shift that isn't evaluated.").font(.system(size: 12.5)).foregroundStyle(pal.mu)
                }
            }.tint(pal.hi)
        }
        .disabled(locked)
        .foregroundStyle(pal.tx)
    }
}

struct AreaCards: View {
    @EnvironmentObject var store: Store
    @Environment(\.pal) var pal
    @Binding var d: DOR
    let locked: Bool
    var body: some View {
        let failing = DORLogic.failing(d)
        VStack(spacing: 12) {
            Card(title: "Most acceptable area", badge: AnyView(Tag(text: "mandatory"))) {
                catMenu(Binding(get: { d.most.cat }, set: { d.most.cat = $0; d.manual.most = true }))
                NoteEditor(text: Binding(get: { d.most.text }, set: { d.most.text = $0; d.manual.most = true }), placeholder: "A specific incident that demonstrates today's performance in this area", minHeight: 80, disabled: locked)
            }
            Card(title: "Least acceptable area", badge: AnyView(failing ? Tag(text: "mandatory – failing grade", style: .danger) : Tag(text: "leave blank – all passing", style: .quiet))) {
                catMenu(Binding(get: { d.least.cat }, set: { d.least.cat = $0; d.manual.least = true }))
                NoteEditor(text: Binding(get: { d.least.text }, set: { d.least.text = $0; d.manual.least = true }), placeholder: failing ? "A specific incident that demonstrates the failing performance" : "Only needed on shifts with a failing grade", minHeight: 80, disabled: locked)
            }
        }.disabled(locked)
    }
    private func catMenu(_ b: Binding<Int?>) -> some View {
        Menu {
            Button("— none —") { b.wrappedValue = nil }
            ForEach(Ref.segs(for: store.trainee(d.tid))) { s in Button("\(s.n). \(s.name)") { b.wrappedValue = s.n } }
        } label: {
            HStack { Text(b.wrappedValue.map { "\($0). \(Ref.name($0))" } ?? "— category # —").foregroundStyle(b.wrappedValue == nil ? pal.dim : pal.tx); Spacer(); Image(systemName: "chevron.up.chevron.down").foregroundStyle(pal.mu) }.fieldBox()
        }
    }
}

struct NarrativeCard: View {
    @Environment(\.pal) var pal
    @Binding var d: DOR
    let locked: Bool
    var body: some View {
        Card(title: "Additional narrative / comments") {
            Text(d.ungraded ? "The main write-up for this ungraded shift. Each line goes in the form's narrative table." : "Per-SEG narratives come from each rating. Use this for general notes; it goes in the form's narrative table without a category #.")
                .font(.system(size: 12.5)).foregroundStyle(pal.mu)
            NoteEditor(text: Binding(get: { d.narrative }, set: { d.narrative = $0; d.manual.narrative = true }), minHeight: 110, disabled: locked)
            Text(Ref.tips).font(.system(size: 10.5)).foregroundStyle(pal.dim).multilineTextAlignment(.center).frame(maxWidth: .infinity)
            Labeled(label: "Trainee comments") { NoteEditor(text: $d.traineeComments, placeholder: "Hand the phone over at review, or type what they say", minHeight: 70, disabled: locked) }
        }
    }
}

struct ShiftDataCard: View {
    @Environment(\.pal) var pal
    @Binding var d: DOR
    let locked: Bool
    var body: some View {
        Card(title: "Shift data") {
            HStack(spacing: 8) {
                Labeled(label: "Calls") { TextField("0", text: $d.calls).keyboardType(.numberPad).fieldBox() }
                Labeled(label: "MV stops") { TextField("0", text: $d.mv).keyboardType(.numberPad).fieldBox() }
                Labeled(label: "Arrests") { TextField("0", text: $d.arrests).keyboardType(.numberPad).fieldBox() }
                Labeled(label: "Viols") { TextField("0", text: $d.viols).keyboardType(.numberPad).fieldBox() }
            }
            Toggle("Radio history attached", isOn: $d.radio).tint(pal.hi)
        }
        .disabled(locked).foregroundStyle(pal.tx)
    }
}

// MARK: - Rating sheet

struct RatingSheet: View {
    @EnvironmentObject var store: Store
    @Environment(\.pal) var pal
    @Environment(\.dismiss) var dismiss
    let dorID: String
    let startN: Int
    let openRTP: (String) -> Void
    @State private var n: Int = 0
    @State private var score = ""

    private var d: Binding<DOR> { store.dorBinding(dorID) }

    var body: some View {
        let dor = d.wrappedValue
        let s = Ref.byN[n] ?? Ref.segs[0]
        let locked = dor.isFinal
        let list = Ref.segs(for: store.trainee(dor.tid)).map(\.n)
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text(s.d).font(.system(size: 13)).foregroundStyle(pal.mu)
                    if dor.ungraded {
                        InfoBox(title: "Not graded", lines: ["Narrative and REM only."], danger: false)
                    } else {
                        ratingGrid(dor, locked: locked)
                    }
                    remBox(dor, locked: locked)
                    if !dor.ungraded { nrtBox(dor, locked: locked) }
                    if !locked {
                        Button("＋ Remedial Training Worksheet for #\(n)") { makeRTP() }.buttonStyle(SecondaryButtonStyle())
                    }
                    if s.test && !dor.ungraded { testHelper(s) }
                    DisclosureGroup("SEG anchors (1 · 4 · 7)") { Anchors(seg: s) }.tint(pal.mu).foregroundStyle(pal.mu)
                    if !s.list.isEmpty && !locked { violations(s) }
                    Labeled(label: "Narrative") {
                        NoteEditor(text: Binding(get: { d.wrappedValue.comment(n) }, set: { v in
                            d.wrappedValue.comments[String(n)] = v
                            d.wrappedValue.manual.comments[String(n)] = !v.trimmingCharacters(in: .whitespaces).isEmpty
                        }), placeholder: "Set the stage · use quotes · include remedial · use SEG language · don't predict", minHeight: 120, disabled: locked)
                    }
                    let notes = store.notes(for: dor.tid).filter { $0.date == dor.date && $0.cat == n }
                    if !notes.isEmpty {
                        Text("YOUR NOTES FOR THIS SEG THIS SHIFT").font(.system(size: 12, weight: .bold)).foregroundStyle(pal.hi2).padding(.top, 4)
                        ForEach(notes) { x in Text(DORLogic.noteLine(x)).font(.system(size: 13.5)).foregroundStyle(pal.tx) }
                    }
                    HStack(spacing: 10) {
                        Button("‹ Prev") { if let i = list.firstIndex(of: n), i > 0 { n = list[i - 1] } }.buttonStyle(SecondaryButtonStyle())
                        Button("Done") { dismiss() }.buttonStyle(PrimaryButtonStyle())
                        Button("Next ›") { if let i = list.firstIndex(of: n), i < list.count - 1 { n = list[i + 1] } }.buttonStyle(SecondaryButtonStyle())
                    }.padding(.top, 6)
                }.padding(16)
            }
            .background(pal.p1.ignoresSafeArea())
            .navigationTitle("\(s.n). \(s.name)").navigationBarTitleDisplayMode(.inline)
            .scrollDismissesKeyboard(.interactively)
        }
        .onAppear { if n == 0 { n = startN } }
        .presentationDetents([.large])
    }

    private func ratingGrid(_ dor: DOR, locked: Bool) -> some View {
        let r = dor.rating(n)
        let prev = DORLogic.previous(store, tid: dor.tid, before: dor.date)?.rating(n)
        return VStack(spacing: 6) {
            HStack(spacing: 6) {
                ForEach(Rating.numbers, id: \.self) { x in
                    let c = pal.ratingColors(x)
                    Button { setRating(x) } label: {
                        Text(x).font(Fonts.mono(18, .bold)).frame(maxWidth: .infinity, minHeight: 48)
                            .foregroundStyle(c.fg).background(c.bg, in: RoundedRectangle(cornerRadius: 10))
                            .overlay(RoundedRectangle(cornerRadius: 10).stroke(r == x ? pal.hi : pal.line, lineWidth: r == x ? 2.5 : 1))
                    }.buttonStyle(.plain).disabled(locked)
                }
            }
            Button { setRating("NO") } label: {
                Text("NO · Not observed").font(.system(size: 14, weight: .bold)).frame(maxWidth: .infinity, minHeight: 42).foregroundStyle(pal.tx)
                    .background(pal.p2, in: RoundedRectangle(cornerRadius: 10))
                    .overlay(RoundedRectangle(cornerRadius: 10).stroke(r == "NO" ? pal.hi : pal.line, lineWidth: r == "NO" ? 2.5 : 1))
            }.buttonStyle(.plain).disabled(locked)
            if let p = prev, Rating.isNum(p) { Text("Previous DOR: \(p)").font(Fonts.mono(12, .regular)).foregroundStyle(pal.dim).frame(maxWidth: .infinity, alignment: .trailing) }
        }
    }

    private func setRating(_ x: String) {
        d.wrappedValue.ratings[String(n)] = x
        d.wrappedValue.manual.ratings[String(n)] = true
        d.wrappedValue.aiCats.removeAll { $0 == n }
        UISelectionFeedbackGenerator().selectionChanged()
    }

    private func remBox(_ dor: DOR, locked: Bool) -> some View {
        HStack {
            Toggle(isOn: Binding(get: { d.wrappedValue.isRem(n) }, set: { on in d.wrappedValue.rem[String(n)] = on; if !on { d.wrappedValue.time[String(n)] = "" } })) {
                Text("REM · remedial training given").font(.system(size: 14, weight: .semibold))
            }.tint(pal.hi)
            TextField("min", text: Binding(get: { d.wrappedValue.minutes(n) }, set: { v in d.wrappedValue.time[String(n)] = v; if !v.isEmpty { d.wrappedValue.rem[String(n)] = true } }))
                .keyboardType(.numberPad).multilineTextAlignment(.center).frame(width: 70).fieldBox()
        }
        .padding(10).background(pal.p2, in: RoundedRectangle(cornerRadius: 10))
        .disabled(locked).foregroundStyle(pal.tx)
    }

    private func nrtBox(_ dor: DOR, locked: Bool) -> some View {
        let done = DORLogic.rtpCompleted(store, tid: dor.tid, cat: n, on: dor.date)
        let all = DORLogic.rtpCount(store, tid: dor.tid, cat: n)
        return Toggle(isOn: Binding(get: { d.wrappedValue.isNRT(n) }, set: { d.wrappedValue.nrt[String(n)] = $0 })) {
            VStack(alignment: .leading, spacing: 2) {
                Text("NRT · not responding to training").font(.system(size: 14, weight: .semibold))
                Text("\(done) of 3 completed RTPs in this SEG\(all > done ? " (\(all - done) open)" : "")").font(.system(size: 12.5)).foregroundStyle(done >= 3 ? pal.mu : pal.neg)
            }
        }
        .tint(pal.hi).padding(10).background(pal.p2, in: RoundedRectangle(cornerRadius: 10))
        .disabled(locked).foregroundStyle(pal.tx)
    }

    private func testHelper(_ s: SEG) -> some View {
        HStack {
            TextField("Test score %", text: $score).keyboardType(.decimalPad).frame(width: 130).fieldBox()
            Button("Apply") {
                guard let v = Double(score) else { return }
                let sug = v < 50 ? "1" : v < 60 ? "2" : v < 70 ? "3" : v < 80 ? "4" : v < 86 ? "5" : v <= 90 ? "6" : "7"
                setRating(sug)
                let cur = d.wrappedValue.comment(n)
                d.wrappedValue.comments[String(n)] = (cur.isEmpty ? "" : cur + "\n") + "Scored \(score)% on \(s.name.replacingOccurrences(of: ": Testing", with: "")) testing."
            }.buttonStyle(SecondaryButtonStyle()).frame(width: 90)
            Text("<70% unacceptable · 70–90% acceptable · >90% superior").font(.system(size: 11.5)).foregroundStyle(pal.mu)
        }
    }

    private func violations(_ s: SEG) -> some View {
        DisclosureGroup("Officer-safety violations observed (tap to add)") {
            VStack(alignment: .leading, spacing: 6) {
                ForEach(Array(s.list.enumerated()), id: \.offset) { i, v in
                    Button {
                        let letter = String(UnicodeScalar(97 + i)!)
                        let cur = d.wrappedValue.comment(n)
                        d.wrappedValue.comments[String(n)] = (cur.isEmpty ? "" : cur + "\n") + "Unsafe practice (\(letter)): \(v)."
                        d.wrappedValue.manual.comments[String(n)] = true
                    } label: {
                        Text("\(String(UnicodeScalar(97 + i)!)). \(v)").font(.system(size: 13)).foregroundStyle(pal.tx).multilineTextAlignment(.leading)
                    }.buttonStyle(.plain)
                }
            }.padding(.top, 6)
        }.tint(pal.mu).foregroundStyle(pal.mu)
    }

    private func makeRTP() {
        let dor = d.wrappedValue
        var r = RTP(); r.tid = dor.tid; r.cat = n; r.date = dor.date; r.phase = dor.phase
        r.week = String(Int(ceil(Double(max(dor.ride, 1)) / 4.0))); r.deficiency = dor.comment(n)
        store.data.rtps[r.id] = r
        dismiss()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { openRTP(r.id) }
    }
}

struct Anchors: View {
    @Environment(\.pal) var pal
    let seg: SEG
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            row("1", seg.a1, pal.neg); row("4", seg.a4, pal.dim); row("7", seg.a7, pal.pos)
        }.padding(.top, 6)
    }
    private func row(_ n: String, _ t: String, _ c: Color) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Text(n).font(Fonts.mono(13, .bold)).foregroundStyle(pal.tx)
            Text(t).font(.system(size: 13)).foregroundStyle(pal.tx2)
        }
        .padding(8).frame(maxWidth: .infinity, alignment: .leading)
        .background(pal.p2, in: RoundedRectangle(cornerRadius: 8))
        .overlay(alignment: .leading) { Rectangle().fill(c).frame(width: 3) }
    }
}

// MARK: - Export

struct ExportSheet: View {
    @EnvironmentObject var store: Store
    @Environment(\.pal) var pal
    @Environment(\.dismiss) var dismiss
    let dorID: String
    @State private var docx: URL? = nil
    @State private var error: String? = nil
    @State private var copied = false

    var body: some View {
        let dor = store.data.dors[dorID] ?? DOR()
        let issues = DORLogic.validate(store, dor)
        let text = DORLogic.text(store, dor)
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if !issues.errors.isEmpty { InfoBox(title: "\(issues.errors.count) required item\(issues.errors.count > 1 ? "s" : "") still open", lines: ["You can export a draft, but fix them before signing."]) }
                    if let url = docx {
                        ShareLink(item: url) { Label("FTEP-1 Word form (.docx)", systemImage: "doc.richtext") }.buttonStyle(PrimaryButtonStyle())
                    } else if let e = error {
                        Text(e).foregroundStyle(pal.neg)
                    } else {
                        ProgressView("Filling your FTEP-1 form…").tint(pal.tx).foregroundStyle(pal.mu)
                    }
                    Text("Your FTEP-1 DOR – Level II and Level III fillable form with every box filled in. Share it to Mail, Files or Word; for a PDF, open it in Word or Files and use Share → Print → Save as PDF.")
                        .font(.system(size: 12.5)).foregroundStyle(pal.mu)
                    HStack(spacing: 10) {
                        Button(copied ? "Copied" : "Copy text") { UIPasteboard.general.string = text; copied = true }.buttonStyle(SecondaryButtonStyle())
                        ShareLink(item: text) { Text("Share text") }.buttonStyle(SecondaryButtonStyle())
                    }
                    DisclosureGroup("Preview text") {
                        Text(text).font(Fonts.mono(11, .regular)).foregroundStyle(Color(hex: "111111")).padding(10)
                            .frame(maxWidth: .infinity, alignment: .leading).background(Color(hex: "FBFAF6"), in: RoundedRectangle(cornerRadius: 8))
                    }.tint(pal.mu).foregroundStyle(pal.mu)
                }.padding(16)
            }
            .background(pal.p1.ignoresSafeArea())
            .navigationTitle("Export DOR # \(dor.ride)").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
        }
        .task {
            do { docx = try FormFiller.makeDocx(store, dor) } catch { self.error = "Couldn't build the Word file: \(error.localizedDescription)" }
        }
    }
}
