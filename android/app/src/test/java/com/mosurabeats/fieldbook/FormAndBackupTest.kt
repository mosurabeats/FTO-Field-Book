package com.mosurabeats.fieldbook

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class FormAndBackupTest {
    private fun documentXml(): String {
        val f = listOf("src/main/assets/FTEP1.docx", "app/src/main/assets/FTEP1.docx").map(::File).first { it.exists() }
        ZipFile(f).use { z -> return z.getInputStream(z.getEntry("word/document.xml")).bufferedReader().readText() }
    }

    @Test fun formHas533Fields() {
        val xml = documentXml()
        assertEquals(533, Regex("<w:ffData>").findAll(xml).count())
    }

    @Test fun fillsTextAndChecks() {
        val xml = documentXml()
        val vals = mapOf(0 to FormFiller.V.Text("12"), 1 to FormFiller.V.Text("11/24/2026"), 4 to FormFiller.V.Text("Trainee & <Co>"),
            7 to FormFiller.V.Check(true), 8 to FormFiller.V.Check(false), 491 to FormFiller.V.Text("Line one\nLine two"))
        val out = FormFiller.fill(xml, vals)
        assertEquals(533, Regex("<w:ffData>").findAll(out).count())
        assertTrue(out.contains("<w:t xml:space=\"preserve\">12</w:t>"))
        assertTrue(out.contains("11/24/2026"))
        assertTrue(out.contains("Trainee &amp; &lt;Co&gt;"))
        assertTrue(out.contains("Line one</w:t><w:br/><w:t xml:space=\"preserve\">Line two"))
        assertTrue(out.contains("<w:checked/>"))
        // still well-formed enough to parse
        javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(out.byteInputStream())
    }

    @Test fun backupRoundTripsAndReadsLegacyWebFormat() {
        val web = """{"app":"fto-fieldbook","v":2,"profile":{"fto":{"name":"Q","init":"QX"},"trainees":[{"id":"t1","name":"A B","phase":"Phase 2"}],"active":"t1","settings":{"carry":false}},
          "notes":{"n1":{"id":"n1","tid":"t1","ts":1732000000000,"date":"2026-11-20","cat":"4","tone":"-","text":"hi","rem":15}},
          "dors":{"d1":{"id":"d1","tid":"t1","date":"2026-11-20","day":3,"ratings":{"4":"NRT","5":"6"},"remedial":"cone drill","minutes":"20"}},
          "rtps":{"r1":{"id":"r1","tid":"t1","cat":4,"completed":"true"}},"checks":{"t1":{"tid":"t1","items":{"1.1":{"p":"2026-11-01"}}}}}"""
        val d = AppData.from(JSONObject(web))
        assertEquals("Phase II", d.profile.trainees[0].phase)
        assertEquals("QX", d.profile.fto.initials)
        assertEquals(false, d.profile.carry)
        assertEquals(4, d.notes["n1"]!!.cat)
        assertEquals("15", d.notes["n1"]!!.rem)
        val dor = d.dors["d1"]!!
        assertEquals(3, dor.ride)
        assertEquals("2", dor.rating(4)); assertTrue(dor.isNRT(4))
        assertTrue(dor.narrative.contains("Remedial: cone drill (20 min)"))
        assertEquals(true, d.rtps["r1"]!!.completed)
        assertEquals("2026-11-01", d.checks["t1"]!!.items["1.1"]!!.p)
        val again = AppData.from(JSONObject(d.toJson().toString()))
        assertEquals("6", again.dors["d1"]!!.rating(5))
        assertEquals(1732000000000L, again.notes["n1"]!!.ts)
    }
}
