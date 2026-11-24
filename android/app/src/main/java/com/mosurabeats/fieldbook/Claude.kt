package com.mosurabeats.fieldbook

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class ClaudeError(msg: String) : Exception(msg)

/** Talks to the Claude API with the user's own key (encrypted with the Android Keystore). */
object ClaudeAPI {
    val key: String? get() = SecureStore.get("anthropicKey")
    val hasKey get() = !key.isNullOrEmpty()

    suspend fun complete(prompt: String, maxTokens: Int = 4096): String = withContext(Dispatchers.IO) {
        val k = key ?: throw ClaudeError("Add your API key under Trainee → Claude drafting.")
        val body = JSONObject().put("model", Prefs.model).put("max_tokens", maxTokens)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        val conn = (URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 20000; readTimeout = 120000; doOutput = true
            setRequestProperty("content-type", "application/json")
            setRequestProperty("x-api-key", k)
            setRequestProperty("anthropic-version", "2023-06-01")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
            when {
                code == 401 || code == 403 -> throw ClaudeError("Your API key was rejected. Check it under Trainee → Claude drafting.")
                code == 429 || code == 529 -> throw ClaudeError("Claude is busy right now. Try again in a minute.")
                code != 200 -> throw ClaudeError("Claude couldn't draft this: ${text.take(160)}")
            }
            val content = runCatching { JSONObject(text).getJSONArray("content") }.getOrNull() ?: throw ClaudeError("Claude's answer couldn't be read. Try again.")
            (0 until content.length()).mapNotNull { content.optJSONObject(it) }.filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }
        } catch (e: IOException) {
            throw ClaudeError("No connection. Try again when you have signal.")
        } finally { conn.disconnect() }
    }

    suspend fun completeJSON(prompt: String): JSONObject {
        val t = complete(prompt)
        val s = t.indexOf('{'); val e = t.lastIndexOf('}')
        if (s < 0 || e <= s) throw ClaudeError("Claude's answer couldn't be read. Try again.")
        return runCatching { JSONObject(t.substring(s, e + 1)) }.getOrElse { throw ClaudeError("Claude's answer couldn't be read. Try again.") }
    }
}

object Drafting {
    private const val voice = "Write in the FIRST PERSON as the FTO (\"I observed\", \"I instructed\", \"I corrected\"). Refer to the trainee only as \"Trainee\" or \"the trainee\": never by name."

    private fun program(store: Store, t: Trainee) =
        "the ${store.profile.agency.ifEmpty { "police department" }} Field Training & Evaluation Program (Vermont Criminal Justice Council, Level ${t.level} officer)"

    private fun segBrief(t: Trainee) = Ref.segsFor(t).joinToString("\n") { "#${it.n} ${it.name}: ${it.d} | 1=${it.a1} | 4=${it.a4} | 7=${it.a7}" }

    private fun notesBlock(ns: List<Note>) = ns.joinToString("\n") {
        "- tone ${it.tone} | SEG ${it.cat?.toString() ?: "untagged"} | ${it.call} ${if (it.caseNo.isEmpty()) "" else "#" + it.caseNo} | rem ${it.rem.ifEmpty { "0" }} | ${it.text}"
    }

    private fun json(m: Map<String, Any?>) = JSONObject().also { o -> m.toSortedMap().forEach { (k, v) -> o.put(k, v ?: JSONObject.NULL) } }.toString()
    private fun jsonList(l: List<Any?>) = JSONArray(l.map { it ?: JSONObject.NULL }).toString()

    private fun keyOf(v: Any?): String? = when (v) { is Number -> v.toInt().toString(); is String -> v; else -> null }

    /** Drafts a DOR from the shift's notes. Ratings and text the FTO set by hand are kept. Returns SEGs drafted. */
    suspend fun draftDOR(store: Store, dorID: String): Int {
        val d0 = store.data.dors[dorID] ?: return 0
        val t = store.trainee(d0.tid) ?: return 0
        val ns = store.notes(d0.tid).filter { it.date == d0.date }.reversed()
        if (ns.isEmpty()) throw ClaudeError("There are no notes on this date to draft from.")
        val segKeys = Ref.segsFor(t).map { it.key }.toSet()
        val fixedR = d0.manual.ratings.filter { it.value }.keys.mapNotNull { k -> d0.ratings[k]?.let { k to it } }.toMap()
        val fixedC = d0.manual.comments.filter { it.value }.keys.mapNotNull { k -> d0.comments[k]?.takeIf { it.isNotBlank() }?.let { k to it } }.toMap()
        val keepMost = d0.manual.most && d0.most.cat != null
        val keepLeast = d0.manual.least
        val keepNarr = d0.manual.narrative && d0.narrative.isNotBlank()

        val prompt = if (d0.ungraded) """
You help a Field Training Officer write a Daily Observation Report (FTEP-1 DOR) for ${program(store, t)}. This shift is NOT GRADED (for example a ride with a non-FTO officer, or an orientation shift): nothing is evaluated. Do not give ratings, scores, NRT, or most/least acceptable areas, and do not judge performance against the standard.
$voice
STYLE: concise, factual, past tense. One or two short sentences per item; no filler. Never write about the notes or the documentation themselves. Never state clock times or imply a time sequence unless a note's own text gives it. Cite call type/case # when given. Never invent facts.
SEG categories (use only to label an item with the best-fitting category #): ${Ref.segsFor(t).joinToString("; ") { "#${it.n} ${it.name}" }}
${d0.phase}, DOR #${d0.ride}.
${if (fixedC.isEmpty()) "" else "SEG narratives the FTO has written (final, do not return them): ${jsonList(fixedC.keys.toList())}"}${if (keepNarr) " The FTO has written the general narrative: return \"narrative\" as an empty string." else ""}
FTO field notes for the ${d0.date} shift, in no guaranteed order (tone: + did well, - needs work, 0 observed; "rem" = remedial training minutes):
${notesBlock(ns)}
Task: turn the notes into the shift narrative. Put each observation that clearly belongs to a SEG under that SEG # (what I showed, discussed or had the trainee do, and how the trainee did). Put orientation activity and anything without a clear SEG in "narrative", one item per line. Report remedial minutes only where a note records remedial training.
Return ONLY JSON: {"comments":{"<seg#>":"..."},"rem":{"<seg#>":<minutes>},"narrative":"..."}
""".trim() else {
            val prev = DORLogic.previous(store, d0.tid, d0.date)
            """
You help a Field Training Officer complete a Daily Observation Report (FTEP-1 DOR) for ${program(store, t)}.
Rating scale: 1 totally unacceptable; 2 a little better than 1; 3 close to 4 but not yet minimally acceptable; 4 minimum acceptable, able to work solo in a safe, skillful, productive and professional manner; 5-6 above acceptable; 7 superior; "NO" not observed.
Narrative documentation is REQUIRED for ratings of 1, 2, 6, 7 and any remedial training (REM). Never assign NRT.
DOR writing standards: SET THE STAGE, USE QUOTES (only quotes present in the notes), USE LISTS, INCLUDE REMEDIAL, FRONT = BACK (ratings must match the narrative), USE SEG language, REMEMBER YOUR AUDIENCE (supervisor, trainee, possibly a review board), DO NOT PREDICT future performance.
$voice
Standardized Evaluation Guidelines (category: description | 1 | 4 | 7):
${segBrief(t)}

${d0.phase}, DOR #${d0.ride}${if (d0.phase == "Phase I" && d0.ride <= 8) " (limbo/orientation weeks)" else ""}.
${prev?.let { "Previous DOR ratings: ${json(it.ratings)}" } ?: "This is the first DOR."}
RATINGS THE FTO HAS SET (final, do not change them; every narrative you write for these SEGs must support exactly this rating): ${json(fixedR)}
NARRATIVES THE FTO HAS WRITTEN (final, do not rewrite or return them): ${jsonList(fixedC.keys.toList())}
${if (keepMost) "The FTO has already written the most acceptable area: do not return \"most\"." else ""}${if (keepLeast) " The FTO has already written the least acceptable area: do not return \"least\"." else ""}${if (keepNarr) " The FTO has written the general narrative: do not return \"narrative\"." else ""}

FTO field notes for the ${d0.date} shift, in no guaranteed order (tone: + did well, - needs work, 0 observed; "rem" = remedial training minutes the FTO logged). Notes are often written after the fact, so they carry no reliable times:
${notesBlock(ns)}

Task: For each SEG the notes give evidence for (assign untagged notes to the best-fitting SEG; one note may support several), give a rating anchored to the SEG language (use the FTO's rating where one is set) and write the narrative from the note: objective, behaviour-based, past tense, first person, cite call type/case # when given, and say what I corrected and how the trainee responded if the note says so. Never invent facts. Ratings of 1, 2, 6, 7 must be justified by the specific behaviour in the note.
STYLE: concise. One or two short sentences per SEG; no filler, no restating the SEG definition, no hedging. Never write about the notes or the documentation themselves (no "I did not document", "no notes were taken", "not observed in notes", "it should be noted").
SEGs with an FTO-set rating but NO note that relates to them: do not explain the gap. If the rating is below 4, the narrative is exactly: "Trainee did not meet the minimum acceptable standard." If the rating is 6 or 7, it is exactly: "Trainee exceeded the minimum acceptable standard." For any other rating, return no narrative for that SEG.
Only report remedial minutes ("rem") for SEGs where the notes explicitly record remedial training. Pick the most acceptable area (SEG # + the specific incident, one or two sentences). Give a least acceptable area ONLY if a final rating (yours or the FTO's) is below 4, in one or two sentences; otherwise return null for it. Put general observations that fit no SEG in "narrative", at most three sentences, or return an empty string if there are none. Never state clock times or imply a time sequence (no "at 0942", "first", "later in the shift") unless a note's own text gives it.
Return ONLY JSON: {"ratings":{"<seg#>":"1".."7"|"NO"},"comments":{"<seg#>":"..."},"rem":{"<seg#>":<minutes>},"most":{"cat":<seg#>,"text":"..."},"least":{"cat":<seg#>,"text":"..."}|null,"narrative":"..."}
""".trim()
        }

        val out = ClaudeAPI.completeJSON(prompt)
        var drafted = 0
        withContext(Dispatchers.Main) {
            // Re-read in case the FTO edited while Claude was working.
            val d = store.data.dors[dorID] ?: return@withContext
            val aiCats = mutableListOf<Int>()
            store.edit {
                if (!d.ungraded) out.optJSONObject("ratings")?.let { r ->
                    for (k in r.keys()) {
                        val s = keyOf(r.opt(k)) ?: continue
                        if (k in segKeys && (Rating.isNum(s) || s == "NO") && d.manual.ratings[k] != true) { d.ratings[k] = s; aiCats.add(k.toInt()) }
                    }
                }
                for (k in segKeys) if (k !in fixedC) d.comments[k] = ""
                out.optJSONObject("comments")?.let { c ->
                    for (k in c.keys()) {
                        val s = c.opt(k) as? String ?: continue
                        if (k in segKeys && k !in fixedC && s.isNotEmpty()) { d.comments[k] = s; if (k.toInt() !in aiCats) aiCats.add(k.toInt()) }
                    }
                }
                out.optJSONObject("rem")?.let { rm ->
                    for (k in rm.keys()) {
                        val m = keyOf(rm.opt(k))?.toDoubleOrNull()?.toInt() ?: continue
                        if (k in segKeys && m > 0 && (d.time[k] ?: "").isEmpty()) { d.rem[k] = true; d.time[k] = m.toString() }
                    }
                }
                if (!d.ungraded) {
                    if (!keepMost) out.optJSONObject("most")?.let { m ->
                        val c = keyOf(m.opt("cat")); if (c != null && c in segKeys) d.most = AreaNote(c.toInt(), m.optString("text"))
                    }
                    if (!keepLeast) {
                        val l = out.optJSONObject("least"); val c = l?.let { keyOf(it.opt("cat")) }
                        if (l != null && c != null && c in segKeys) d.least = AreaNote(c.toInt(), l.optString("text"))
                        else if (!DORLogic.failing(d)) d.least = AreaNote()
                    }
                }
                if (!keepNarr) d.narrative = (out.opt("narrative") as? String ?: "").trim()
                d.ai = true; d.aiCats = aiCats
                for (n in ns) if (n.id !in d.used) d.used.add(n.id)
            }
            drafted = aiCats.size
        }
        return drafted
    }

    suspend fun draftRTP(store: Store, rtpID: String) {
        val r0 = store.data.rtps[rtpID] ?: return
        val cat = r0.cat ?: return
        val s = Ref.byN[cat] ?: return
        val t = store.trainee(r0.tid) ?: return
        val ds = JSONArray(store.dors(r0.tid).filter { it.date <= r0.date }.takeLast(6).map {
            JSONObject().put("date", it.date).put("narrative", it.comment(cat)).put("rating", it.rating(cat)).put("rem", it.isRem(cat)).put("time", it.minutes(cat))
        }).toString()
        val ns = store.notes(r0.tid).filter { it.cat == cat }.take(25).joinToString(" || ") { "${it.date} ${it.tone} ${it.text}" }
        val prior = JSONArray(store.rtps(r0.tid).filter { it.cat == cat && it.id != r0.id }.map {
            JSONObject().put("assignment", it.assignment).put("completed", it.completed ?: JSONObject.NULL).put("date", it.date)
        }).toString()
        val prompt = """
You help a Field Training Officer in ${program(store, t)} write a Remedial Training Worksheet for SEG #${s.n} ${s.name}.
SEG: ${s.d} | 1=${s.a1} | 4 (acceptable)=${s.a4}
Recent DOR entries for this SEG: $ds
FTO notes for this SEG: $ns
Prior RTPs in this SEG: $prior
Existing draft deficiency text: ${r0.deficiency.ifEmpty { "(none)" }}
Write two plain-text sections. $voice "deficiency": define the problem specifically with dated examples from the data, measured against the SEG's acceptable standard, and describe the training already conducted. "assignment": specific, measurable training assignments with clear performance goals and a time frame (e.g. "practice self-initiated car stops daily for 4 shifts: at least 4 F.I.s and 2 citations per shift"), and state that the trainee is expected to perform at a satisfactory level in this area. Be concise and direct: short sentences, no filler, never write about the notes or documentation themselves. Use only facts in the data. Return ONLY JSON {"deficiency":"...","assignment":"..."}
""".trim()
        val out = ClaudeAPI.completeJSON(prompt)
        withContext(Dispatchers.Main) {
            val r = store.data.rtps[rtpID] ?: return@withContext
            store.edit {
                (out.opt("deficiency") as? String)?.let { r.deficiency = it }
                (out.opt("assignment") as? String)?.let { r.assignment = it }
            }
        }
    }

    suspend fun summary(store: Store, t: Trainee, ds: List<DOR>, weekly: Boolean): String {
        val compact = JSONArray(ds.map {
            JSONObject().put("date", it.date).put("ride", it.ride).put("ungraded", it.ungraded)
                .put("ratings", JSONObject(it.ratings as Map<*, *>)).put("rem", JSONObject(it.rem as Map<*, *>))
                .put("time", JSONObject(it.time as Map<*, *>)).put("nrt", JSONObject(it.nrt as Map<*, *>))
                .put("most", it.most.toJson()).put("least", it.least.toJson()).put("narr", JSONObject(it.comments as Map<*, *>))
        }).toString()
        val rt = JSONArray(store.rtps(t.id).map {
            JSONObject().put("cat", it.cat ?: JSONObject.NULL).put("date", it.date).put("assignment", it.assignment)
                .put("completed", it.completed ?: JSONObject.NULL).put("competent", it.competent ?: JSONObject.NULL)
        }).toString()
        val ck = store.checks(t.id).items
        val due = Phase.due(t.phase)
        val missing = Ref.checklist.flatMap { sec -> sec.items.filter { i -> i.ph != null && i.ph in due && ck[i.id]?.p == null }.map { it.t } }.take(40)
        val ask = if (weekly)
            "Write the \"COMMENTS REGARDING STRENGTHS, WEAKNESSES AND PROGRESS\" section of the FTEP Supervisor Weekly Report: overall performance measured by the SEGs, specific strengths and weaknesses with examples, whether progress is increasing, decreasing or unchanged, and remedial training provided with its effect and any further training needed. Under 250 words."
        else
            "Write a Phase Evaluation Form summary for ${t.phase}: 1) strengths with specific examples; 2) weaknesses with specific examples; 3) where remedial training was given or special testing done, a brief description of the process and result; 4) recommendations for follow-up training for the next FTO/phase; 5) a clear statement of whether the trainee IS or IS NOT currently performing at the level of a solo patrol officer, based only on the record. Under 400 words."
        val prompt = """
You help a Field Training Officer in ${program(store, t)}. The trainee is currently in ${t.phase}.
$voice
SEGs: ${Ref.segsFor(t).joinToString("; ") { "#${it.n} ${it.name}" }}. Scale: 4 = minimum acceptable for solo work. REM = remedial training given (TIME minutes). NRT = not responding to training. Ungraded DORs carry narrative only.
DORs: $compact
Remedial Training Plans: $rt
Standard Task Checklist items due by this phase not yet performed: ${if (missing.isEmpty()) "none" else missing.joinToString("; ")}
$ask
Plain text, no markdown symbols, first person as the FTO, past tense, concise (short sentences, no filler, never comment on what was or wasn't documented), only facts in the data, do not predict future performance.
""".trim()
        return ClaudeAPI.complete(prompt).trim()
    }
}
