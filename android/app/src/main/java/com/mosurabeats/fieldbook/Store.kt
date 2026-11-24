package com.mosurabeats.fieldbook

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File

/**
 * Holds every record and saves it to fieldbook.json in the app's private storage
 * (included in Android's own backup). Same shape as the web and iPhone backups.
 * Records are mutable objects; every change goes through [edit], which bumps [rev] so Compose redraws.
 */
class Store(private val ctx: Context) {
    private val file = File(ctx.filesDir, "fieldbook.json")
    private var _data: AppData = runCatching { AppData.from(JSONObject(file.readText())) }.getOrElse { AppData() }
    var rev by mutableIntStateOf(0)
        private set
    var lastSaveError by mutableStateOf<String?>(null)

    /** Reading this inside a composable subscribes it to every change. */
    val data: AppData get() { rev; return _data }

    private val handler = Handler(Looper.getMainLooper())
    private val saver = Runnable { saveNow() }

    fun edit(block: AppData.() -> Unit) {
        _data.block()
        rev++
        handler.removeCallbacks(saver)
        handler.postDelayed(saver, 400)
    }

    fun saveNow() {
        handler.removeCallbacks(saver)
        try {
            val tmp = File(ctx.filesDir, "fieldbook.json.tmp")
            tmp.writeText(_data.toJson().toString())
            if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
            lastSaveError = null
        } catch (e: Exception) { lastSaveError = e.message }
    }

    // ---------- Convenience ----------
    val profile get() = data.profile
    val trainee: Trainee? get() = data.profile.trainees.firstOrNull { it.id == data.profile.active }
    fun trainee(id: String) = data.profile.trainees.firstOrNull { it.id == id }

    fun notes(tid: String): List<Note> = data.notes.values.filter { it.tid == tid }
        .sortedWith(compareByDescending<Note> { it.date }.thenByDescending { it.ts })
    fun dors(tid: String): List<DOR> = data.dors.values.filter { it.tid == tid }
        .sortedWith(compareBy<DOR> { it.date }.thenBy { it.created })
    fun rtps(tid: String): List<RTP> = data.rtps.values.filter { it.tid == tid }.sortedByDescending { it.date }
    fun checks(tid: String): Checks = data.checks[tid] ?: Checks(tid)

    // ---------- Backup ----------
    fun exportBackup(): File {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val out = File(dir, "FieldBook_backup_${DateUtil.today()}.json")
        out.writeText(_data.toJson().toString(2))
        Prefs.lastBackup = DateUtil.today()
        return out
    }

    /** Merges a backup (from this app, the iPhone app or the web app) into the current records. */
    fun importBackup(text: String): Pair<Int, Int> {
        val incoming = try { AppData.from(JSONObject(text)) } catch (e: Exception) { throw IllegalArgumentException("That file isn't a FieldBook backup.") }
        if (incoming.notes.isEmpty() && incoming.profile.trainees.isEmpty() && incoming.dors.isEmpty())
            throw IllegalArgumentException("That file isn't a FieldBook backup.")
        edit {
            for (t in incoming.profile.trainees) if (profile.trainees.none { it.id == t.id }) profile.trainees.add(t)
            if (profile.active == null) profile.active = incoming.profile.active ?: profile.trainees.firstOrNull()?.id
            if (profile.fto.name.isEmpty()) profile.fto = incoming.profile.fto
            notes.putAll(incoming.notes); dors.putAll(incoming.dors); rtps.putAll(incoming.rtps); checks.putAll(incoming.checks)
        }
        saveNow()
        return incoming.notes.size to incoming.dors.size
    }
}

/** Small settings (theme, lock, model, last backup) that Compose can observe. */
object Prefs {
    private lateinit var sp: SharedPreferences
    private val _theme = mutableStateOf("k11")
    private val _lock = mutableStateOf(false)
    private val _model = mutableStateOf("claude-sonnet-5-5")
    private val _lastBackup = mutableStateOf<String?>(null)
    var theme: String get() = _theme.value; set(v) { _theme.value = v; save() }
    var lockEnabled: Boolean get() = _lock.value; set(v) { _lock.value = v; save() }
    var model: String get() = _model.value; set(v) { _model.value = v; save() }
    var lastBackup: String? get() = _lastBackup.value; set(v) { _lastBackup.value = v; save() }

    fun load(ctx: Context) {
        sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        _theme.value = sp.getString("theme", "k11") ?: "k11"
        _lock.value = sp.getBoolean("lockEnabled", false)
        _model.value = sp.getString("claudeModel", "claude-sonnet-5-5") ?: "claude-sonnet-5-5"
        _lastBackup.value = sp.getString("lastBackup", null)
    }

    private fun save() {
        if (!::sp.isInitialized) return
        sp.edit().putString("theme", theme).putBoolean("lockEnabled", lockEnabled)
            .putString("claudeModel", model).putString("lastBackup", lastBackup).apply()
    }
}
