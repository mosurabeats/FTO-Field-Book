package com.mosurabeats.fieldbook

/** DOR rules from the Montpelier / VCJTC FTEP manuals (same as the web and iPhone apps). */
object DORLogic {
    fun nextRide(store: Store, tid: String, phase: String, date: String, excluding: String? = null): Int =
        store.dors(tid).count { it.phase == phase && it.date <= date && it.id != excluding } + 1

    /** Last graded DOR before a date (ungraded shifts never carry ratings forward). */
    fun previous(store: Store, tid: String, before: String): DOR? =
        store.dors(tid).lastOrNull { it.date < before && !it.ungraded }

    fun noteLine(n: Note): String {
        var s = ""
        if (n.call.isNotEmpty()) s = n.call + (if (n.caseNo.isEmpty()) "" else " #${n.caseNo}") + " – "
        else if (n.caseNo.isNotEmpty()) s = "#${n.caseNo} – "
        s += n.text
        if (n.rem.isNotEmpty()) s += " [remedial ${n.rem} min]"
        return s.trim()
    }

    fun failing(d: DOR) = d.ratings.values.any { Rating.isNum(it) && (it.toIntOrNull() ?: 4) < 4 }

    fun build(store: Store, t: Trainee, date: String): String {
        store.dors(t.id).firstOrNull { it.date == date }?.let { return it.id }
        val d = DOR()
        d.tid = t.id; d.date = date; d.phase = t.phase
        d.ride = nextRide(store, t.id, t.phase, date)
        d.shiftHours = t.shift; d.fto = store.profile.fto.name
        val prev = previous(store, t.id, date)
        for (s in Ref.segsFor(t)) {
            val pr = prev?.rating(s.n) ?: "NO"
            d.ratings[s.key] = if (store.profile.carry && Rating.isNum(pr)) pr else "NO"
        }
        pullNotes(store, d)
        store.edit { dors[d.id] = d }
        return d.id
    }

    /** Copies the shift's not-yet-used notes into the DOR as a starting point. Caller wraps in store.edit when needed. */
    fun pullNotes(store: Store, d: DOR): Int {
        val ns = store.notes(d.tid).filter { it.date == d.date && it.id !in d.used }.reversed()
        val pos = mutableMapOf<Int, MutableList<Note>>(); val neg = mutableMapOf<Int, MutableList<Note>>()
        for (n in ns) {
            d.used.add(n.id)
            val k = n.cat
            if (k != null) {
                val key = k.toString()
                val cur = d.comments[key] ?: ""
                d.comments[key] = (if (cur.isEmpty()) "" else "$cur\n") + noteLine(n)
                if (!d.ungraded && !Rating.isNum(d.ratings[key] ?: "NO")) d.ratings[key] = if (n.tone == "-") "3" else "4"
                val m = n.rem.toIntOrNull()
                if (m != null && m > 0) { d.rem[key] = true; d.time[key] = ((d.time[key]?.toIntOrNull() ?: 0) + m).toString() }
                if (n.tone == "+") pos.getOrPut(k) { mutableListOf() }.add(n)
                if (n.tone == "-") neg.getOrPut(k) { mutableListOf() }.add(n)
            } else {
                d.narrative = (if (d.narrative.isEmpty()) "" else d.narrative + "\n") + noteLine(n)
            }
        }
        if (!d.ungraded) {
            if (d.most.cat == null) pos.maxByOrNull { it.value.size }?.let { d.most = AreaNote(it.key, it.value.joinToString(" ") { n -> noteLine(n) }) }
            if (d.least.cat == null) neg.maxByOrNull { it.value.size }?.let { d.least = AreaNote(it.key, it.value.joinToString(" ") { n -> noteLine(n) }) }
        }
        val day = store.notes(d.tid).filter { it.date == d.date }
        val callKeys = day.filter { it.call.isNotEmpty() || it.caseNo.isNotEmpty() }.map { if (it.caseNo.isEmpty()) it.call + "@" + it.id else it.caseNo }.toSet()
        val mvRe = Regex("mv|stop|traffic", RegexOption.IGNORE_CASE)
        val mvKeys = day.filter { mvRe.containsMatchIn(it.call) }.map { it.caseNo.ifEmpty { it.id } }.toSet()
        val arrests = day.filter { it.call.contains("arrest", ignoreCase = true) }.map { it.caseNo.ifEmpty { it.id } }.toSet()
        if (d.calls.isEmpty() && callKeys.isNotEmpty()) d.calls = callKeys.size.toString()
        if (d.mv.isEmpty() && mvKeys.isNotEmpty()) d.mv = mvKeys.size.toString()
        if (d.arrests.isEmpty() && arrests.isNotEmpty()) d.arrests = arrests.size.toString()
        return ns.size
    }

    fun rtpCompleted(store: Store, tid: String, cat: Int, on: String? = null): Int =
        store.data.rtps.values.count { it.tid == tid && it.cat == cat && it.completed == true && (on == null || it.date <= on) }

    fun rtpCount(store: Store, tid: String, cat: Int): Int = store.data.rtps.values.count { it.tid == tid && it.cat == cat }

    class Issues { val errors = mutableListOf<String>(); val warnings = mutableListOf<String>() }

    fun validate(store: Store, d: DOR): Issues {
        val v = Issues()
        val segs = Ref.segsFor(store.trainee(d.tid))
        if (d.ungraded) {
            val any = d.narrative.isNotBlank() || d.comments.values.any { it.isNotBlank() }
            if (!any) v.errors.add("An ungraded DOR still needs a narrative")
            for (s in segs) {
                if (d.isRem(s.n) && d.comment(s.n).isBlank()) v.errors.add("#${s.n} ${s.name}: REM needs narrative")
                if (d.isRem(s.n) && d.minutes(s.n).isEmpty()) v.warnings.add("#${s.n}: REM checked — add TIME (minutes)")
            }
            if (d.totalHours.isEmpty()) v.warnings.add("Total hours worked is blank")
            return v
        }
        for (s in segs) {
            val r = d.rating(s.n); val c = d.comment(s.n).trim()
            if ((Rating.needsNarrative(r) || d.isRem(s.n) || d.isNRT(s.n)) && c.isEmpty()) {
                val why = if (d.isNRT(s.n)) "NRT" else if (d.isRem(s.n)) "REM" else "rated $r"
                v.errors.add("#${s.n} ${s.name}: $why needs narrative")
            }
            if (d.isNRT(s.n)) {
                val k = rtpCompleted(store, d.tid, s.n, d.date)
                if (k < 3) v.errors.add("#${s.n}: NRT needs 3 completed RTPs in this SEG ($k logged)")
                if (r !in listOf("1", "2")) v.warnings.add("#${s.n}: NRT score should be a 1 or 2")
            }
            if (d.isRem(s.n) && d.minutes(s.n).isEmpty()) v.warnings.add("#${s.n}: REM checked — add TIME (minutes)")
        }
        if (d.most.cat == null || d.most.text.isBlank()) v.errors.add("Most acceptable area + specific incident is mandatory")
        val f = failing(d)
        if (f && (d.least.cat == null || d.least.text.isBlank())) v.errors.add("A failing grade was given: least acceptable area + incident is mandatory")
        if (!f && (d.least.cat != null || d.least.text.isNotBlank())) v.warnings.add("All ratings are passing: least acceptable should be left blank")
        if (d.totalHours.isEmpty()) v.warnings.add("Total hours worked is blank")
        if (d.nonFto.isNotBlank()) v.warnings.add("A non-FTO is listed: per the FTEP manual a non-FTO ride gets no evaluation — tick Not graded")
        return v
    }

    data class NarrativeRow(val cat: String, val text: String)

    fun narrativeRows(store: Store, d: DOR): List<NarrativeRow> {
        val rows = mutableListOf<NarrativeRow>()
        val ws = Regex("\\s*\\n+\\s*")
        for (s in Ref.segsFor(store.trainee(d.tid))) {
            val c = d.comment(s.n).trim(); val r = d.rating(s.n)
            if (c.isEmpty() || !(d.ungraded || Rating.isNum(r) || d.isRem(s.n) || d.isNRT(s.n))) continue
            var txt = c.replace(ws, " ")
            if (d.isRem(s.n) && d.minutes(s.n).isNotEmpty() && !c.contains("remedial", ignoreCase = true)) txt += " Remedial training: ${d.minutes(s.n)} min."
            rows.add(NarrativeRow(s.key, txt))
        }
        for (line in d.narrative.split("\n")) { val l = line.trim(); if (l.isNotEmpty()) rows.add(NarrativeRow("", l)) }
        return rows
    }

    // ---------- Weekly report ----------

    fun weeks(store: Store, tid: String): List<Pair<String, List<DOR>>> {
        val order = mutableListOf<String>(); val map = mutableMapOf<String, MutableList<DOR>>()
        for (d in store.dors(tid)) {
            val k = "${d.phase} · Week ${Phase.week(d.ride)}"
            if (k !in map) order.add(k)
            map.getOrPut(k) { mutableListOf() }.add(d)
        }
        return order.map { it to map[it]!! }
    }

    fun weekText(t: Trainee, ds: List<DOR>): String {
        val first = ds.firstOrNull() ?: return ""; val last = ds.last()
        fun sum(f: (DOR) -> String) = ds.sumOf { f(it).toIntOrNull() ?: 0 }
        val mins = sortedMapOf<Int, Int>(); val rem = mutableListOf<String>()
        for (d in ds) for ((k, on) in d.rem) if (on) {
            val n = k.toIntOrNull() ?: continue
            val tm = d.time[k] ?: ""
            mins[n] = (mins[n] ?: 0) + (tm.toIntOrNull() ?: 0)
            rem.add("${DateUtil.short(d.date)} #$n ${Ref.name(n)}${if (tm.isEmpty()) "" else " ($tm min)"}")
        }
        val L = mutableListOf("FTEP SUPERVISOR WEEKLY REPORT — DATA", "Recruit officer: ${t.name}${if (t.badge.isEmpty()) "" else " #" + t.badge}",
            "Training week: ${first.phase}, week ${Phase.week(first.ride)} · ${DateUtil.us(first.date)} to ${DateUtil.us(last.date)} · ${ds.size} DORs", "",
            "REMEDIAL TRAINING:")
        L += if (rem.isEmpty()) listOf("None this week") else rem
        L += listOf("", "MOST ACCEPTABLE:") + ds.map { "${DateUtil.short(it.date)}: ${it.most.cat?.let { c -> "#$c ${Ref.name(c)}" } ?: if (it.ungraded) "— (not graded)" else "—"}" }
        L += listOf("", "LEAST ACCEPTABLE:") + ds.map { "${DateUtil.short(it.date)}: ${it.least.cat?.let { c -> "#$c ${Ref.name(c)}" } ?: "— (all passing)"}" }
        L += listOf("", "DOCUMENTED TRAINING MINUTES:") + (if (mins.isEmpty()) listOf("None") else mins.map { "#${it.key} ${Ref.name(it.key)}: ${it.value}" })
        L += listOf("", "STATS:", "Calls for Service: ${sum { it.calls }}", "MV Stops: ${sum { it.mv }}", "Arrests: ${sum { it.arrests }}", "Violations: ${sum { it.viols }}")
        return L.joinToString("\n")
    }

    // ---------- Plain text ----------

    fun text(store: Store, d: DOR): String {
        val t = store.trainee(d.tid) ?: Trainee(); val p = store.profile
        val line = "─".repeat(40)
        val L = mutableListOf("DOR # ${d.ride}   DAILY OBSERVATION REPORT – LEVEL II and LEVEL III")
        if (p.agency.isNotEmpty()) L.add(p.agency)
        L += listOf(line, "Date: ${DateUtil.us(d.date)}   Shift: ${d.shiftHours.ifEmpty { "—" }}   Total hrs: ${d.totalHours.ifEmpty { "—" }}",
            "Trainee: ${t.name}${if (t.badge.isEmpty()) "" else " #" + t.badge}",
            "FTO: ${d.fto}${if (d.nonFto.isEmpty()) "" else "   Non-FTO: " + d.nonFto}", Phase.label(d.phase, d.ride))
        if (d.assignment.isNotEmpty()) L.add("Assignment: ${d.assignment}")
        L += listOf(line, if (d.ungraded) "NOT GRADED" else "#  CATEGORY                       RATE REM TIME NRT")
        if (!d.ungraded) {
            for (g in Ref.groups) {
                val ss = Ref.segsFor(t).filter { it.g == g }; if (ss.isEmpty()) continue
                L.add(g.uppercase())
                for (s in ss) {
                    val nm = (s.name + ".".repeat(30)).take(30)
                    L.add("${s.n.toString().padStart(2)} $nm ${d.rating(s.n).padEnd(3)} ${if (d.isRem(s.n)) "X" else " "}  ${d.minutes(s.n).padEnd(4)} ${if (d.isNRT(s.n)) "X" else ""}")
                }
            }
            L += listOf(line, "MOST ACCEPTABLE: ${d.most.cat?.let { "#$it ${Ref.name(it)}" } ?: "—"}")
            if (d.most.text.isNotEmpty()) L.add(d.most.text)
            L += listOf("", "LEAST ACCEPTABLE: ${d.least.cat?.let { "#$it ${Ref.name(it)}" } ?: "— (all ratings passing)"}")
            if (d.least.text.isNotEmpty()) L.add(d.least.text)
            L.add("")
        }
        L.add("DOCUMENTATION OF PERFORMANCE AND COMMENTS")
        for (r in narrativeRows(store, d)) L.add("${if (r.cat.isEmpty()) "  -" else "#" + r.cat}  ${r.text}")
        L.add("")
        if (d.traineeComments.isNotEmpty()) L += listOf("TRAINEE COMMENTS:", d.traineeComments, "")
        L.add("SHIFT DATA  Calls: ${d.calls.ifEmpty { "0" }}  MV stops: ${d.mv.ifEmpty { "0" }}  Arrests: ${d.arrests.ifEmpty { "0" }}  Viols: ${d.viols.ifEmpty { "0" }}  Radio history: ${if (d.radio) "Yes" else "No"}")
        L += listOf(line, "Trainee Signature ____________________ Date ______", "FTO / Non-FTO Signature ______________ Date ______")
        return L.joinToString("\n")
    }

    fun rtpText(store: Store, r: RTP): String {
        val t = store.trainee(r.tid) ?: Trainee()
        fun yn(v: Boolean?) = when (v) { true -> "Yes [X]  No [ ]"; false -> "Yes [ ]  No [X]"; null -> "Yes [ ]  No [ ]" }
        return listOf("FIELD TRAINING AND EVALUATION PROGRAM — REMEDIAL TRAINING WORKSHEET", store.profile.agency, "",
            "Trainee: ${t.name}${if (t.badge.isEmpty()) "" else " #" + t.badge}    Date: ${DateUtil.us(r.date)}    Training Week #: ${r.week}",
            "Area: ${r.cat?.let { "#$it ${Ref.name(it)}" } ?: ""}", "Complete assignment by: ${DateUtil.us(r.due)}", "",
            "PERFORMANCE DEFICIENCIES:", r.deficiency, "", "TRAINING ASSIGNMENT:", r.assignment, "",
            "Trainee Signature: ____________________   FTO Signature: ____________________", "",
            "ASSIGNMENT COMPLETION:",
            "1. Has the trainee satisfactorily completed the training plan?  ${yn(r.completed)}",
            "2. Is the trainee now performing at a competent level?  ${yn(r.competent)}",
            "3. Has an additional assignment been given?  ${yn(r.additional)}", "",
            "COMMENTS:", r.comments, "",
            "Trainee Signature: ____________________  Date: ________", "FTO Signature: ________________________  Date: ________",
            "FTEP Supervisor Signature: _____________  Date: ________").joinToString("\n")
    }

    fun checklistText(t: Trainee, c: Checks): String {
        val L = mutableListOf("STANDARD TASK CHECKLIST — ${t.name}", "Legend: D=Discussed  Dm=Demonstrated  P=Performed (dates)", "")
        fun f(v: String?) = v?.let { DateUtil.us(it) } ?: "—"
        for (s in Ref.checklist) {
            L.add("${s.n}. ${s.t.uppercase()}")
            for (i in s.items) {
                val ph = i.ph
                if (ph == null) { L.add("   " + i.t); continue }
                val m = c.items[i.id] ?: CheckMark()
                L.add("${"  ".repeat(i.lvl + 1)}[${Ref.phaseLabel[ph] ?: ph}] ${i.t} — D:${f(m.d)} Dm:${f(m.dm)} P:${f(m.p)}")
            }
            L.add("")
        }
        return L.joinToString("\n")
    }
}
