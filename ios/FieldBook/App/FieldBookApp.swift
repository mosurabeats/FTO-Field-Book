import SwiftUI

@main
struct FieldBookApp: App {
    @StateObject private var store = Store()
    @StateObject private var lock = LockManager()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(store)
                .environmentObject(lock)
                .onChange(of: scenePhase) { _, phase in
                    switch phase {
                    case .background: store.saveNow(); lock.appWentToBackground()
                    case .active: if lock.locked { lock.unlock() }
                    default: break
                    }
                }
        }
    }
}

enum Route: Hashable { case dor(String), rtp(String) }

struct RootView: View {
    @EnvironmentObject var store: Store
    @EnvironmentObject var lock: LockManager
    @AppStorage("theme") private var theme = "k11"
    @Environment(\.colorScheme) private var systemScheme
    @State private var tab = 0

    var body: some View {
        let pal = Palette.named(theme, systemDark: systemScheme == .dark)
        ZStack {
            TabView(selection: $tab) {
                NavigationStack { NotesView(tab: $tab).routes() }.tabItem { Label("Notes", systemImage: "square.and.pencil") }.tag(0)
                NavigationStack { DORListView().routes() }.tabItem { Label("DORs", systemImage: "doc.text") }.tag(1)
                NavigationStack { TasksView() }.tabItem { Label("Tasks", systemImage: "checklist") }.tag(2)
                NavigationStack { ProgressTab() }.tabItem { Label("Progress", systemImage: "chart.xyaxis.line") }.tag(3)
                NavigationStack { SettingsView() }.tabItem { Label("Trainee", systemImage: "person.crop.circle") }.tag(4)
            }
            .tint(pal.hi2)
            if lock.enabled && lock.locked { LockScreen().transition(.opacity) }
        }
        .environment(\.pal, pal)
        .preferredColorScheme(pal.dark ? .dark : .light)
    }
}

extension View {
    func routes() -> some View {
        navigationDestination(for: Route.self) { r in
            switch r {
            case .dor(let id): DOREditorView(dorID: id)
            case .rtp(let id): RTPEditorView(rtpID: id)
            }
        }
    }

    /// Standard screen chrome: themed background, black-deck nav bar with the trainee header.
    func screen(_ pal: Palette, store: Store) -> some View {
        self.scrollContentBackground(.hidden)
            .background(pal.bg.ignoresSafeArea())
            .toolbarBackground(pal.hdr, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbarBackground(pal.p1, for: .tabBar)
            .toolbarBackground(.visible, for: .tabBar)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .principal) { HeaderTitle() } }
    }
}

struct HeaderTitle: View {
    @EnvironmentObject var store: Store
    @Environment(\.pal) var pal
    var body: some View {
        HStack(spacing: 10) {
            AppLogo(size: 30)
            VStack(alignment: .leading, spacing: 0) {
                Text(store.trainee?.name ?? "FTO FieldBook").font(.system(size: 16, weight: .bold)).foregroundStyle(pal.hdrtx).lineLimit(1)
                Text(subtitle).font(.system(size: 11.5)).foregroundStyle(pal.hdrmu).lineLimit(1)
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity)
    }
    var subtitle: String {
        guard let t = store.trainee else { return "Field training · FTEP" }
        let n = store.dors(for: t.id).filter { $0.phase == t.phase }.count
        return "Level \(t.level) · \(t.phase) · \(n) ride\(n == 1 ? "" : "s") logged"
    }
}

struct LockScreen: View {
    @EnvironmentObject var lock: LockManager
    @Environment(\.pal) var pal
    var body: some View {
        ZStack {
            pal.bg.ignoresSafeArea()
            VStack(spacing: 22) {
                AppLogo(size: 96)
                Text("FieldBook is locked").font(.system(size: 20, weight: .bold)).foregroundStyle(pal.tx)
                Button { lock.unlock() } label: { Label("Unlock with \(lock.biometryName)", systemImage: "faceid") }
                    .buttonStyle(PrimaryButtonStyle()).frame(maxWidth: 280)
                if let e = lock.lastError { Text(e).font(.footnote).foregroundStyle(pal.mu) }
            }.padding()
        }
        .onAppear { lock.unlock() }
    }
}

/// Shown on tabs that need a trainee when none exists yet.
struct Onboarding: View {
    @Environment(\.pal) var pal
    @State private var showAdd = false
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Card(title: "Get started") {
                Text("Add your trainee. Log observations on calls, and each shift's DOR fills itself in from those notes, using the 40 VCJTC Level II/III SEGs. When you're done it exports as your FTEP-1 form.")
                    .foregroundStyle(pal.tx)
                Button("Add trainee") { showAdd = true }.buttonStyle(PrimaryButtonStyle())
            }
            Spacer()
        }
        .padding(14)
        .sheet(isPresented: $showAdd) { TraineeEditor(traineeID: nil) }
    }
}
