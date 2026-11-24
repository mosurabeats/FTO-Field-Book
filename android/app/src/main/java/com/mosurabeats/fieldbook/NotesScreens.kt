package com.mosurabeats.fieldbook

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The note being typed survives switching tabs. */
object Draft {
    var text by mutableStateOf("")
    var tone by mutableStateOf("0")
    var cat by mutableStateOf<Int?>(null)
    var call by mutableStateOf("")
    var caseNo by mutableStateOf("")
    var date by mutableStateOf(DateUtil.today())
    var time by mutableStateOf("")
    var rem by mutableStateOf("")
}

@Composable
fun NotesTab(goDORs: () -> Unit) {
    val store = App.store
    val t = store.trainee ?: return Onboarding()
    val p = pal(); val nav = LocalNav.current; val toast = LocalToast.current
    val haptic = LocalHapticFeedback.current; val focus = LocalFocusManager.current
    var pickCat by remember { mutableStateOf(false) }
    var pickFilter by remember { mutableStateOf(false) }
    var filterCat by remember { mutableStateOf<Int?>(null) }
    var todayOnly by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }

    var list = store.notes(t.id)
    filterCat?.let { c -> list = list.filter { it.cat == c } }
    if (todayOnly) list = list.filter { it.date == DateUtil.today() }
    val days = list.groupBy { it.date }
    val keys = days.keys.sortedDescending()
    val dors = store.dors(t.id)

    fun save() {
        val txt = Draft.text.trim()
        if (txt.isEmpty()) { toast("Type or dictate a note first"); return }
        val n = Note(tid = t.id, date = Draft.date, time = Draft.time.trim(), cat = Draft.cat, tone = Draft.tone, text = txt,
            call = Draft.call.trim(), caseNo = Draft.caseNo.trim(), rem = Draft.rem.trim())
        store.edit { notes[n.id] = n }
        Draft.text = ""; Draft.tone = "0"; Draft.cat = null; Draft.time = ""; Draft.rem = ""; Draft.date = DateUtil.today()
        focus.clearFocus()
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        toast("Logged" + (n.cat?.let { " to #$it" } ?: ""))
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Card {
                NoteEditor(Draft.text, { Draft.text = it }, "What happened? e.g. 'Stood in front of driver's door on MV stop – corrected on scene'", 92.dp)
                ToneSelector(Draft.tone) { Draft.tone = it }
                Labeled("SEG category") {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.p2).border(1.dp, p.line, RoundedCornerShape(10.dp))
                        .clickable { pickCat = true }.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        val c = Draft.cat
                        if (c != null) { Text("$c", fontFamily = Mono, color = p.hi2, fontWeight = FontWeight.SemiBold); Spacer(Modifier.width(8.dp)); Text(Ref.name(c), color = p.tx, modifier = Modifier.weight(1f)) }
                        else Text("Tap to tag a SEG (optional)", color = p.dim, modifier = Modifier.weight(1f))
                        Icon(Icons.Default.KeyboardArrowDown, null, tint = p.mu)
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    recentCats(t).forEach { c -> Chip("$c · ${Ref.name(c)}", Draft.cat == c) { Draft.cat = if (Draft.cat == c) null else c } }
                }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.p2)
                    .border(1.dp, if (Draft.rem.isEmpty()) p.line else p.hi, RoundedCornerShape(10.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Remedial training given", color = p.tx, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("Marks REM + TIME on the DOR", color = p.mu, fontSize = 12.5.sp)
                    }
                    Field(Draft.rem, { Draft.rem = it.filter(Char::isDigit) }, Modifier.width(76.dp), "min", number = true, center = true)
                }
                Disclosure("Call, case #, date & time", details) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Ref.calls.forEach { c -> Chip(c, Draft.call == c) { Draft.call = if (Draft.call == c) "" else c } }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field(Draft.call, { Draft.call = it }, Modifier.weight(1f), "Call type")
                        Field(Draft.caseNo, { Draft.caseNo = it }, Modifier.weight(1f), "Case / incident #")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        DateField(Draft.date) { Draft.date = it }
                        Field(Draft.time, { Draft.time = it }, Modifier.weight(1f), "Time (optional)", number = true, mono = true)
                    }
                }
                PrimaryButton("Log note") { save() }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Segmented(listOf(false to "All", true to "Today"), todayOnly, Modifier.width(150.dp)) { todayOnly = it }
                Spacer(Modifier.weight(1f))
                val fc = filterCat
                if (fc != null) Chip("$fc · ${Ref.name(fc)} ✕", true) { filterCat = null } else Chip("Filter by SEG ▾") { pickFilter = true }
            }
        }
        if (keys.isEmpty()) item {
            Text("No notes yet. Log what you see on each call and they'll go onto the DOR automatically.", color = p.mu,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 30.dp))
        }
        for (day in keys) {
            val ns = days[day]!!
            item(key = "h$day") {
                val dor = dors.firstOrNull { it.date == day }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${DateUtil.pretty(day)} · ${ns.size}".uppercase(), fontSize = 12.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, color = p.mu, modifier = Modifier.weight(1f))
                    if (dor != null) Text("DOR ✓", color = p.mu, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.clickable { nav.navigate("dor/${dor.id}") }.padding(4.dp))
                    else Text("Build DOR →", color = p.hi2, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                        modifier = Modifier.clickable { nav.navigate("dor/" + DORLogic.build(store, t, day)) }.padding(4.dp))
                }
            }
            items(ns, key = { it.id }) { n -> NoteRow(n) { nav.navigate("note/${n.id}") } }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
    if (pickCat) SEGPicker(allowNone = true, onDismiss = { pickCat = false }) { Draft.cat = it }
    if (pickFilter) SEGPicker(allowNone = false, onDismiss = { pickFilter = false }) { filterCat = it }
}

private fun recentCats(t: Trainee): List<Int> {
    val count = mutableMapOf<Int, Int>()
    App.store.notes(t.id).take(80).forEach { n -> n.cat?.let { count[it] = (count[it] ?: 0) + 1 } }
    val top = count.entries.sortedByDescending { it.value }.map { it.key }.toMutableList()
    for (n in listOf(4, 11, 22, 19, 12, 37, 5, 9, 35)) if (n !in top) top.add(n)
    val allowed = Ref.segsFor(t).map { it.n }.toSet()
    return top.take(9).filter { it in allowed }
}

@Composable
fun ToneSelector(tone: String, onChange: (String) -> Unit) {
    val p = pal()
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(p.p2).border(1.dp, p.line, RoundedCornerShape(11.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        listOf(Triple("＋ Did well", "+", p.pos), Triple("• Observed", "0", p.tx), Triple("－ Needs work", "-", p.neg)).forEach { (label, v, c) ->
            val on = tone == v
            Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (on) (if (v == "0") p.p3 else c.copy(alpha = .16f)) else Color.Transparent)
                .clickable { onChange(v) }.padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                Text(label, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = if (on) c else p.mu, maxLines = 1)
            }
        }
    }
}

@Composable
fun NoteRow(n: Note, onClick: () -> Unit) {
    val p = pal()
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.p1).border(1.dp, p.line, RoundedCornerShape(12.dp))
        .clickable(onClick = onClick).padding(12.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.width(4.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(if (n.tone == "+") p.pos else if (n.tone == "-") p.neg else p.dim))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (n.time.isNotEmpty()) Text(n.time, fontFamily = Mono, fontSize = 11.5.sp, color = p.mu)
                val c = n.cat
                if (c != null) Text("$c · ${Ref.name(c)}", color = p.hi2, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                else Text("untagged", color = p.mu, fontSize = 12.sp)
                if (n.call.isNotEmpty()) Text(n.call, color = p.mu, fontSize = 12.sp, maxLines = 1)
                if (n.caseNo.isNotEmpty()) Text("#" + n.caseNo, fontFamily = Mono, color = p.mu, fontSize = 11.5.sp, maxLines = 1)
                if (n.rem.isNotEmpty()) Tag("REM ${n.rem}m", TagStyle.Quiet)
            }
            Text(n.text, color = p.tx, fontSize = 15.sp)
        }
    }
}

@Composable
fun NoteEditScreen(id: String) {
    val store = App.store; val nav = LocalNav.current
    val orig = store.data.notes[id]
    if (orig == null) { Page("Note") { Text("This note was deleted.", color = pal().mu) }; return }
    var n by remember { mutableStateOf(orig.copy()) }
    var pick by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val p = pal()
    fun upd(f: Note.() -> Unit) { n = n.copy().apply(f) }
    Page("Edit note", actions = {
        TextButton({ val x = n.copy().apply { text = text.trim() }; store.edit { notes[x.id] = x }; nav.popBackStack() }) { Text("Save", color = p.hi2, fontWeight = FontWeight.Bold) }
    }) {
        NoteEditor(n.text, { v -> upd { text = v } }, minHeight = 110.dp)
        ToneSelector(n.tone) { v -> upd { tone = v } }
        Labeled("SEG") { PickRow(n.cat?.let { "$it · ${Ref.name(it)}" } ?: "No category", n.cat == null) { pick = true } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(n.call, { v -> upd { call = v } }, Modifier.weight(1f), "Call type")
            Field(n.caseNo, { v -> upd { caseNo = v } }, Modifier.weight(1f), "Case #")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            DateField(n.date) { v -> upd { date = v } }
            Field(n.time, { v -> upd { time = v } }, Modifier.weight(1f), "Time", number = true)
            Field(n.rem, { v -> upd { rem = v.filter(Char::isDigit) } }, Modifier.weight(1f), "REM min", number = true)
        }
        SecondaryButton("Delete note", danger = true) { confirm = true }
    }
    if (pick) SEGPicker(allowNone = true, onDismiss = { pick = false }) { c -> upd { cat = c } }
    if (confirm) Confirm("Delete this note?", "Delete", { confirm = false }) { store.edit { notes.remove(id) }; nav.popBackStack() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SEGPicker(allowNone: Boolean, onDismiss: () -> Unit, pick: (Int?) -> Unit) {
    val p = pal()
    var q by remember { mutableStateOf("") }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = p.bg) {
        Column(Modifier.padding(horizontal = 14.dp)) {
            Text("SEG category", color = p.tx, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(bottom = 8.dp))
            Field(q, { q = it }, placeholder = "Search (radio, safety, report)")
        }
        LazyColumn(Modifier.fillMaxWidth().imePadding(), contentPadding = PaddingValues(14.dp)) {
            if (allowNone) item {
                Text("No category", color = p.mu, modifier = Modifier.fillMaxWidth().clickable { pick(null); onDismiss() }.padding(vertical = 12.dp))
            }
            for (g in Ref.groups) {
                val ss = Ref.segsFor(App.store.trainee).filter { it.g == g && (q.isEmpty() || "${it.n} ${it.name} ${it.d}".contains(q, ignoreCase = true)) }
                if (ss.isEmpty()) continue
                item(key = g) { Box(Modifier.padding(top = 12.dp, bottom = 4.dp)) { GroupHeader(g) } }
                items(ss, key = { it.n }) { s ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { pick(s.n); onDismiss() }.padding(vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("${s.n}", fontFamily = Mono, color = p.dim, fontSize = 12.sp, modifier = Modifier.width(24.dp))
                        Column {
                            Text(s.name, color = p.tx, fontSize = 15.sp)
                            Text(s.d, color = p.mu, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(30.dp)) }
        }
    }
}
