package com.mosurabeats.fieldbook

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil

// ---------- DOR list ----------

@Composable
fun DORsTab() {
    val store = App.store
    val t = store.trainee ?: return Onboarding()
    val p = pal(); val nav = LocalNav.current
    var date by remember { mutableStateOf(DateUtil.today()) }
    val all = store.dors(t.id)
    val existing = all.firstOrNull { it.date == date }
    val rt = store.rtps(t.id)
    TabColumn {
        val lb = Prefs.lastBackup
        val stale = lb?.let { DateUtil.parse(it) }?.let { java.time.LocalDate.now().toEpochDay() - it.toEpochDay() > 7 } ?: true
        if (stale && all.size >= 3) InfoBox("Back up your records", listOf("Last backup: ${lb?.let { DateUtil.us(it) } ?: "never"}. Trainee → Export backup saves a copy."), danger = false)
        Card("Daily Observation Report") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DateField(date) { date = it }
                Spacer(Modifier.weight(1f))
                PrimaryButton(if (existing != null) "Open DOR" else "Build DOR", Modifier.width(150.dp)) { nav.navigate("dor/" + DORLogic.build(store, t, date)) }
            }
            val n = store.notes(t.id).count { it.date == date }
            val ride = existing?.ride ?: DORLogic.nextRide(store, t.id, t.phase, date)
            Text("$n note${if (n == 1) "" else "s"} on this date · ${Phase.label(existing?.phase ?: t.phase, ride)}", fontSize = 13.sp, color = p.mu)
        }
        Card("Remedial Training Plans") {
            if (rt.isEmpty()) Hint("None yet. Turning on REM for a SEG offers one. You need 3 completed RTPs in a SEG before NRT can be given.")
            rt.take(8).forEach { r -> RTPRow(r) { nav.navigate("rtp/${r.id}") } }
            SecondaryButton("＋ New RTP") {
                val r = RTP().apply { tid = t.id; phase = t.phase }
                store.edit { rtps[r.id] = r }; nav.navigate("rtp/${r.id}")
            }
        }
        if (all.isEmpty()) Text("No DORs yet.", color = p.mu, modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp), textAlign = TextAlign.Center)
        all.reversed().forEach { d -> DORRow(d) { nav.navigate("dor/${d.id}") } }
    }
}

@Composable
fun DORRow(d: DOR, onClick: () -> Unit) {
    val p = pal()
    val nums = d.ratings.values.mapNotNull { if (Rating.isNum(it)) it.toInt() else null }
    val avg = if (nums.isEmpty() || d.ungraded) null else nums.average()
    val lo = if (d.ungraded) 0 else nums.count { it < 4 }
    val rem = d.rem.values.count { it }; val nrt = d.nrt.values.count { it }
    val v = DORLogic.validate(App.store, d)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.p1).border(1.dp, p.line, RoundedCornerShape(12.dp))
        .clickable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RatingPill(avg?.let { "%.1f".format(it) } ?: "–")
        Column(Modifier.weight(1f)) {
            Text("DOR ${d.ride} · ${DateUtil.pretty(d.date)}", color = p.tx, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(listOfNotNull(d.phase, if (d.ungraded) "not graded" else null, if (lo > 0) "$lo below 4" else null,
                if (rem > 0) "$rem REM" else null, if (nrt > 0) "$nrt NRT" else null).joinToString(" · "), fontFamily = Mono, fontSize = 12.sp, color = p.mu)
        }
        when {
            d.isFinal -> Text("FINAL", color = p.pos, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            v.errors.isNotEmpty() -> Tag("${v.errors.size}", TagStyle.Danger)
            else -> Tag("DRAFT")
        }
    }
}

@Composable
fun RTPRow(r: RTP, onClick: () -> Unit) {
    val p = pal()
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(r.cat?.toString() ?: "–", fontFamily = Mono, color = p.hi2, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(r.cat?.let { Ref.name(it) } ?: "Remedial", color = p.tx)
            Text(DateUtil.short(r.date) + if (r.due.isEmpty()) "" else " · due " + DateUtil.short(r.due), fontSize = 12.sp, color = p.mu)
        }
        when (r.completed) { true -> Text("DONE", color = p.pos, fontWeight = FontWeight.Bold, fontSize = 12.sp); false -> Tag("NOT MET", TagStyle.Danger); null -> Tag("OPEN") }
    }
}

// ---------- DOR editor ----------

@Composable
fun DOREditorScreen(id: String) {
    val store = App.store; val nav = LocalNav.current; val toast = LocalToast.current; val p = pal()
    val d = store.data.dors[id]
    if (d == null) { Page("DOR") { Text("This DOR was deleted.", color = p.mu) }; return }
    val locked = d.isFinal
    val issues = DORLogic.validate(store, d)
    val scope = rememberCoroutineScope()
    var drafting by remember { mutableStateOf(false) }
    var aiError by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var addSEG by remember { mutableStateOf(false) }
    fun ed(f: () -> Unit) = store.edit { f() }

    Page("DOR # ${d.ride}", actions = { if (locked) Text("FINAL  ", color = p.pos, fontWeight = FontWeight.Bold) else Tag("DRAFT"); Spacer(Modifier.width(12.dp)) }) {
        // Header
        Card("DOR # ${d.ride} · ${DateUtil.pretty(d.date)}") {
            Hint(Phase.label(d.phase, d.ride))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Labeled("Phase", Modifier.weight(1f)) {
                    MenuField(d.phase, Phase.all.map { it to it }, enabled = !locked) { ph -> ed { d.phase = ph; d.ride = DORLogic.nextRide(store, d.tid, ph, d.date, d.id) } }
                }
                Labeled("DOR / ride #", Modifier.width(120.dp)) { Field(d.ride.toString(), { v -> v.toIntOrNull()?.let { ed { d.ride = it } } }, number = true, enabled = !locked) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Labeled("Shift hours", Modifier.weight(1f)) { Field(d.shiftHours, { v -> ed { d.shiftHours = v } }, placeholder = "0600–1600", enabled = !locked) }
                Labeled("Total hrs worked", Modifier.width(140.dp)) { Field(d.totalHours, { v -> ed { d.totalHours = v } }, decimal = true, enabled = !locked) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Labeled("FTO", Modifier.weight(1f)) { Field(d.fto, { v -> ed { d.fto = v } }, enabled = !locked) }
                Labeled("Non-FTO (if any)", Modifier.weight(1f)) { Field(d.nonFto, { v -> ed { d.nonFto = v } }, enabled = !locked) }
            }
            Labeled("Assignment or reason for no evaluation") { Field(d.assignment, { v -> ed { d.assignment = v } }, placeholder = "e.g. Day patrol, Car 12", enabled = !locked) }
            Toggle("Attachments", d.attach, { v -> ed { d.attach = v } }, enabled = !locked)
            Toggle("Not graded", d.ungraded, { on ->
                ed {
                    d.ungraded = on
                    if (on && d.assignment.isEmpty()) d.assignment = if (d.nonFto.isEmpty()) Rating.unratedReason else "Not graded – ride with non-FTO ${d.nonFto}"
                    if (!on && d.assignment.startsWith("Not graded")) d.assignment = ""
                }
            }, "Narrative only: no ratings, NRT or most/least. For a ride with a non-FTO or any shift that isn't evaluated.", enabled = !locked)
        }

        // Drafting
        if (!locked) Card {
            val newNotes = store.notes(d.tid).count { it.date == d.date && it.id !in d.used }
            if (newNotes > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$newNotes new note${if (newNotes > 1) "s" else ""} for this date since the DOR was built.", color = p.tx, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                SecondaryButton("Pull in", Modifier.width(96.dp)) { var n = 0; ed { n = DORLogic.pullNotes(store, d) }; toast("Pulled in $n note${if (n == 1) "" else "s"}") }
            }
            if (ClaudeAPI.hasKey) {
                AIButton(if (drafting) "Reading notes against the SEGs…" else "✦ Draft ${if (d.ungraded) "narrative" else "ratings & narrative"} with Claude", drafting) {
                    drafting = true; aiError = null
                    scope.launch {
                        try { val n = Drafting.draftDOR(store, id); toast("Claude drafted $n SEGs — check each ✦") }
                        catch (e: Exception) { aiError = e.message }
                        drafting = false
                    }
                }
                Hint(if (d.ungraded) "Writes a short narrative from this shift's notes, with SEG numbers where they fit. Nothing is graded. Anything you typed yourself is kept."
                     else "Suggests ratings and documentation from this shift's notes. It never sets NRT. Ratings and narratives you set yourself are kept on a redraft. Every ✦ is a suggestion until you check it.")
                aiError?.let { Text(it, color = p.neg, fontSize = 13.sp) }
            } else Hint("Add your Claude API key under Trainee → Claude drafting to draft DORs from your notes.")
        }
        if (!locked && issues.errors.isNotEmpty()) InfoBox("Required before final", issues.errors)
        if (!locked && issues.warnings.isNotEmpty()) InfoBox("Check", issues.warnings, danger = false)
        if (d.ai && !locked) InfoBox("✦ Claude drafted the items marked ✦", listOf("Confirm each one against what you saw."), danger = false)

        if (d.ungraded) {
            Card("Not graded") {
                Hint("This DOR is narrative only: the rating boxes on the form stay blank. Write the narrative below, and add a SEG narrative wherever you want a category # on the form.")
                Ref.segsFor(store.trainee(d.tid)).filter { d.comment(it.n).isNotBlank() || d.isRem(it.n) }.forEach { s ->
                    SEGRow(s, d, null, showPill = false) { nav.navigate("rate/$id/${s.n}") }
                }
                if (!locked) SecondaryButton("＋ Narrative for a SEG") { addSEG = true }
            }
        } else {
            RatingsCard(d)
            AreaCards(d, locked)
        }

        Card("Additional narrative / comments") {
            Hint(if (d.ungraded) "The main write-up for this ungraded shift. Each line goes in the form's narrative table."
                 else "Per-SEG narratives come from each rating. Use this for general notes; it goes in the form's narrative table without a category #.")
            NoteEditor(d.narrative, { v -> ed { d.narrative = v; d.manual.narrative = true } }, minHeight = 110.dp, enabled = !locked)
            Text(Ref.tips, fontSize = 10.5.sp, color = p.dim, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Labeled("Trainee comments") { NoteEditor(d.traineeComments, { v -> ed { d.traineeComments = v } }, "Hand the phone over at review, or type what they say", 70.dp, !locked) }
        }
        Card("Shift data") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Labeled("Calls", Modifier.weight(1f)) { Field(d.calls, { v -> ed { d.calls = v } }, placeholder = "0", number = true, enabled = !locked) }
                Labeled("MV stops", Modifier.weight(1f)) { Field(d.mv, { v -> ed { d.mv = v } }, placeholder = "0", number = true, enabled = !locked) }
                Labeled("Arrests", Modifier.weight(1f)) { Field(d.arrests, { v -> ed { d.arrests = v } }, placeholder = "0", number = true, enabled = !locked) }
                Labeled("Viols", Modifier.weight(1f)) { Field(d.viols, { v -> ed { d.viols = v } }, placeholder = "0", number = true, enabled = !locked) }
            }
            Toggle("Radio history attached", d.radio, { v -> ed { d.radio = v } }, enabled = !locked)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("Export FTEP-1", Modifier.weight(1f)) { nav.navigate("export/$id") }
            if (locked) SecondaryButton("Reopen", Modifier.weight(1f)) { ed { d.status = "draft" } }
            else SecondaryButton("Mark final", Modifier.weight(1f)) {
                if (issues.errors.isEmpty()) { ed { d.status = "final"; d.finalAt = System.currentTimeMillis() }; toast("DOR marked final") }
                else toast("Fix the items in red first")
            }
        }
        SecondaryButton("Delete this DOR", danger = true) { confirmDelete = true }
    }
    if (addSEG) SEGPicker(allowNone = false, onDismiss = { addSEG = false }) { n -> if (n != null) nav.navigate("rate/$id/$n") }
    if (confirmDelete) Confirm("Delete this DOR? Your notes are kept.", "Delete DOR", { confirmDelete = false }) { nav.popBackStack(); store.edit { dors.remove(id) } }
}

@Composable
private fun RatingsCard(d: DOR) {
    val p = pal(); val nav = LocalNav.current
    val prev = DORLogic.previous(App.store, d.tid, d.date)
    val segs = Ref.segsFor(App.store.trainee(d.tid))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.p1).border(1.dp, p.line, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
        for (g in Ref.groups) {
            val ss = segs.filter { it.g == g }; if (ss.isEmpty()) continue
            Box(Modifier.padding(top = 16.dp, bottom = 4.dp)) { GroupHeader(g) }
            for (s in ss) {
                Ref.subhead[s.n]?.let { Text(it, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = p.mu, modifier = Modifier.padding(top = 8.dp)) }
                SEGRow(s, d, prev?.rating(s.n)) { nav.navigate("rate/${d.id}/${s.n}") }
                HorizontalDivider(color = p.line)
            }
        }
    }
}

@Composable
fun SEGRow(s: SEG, d: DOR, prev: String?, showPill: Boolean = true, onClick: () -> Unit) {
    val p = pal()
    val r = d.rating(s.n); val c = d.comment(s.n)
    val missing = !d.ungraded && (Rating.needsNarrative(r) || d.isRem(s.n) || d.isNRT(s.n)) && c.isBlank()
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("${s.n}", fontFamily = Mono, fontSize = 12.sp, color = p.dim, modifier = Modifier.width(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(s.name, color = p.tx, fontSize = 15.sp, modifier = Modifier.weight(1f, fill = false))
                if (missing) Tag("narrative", TagStyle.Danger)
                if (d.isRem(s.n)) Tag("REM" + if (d.minutes(s.n).isEmpty()) "" else " ${d.minutes(s.n)}m", TagStyle.Quiet)
                if (d.isNRT(s.n) && !d.ungraded) Tag("NRT", TagStyle.Danger)
                if (s.n in d.aiCats) Tag("✦", TagStyle.Info)
            }
            if (c.isNotEmpty()) Text(c.lineSequence().first(), fontSize = 12.5.sp, color = p.mu, maxLines = 1, overflow = TextOverflow.Ellipsis)
            else if (prev != null && showPill) Text("prev $prev", fontFamily = Mono, fontSize = 11.sp, color = p.dim)
        }
        if (showPill) RatingPill(r) else Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = p.dim)
    }
}

@Composable
private fun AreaCards(d: DOR, locked: Boolean) {
    val store = App.store
    val failing = DORLogic.failing(d)
    val segItems = listOf<Pair<Int?, String>>(null to "— none —") + Ref.segsFor(store.trainee(d.tid)).map { it.n to "${it.n}. ${it.name}" }
    Card("Most acceptable area", badge = { Tag("mandatory") }) {
        MenuField(d.most.cat?.let { "$it. ${Ref.name(it)}" } ?: "— category # —", segItems, d.most.cat == null, !locked) { c -> store.edit { d.most.cat = c; d.manual.most = true } }
        NoteEditor(d.most.text, { v -> store.edit { d.most.text = v; d.manual.most = true } }, "A specific incident that demonstrates today's performance in this area", 80.dp, !locked)
    }
    Card("Least acceptable area", badge = { if (failing) Tag("mandatory – failing grade", TagStyle.Danger) else Tag("leave blank – all passing", TagStyle.Quiet) }) {
        MenuField(d.least.cat?.let { "$it. ${Ref.name(it)}" } ?: "— category # —", segItems, d.least.cat == null, !locked) { c -> store.edit { d.least.cat = c; d.manual.least = true } }
        NoteEditor(d.least.text, { v -> store.edit { d.least.text = v; d.manual.least = true } },
            if (failing) "A specific incident that demonstrates the failing performance" else "Only needed on shifts with a failing grade", 80.dp, !locked)
    }
}

// ---------- Rating page ----------

@Composable
fun RatingScreen(dorId: String, startN: Int) {
    val store = App.store; val nav = LocalNav.current; val p = pal(); val haptic = LocalHapticFeedback.current
    val d = store.data.dors[dorId]
    if (d == null) { Page("SEG") { Text("This DOR was deleted.", color = p.mu) }; return }
    var n by remember { mutableIntStateOf(startN) }
    var score by remember { mutableStateOf("") }
    val s = Ref.byN[n] ?: Ref.segs[0]
    val locked = d.isFinal
    val list = Ref.segsFor(store.trainee(d.tid)).map { it.n }
    val key = n.toString()

    fun setRating(x: String) {
        store.edit { d.ratings[key] = x; d.manual.ratings[key] = true; d.aiCats.remove(n) }
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    fun appendComment(line: String) { val cur = d.comment(n); store.edit { d.comments[key] = (if (cur.isEmpty()) "" else "$cur\n") + line; d.manual.comments[key] = true } }

    Page("${s.n}. ${s.name}") {
        Hint(s.d)
        if (d.ungraded) InfoBox("Not graded", listOf("Narrative and REM only."), danger = false)
        else {
            val r = d.rating(n)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Rating.numbers.forEach { x ->
                    val (fg, bg) = p.ratingColors(x)
                    Box(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(10.dp)).background(bg)
                        .border(if (r == x) 2.5.dp else 1.dp, if (r == x) p.hi else p.line, RoundedCornerShape(10.dp))
                        .clickable(enabled = !locked) { setRating(x) }, contentAlignment = Alignment.Center) {
                        Text(x, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = fg)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(10.dp)).background(p.p2)
                .border(if (r == "NO") 2.5.dp else 1.dp, if (r == "NO") p.hi else p.line, RoundedCornerShape(10.dp))
                .clickable(enabled = !locked) { setRating("NO") }, contentAlignment = Alignment.Center) {
                Text("NO · Not observed", color = p.tx, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            DORLogic.previous(store, d.tid, d.date)?.rating(n)?.takeIf { Rating.isNum(it) }?.let {
                Text("Previous DOR: $it", fontFamily = Mono, fontSize = 12.sp, color = p.dim, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End)
            }
        }
        // REM
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.p2).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Toggle("REM · remedial training given", d.isRem(n), { on -> store.edit { d.rem[key] = on; if (!on) d.time[key] = "" } }, enabled = !locked) }
            Spacer(Modifier.width(8.dp))
            Field(d.minutes(n), { v -> store.edit { d.time[key] = v.filter(Char::isDigit); if (v.isNotEmpty()) d.rem[key] = true } }, Modifier.width(72.dp), "min", number = true, center = true, enabled = !locked)
        }
        // NRT
        if (!d.ungraded) {
            val done = DORLogic.rtpCompleted(store, d.tid, n, d.date); val all = DORLogic.rtpCount(store, d.tid, n)
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.p2).padding(10.dp)) {
                Toggle("NRT · not responding to training", d.isNRT(n), { on -> store.edit { d.nrt[key] = on } },
                    "$done of 3 completed RTPs in this SEG" + if (all > done) " (${all - done} open)" else "", enabled = !locked)
            }
        }
        if (!locked) SecondaryButton("＋ Remedial Training Worksheet for #$n") {
            val r = RTP().apply { tid = d.tid; cat = n; date = d.date; phase = d.phase; week = ceil(maxOf(d.ride, 1) / 4.0).toInt().toString(); deficiency = d.comment(n) }
            store.edit { rtps[r.id] = r }
            nav.navigate("rtp/${r.id}")
        }
        if (s.test && !d.ungraded && !locked) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Field(score, { score = it }, Modifier.width(140.dp), "Test score %", decimal = true)
                SecondaryButton("Apply", Modifier.width(96.dp)) {
                    val v = score.toDoubleOrNull() ?: return@SecondaryButton
                    val sug = when { v < 50 -> "1"; v < 60 -> "2"; v < 70 -> "3"; v < 80 -> "4"; v < 86 -> "5"; v <= 90 -> "6"; else -> "7" }
                    setRating(sug)
                    appendComment("Scored $score% on ${s.name.replace(": Testing", "")} testing.")
                }
            }
            Text("<70% unacceptable · 70–90% acceptable · >90% superior", fontSize = 11.5.sp, color = p.mu)
        }
        Disclosure("SEG anchors (1 · 4 · 7)") { Anchors(s) }
        if (s.list.isNotEmpty() && !locked) Disclosure("Officer-safety violations observed (tap to add)") {
            s.list.forEachIndexed { i, v ->
                val letter = ('a' + i).toString()
                Text("$letter. $v", color = p.tx, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().clickable { appendComment("Unsafe practice ($letter): $v.") }.padding(vertical = 4.dp))
            }
        }
        Labeled("Narrative") {
            NoteEditor(d.comment(n), { v -> store.edit { d.comments[key] = v; d.manual.comments[key] = v.isNotBlank() } },
                "Set the stage · use quotes · include remedial · use SEG language · don't predict", 120.dp, !locked)
        }
        val notes = store.notes(d.tid).filter { it.date == d.date && it.cat == n }
        if (notes.isNotEmpty()) {
            GroupHeader("Your notes for this SEG this shift")
            notes.forEach { Text(DORLogic.noteLine(it), color = p.tx, fontSize = 13.5.sp) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val i = list.indexOf(n)
            SecondaryButton("‹ Prev", Modifier.weight(1f), enabled = i > 0) { if (i > 0) n = list[i - 1] }
            PrimaryButton("Done", Modifier.weight(1f)) { nav.popBackStack() }
            SecondaryButton("Next ›", Modifier.weight(1f), enabled = i in 0 until list.size - 1) { if (i < list.size - 1) n = list[i + 1] }
        }
    }
}

@Composable
fun Anchors(s: SEG) {
    val p = pal()
    listOf(Triple("1", s.a1, p.neg), Triple("4", s.a4, p.dim), Triple("7", s.a7, p.pos)).forEach { (n, t, c) ->
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(p.p2).height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(c))
            Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(n, fontFamily = Mono, fontWeight = FontWeight.Bold, color = p.tx, fontSize = 13.sp)
                Text(t, color = p.tx2, fontSize = 13.sp)
            }
        }
    }
}

// ---------- Export ----------

@Composable
fun ExportScreen(dorId: String) {
    val store = App.store; val ctx = LocalContext.current; val p = pal(); val toast = LocalToast.current
    val d = store.data.dors[dorId]
    if (d == null) { Page("Export") { Text("This DOR was deleted.", color = p.mu) }; return }
    var file by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(dorId) {
        try { file = withContext(Dispatchers.IO) { FormFiller.makeDocx(ctx, store, d) } }
        catch (e: Exception) { error = "Couldn't build the Word file: ${e.message}" }
    }
    val issues = DORLogic.validate(store, d)
    val text = DORLogic.text(store, d)
    Page("Export DOR # ${d.ride}") {
        if (issues.errors.isNotEmpty()) InfoBox("${issues.errors.size} required item${if (issues.errors.size > 1) "s" else ""} still open", listOf("You can export a draft, but fix them before signing."))
        val f = file
        when {
            f != null -> PrimaryButton("Share FTEP-1 Word form (.docx)") { shareFile(ctx, f, "application/vnd.openxmlformats-officedocument.wordprocessingml.document") }
            error != null -> Text(error!!, color = p.neg)
            else -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), color = p.tx, strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("Filling your FTEP-1 form…", color = p.mu) }
        }
        Hint("Your FTEP-1 DOR – Level II and Level III fillable form with every box filled in. Share it to Gmail, Drive or Word. For a PDF, open it in Word or Google Docs and use Print → Save as PDF.")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Copy text", Modifier.weight(1f)) { copyText(ctx, text); toast("Copied") }
            SecondaryButton("Share text", Modifier.weight(1f)) { shareText(ctx, text, "DOR # ${d.ride}") }
        }
        Disclosure("Preview text") {
            Text(text, fontFamily = Mono, fontSize = 11.sp, color = Color(0xFF111111),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFFFBFAF6)).padding(10.dp))
        }
    }
}
