package com.mosurabeats.fieldbook

import android.content.Context
import org.json.JSONArray
import kotlin.math.ceil
import kotlin.math.max

data class SEG(
    val n: Int, val g: String, val name: String, val d: String,
    val a1: String, val a4: String, val a7: String, val list: List<String>, val test: Boolean, val l2: Boolean,
) { val key get() = n.toString() }

data class ChecklistItem(val id: String, val t: String, val ph: String?, val lvl: Int)
data class ChecklistSection(val n: Int, val t: String, val items: List<ChecklistItem>)

object Ref {
    lateinit var segs: List<SEG>; private set
    lateinit var checklist: List<ChecklistSection>; private set
    lateinit var byN: Map<Int, SEG>; private set

    val groups = listOf("Critical Performance Tasks", "Frequent Performance Tasks", "Knowledge", "Attitude & Relationships", "Appearance")
    val subhead = mapOf(25 to "Department Policy & Procedure", 27 to "Criminal Statutes", 29 to "Resources & Alternatives", 31 to "Traffic Statutes", 33 to "Scope of Practice")
    val phaseLabel = mapOf("L" to "Limbo", "1" to "Phase I", "2" to "Phase II", "3" to "Phase III")
    const val tips = "SET THE STAGE · USE QUOTES · USE LISTS · INCLUDE REMEDIAL · FRONT = BACK · USE SEGs · REMEMBER YOUR AUDIENCE · DO NOT PREDICT"
    val calls = listOf("MV stop", "Crash", "Alarm", "Domestic", "Welfare check", "Suspicious", "Theft", "Disturbance", "Arrest", "Mental health", "Foot patrol", "Assist")

    fun load(ctx: Context) {
        if (::segs.isInitialized) return
        val sa = JSONArray(ctx.assets.open("segs.json").bufferedReader().readText())
        segs = (0 until sa.length()).map { i ->
            val o = sa.getJSONObject(i)
            SEG(o.getInt("n"), o.s("g"), o.s("name"), o.s("d"), o.s("a1"), o.s("a4"), o.s("a7"),
                o.strList("list"), o.b("test"), o.b("l2"))
        }
        byN = segs.associateBy { it.n }
        val ca = JSONArray(ctx.assets.open("checklist.json").bufferedReader().readText())
        checklist = (0 until ca.length()).map { i ->
            val o = ca.getJSONObject(i)
            val items = o.getJSONArray("items")
            ChecklistSection(o.getInt("n"), o.s("t"), (0 until items.length()).map { j ->
                val it = items.getJSONObject(j)
                ChecklistItem(it.s("id"), it.s("t"), it.s("ph").ifEmpty { null }, it.i("lvl") ?: 0)
            })
        }
    }

    fun segsFor(t: Trainee?): List<SEG> = segs.filter { !(it.l2 && t?.level == "III") }
    fun name(n: Int?): String = n?.let { byN[it]?.name } ?: ""
}

object Phase {
    val all = listOf("Phase I", "Phase II", "Phase III", "Remedial")
    val rides = mapOf("Phase I" to 24, "Phase II" to 16, "Phase III" to 16, "Remedial" to 8)
    fun normalize(p: String): String {
        if (p in all) return p
        val m = mapOf("Limbo" to "Phase I", "Phase 1" to "Phase I", "Phase 2" to "Phase II", "Phase 3" to "Phase III",
            "Phase 4" to "Phase III", "Shadow / Eval" to "Phase III", "Extended" to "Remedial")
        return m[p] ?: "Phase I"
    }
    fun week(ride: Int) = ceil(max(ride, 1) / 4.0).toInt()
    fun label(phase: String, ride: Int): String {
        val limbo = if (phase == "Phase I" && ride <= 8) " (Limbo)" else ""
        return "$phase · Ride $ride/${rides[phase] ?: 16} · Wk ${week(ride)}$limbo"
    }
    fun due(phase: String): List<String> = when (phase) {
        "Phase I" -> listOf("L", "1"); "Phase II" -> listOf("L", "1", "2"); else -> listOf("L", "1", "2", "3")
    }
}

object Rating {
    val numbers = listOf("1", "2", "3", "4", "5", "6", "7")
    fun isNum(r: String) = r in numbers
    fun needsNarrative(r: String) = r in listOf("1", "2", "6", "7")
    const val unratedReason = "Not graded"
}
