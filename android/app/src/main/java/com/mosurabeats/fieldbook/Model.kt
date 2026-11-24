package com.mosurabeats.fieldbook

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

// ---------- Lenient JSON reading (the web app stored some numbers as strings and vice versa) ----------

fun JSONObject.s(k: String, def: String = ""): String {
    if (!has(k) || isNull(k)) return def
    return when (val v = get(k)) {
        is String -> v
        is Int, is Long -> v.toString()
        is Number -> { val d = v.toDouble(); if (d == Math.rint(d) && !d.isInfinite()) d.toLong().toString() else d.toString() }
        is Boolean -> if (v) "true" else "false"
        else -> def
    }
}

fun JSONObject.i(k: String): Int? {
    if (!has(k) || isNull(k)) return null
    return when (val v = get(k)) {
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull() ?: v.trim().toDoubleOrNull()?.toInt()
        else -> null
    }
}

fun JSONObject.l(k: String): Long {
    if (!has(k) || isNull(k)) return 0
    return when (val v = get(k)) {
        is Number -> v.toLong()
        is String -> v.toDoubleOrNull()?.toLong() ?: 0
        else -> 0
    }
}

fun JSONObject.ob(k: String): Boolean? {
    if (!has(k) || isNull(k)) return null
    return when (val v = get(k)) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is String -> v == "true" || v == "1"
        else -> null
    }
}

fun JSONObject.b(k: String) = ob(k) ?: false

fun JSONObject.obj(k: String): JSONObject? = optJSONObject(k)

fun JSONObject.strMap(k: String): MutableMap<String, String> {
    val o = optJSONObject(k) ?: return mutableMapOf()
    val m = mutableMapOf<String, String>()
    for (key in o.keys()) m[key] = o.s(key)
    return m
}

fun JSONObject.boolMap(k: String): MutableMap<String, Boolean> {
    val o = optJSONObject(k) ?: return mutableMapOf()
    val m = mutableMapOf<String, Boolean>()
    for (key in o.keys()) m[key] = o.b(key)
    return m
}

fun JSONObject.strList(k: String): MutableList<String> {
    val a = optJSONArray(k) ?: return mutableListOf()
    return (0 until a.length()).mapNotNull { a.opt(it)?.toString() }.toMutableList()
}

fun JSONObject.intList(k: String): MutableList<Int> {
    val a = optJSONArray(k) ?: return mutableListOf()
    return (0 until a.length()).mapNotNull { idx ->
        when (val v = a.opt(idx)) { is Number -> v.toInt(); is String -> v.toIntOrNull(); else -> null }
    }.toMutableList()
}

private fun mapObj(m: Map<String, Any?>): JSONObject = JSONObject().also { o -> m.forEach { (k, v) -> o.put(k, v) } }
private fun newId() = UUID.randomUUID().toString().uppercase()

// ---------- Profile ----------

class FTOInfo(var name: String = "", var badge: String = "", var initials: String = "") {
    fun toJson() = JSONObject().put("name", name).put("badge", badge).put("init", initials)
    companion object { fun from(o: JSONObject?) = if (o == null) FTOInfo() else FTOInfo(o.s("name"), o.s("badge"), o.s("init")) }
    fun copy() = FTOInfo(name, badge, initials)
}

class Trainee(
    var id: String = newId(), var name: String = "", var badge: String = "", var level: String = "II",
    var phase: String = "Phase I", var start: String = DateUtil.today(), var shift: String = "",
) {
    fun copy() = Trainee(id, name, badge, level, phase, start, shift)
    fun toJson() = JSONObject().put("id", id).put("name", name).put("badge", badge).put("level", level)
        .put("phase", phase).put("start", start).put("shift", shift)
    companion object {
        fun from(o: JSONObject) = Trainee(o.s("id", newId()), o.s("name"), o.s("badge"), o.s("level", "II"),
            Phase.normalize(o.s("phase", "Phase I")), o.s("start"), o.s("shift"))
    }
}

class Profile {
    var fto = FTOInfo()
    var agency = "Montpelier Police Department"
    var trainees = mutableListOf<Trainee>()
    var active: String? = null
    var carry = true

    fun toJson(): JSONObject = JSONObject().put("fto", fto.toJson()).put("agency", agency)
        .put("trainees", JSONArray(trainees.map { it.toJson() })).put("active", active ?: JSONObject.NULL)
        .put("settings", JSONObject().put("carry", carry))

    companion object {
        fun from(o: JSONObject?): Profile {
            val p = Profile(); if (o == null) return p
            p.fto = FTOInfo.from(o.obj("fto"))
            o.s("agency").let { if (it.isNotEmpty()) p.agency = it }
            o.optJSONArray("trainees")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { p.trainees.add(Trainee.from(it)) } }
            p.active = o.s("active").ifEmpty { null }
            p.carry = o.obj("settings")?.ob("carry") ?: true
            return p
        }
    }
}

// ---------- Note ----------

class Note(
    var id: String = newId(), var tid: String = "", var ts: Long = System.currentTimeMillis(), var date: String = DateUtil.today(),
    var time: String = "", var cat: Int? = null, var tone: String = "0", var text: String = "",
    var call: String = "", var caseNo: String = "", var rem: String = "",
) {
    fun copy() = Note(id, tid, ts, date, time, cat, tone, text, call, caseNo, rem)
    fun toJson(): JSONObject = JSONObject().put("id", id).put("tid", tid).put("ts", ts).put("date", date).put("time", time)
        .put("cat", cat ?: JSONObject.NULL).put("tone", tone).put("text", text).put("call", call).put("caseNo", caseNo).put("rem", rem)
    companion object {
        fun from(o: JSONObject) = Note(o.s("id", newId()), o.s("tid"), o.l("ts"), o.s("date", DateUtil.today()), o.s("time"),
            o.i("cat"), o.s("tone", "0"), o.s("text"), o.s("call"), o.s("caseNo"), o.s("rem"))
    }
}

// ---------- DOR ----------

class AreaNote(var cat: Int? = null, var text: String = "") {
    fun toJson(): JSONObject = JSONObject().put("cat", cat ?: JSONObject.NULL).put("text", text)
    companion object { fun from(o: JSONObject?) = if (o == null) AreaNote() else AreaNote(o.i("cat"), o.s("text")) }
}

class ManualFlags {
    var ratings = mutableMapOf<String, Boolean>()
    var comments = mutableMapOf<String, Boolean>()
    var most = false
    var least = false
    var narrative = false
    fun toJson(): JSONObject = JSONObject().put("ratings", mapObj(ratings)).put("comments", mapObj(comments))
        .put("most", most).put("least", least).put("narrative", narrative)
    companion object {
        fun from(o: JSONObject?): ManualFlags {
            val m = ManualFlags(); if (o == null) return m
            m.ratings = o.boolMap("ratings"); m.comments = o.boolMap("comments")
            m.most = o.b("most"); m.least = o.b("least"); m.narrative = o.b("narrative")
            return m
        }
    }
}

class DOR {
    var id = newId()
    var tid = ""
    var date = DateUtil.today()
    var status = "draft"
    var phase = "Phase I"
    var ride = 1
    var shiftHours = ""
    var totalHours = ""
    var fto = ""
    var nonFto = ""
    var assignment = ""
    var attach = false
    var radio = false
    var ungraded = false
    var ratings = mutableMapOf<String, String>()
    var comments = mutableMapOf<String, String>()
    var nrt = mutableMapOf<String, Boolean>()
    var rem = mutableMapOf<String, Boolean>()
    var time = mutableMapOf<String, String>()
    var most = AreaNote()
    var least = AreaNote()
    var narrative = ""
    var traineeComments = ""
    var calls = ""
    var mv = ""
    var arrests = ""
    var viols = ""
    var used = mutableListOf<String>()
    var created = System.currentTimeMillis()
    var finalAt: Long? = null
    var ai = false
    var aiCats = mutableListOf<Int>()
    var manual = ManualFlags()

    val isFinal get() = status == "final"
    fun rating(n: Int) = ratings[n.toString()] ?: "NO"
    fun comment(n: Int) = comments[n.toString()] ?: ""
    fun isRem(n: Int) = rem[n.toString()] ?: false
    fun isNRT(n: Int) = nrt[n.toString()] ?: false
    fun minutes(n: Int) = time[n.toString()] ?: ""

    fun toJson(): JSONObject = JSONObject().put("id", id).put("tid", tid).put("date", date).put("status", status)
        .put("phase", phase).put("ride", ride).put("shiftHours", shiftHours).put("totalHours", totalHours)
        .put("fto", fto).put("nonFto", nonFto).put("assignment", assignment).put("attach", attach).put("radio", radio)
        .put("ungraded", ungraded).put("ratings", mapObj(ratings)).put("comments", mapObj(comments))
        .put("nrt", mapObj(nrt)).put("rem", mapObj(rem)).put("time", mapObj(time))
        .put("most", most.toJson()).put("least", least.toJson()).put("narrative", narrative)
        .put("traineeComments", traineeComments).put("calls", calls).put("mv", mv).put("arrests", arrests).put("viols", viols)
        .put("used", JSONArray(used)).put("created", created).put("finalAt", finalAt ?: JSONObject.NULL)
        .put("ai", ai).put("aiCats", JSONArray(aiCats)).put("manual", manual.toJson())

    fun deepCopy(): DOR = from(toJson())

    companion object {
        fun from(o: JSONObject): DOR {
            val d = DOR()
            d.id = o.s("id", newId()); d.tid = o.s("tid"); d.date = o.s("date", DateUtil.today())
            d.status = o.s("status", "draft"); d.phase = Phase.normalize(o.s("phase", "Phase I"))
            d.ride = o.i("ride") ?: o.i("day") ?: 1
            d.shiftHours = o.s("shiftHours").ifEmpty { o.s("shift") }
            d.totalHours = o.s("totalHours"); d.fto = o.s("fto"); d.nonFto = o.s("nonFto"); d.assignment = o.s("assignment")
            d.attach = o.b("attach"); d.radio = o.b("radio"); d.ungraded = o.b("ungraded")
            d.ratings = o.strMap("ratings"); d.comments = o.strMap("comments")
            d.nrt = o.boolMap("nrt"); d.rem = o.boolMap("rem"); d.time = o.strMap("time")
            d.most = AreaNote.from(o.obj("most")); d.least = AreaNote.from(o.obj("least"))
            d.narrative = o.s("narrative"); d.traineeComments = o.s("traineeComments")
            d.calls = o.s("calls"); d.mv = o.s("mv"); d.arrests = o.s("arrests"); d.viols = o.s("viols")
            d.used = o.strList("used"); d.created = o.l("created")
            d.finalAt = o.l("finalAt").takeIf { it != 0L }
            d.ai = o.b("ai"); d.aiCats = o.intList("aiCats")
            d.manual = ManualFlags.from(o.obj("manual"))
            // Legacy: an "NRT" rating becomes a 2 with the NRT box ticked.
            for ((k, v) in d.ratings.toMap()) if (v == "NRT") { d.ratings[k] = "2"; d.nrt[k] = true }
            val legacyRem = o.s("remedial")
            if (legacyRem.isNotEmpty()) {
                val mins = o.s("minutes")
                d.narrative += (if (d.narrative.isEmpty()) "" else "\n") + "Remedial: " + legacyRem + (if (mins.isEmpty()) "" else " ($mins min)")
            }
            return d
        }
    }
}

// ---------- RTP ----------

class RTP {
    var id = newId()
    var tid = ""
    var cat: Int? = null
    var date = DateUtil.today()
    var week = ""
    var phase = "Phase I"
    var due = ""
    var deficiency = ""
    var assignment = ""
    var completed: Boolean? = null
    var competent: Boolean? = null
    var additional: Boolean? = null
    var comments = ""
    var created = System.currentTimeMillis()

    fun toJson(): JSONObject = JSONObject().put("id", id).put("tid", tid).put("cat", cat ?: JSONObject.NULL).put("date", date)
        .put("week", week).put("phase", phase).put("due", due).put("deficiency", deficiency).put("assignment", assignment)
        .put("completed", completed ?: JSONObject.NULL).put("competent", competent ?: JSONObject.NULL)
        .put("additional", additional ?: JSONObject.NULL).put("comments", comments).put("created", created)

    companion object {
        fun from(o: JSONObject): RTP {
            val r = RTP()
            r.id = o.s("id", newId()); r.tid = o.s("tid"); r.cat = o.i("cat"); r.date = o.s("date", DateUtil.today())
            r.week = o.s("week"); r.phase = Phase.normalize(o.s("phase", "Phase I")); r.due = o.s("due")
            r.deficiency = o.s("deficiency"); r.assignment = o.s("assignment")
            r.completed = o.ob("completed"); r.competent = o.ob("competent"); r.additional = o.ob("additional")
            r.comments = o.s("comments"); r.created = o.l("created")
            return r
        }
    }
}

// ---------- Standard Task Checklist ----------

class CheckMark(var d: String? = null, var dm: String? = null, var p: String? = null, var by: String? = null) {
    fun toJson(): JSONObject = JSONObject().apply { d?.let { put("d", it) }; dm?.let { put("dm", it) }; p?.let { put("p", it) }; by?.let { put("by", it) } }
    companion object {
        fun from(o: JSONObject) = CheckMark(o.s("d").ifEmpty { null }, o.s("dm").ifEmpty { null }, o.s("p").ifEmpty { null }, o.s("by").ifEmpty { null })
    }
}

class Checks(var tid: String = "", var items: MutableMap<String, CheckMark> = mutableMapOf()) {
    fun toJson(): JSONObject = JSONObject().put("tid", tid).put("items", JSONObject().also { o -> items.forEach { (k, v) -> o.put(k, v.toJson()) } })
    companion object {
        fun from(o: JSONObject): Checks {
            val c = Checks(o.s("tid"))
            o.optJSONObject("items")?.let { it2 -> for (k in it2.keys()) it2.optJSONObject(k)?.let { c.items[k] = CheckMark.from(it) } }
            return c
        }
    }
}

// ---------- Whole store / backup (same shape as the web and iPhone apps) ----------

class AppData {
    var profile = Profile()
    var notes = mutableMapOf<String, Note>()
    var dors = mutableMapOf<String, DOR>()
    var rtps = mutableMapOf<String, RTP>()
    var checks = mutableMapOf<String, Checks>()

    fun toJson(): JSONObject {
        fun <T> m(map: Map<String, T>, f: (T) -> JSONObject) = JSONObject().also { o -> map.forEach { (k, v) -> o.put(k, f(v)) } }
        return JSONObject().put("app", "fto-fieldbook").put("v", 2).put("exported", Instant.now().toString())
            .put("profile", profile.toJson()).put("notes", m(notes) { it.toJson() }).put("dors", m(dors) { it.toJson() })
            .put("rtps", m(rtps) { it.toJson() }).put("checks", m(checks) { it.toJson() })
    }

    companion object {
        fun from(o: JSONObject): AppData {
            val d = AppData()
            d.profile = Profile.from(o.obj("profile"))
            fun <T> each(k: String, f: (JSONObject) -> T, put: (String, T) -> Unit) {
                val x = o.optJSONObject(k) ?: return
                for (key in x.keys()) x.optJSONObject(key)?.let { runCatching { put(key, f(it)) } }
            }
            each("notes", Note::from) { k, v -> d.notes[k] = v }
            each("dors", DOR::from) { k, v -> d.dors[k] = v }
            each("rtps", RTP::from) { k, v -> d.rtps[k] = v }
            each("checks", Checks::from) { k, v -> d.checks[k] = v }
            return d
        }
    }
}

// ---------- Dates ----------

object DateUtil {
    private val ymd = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
    fun today(): String = LocalDate.now().format(ymd)
    fun parse(s: String): LocalDate? = runCatching { LocalDate.parse(s, ymd) }.getOrNull()
    fun string(d: LocalDate): String = d.format(ymd)
    fun us(s: String): String { val p = s.split("-"); return if (p.size == 3) "${p[1]}/${p[2]}/${p[0]}" else s }
    fun short(s: String): String {
        val p = s.split("-"); val m = p.getOrNull(1)?.toIntOrNull(); val d = p.getOrNull(2)?.toIntOrNull()
        return if (p.size == 3 && m != null && d != null) "$m/$d" else s
    }
    fun pretty(s: String): String = parse(s)?.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)) ?: s
}
