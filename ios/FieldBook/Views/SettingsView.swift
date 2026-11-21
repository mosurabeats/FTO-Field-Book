import SwiftUI
import UniformTypeIdentifiers

struct SettingsView: View {
    @EnvironmentObject var store: Store
    @EnvironmentObject var lock: LockManager
    @Environment(\.pal) var pal
    @AppStorage("theme") private var theme = "k11"
    @AppStorage("claudeModel") private var model = "claude-sonnet-5-5"
    @State private var editTrainee: TraineeTarget? = nil
    @State private var keyText = ""
    @State private var hasKey = ClaudeAPI.hasKey
    @State private var importing = false
    @State private var backupURL: URL? = nil
    @State private var toast: String? = nil
    @State private var segRef: SEG? = nil

    struct TraineeTarget: Identifiable { let id: String; let existing: String? }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                appearance
                trainees
                fto
                Card(title: "DOR defaults") {
                    Toggle(isOn: $store.data.profile.settings.carry) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Carry forward the last ratings")
                            Text("New DORs start from the previous graded shift's scores instead of NO").font(.system(size: 12.5)).foregroundStyle(pal.mu)
                        }
                    }.tint(pal.hi).foregroundStyle(pal.tx)
                }
                Card(title: "Security") {
                    Toggle(isOn: Binding(get: { lock.enabled }, set: { lock.setEnabled($0) })) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Lock with \(lock.biometryName)")
                            Text("Asks for \(lock.biometryName) or your passcode whenever FieldBook opens.").font(.system(size: 12.5)).foregroundStyle(pal.mu)
                        }
                    }.tint(pal.hi).foregroundStyle(pal.tx)
                }
                claude
                backup
                rules
                Card(title: "SEG reference") {
                    ForEach(Ref.groups, id: \.self) { g in
                        DisclosureGroup(g) {
                            ForEach(Ref.segs.filter { $0.g == g }) { s in
                                Button { segRef = s } label: {
                                    HStack { Text("\(s.n)").font(Fonts.mono(12, .regular)).foregroundStyle(pal.dim).frame(width: 22, alignment: .leading); Text(s.name + (s.l2 ? " (not Level III)" : "")).foregroundStyle(pal.tx); Spacer() }.padding(.vertical, 6)
                                }.buttonStyle(.plain)
                            }
                        }.tint(pal.mu).foregroundStyle(pal.tx)
                    }
                }
                Text("DORs are training records. Handle exports per department records policy. Case #s are enough; no victim or suspect names are needed here.\nFieldBook \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "") (\(Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? ""))")
                    .font(.system(size: 12)).foregroundStyle(pal.mu).multilineTextAlignment(.center).frame(maxWidth: .infinity)
            }.padding(14)
        }
        .scrollDismissesKeyboard(.interactively)
        .screen(pal, store: store)
        .sheet(item: $editTrainee) { tt in TraineeEditor(traineeID: tt.existing) }
        .sheet(item: $segRef) { s in
            NavigationStack {
                ScrollView { VStack(alignment: .leading, spacing: 10) { Text(s.d).foregroundStyle(pal.mu); Anchors(seg: s)
                    if !s.list.isEmpty { Text("Unacceptable practices").font(.headline).foregroundStyle(pal.tx); ForEach(Array(s.list.enumerated()), id: \.offset) { i, v in Text("\(String(UnicodeScalar(97 + i)!)). \(v)").font(.system(size: 13.5)).foregroundStyle(pal.tx) } }
                }.padding(16) }
                .background(pal.p1.ignoresSafeArea())
                .navigationTitle("\(s.n). \(s.name)").navigationBarTitleDisplayMode(.inline)
            }.presentationDetents([.medium, .large])
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.json, .plainText, .data]) { result in
            guard case .success(let url) = result else { return }
            let ok = url.startAccessingSecurityScopedResource(); defer { if ok { url.stopAccessingSecurityScopedResource() } }
            do {
                let d = try Data(contentsOf: url)
                let r = try store.importBackup(d)
                toast = "Imported \(r.notes) notes and \(r.dors) DORs"
            } catch { toast = error.localizedDescription }
        }
        .overlay(alignment: .bottom) { ToastView(text: $toast) }
    }

    private var appearance: some View {
        Card(title: "Appearance") {
            HStack(spacing: 8) {
                themeButton("k11", "Deck", ["1F2023", "2C2E32", "D8262E"])
                themeButton("k11s", "Silver", ["D6D7DA", "1F2023", "D8262E"])
                themeButton("auto", "Auto", ["D6D7DA", "1F2023"])
                themeButton("slate", "Slate", ["4E5C6A", "3C4753", "39B4F2"])
            }
            Text("Deck and Silver use the 11K colours. Auto uses Silver by day and Deck when your phone is in dark mode. Slate is the 26K look.").font(.system(size: 12.5)).foregroundStyle(pal.mu)
        }
    }

    private func themeButton(_ k: String, _ l: String, _ colors: [String]) -> some View {
        Button { theme = k } label: {
            VStack(spacing: 6) {
                HStack(spacing: 0) { ForEach(colors, id: \.self) { Color(hex: $0) } }.frame(height: 32).clipShape(RoundedRectangle(cornerRadius: 7))
                    .overlay(RoundedRectangle(cornerRadius: 7).stroke(pal.line, lineWidth: 1))
                Text(l).font(.system(size: 13, weight: .semibold)).foregroundStyle(pal.tx)
            }
            .padding(8).frame(maxWidth: .infinity)
            .background(theme == k ? pal.hi.opacity(0.12) : pal.p2, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(theme == k ? pal.hi : pal.line, lineWidth: 1.5))
        }.buttonStyle(.plain)
    }

    private var trainees: some View {
        Card(title: "Trainees") {
            if store.profile.trainees.isEmpty { Text("No trainees yet.").foregroundStyle(pal.mu) }
            ForEach(store.profile.trainees) { t in
                HStack(spacing: 10) {
                    Button { store.data.profile.active = t.id; toast = "Active trainee: \(t.name)" } label: {
                        HStack(spacing: 10) {
                            Image(systemName: store.profile.active == t.id ? "largecircle.fill.circle" : "circle").foregroundStyle(store.profile.active == t.id ? pal.hi : pal.mu)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(t.name).fontWeight(.semibold).foregroundStyle(pal.tx)
                                Text([t.badge.isEmpty ? nil : "#\(t.badge)", "Level \(t.level)", t.phase, t.start.isEmpty ? nil : "started \(DateUtil.us(t.start))"].compactMap { $0 }.joined(separator: " · "))
                                    .font(.system(size: 12.5)).foregroundStyle(pal.mu)
                            }
                            Spacer()
                        }
                    }.buttonStyle(.plain)
                    Button("Edit") { editTrainee = TraineeTarget(id: t.id, existing: t.id) }.font(.system(size: 13, weight: .semibold)).foregroundStyle(pal.mu)
                }.padding(.vertical, 4)
            }
            Button("Add trainee") { editTrainee = TraineeTarget(id: UUID().uuidString, existing: nil) }.buttonStyle(PrimaryButtonStyle())
        }
    }

    private var fto: some View {
        Card(title: "You (FTO)") {
            HStack(spacing: 8) {
                Labeled(label: "Name") { TextField("", text: $store.data.profile.fto.name).fieldBox() }
                Labeled(label: "Badge") { TextField("", text: $store.data.profile.fto.badge).fieldBox() }.frame(width: 90)
                Labeled(label: "Initials") { TextField("", text: $store.data.profile.fto.initials).textInputAutocapitalization(.characters).fieldBox() }.frame(width: 76)
            }
            Labeled(label: "Agency") { TextField("", text: $store.data.profile.agency).fieldBox() }
        }
    }

    private var claude: some View {
        Card(title: "Claude drafting") {
            Text("The ✦ buttons draft DOR narratives, remedial worksheets and phase evaluations from your notes using your own Anthropic API key (console: platform.claude.com → Settings → API keys). The key is stored in the iPhone Keychain, and notes are sent only when you tap a ✦ button. The trainee's name is never sent.")
                .font(.system(size: 12.5)).foregroundStyle(pal.mu)
            SecureField(hasKey ? "Key saved ✓ — paste a new one to replace" : "sk-ant-…", text: $keyText).textInputAutocapitalization(.never).autocorrectionDisabled().fieldBox()
            HStack(spacing: 10) {
                Button("Save key") {
                    let k = keyText.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard k.hasPrefix("sk-ant-") else { toast = "That doesn't look like an Anthropic API key"; return }
                    Keychain.set(ClaudeAPI.keyAccount, k); keyText = ""; hasKey = true; toast = "Key saved — ✦ drafting is on"
                }.buttonStyle(PrimaryButtonStyle())
                if hasKey { Button("Remove") { Keychain.set(ClaudeAPI.keyAccount, nil); hasKey = false; toast = "Key removed" }.buttonStyle(SecondaryButtonStyle(danger: true)).frame(width: 110) }
            }
            Picker("Model", selection: $model) {
                Text("Sonnet 5.5 (≈$1/month)").tag("claude-sonnet-5-5")
                Text("Opus 5.5 (best, ≈2×)").tag("claude-opus-5-5")
            }.pickerStyle(.menu).tint(pal.tx)
        }
    }

    private var backup: some View {
        Card(title: "Backup") {
            let lb = UserDefaults.standard.string(forKey: "lastBackup")
            Text("Records are stored inside the app and included in your iPhone's iCloud backup. Export a copy anytime; it also imports into the web version, and the web version's backups import here. Last export: \(lb.map(DateUtil.us) ?? "never").")
                .font(.system(size: 12.5)).foregroundStyle(pal.mu)
            HStack(spacing: 10) {
                if let url = backupURL {
                    ShareLink(item: url) { Text("Share backup") }.buttonStyle(PrimaryButtonStyle())
                } else {
                    Button("Export backup") { backupURL = try? store.exportBackup() }.buttonStyle(PrimaryButtonStyle())
                }
                Button("Import") { importing = true }.buttonStyle(SecondaryButtonStyle())
            }
        }
    }

    private var rules: some View {
        Card(title: "Rules this app enforces") {
            ForEach(["Narrative required for 1, 2, 6, 7, REM and NRT", "NRT only after 3 completed RTPs in that SEG; score should be 1 or 2",
                     "Most acceptable is always mandatory; least acceptable only when a failing grade (below 4) is given — otherwise left blank",
                     "DOR # counts rides within the phase: Phase I is 24 rides (rides 1–8 limbo), Phases II and III are 16",
                     "Not graded DORs are narrative only (non-FTO rides)", "Level III trainees: SEGs 33–34 (scope of practice) don't apply"], id: \.self) {
                Text("• " + $0).font(.system(size: 13.5)).foregroundStyle(pal.tx2)
            }
        }
    }
}

struct TraineeEditor: View {
    @EnvironmentObject var store: Store
    @Environment(\.pal) var pal
    @Environment(\.dismiss) var dismiss
    let traineeID: String?
    @State private var t = Trainee()
    @State private var confirmRemove = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Labeled(label: "Name") { TextField("Officer First Last", text: $t.name).fieldBox() }
                    HStack(spacing: 10) {
                        Labeled(label: "Badge #") { TextField("", text: $t.badge).fieldBox() }
                        Labeled(label: "Program start") { DatePicker("", selection: Binding(get: { DateUtil.date(t.start) ?? Date() }, set: { t.start = DateUtil.string($0) }), displayedComponents: .date).labelsHidden() }
                    }
                    Labeled(label: "Certification") {
                        Picker("", selection: $t.level) { Text("Level II").tag("II"); Text("Level III").tag("III") }.pickerStyle(.segmented)
                    }
                    Text("Level III hides SEGs 33–34 (scope of practice).").font(.system(size: 12.5)).foregroundStyle(pal.mu)
                    Labeled(label: "Current phase") {
                        Picker("", selection: $t.phase) { ForEach(Phase.all, id: \.self) { Text($0).tag($0) } }.pickerStyle(.segmented)
                    }
                    Text("Changing phase starts DOR numbering at ride 1 for the new phase.").font(.system(size: 12.5)).foregroundStyle(pal.mu)
                    Labeled(label: "Usual shift hours") { TextField("e.g. 0700–1700", text: $t.shift).fieldBox() }
                    if traineeID != nil {
                        Button("Remove trainee", role: .destructive) { confirmRemove = true }.buttonStyle(SecondaryButtonStyle(danger: true)).padding(.top, 10)
                    }
                }.padding(16)
            }
            .background(pal.p1.ignoresSafeArea())
            .navigationTitle(traineeID == nil ? "Add trainee" : "Edit trainee").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        t.name = t.name.trimmingCharacters(in: .whitespaces)
                        guard !t.name.isEmpty else { return }
                        if let i = store.data.profile.trainees.firstIndex(where: { $0.id == t.id }) { store.data.profile.trainees[i] = t }
                        else { store.data.profile.trainees.append(t); store.data.profile.active = t.id }
                        dismiss()
                    }.fontWeight(.bold).disabled(t.name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .confirmationDialog("Remove \(t.name)? Their records stay in your backup data but are hidden here.", isPresented: $confirmRemove, titleVisibility: .visible) {
                Button("Remove", role: .destructive) {
                    store.data.profile.trainees.removeAll { $0.id == t.id }
                    if store.data.profile.active == t.id { store.data.profile.active = store.data.profile.trainees.first?.id }
                    dismiss()
                }
            }
        }
        .onAppear { if let id = traineeID, let x = store.trainee(id) { t = x } }
    }
}
