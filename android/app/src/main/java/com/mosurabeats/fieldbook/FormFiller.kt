package com.mosurabeats.fieldbook

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Fills the department's own FTEP-1 DOR (Level II/III fillable Word form) field by field.
 * Field order: 0 DOR#, 1 date, 2 shift hours, 3 total hours, 4 trainee, 5 FTO, 6 non-FTO, 7/8 attachments yes/no,
 * 9 assignment, 10…489 = 40 SEG rows × 12 cells (1–7, NO, NAR, NRT, REM, TIME), 490/491 most cat/text,
 * 492/493 least cat/text, 494/495 additional narrative yes/no, 496…525 = 15 narrative rows (cat, text),
 * 526 trainee comments, 527/528 radio yes/no, 529–532 calls/MV/arrests/viols.
 */
object FormFiller {
    sealed class V { data class Text(val s: String) : V(); data class Check(val on: Boolean) : V() }

    fun values(store: Store, d: DOR): Pair<Map<Int, V>, List<DORLogic.NarrativeRow>> {
        val t = store.trainee(d.tid) ?: Trainee()
        val v = mutableMapOf<Int, V>()
        fun tx(i: Int, s: String) { v[i] = V.Text(s) }
        tx(0, d.ride.toString()); tx(1, DateUtil.us(d.date)); tx(2, d.shiftHours); tx(3, d.totalHours)
        tx(4, t.name + if (t.badge.isEmpty()) "" else " #" + t.badge); tx(5, d.fto); tx(6, d.nonFto)
        v[7] = V.Check(d.attach); v[8] = V.Check(!d.attach)
        tx(9, if (d.ungraded && d.assignment.isEmpty()) Rating.unratedReason else d.assignment)
        for (n in 1..40) {
            val b = 10 + (n - 1) * 12
            for (c in 0 until 12) tx(b + c, "")
            val s = Ref.byN[n] ?: continue
            if (t.level == "III" && s.l2) continue
            val r = d.rating(n)
            if (!d.ungraded) { if (Rating.isNum(r)) tx(b + r.toInt() - 1, "X") else tx(b + 7, "X") }
            val hasNarr = d.comment(n).isNotBlank()
            if (hasNarr && (d.ungraded || Rating.isNum(r) || d.isRem(n) || d.isNRT(n))) tx(b + 8, "X")
            if (d.isNRT(n) && !d.ungraded) tx(b + 9, "X")
            if (d.isRem(n)) tx(b + 10, "X")
            if (d.minutes(n).isNotEmpty()) tx(b + 11, d.minutes(n))
        }
        if (d.ungraded) { tx(490, ""); tx(491, ""); tx(492, ""); tx(493, "") }
        else {
            tx(490, d.most.cat?.toString() ?: ""); tx(491, d.most.text)
            tx(492, d.least.cat?.toString() ?: ""); tx(493, d.least.text)
        }
        val rows = DORLogic.narrativeRows(store, d)
        val over = rows.size > 15
        v[494] = V.Check(over); v[495] = V.Check(!over)
        for (i in 0 until 15) { val r = rows.getOrNull(i); tx(496 + i * 2, r?.cat ?: ""); tx(497 + i * 2, r?.text ?: "") }
        tx(526, d.traineeComments); v[527] = V.Check(d.radio); v[528] = V.Check(!d.radio)
        tx(529, d.calls); tx(530, d.mv); tx(531, d.arrests); tx(532, d.viols)
        return v to (if (over) rows.drop(15) else emptyList())
    }

    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private val rPrRe = Regex("<w:rPr>.*?</w:rPr>", RegexOption.DOT_MATCHES_ALL)

    private fun runs(text: String) = text.split("\n").mapIndexed { i, l -> (if (i > 0) "<w:br/>" else "") + "<w:t xml:space=\"preserve\">${esc(l)}</w:t>" }.joinToString("")

    fun fill(xml: String, values: Map<Int, V>): String {
        val doc = StringBuilder(xml)
        val starts = mutableListOf<Int>()
        var pos = 0
        while (true) { val f = doc.indexOf("<w:ffData>", pos); if (f < 0) break; starts.add(f); pos = f + 1 }
        for (k in starts.indices.reversed()) {
            val v = values[k] ?: continue
            val s = starts[k]
            val ffEnd = doc.indexOf("</w:ffData>", s); if (ffEnd < 0) continue
            val ff = doc.substring(s, ffEnd)
            if (ff.contains("<w:checkBox")) {
                if (v !is V.Check) continue
                var nf = ff.replace(Regex("<w:checked[^>]*/>"), "")
                nf = nf.replace(Regex("<w:default w:val=\"[01]\"/>"), "<w:default w:val=\"${if (v.on) 1 else 0}\"/>" + if (v.on) "<w:checked/>" else "")
                doc.replace(s, ffEnd, nf)
                continue
            }
            if (v !is V.Text) continue
            val sep = doc.indexOf("w:fldCharType=\"separate\"", s); if (sep < 0) continue
            val rEnd = doc.indexOf("</w:r>", sep); if (rEnd < 0) continue
            val a = rEnd + 6
            val endc = doc.indexOf("w:fldCharType=\"end\"", s); if (endc < 0) continue
            val b = maxOf(doc.lastIndexOf("<w:r>", endc), doc.lastIndexOf("<w:r ", endc))
            if (b < a) continue
            val mid = doc.substring(a, b)
            var rPr = rPrRe.find(mid)?.value ?: ""
            if (rPr.isEmpty()) {
                val begR = maxOf(doc.lastIndexOf("<w:r>", s), doc.lastIndexOf("<w:r ", s))
                if (begR >= 0) rPr = rPrRe.find(doc.substring(begR, s))?.value ?: ""
            }
            doc.replace(a, b, "<w:r>$rPr${runs(v.s)}</w:r>")
        }
        return doc.toString()
    }

    fun appendOverflow(xml: String, rows: List<DORLogic.NarrativeRow>, header: String): String {
        val at = xml.lastIndexOf("<w:sectPr")
        if (rows.isEmpty() || at < 0) return xml
        fun p(txt: String, bold: Boolean = false) =
            "<w:p><w:r><w:rPr><w:rFonts w:ascii=\"Arial\" w:hAnsi=\"Arial\" w:cs=\"Arial\"/>${if (bold) "<w:b/>" else ""}<w:sz w:val=\"20\"/></w:rPr>${runs(txt)}</w:r></w:p>"
        val x = StringBuilder("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>" + p(header, true) + p(""))
        for (r in rows) x.append(p(if (r.cat.isEmpty()) "General" else "Category #${r.cat} – ${Ref.name(r.cat.toIntOrNull()).uppercase()}", true) + p(r.text) + p(""))
        return xml.substring(0, at) + x + xml.substring(at)
    }

    /** Builds the filled .docx in the cache and returns it. */
    fun makeDocx(ctx: Context, store: Store, d: DOR): File {
        val t = store.trainee(d.tid) ?: Trainee()
        val safe = t.name.replace(Regex("[^A-Za-z0-9]+"), "_")
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val out = File(dir, "DOR%02d_%s_%s.docx".format(d.ride, safe, d.date))
        val (vals, overflow) = values(store, d)
        ZipInputStream(ctx.assets.open("FTEP1.docx")).use { zin ->
            ZipOutputStream(out.outputStream()).use { zout ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (e.isDirectory) continue
                    val buf = ByteArrayOutputStream(); zin.copyTo(buf)
                    var bytes = buf.toByteArray()
                    if (e.name == "word/document.xml") {
                        var filled = fill(String(bytes, Charsets.UTF_8), vals)
                        filled = appendOverflow(filled, overflow, "ADDITIONAL NARRATIVE — DOR # ${d.ride} — ${t.name} — ${DateUtil.us(d.date)}")
                        bytes = filled.toByteArray(Charsets.UTF_8)
                    }
                    zout.putNextEntry(ZipEntry(e.name)); zout.write(bytes); zout.closeEntry()
                }
            }
        }
        return out
    }
}
