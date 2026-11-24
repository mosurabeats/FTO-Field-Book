package com.mosurabeats.fieldbook

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsTab() {
    val store = App.store; val nav = LocalNav.current; val ctx = LocalContext.current; val act = LocalAct.current
    val p = pal(); val toast = LocalToast.current
    var keyText by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(ClaudeAPI.hasKey) }
    var segRef by remember { mutableStateOf<SEG?>(null) }
    val prof = store.profile
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
            val (n, d) = store.importBackup(text)
            toast("Imported $n notes and $d DORs")
        } catch (e: Exception) { toast(e.message ?: "Couldn't read that file") }
    }

    TabColumn {
        Card("Appearance") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeButton("k11", "Deck", listOf("1F2023", "2C2E32", "D8262E"))
                ThemeButton("k11s", "Silver", listOf("D6D7DA", "1F2023", "D8262E"))
                ThemeButton("auto", "Auto", listOf("D6D7DA", "1F2023"))
                ThemeButton("slate", "Slate", listOf("4E5C6A", "3C4753", "39B4F2"))
            }
            Hint("Deck and Silver use the 11K colours. Auto uses Silver by day and Deck when your phone is in dark mode. Slate is the 26K look.")
        }
        Card("Trainees") {
            if (prof.trainees.isEmpty()) Text("No trainees yet.", color = p.mu)
            prof.trainees.forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.weight(1f).clickable { store.edit { profile.active = t.id }; toast("Active trainee: ${t.name}") }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val on = prof.active == t.id
                        Icon(if (on) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked, null, tint = if (on) p.hi else p.mu)
                        Column {
                            Text(t.name, color = p.tx, fontWeight = FontWeight.SemiBold)
                            Text(listOfNotNull(if (t.badge.isEmpty()) null else "#${t.badge}", "Level ${t.level}", t.phase,
                                if (t.start.isEmpty()) null else "started ${DateUtil.us(t.start)}").joinToString(" · "), fontSize = 12.5.sp, color = p.mu)
                        }
                    }
                    TextButton({ nav.navigate("trainee/${t.id}") }) { Text("Edit", color = p.mu, fontWeight = FontWeight.SemiBold) }
                }
            }
            PrimaryButton("Add trainee") { nav.navigate("trainee/new") }
        }
        Card("You (FTO)") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Labeled("Name", Modifier.weight(1f)) { Field(prof.fto.name, { v -> store.edit { profile.fto.name = v } }) }
                Labeled("Badge", Modifier.width(90.dp)) { Field(prof.fto.badge, { v -> store.edit { profile.fto.badge = v } }) }
                Labeled("Initials", Modifier.width(76.dp)) { Field(prof.fto.initials, { v -> store.edit { profile.fto.initials = v } }, caps = true) }
            }
            Labeled("Agency") { Field(prof.agency, { v -> store.edit { profile.agency = v } }) }
        }
        Card("DOR defaults") {
            Toggle("Carry forward the last ratings", prof.carry, { v -> store.edit { profile.carry = v } },
                "New DORs start from the previous graded shift's scores instead of NO")
        }
        Card("Security") {
            Toggle("Lock with fingerprint or screen lock", Prefs.lockEnabled, { on -> App.lock.setEnabled(act, on) },
                "Asks for your fingerprint, face or phone PIN whenever FieldBook opens.")
        }
        Card("Claude drafting") {
            Hint("The ✦ buttons draft DOR narratives, remedial worksheets and phase evaluations from your notes using your own Anthropic API key (console: platform.claude.com → Settings → API keys). The key is encrypted on this phone, and notes are sent only when you tap a ✦ button. The trainee's name is never sent.")
            Field(keyText, { keyText = it.trim() }, placeholder = if (hasKey) "Key saved ✓ — paste a new one to replace" else "sk-ant-…", password = true)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("Save key", Modifier.weight(1f)) {
                    if (!keyText.startsWith("sk-ant-")) { toast("That doesn't look like an Anthropic API key"); return@PrimaryButton }
                    SecureStore.set("anthropicKey", keyText); keyText = ""; hasKey = true; toast("Key saved — ✦ drafting is on")
                }
                if (hasKey) SecondaryButton("Remove", Modifier.width(110.dp), danger = true) { SecureStore.set("anthropicKey", null); hasKey = false; toast("Key removed") }
            }
            Labeled("Model") {
                MenuField(if (Prefs.model == "claude-opus-5-5") "Opus 5.5 (best, ≈2×)" else "Sonnet 5.5 (≈$1/month)",
                    listOf("claude-sonnet-5-5" to "Sonnet 5.5 (≈$1/month)", "claude-opus-5-5" to "Opus 5.5 (best, ≈2×)")) { Prefs.model = it }
            }
        }
        Card("Backup") {
            Hint("Records are stored inside the app and included in your phone's Google backup. Export a copy anytime; it also imports into the iPhone and web versions, and their backups import here. Last export: ${Prefs.lastBackup?.let { DateUtil.us(it) } ?: "never"}.")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("Export backup", Modifier.weight(1f)) { shareFile(ctx, store.exportBackup(), "application/json") }
                SecondaryButton("Import", Modifier.weight(1f)) { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
            }
        }
        Card("Rules this app enforces") {
            listOf("Narrative required for 1, 2, 6, 7, REM and NRT", "NRT only after 3 completed RTPs in that SEG; score should be 1 or 2",
                "Most acceptable is always mandatory; least acceptable only when a failing grade (below 4) is given — otherwise left blank",
                "DOR # counts rides within the phase: Phase I is 24 rides (rides 1–8 limbo), Phases II and III are 16",
                "Not graded DORs are narrative only (non-FTO rides)", "Level III trainees: SEGs 33–34 (scope of practice) don't apply")
                .forEach { Text("• $it", fontSize = 13.5.sp, color = p.tx2) }
        }
        Card("SEG reference") {
            Ref.groups.forEach { g ->
                Disclosure(g) {
                    Ref.segs.filter { it.g == g }.forEach { s ->
                        Row(Modifier.fillMaxWidth().clickable { segRef = s }.padding(vertical = 6.dp)) {
                            Text("${s.n}", fontFamily = Mono, fontSize = 12.sp, color = p.dim, modifier = Modifier.width(24.dp))
                            Text(s.name + if (s.l2) " (not Level III)" else "", color = p.tx)
                        }
                    }
                }
            }
        }
        val ver = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).let { "${it.versionName} (${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(it)})" } }.getOrDefault("")
        Text("DORs are training records. Handle exports per department records policy. Case #s are enough; no victim or suspect names are needed here.\nFieldBook $ver",
            fontSize = 12.sp, color = p.mu, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
    segRef?.let { s -> SEGRefSheet(s) { segRef = null } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SEGRefSheet(s: SEG, onDismiss: () -> Unit) {
    val p = pal()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = p.p1) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 30.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${s.n}. ${s.name}", color = p.tx, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text(s.d, color = p.mu)
            Anchors(s)
            if (s.list.isNotEmpty()) {
                Text("Unacceptable practices", color = p.tx, fontWeight = FontWeight.Bold)
                s.list.forEachIndexed { i, v -> Text("${'a' + i}. $v", color = p.tx, fontSize = 13.5.sp) }
            }
        }
    }
}

@Composable
private fun RowScope.ThemeButton(k: String, label: String, colors: List<String>) {
    val p = pal(); val on = Prefs.theme == k
    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (on) p.hi.copy(alpha = .12f) else p.p2)
        .border(1.5.dp, if (on) p.hi else p.line, RoundedCornerShape(12.dp)).clickable { Prefs.theme = k }.padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(7.dp)).border(1.dp, p.line, RoundedCornerShape(7.dp))) {
            colors.forEach { Box(Modifier.weight(1f).fillMaxHeight().background(hex(it))) }
        }
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = p.tx)
    }
}

@Composable
fun TraineeEditorScreen(id: String?) {
    val store = App.store; val nav = LocalNav.current; val p = pal()
    var t by remember { mutableStateOf(id?.let { store.trainee(it)?.copy() } ?: Trainee()) }
    var confirm by remember { mutableStateOf(false) }
    fun upd(f: Trainee.() -> Unit) { t = t.copy().apply(f) }
    Page(if (id == null) "Add trainee" else "Edit trainee", actions = {
        TextButton({
            val x = t.copy().apply { name = name.trim() }
            if (x.name.isEmpty()) return@TextButton
            store.edit {
                val i = profile.trainees.indexOfFirst { it.id == x.id }
                if (i >= 0) profile.trainees[i] = x else { profile.trainees.add(x); profile.active = x.id }
            }
            nav.popBackStack()
        }, enabled = t.name.isNotBlank()) { Text("Save", color = if (t.name.isNotBlank()) p.hi2 else p.dim, fontWeight = FontWeight.Bold) }
    }) {
        Labeled("Name") { Field(t.name, { v -> upd { name = v } }, placeholder = "Officer First Last") }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            Labeled("Badge #", Modifier.weight(1f)) { Field(t.badge, { v -> upd { badge = v } }) }
            Labeled("Program start") { DateField(t.start) { v -> upd { start = v } } }
        }
        Labeled("Certification") { Segmented(listOf("II" to "Level II", "III" to "Level III"), t.level) { v -> upd { level = v } } }
        Hint("Level III hides SEGs 33–34 (scope of practice).")
        Labeled("Current phase") { Segmented(Phase.all.map { it to it.replace("Phase ", "") }, t.phase) { v -> upd { phase = v } } }
        Hint("Changing phase starts DOR numbering at ride 1 for the new phase.")
        Labeled("Usual shift hours") { Field(t.shift, { v -> upd { shift = v } }, placeholder = "e.g. 0700–1700") }
        if (id != null) SecondaryButton("Remove trainee", danger = true) { confirm = true }
    }
    if (confirm) Confirm("Remove ${t.name}? Their records stay in your backup data but are hidden here.", "Remove", { confirm = false }) {
        nav.popBackStack()
        store.edit {
            profile.trainees.removeAll { it.id == t.id }
            if (profile.active == t.id) profile.active = profile.trainees.firstOrNull()?.id
        }
    }
}
