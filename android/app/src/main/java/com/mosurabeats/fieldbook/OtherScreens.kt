package com.mosurabeats.fieldbook

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// ---------- Remedial Training Worksheet ----------

@Composable
fun RTPEditorScreen(id: String) {
    val store = App.store; val nav = LocalNav.current; val ctx = LocalContext.current; val p = pal()
    val r = store.data.rtps[id]
    if (r == null) { Page("RTP") { Text("This RTP was deleted.", color = p.mu) }; return }
    val scope = rememberCoroutineScope()
    var drafting by remember { mutableStateOf(false) }
    var aiError by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    fun ed(f: () -> Unit) = store.edit { f() }
    val done = r.cat?.let { DORLogic.rtpCompleted(store, r.tid, it) } ?: 0

    Page("Remedial Training", actions = { if (r.completed == true) Text("DONE  ", color = p.pos, fontWeight = FontWeight.Bold) else Tag("OPEN"); Spacer(Modifier.width(12.dp)) }) {
        Card("Remedial Training Worksheet") {
            Labeled("SEG / area") {
                MenuField(r.cat?.let { "$it. ${Ref.name(it)}" } ?: "— select —", Ref.segsFor(store.trainee(r.tid)).map { it.n to "${it.n}. ${it.name}" }, r.cat == null) { c -> ed { r.cat = c } }
            }
            if (r.cat != null) Hint("$done completed RTP${if (done == 1) "" else "s"} in this SEG" + if (done >= 3) " — NRT may be applied if still deficient" else " · ${3 - done} more before NRT is allowed")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                Labeled("Date") { DateField(r.date) { v -> ed { r.date = v } } }
                Labeled("Training week #", Modifier.weight(1f)) { Field(r.week, { v -> ed { r.week = v } }, number = true) }
            }
            Labeled("Complete assignment by") { DateField(r.due) { v -> ed { r.due = v } } }
        }
        Card("Performance deficiencies") {
            Hint("Define the problem specifically with examples. Describe the training already conducted.")
            NoteEditor(r.deficiency, { v -> ed { r.deficiency = v } }, minHeight = 130.dp)
        }
        Card("Training assignment") {
            Hint("Specific, measurable goals, e.g. \"practice self-initiated car stops daily for 4 shifts: at least 4 F.I.s and 2 citations per shift.\"")
            NoteEditor(r.assignment, { v -> ed { r.assignment = v } }, minHeight = 130.dp)
            if (ClaudeAPI.hasKey) {
                AIButton(if (drafting) "Drafting…" else "✦ Draft deficiency & assignment with Claude", drafting, enabled = r.cat != null) {
                    drafting = true; aiError = null
                    scope.launch { try { Drafting.draftRTP(store, id) } catch (e: Exception) { aiError = e.message }; drafting = false }
                }
                aiError?.let { Text(it, color = p.neg, fontSize = 13.sp) }
            }
        }
        Card("Assignment completion") {
            YesNo("Satisfactorily completed the training plan?", r.completed) { v -> ed { r.completed = v } }
            YesNo("Now performing at a competent level?", r.competent) { v -> ed { r.competent = v } }
            YesNo("Additional assignment given?", r.additional) { v -> ed { r.additional = v } }
            Labeled("Comments") { NoteEditor(r.comments, { v -> ed { r.comments = v } }, "If not satisfactorily completed, specific recommendations are required", 80.dp) }
            if (r.completed == false || r.additional == true) SecondaryButton("Create follow-up RTP") {
                val n = RTP().apply { tid = r.tid; cat = r.cat; phase = r.phase; deficiency = "Follow-up to RTP of ${DateUtil.us(r.date)}.\n" + r.comments }
                store.edit { rtps[n.id] = n }; nav.navigate("rtp/${n.id}")
            }
        }
        InfoBox("Hand-off", listOf("The originating FTO must pass this assignment on to the trainee's next FTO so follow-up can be tracked."), danger = false)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("Share worksheet", Modifier.weight(1f)) { shareText(ctx, DORLogic.rtpText(store, r), "Remedial Training Worksheet") }
            SecondaryButton("Delete", Modifier.width(110.dp), danger = true) { confirm = true }
        }
    }
    if (confirm) Confirm("Delete this RTP?", "Delete", { confirm = false }) { nav.popBackStack(); store.edit { rtps.remove(id) } }
}

@Composable
private fun YesNo(label: String, v: Boolean?, set: (Boolean?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = pal().tx, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Segmented(listOf<Pair<Boolean?, String>>(null to "—", true to "Yes", false to "No"), v, Modifier.width(150.dp)) { set(it) }
    }
}

// ---------- Standard Task Checklist ----------

private fun phaseColor(ph: String) = when (ph) { "L" -> hex("E5C400"); "1" -> hex("E5483D"); "2" -> hex("F39A1C"); else -> hex("6FBF3F") }

@Composable
fun TasksTab() {
    val store = App.store
    val t = store.trainee ?: return Onboarding()
    val p = pal(); val ctx = LocalContext.current; val haptic = LocalHapticFeedback.current
    var filter by remember { mutableStateOf("due") }
    var q by remember { mutableStateOf("") }
    val expanded = remember { mutableStateListOf<Int>() }
    val checks = store.checks(t.id); val ck = checks.items
    val due = Phase.due(t.phase)
    val all = Ref.checklist.flatMap { s -> s.items.filter { it.ph != null } }
    fun done(i: ChecklistItem) = ck[i.id]?.p != null
    fun touched(i: ChecklistItem) = ck[i.id]?.let { it.d != null || it.dm != null || it.p != null } ?: false
    fun keep(i: ChecklistItem): Boolean {
        val ph = i.ph ?: return q.isEmpty()
        if (q.isNotEmpty() && !i.t.contains(q, ignoreCase = true)) return false
        return when (filter) { "due" -> ph in due && !done(i); "open" -> !touched(i); "all" -> true; else -> ph == filter }
    }
    fun toggle(i: ChecklistItem, k: String) {
        val today = DateUtil.today()
        store.edit {
            val c = this.checks.getOrPut(t.id) { Checks(t.id) }
            val m = c.items.getOrPut(i.id) { CheckMark() }
            when (k) { "d" -> m.d = if (m.d == null) today else null; "dm" -> m.dm = if (m.dm == null) today else null; else -> m.p = if (m.p == null) today else null }
            if (profile.fto.initials.isNotEmpty()) m.by = profile.fto.initials
        }
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    TabColumn {
        Card("Standard Task Checklist") {
            Hint("Tap Disc, Demo or Perf to stamp today's date. Colours match the phase codes in your manual.")
            listOf("L", "1", "2", "3").forEach { ph ->
                val items = all.filter { it.ph == ph }; val d = items.count(::done)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(phaseColor(ph)))
                    Text(Ref.phaseLabel[ph] ?: ph, color = p.tx, fontSize = 13.sp, modifier = Modifier.width(72.dp))
                    LinearProgressIndicator({ if (items.isEmpty()) 0f else d.toFloat() / items.size }, Modifier.weight(1f).height(6.dp).clip(CircleShape), color = p.pos, trackColor = p.p3, drawStopIndicator = {})
                    Text("$d/${items.size}", fontFamily = Mono, fontSize = 12.sp, color = p.mu, modifier = Modifier.width(60.dp), textAlign = TextAlign.End)
                }
            }
        }
        Field(q, { q = it }, placeholder = "Search tasks (DUI, pursuit, juvenile)")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("due" to "Due by ${t.phase}", "open" to "Not started", "all" to "All", "L" to "Limbo", "1" to "Phase I", "2" to "Phase II", "3" to "Phase III")
                .forEach { (k, l) -> Chip(l, filter == k) { filter = k } }
        }
        for (sec in Ref.checklist) {
            val items = sec.items.filter(::keep)
            if (items.none { it.ph != null }) continue
            val real = sec.items.filter { it.ph != null }
            val open = sec.n in expanded || q.isNotEmpty()
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.p1).border(1.dp, p.line, RoundedCornerShape(14.dp)).padding(12.dp)) {
                Row(Modifier.fillMaxWidth().clickable { if (sec.n in expanded) expanded.remove(sec.n) else expanded.add(sec.n) }, verticalAlignment = Alignment.CenterVertically) {
                    Text("${sec.n}", fontFamily = Mono, color = p.hi2, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(26.dp))
                    Text(sec.t, color = p.tx, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("${real.count(::done)}/${real.size}", fontFamily = Mono, fontSize = 12.sp, color = p.mu)
                    Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, tint = p.mu)
                }
                if (open) items.forEach { i ->
                    val ph = i.ph
                    if (ph == null) Text(i.t.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = p.mu, modifier = Modifier.padding(vertical = 8.dp))
                    else CheckRow(i, phaseColor(ph), ck[i.id] ?: CheckMark()) { k -> toggle(i, k) }
                }
            }
        }
        SecondaryButton("Share checklist") { shareText(ctx, DORLogic.checklistText(t, checks), "Standard Task Checklist") }
    }
}

@Composable
private fun CheckRow(i: ChecklistItem, color: Color, m: CheckMark, tap: (String) -> Unit) {
    val p = pal()
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Text(i.t, fontSize = if (i.lvl == 0) 14.sp else 13.sp, color = if (i.lvl == 0) p.tx else p.tx2, modifier = Modifier.weight(1f).padding(start = (i.lvl * 12).dp))
            Stamp("Disc", m.d) { tap("d") }; Stamp("Demo", m.dm) { tap("dm") }; Stamp("Perf", m.p) { tap("p") }
        }
        HorizontalDivider(color = p.line)
    }
}

@Composable
private fun Stamp(label: String, v: String?, onClick: () -> Unit) {
    val p = pal(); val on = v != null
    Column(Modifier.size(46.dp, 38.dp).clip(RoundedCornerShape(8.dp)).background(if (on) p.pos.copy(alpha = .16f) else p.p2)
        .border(1.dp, if (on) p.pos else p.line, RoundedCornerShape(8.dp)).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(label, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = if (on) p.pos else p.dim)
        if (v != null) Text(DateUtil.short(v), fontSize = 9.5.sp, color = p.pos)
    }
}

// ---------- Progress ----------

private class Trend(val seg: SEG, val vals: List<Pair<Int, Int>>, val last: Int?, val avg3: Double?, val nrt: Boolean, val rem: Int)

@Composable
fun ProgressTab() {
    val store = App.store
    val t = store.trainee ?: return Onboarding()
    val p = pal(); val ctx = LocalContext.current; val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var weekKey by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var aiError by remember { mutableStateOf<String?>(null) }

    val all = store.dors(t.id)
    val graded = all.filter { !it.ungraded }
    val trends = Ref.segsFor(t).map { s ->
        val vals = graded.mapIndexedNotNull { i, d -> d.rating(s.n).toIntOrNull()?.let { i to it } }
        val last3 = vals.takeLast(3).map { it.second }
        Trend(s, vals, vals.lastOrNull()?.second, if (last3.isEmpty()) null else last3.average(), graded.any { it.isNRT(s.n) }, graded.count { it.isRem(s.n) })
    }
    val watch = trends.filter { (it.avg3 ?: 9.0) < 4 || it.nrt }
    val mins = all.sumOf { d -> d.time.values.sumOf { it.toIntOrNull() ?: 0 } }
    val weeks = DORLogic.weeks(store, t.id)
    val wk = weeks.firstOrNull { it.first == weekKey } ?: weeks.lastOrNull()

    fun run(ds: List<DOR>, weekly: Boolean) {
        working = true; aiError = null
        scope.launch {
            try { summary = Drafting.summary(store, t, ds, weekly) } catch (e: Exception) { aiError = e.message }
            working = false
        }
    }

    TabColumn {
        if (all.isEmpty()) { Text("Progress shows up once you have a DOR or two.", color = p.mu, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp)); return@TabColumn }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("${all.size}", "DORs"); Stat("${store.notes(t.id).size}", "notes"); Stat("%.1f".format(mins / 60.0), "hrs remedial")
        }
        Card("Watch list (last 3 avg < 4 or NRT)") {
            if (watch.isEmpty()) Text("Nothing trending below acceptable.", color = p.pos)
            watch.forEach { TrendRow(it) }
        }
        if (wk != null) Card("Weekly report data") {
            MenuField(wk.first, weeks.map { it.first to "${it.first} (${it.second.size} DORs)" }) { weekKey = it }
            val txt = DORLogic.weekText(t, wk.second)
            Text(txt, fontFamily = Mono, fontSize = 11.sp, color = Color(0xFF111111), modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFFFBFAF6)).padding(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Copy", Modifier.weight(1f)) { copyText(ctx, txt); toast("Copied") }
                if (ClaudeAPI.hasKey) Box(Modifier.weight(1f)) { AIButton("✦ Weekly comments", working) { run(wk.second, true) } }
            }
        }
        if (ClaudeAPI.hasKey) Card("Phase evaluation") {
            Hint("Claude reads every DOR, RTP and note for this trainee's current phase and drafts the Phase Evaluation: strengths, weaknesses, remedial training and its effect, recommended follow-up, and whether the trainee is performing at the level of a solo patrol officer.")
            AIButton("✦ Draft phase evaluation", working) { run(all.filter { it.phase == t.phase }, false) }
        }
        aiError?.let { Text(it, color = p.neg) }
        summary?.let { s ->
            Card("✦ Draft — review & edit before you use it") {
                SelectionContainer { Text(s, color = p.tx, fontSize = 14.5.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("Copy", Modifier.weight(1f)) { copyText(ctx, s); toast("Copied") }
                    SecondaryButton("Share", Modifier.weight(1f)) { shareText(ctx, s, "FTEP evaluation draft") }
                }
            }
        }
        Card("All SEGs · ${graded.size} graded DORs") { trends.forEach { TrendRow(it) } }
    }
}

@Composable
private fun RowScope.Stat(v: String, l: String) {
    val p = pal()
    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(p.p1).border(1.dp, p.line, RoundedCornerShape(12.dp)).padding(12.dp)) {
        Text(v, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = p.tx)
        Text(l, fontSize = 12.sp, color = p.mu)
    }
}

@Composable
private fun TrendRow(tr: Trend) {
    val p = pal()
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${tr.seg.n}", fontFamily = Mono, fontSize = 11.5.sp, color = p.dim, modifier = Modifier.width(22.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr.seg.name, fontSize = 13.5.sp, color = p.tx, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (tr.rem > 0) Tag("REM×${tr.rem}", TagStyle.Quiet)
            if (tr.nrt) Tag("NRT", TagStyle.Danger)
        }
        Sparkline(tr, Modifier.size(96.dp, 28.dp))
        RatingPill(tr.last?.toString() ?: "—", Modifier.scale(.85f))
    }
}

@Composable
private fun Sparkline(tr: Trend, modifier: Modifier) {
    val p = pal()
    val lineColor = if ((tr.last ?: 4) < 4) p.neg else p.acc
    Canvas(modifier) {
        fun y(v: Int) = size.height - (v - 1) / 6f * size.height
        drawLine(p.dim.copy(alpha = .6f), Offset(0f, y(4)), Offset(size.width, y(4)), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
        if (tr.vals.isEmpty()) return@Canvas
        val minX = tr.vals.first().first; val maxX = tr.vals.last().first
        fun x(i: Int) = if (maxX == minX) size.width / 2 else (i - minX).toFloat() / (maxX - minX) * size.width
        tr.vals.zipWithNext().forEach { (a, b) -> drawLine(lineColor, Offset(x(a.first), y(a.second)), Offset(x(b.first), y(b.second)), 1.6.dp.toPx()) }
        tr.vals.forEach { (i, v) -> drawCircle(if (v < 4) p.neg else if (v > 5) p.pos else p.tx2, 2.dp.toPx(), Offset(x(i), y(v))) }
    }
}
