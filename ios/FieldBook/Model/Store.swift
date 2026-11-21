import Foundation
import SwiftUI

/// Holds every record and saves it to a JSON file in the app's private storage
/// (included in the phone's iCloud/computer backups). Same shape as the web app's backup.
final class Store: ObservableObject {
    @Published var data: AppData { didSet { scheduleSave() } }
    @Published var lastSaveError: String? = nil

    private let url: URL
    private var saveWork: DispatchWorkItem?

    init() {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        url = dir.appendingPathComponent("fieldbook.json")
        if let d = try? Data(contentsOf: url), let v = try? JSONDecoder().decode(AppData.self, from: d) {
            data = v
        } else {
            data = AppData()
        }
    }

    private func scheduleSave() {
        saveWork?.cancel()
        let w = DispatchWorkItem { [weak self] in self?.saveNow() }
        saveWork = w
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4, execute: w)
    }

    func saveNow() {
        do {
            let enc = JSONEncoder(); enc.outputFormatting = [.sortedKeys]
            let d = try enc.encode(data)
            try d.write(to: url, options: [.atomic, .completeFileProtectionUnlessOpen])
            lastSaveError = nil
        } catch { lastSaveError = error.localizedDescription }
    }

    // MARK: - Convenience

    var profile: Profile { data.profile }
    var trainee: Trainee? { data.profile.trainees.first { $0.id == data.profile.active } }
    func trainee(_ id: String) -> Trainee? { data.profile.trainees.first { $0.id == id } }

    func notes(for tid: String) -> [Note] {
        data.notes.values.filter { $0.tid == tid }.sorted { $0.date != $1.date ? $0.date > $1.date : $0.ts > $1.ts }
    }
    func dors(for tid: String) -> [DOR] {
        data.dors.values.filter { $0.tid == tid }.sorted { $0.date != $1.date ? $0.date < $1.date : $0.created < $1.created }
    }
    func rtps(for tid: String) -> [RTP] {
        data.rtps.values.filter { $0.tid == tid }.sorted { $0.date > $1.date }
    }
    func checks(for tid: String) -> Checks { data.checks[tid] ?? Checks(tid: tid) }

    func dorBinding(_ id: String) -> Binding<DOR> {
        Binding(get: { self.data.dors[id] ?? DOR() }, set: { self.data.dors[id] = $0 })
    }
    func rtpBinding(_ id: String) -> Binding<RTP> {
        Binding(get: { self.data.rtps[id] ?? RTP() }, set: { self.data.rtps[id] = $0 })
    }

    // MARK: - Backup

    func exportBackup() throws -> URL {
        let enc = JSONEncoder(); enc.outputFormatting = [.prettyPrinted, .sortedKeys]
        let d = try enc.encode(data)
        let out = FileManager.default.temporaryDirectory.appendingPathComponent("FieldBook_backup_\(DateUtil.today()).json")
        try d.write(to: out, options: .atomic)
        UserDefaults.standard.set(DateUtil.today(), forKey: "lastBackup")
        return out
    }

    /// Merges a backup (from this app or the web app) into the current records.
    func importBackup(_ d: Data) throws -> (notes: Int, dors: Int) {
        let incoming = try JSONDecoder().decode(AppData.self, from: d)
        guard !incoming.notes.isEmpty || !incoming.profile.trainees.isEmpty || !incoming.dors.isEmpty else {
            throw NSError(domain: "FieldBook", code: 1, userInfo: [NSLocalizedDescriptionKey: "That file isn't a FieldBook backup."])
        }
        var cur = data
        for t in incoming.profile.trainees where !cur.profile.trainees.contains(where: { $0.id == t.id }) { cur.profile.trainees.append(t) }
        if cur.profile.active == nil { cur.profile.active = incoming.profile.active ?? cur.profile.trainees.first?.id }
        if cur.profile.fto.name.isEmpty { cur.profile.fto = incoming.profile.fto }
        cur.notes.merge(incoming.notes) { _, new in new }
        cur.dors.merge(incoming.dors) { _, new in new }
        cur.rtps.merge(incoming.rtps) { _, new in new }
        cur.checks.merge(incoming.checks) { _, new in new }
        data = cur
        saveNow()
        return (incoming.notes.count, incoming.dors.count)
    }
}
