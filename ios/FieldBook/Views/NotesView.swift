import SwiftUI

struct NoteDraft: Codable, Equatable {
    var text = ""
    var tone = "0"
    var cat: Int? = nil
    var call = ""
    var caseNo = ""
    var date = Date()
    var time = ""
    var rem = ""
}

struct NotesView: View {
    @EnvironmentObject var store: Store
    @Environment(\.pal) var pal
    @Binding var tab: Int
    @State private var draft = NoteDraft()
    @State private var showDetails = false
    @State private var pickCat = false
    @State private var filterCat: Int? = nil
    @State private var pickFilter = false
    @State private var todayOnly = false
    @State private var editing: Note? = nil
    @State private var openDOR: String? = nil
    @State private var toast: String? = nil
    @FocusState private var focused: Bool

    var body: some View {
        Group {
            if let t = store.trainee { content(t) } else { Onboarding() }
        }
        .screen(pal, store: store)
        .sheet(isPresented: $pickCat) { SEGPicker(allowNone: true) { draft.cat = $0 } }
        .sheet(isPresented: $pickFilter) { SEGPicker(allowNone: false) { filterCat = $0 } }
        .sheet(item: $editing) { n in NoteEditSheet(note: n) }
        .navigationDestination(item: $openDOR) { id in DOREditorView(dorID: id) }
        .overlay(alignment: .bottom) { ToastView(text: $toast) }
    }

    private func content(_ t: Trainee) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                composer(t)
                filters
                noteList(t)
            }
            .padding(14)
        }
        .scrollDismissesKeyboard(.interactively)
    }

    private func recentCats(_ t: Trainee) -> [Int] {
        var count: [Int: Int] = [:]
        for n in store.notes(for: t.id).prefix(80) { if let c = n.cat { count[c, default: 0] += 1 } }
        var top = count.sorted { $0.value > $1.value }.map(\.key)
        for n in [4, 11, 22, 19, 12, 37, 5, 9, 35] where !top.contains(n) { top.append(n) }
        return Array(top.prefix(9)).filter { c in Ref.segs(for: t).contains { $0.n == c } }
    }

    private func composer(_ t: Trainee) -> some View {
        Card {
            NoteEditor(text: $draft.text, placeholder: "What happened? e.g. 'Stood in front of driver's door on MV stop – corrected on scene'", minHeight: 92)
                .focused($focused)
            ToneSelector(tone: $draft.tone)
            Labeled(label: "SEG category") {
                Button { pickCat = true } label: {
                    HStack {
                        if let c = draft.cat { Text("\(c)").font(Fonts.mono(13)).foregroundStyle(pal.hi2); Text(Ref.name(c)).foregroundStyle(pal.tx) }
                        else { Text("Tap to tag a SEG (optional)").foregroundStyle(pal.dim) }
                        Spacer(); Image(systemName: "chevron.down").foregroundStyle(pal.mu)
                    }.fieldBox()
                }.buttonStyle(.plain)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(recentCats(t), id: \.self) { c in
                        Chip(text: "\(c) · \(Ref.name(c))", on: draft.cat == c) { draft.cat = draft.cat == c ? nil : c }
                    }
                }
            }
            HStack {
                VStack(alignment: .leading, spacing: 1) {
                    Text("Remedial training given").font(.system(size: 14, weight: .semibold)).foregroundStyle(pal.tx)
                    Text("Marks REM + TIME on the DOR").font(.system(size: 12.5)).foregroundStyle(pal.mu)
                }
                Spacer()
                TextField("min", text: $draft.rem).keyboardType(.numberPad).multilineTextAlignment(.center).frame(width: 72).fieldBox()
            }
            .padding(10).background(pal.p2, in: RoundedRectangle(cornerRadius: 10))
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(draft.rem.isEmpty ? pal.line : pal.hi, lineWidth: 1))
            DisclosureGroup(isExpanded: $showDetails) {
                VStack(alignment: .leading, spacing: 8) {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) { ForEach(Ref.calls, id: \.self) { c in Chip(text: c, on: draft.call == c) { draft.call = draft.call == c ? "" : c } } }
                    }
                    HStack {
                        TextField("Call type", text: $draft.call).fieldBox()
                        TextField("Case / incident #", text: $draft.caseNo).fieldBox()
                    }
                    HStack {
                        DatePicker("Date", selection: $draft.date, displayedComponents: .date).labelsHidden()
                        Spacer()
                        TextField("Time (optional)", text: $draft.time).keyboardType(.numberPad).font(Fonts.mono(15)).frame(width: 150).fieldBox()
                    }
                }.padding(.top, 6)
            } label: {
                Text("Call, case #, date & time").font(.system(size: 14, weight: .semibold)).foregroundStyle(pal.mu)
            }
            .tint(pal.mu)
            Button("Log note") { save(t) }.buttonStyle(PrimaryButtonStyle())
        }
    }

    private var filters: some View {
        HStack {
            Picker("", selection: $todayOnly) { Text("All").tag(false); Text("Today").tag(true) }
                .pickerStyle(.segmented).frame(width: 150)
            Spacer()
            if let c = filterCat {
                Chip(text: "\(c) · \(Ref.name(c)) ✕", on: true) { filterCat = nil }
            } else {
                Chip(text: "Filter by SEG ▾") { pickFilter = true }
            }
        }
    }

    private func noteList(_ t: Trainee) -> some View {
        var list = store.notes(for: t.id)
        if let c = filterCat { list = list.filter { $0.cat == c } }
        if todayOnly { list = list.filter { $0.date == DateUtil.today() } }
        let days = Dictionary(grouping: list, by: \.date)
        let keys = days.keys.sorted(by: >)
        return VStack(alignment: .leading, spacing: 8) {
            if keys.isEmpty {
                Text("No notes yet. Log what you see on each call and they'll go onto the DOR automatically.")
                    .foregroundStyle(pal.mu).frame(maxWidth: .infinity).multilineTextAlignment(.center).padding(.vertical, 30)
            }
            ForEach(keys, id: \.self) { day in
                let dor = store.dors(for: t.id).first { $0.date == day }
                HStack {
                    Text("\(DateUtil.pretty(day)) · \(days[day]!.count)".uppercased()).font(.system(size: 12.5, weight: .bold)).kerning(0.6).foregroundStyle(pal.mu)
                    Spacer()
                    if let dor {
                        NavigationLink(value: Route.dor(dor.id)) { Text("DOR ✓").font(.system(size: 13, weight: .semibold)).foregroundStyle(pal.mu) }
                    } else {
                        Button { openDOR = DORLogic.build(store, trainee: t, date: day) } label: {
                            Text("Build DOR →").font(.system(size: 13, weight: .bold)).foregroundStyle(pal.hi2)
                        }
                    }
                }.padding(.top, 10)
                ForEach(days[day]!) { n in NoteRow(note: n).onTapGesture { editing = n } }
            }
        }
    }

    private func save(_ t: Trainee) {
        let txt = draft.text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !txt.isEmpty else { toast = "Type or dictate a note first"; return }
        var n = Note()
        n.tid = t.id; n.date = DateUtil.string(draft.date); n.time = draft.time.trimmingCharacters(in: .whitespaces)
        n.cat = draft.cat; n.tone = draft.tone; n.text = txt; n.call = draft.call.trimmingCharacters(in: .whitespaces)
        n.caseNo = draft.caseNo.trimmingCharacters(in: .whitespaces); n.rem = draft.rem.trimmingCharacters(in: .whitespaces)
        store.data.notes[n.id] = n
        let keepCall = draft.call, keepCase = draft.caseNo
        draft = NoteDraft(); draft.call = keepCall; draft.caseNo = keepCase
        focused = false
        toast = "Logged" + (n.cat.map { " to #\($0)" } ?? "")
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
    }
}

struct ToneSelector: View {
    @Environment(\.pal) var pal
    @Binding var tone: String
    var body: some View {
        HStack(spacing: 3) {
            seg("＋ Did well", "+", pal.pos)
            seg("• Observed", "0", pal.tx)
            seg("－ Needs work", "-", pal.neg)
        }
        .padding(3).background(pal.p2, in: RoundedRectangle(cornerRadius: 11))
        .overlay(RoundedRectangle(cornerRadius: 11).stroke(pal.line, lineWidth: 1))
    }
    private func seg(_ label: String, _ v: String, _ c: Color) -> some View {
        Button { tone = v } label: {
            Text(label).font(.system(size: 13.5, weight: .semibold)).frame(maxWidth: .infinity).padding(.vertical, 9)
                .foregroundStyle(tone == v ? c : pal.mu)
                .background(tone == v ? (v == "0" ? pal.p3 : c.opacity(0.16)) : .clear, in: RoundedRectangle(cornerRadius: 8))
        }.buttonStyle(.plain)
    }
}

struct NoteRow: View {
    @Environment(\.pal) var pal
    let note: Note
    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            RoundedRectangle(cornerRadius: 2).fill(note.tone == "+" ? pal.pos : note.tone == "-" ? pal.neg : pal.dim).frame(width: 4)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 8) {
                    if !note.time.isEmpty { Text(note.time).font(Fonts.mono(11.5)) }
                    if let c = note.cat { Text("\(c) · \(Ref.name(c))").foregroundStyle(pal.hi2).fontWeight(.semibold) } else { Text("untagged") }
                    if !note.call.isEmpty { Text(note.call) }
                    if !note.caseNo.isEmpty { Text("#" + note.caseNo).font(Fonts.mono(11.5)) }
                    if !note.rem.isEmpty { Tag(text: "REM \(note.rem)m", style: .quiet) }
                }.font(.system(size: 12)).foregroundStyle(pal.mu).lineLimit(1)
                Text(note.text).font(.system(size: 15)).foregroundStyle(pal.tx).frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(12)
        .background(pal.p1, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(pal.line, lineWidth: 1))
        .contentShape(Rectangle())
    }
}

struct NoteEditSheet: View {
    @EnvironmentObject var store: Store
    @Environment(\.pal) var pal
    @Environment(\.dismiss) var dismiss
    @State var note: Note
    @State private var pickCat = false
    @State private var confirmDelete = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    NoteEditor(text: $note.text, minHeight: 110)
                    ToneSelector(tone: $note.tone)
                    Labeled(label: "SEG") {
                        Button { pickCat = true } label: {
                            HStack { Text(note.cat.map { "\($0) · \(Ref.name($0))" } ?? "No category").foregroundStyle(note.cat == nil ? pal.dim : pal.tx); Spacer(); Image(systemName: "chevron.down") }.fieldBox()
                        }.buttonStyle(.plain)
                    }
                    HStack { TextField("Call type", text: $note.call).fieldBox(); TextField("Case #", text: $note.caseNo).fieldBox() }
                    HStack {
                        DatePicker("", selection: Binding(get: { DateUtil.date(note.date) ?? Date() }, set: { note.date = DateUtil.string($0) }), displayedComponents: .date).labelsHidden()
                        TextField("Time", text: $note.time).keyboardType(.numberPad).fieldBox()
                        TextField("REM min", text: $note.rem).keyboardType(.numberPad).fieldBox()
                    }
                    Button("Delete note", role: .destructive) { confirmDelete = true }.buttonStyle(SecondaryButtonStyle(danger: true))
                }.padding(16)
            }
            .background(pal.bg.ignoresSafeArea())
            .navigationTitle("Edit note").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Save") { note.text = note.text.trimmingCharacters(in: .whitespacesAndNewlines); store.data.notes[note.id] = note; dismiss() }.fontWeight(.bold) }
            }
            .sheet(isPresented: $pickCat) { SEGPicker(allowNone: true) { note.cat = $0 } }
            .confirmationDialog("Delete this note?", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("Delete", role: .destructive) { store.data.notes[note.id] = nil; dismiss() }
            }
        }
        .presentationDetents([.large])
    }
}

struct SEGPicker: View {
    @EnvironmentObject var store: Store
    @Environment(\.pal) var pal
    @Environment(\.dismiss) var dismiss
    var allowNone = false
    let pick: (Int?) -> Void
    @State private var q = ""

    var body: some View {
        NavigationStack {
            List {
                if allowNone { Button("No category") { pick(nil); dismiss() }.foregroundStyle(pal.mu).listRowBackground(pal.p1) }
                ForEach(Ref.groups, id: \.self) { g in
                    let ss = Ref.segs(for: store.trainee).filter { $0.g == g && (q.isEmpty || "\($0.n) \($0.name) \($0.d)".localizedCaseInsensitiveContains(q)) }
                    if !ss.isEmpty {
                        Section {
                            ForEach(ss) { s in
                                Button { pick(s.n); dismiss() } label: {
                                    HStack(alignment: .top, spacing: 10) {
                                        Text("\(s.n)").font(Fonts.mono(12)).foregroundStyle(pal.dim).frame(width: 22, alignment: .leading)
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text(s.name).foregroundStyle(pal.tx)
                                            Text(s.d).font(.system(size: 12)).foregroundStyle(pal.mu).lineLimit(2)
                                        }
                                    }
                                }.listRowBackground(pal.p1)
                            }
                        } header: { Text(g).foregroundStyle(pal.hi2) }
                    }
                }
            }
            .scrollContentBackground(.hidden).background(pal.bg.ignoresSafeArea())
            .searchable(text: $q, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search (radio, safety, report)")
            .navigationTitle("SEG category").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
        }
    }
}

struct ToastView: View {
    @Environment(\.pal) var pal
    @Binding var text: String?
    var body: some View {
        Group {
            if let t = text {
                Text(t).font(.system(size: 15, weight: .medium)).foregroundStyle(.white)
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .background(Color(hex: "17181B").opacity(0.95), in: Capsule())
                    .padding(.bottom, 14)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .task(id: t) { try? await Task.sleep(nanoseconds: 2_200_000_000); withAnimation { text = nil } }
            }
        }.animation(.easeOut(duration: 0.2), value: text)
    }
}
