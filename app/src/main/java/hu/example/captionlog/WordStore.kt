package hu.example.captionlog

import android.content.Context
import java.io.File

/** A tartós szólista: soronként "orosz<TAB>magyar", külön fájlban, a törlés nem érinti. */
object WordStore {
    private val sepRe = Regex("\\s{2,}|\\t|\\s*\\|\\s*|\\s+[–—-]\\s+")
    private val bulletRe = Regex("^\\s*(?:[-•–·*]+|\\d+[.)])\\s*")
    private val markerRe = Regex("^(?:\\d+[.)]|[-•–—·*|:]+)$")
    private val cyr = Regex("[\\u0400-\\u04FF]")
    private val spaces = Regex("\\s+")

    fun file(ctx: Context) = File(ctx.filesDir, "wordlist.txt")

    fun load(ctx: Context): List<Pair<String, String>> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { ln ->
            val i = ln.indexOf('\t')
            if (i < 0) null else Pair(ln.substring(0, i), ln.substring(i + 1))
        }
    }

    private fun norm(s: String) = s.replace(spaces, " ").trim()

    /**
     * Cirill szavak sora = orosz rész, utána a nem cirill szavak sora = magyar rész.
     * Így egyetlen sorba folyt, több tételt tartalmazó szöveg is szétszedhető.
     */
    private fun parseRuns(s: String): List<Pair<String, String>> {
        val toks = s.split(spaces).filter { it.isNotEmpty() }
        val out = ArrayList<Pair<String, String>>()
        var i = 0
        while (i < toks.size) {
            while (i < toks.size && !cyr.containsMatchIn(toks[i])) i++
            if (i >= toks.size) break
            val ru = ArrayList<String>()
            while (i < toks.size && cyr.containsMatchIn(toks[i])) { ru.add(toks[i]); i++ }
            val hu = ArrayList<String>()
            while (i < toks.size && !cyr.containsMatchIn(toks[i])) { hu.add(toks[i]); i++ }
            // a következő tétel sorszáma/felsorolásjele ne a magyar rész végére kerüljön
            while (i < toks.size && hu.isNotEmpty() && markerRe.matches(hu[hu.size - 1])) {
                hu.removeAt(hu.size - 1)
            }
            while (hu.isNotEmpty() && markerRe.matches(hu[0])) hu.removeAt(0)
            if (hu.isNotEmpty()) out.add(Pair(ru.joinToString(" "), hu.joinToString(" ")))
        }
        return out
    }

    /** Egy sorból 0, 1 vagy több (orosz, magyar) tételt ad. */
    fun parseLine(raw: String): List<Pair<String, String>> {
        var s = raw.replace("*", "").replace("`", "").trim()
        s = s.replace(bulletRe, "").trim()
        if (s.isEmpty()) return emptyList()

        // 1) elválasztó alapján (több szóköz, tabulátor, |, szóközök közti kötőjel)
        val m = sepRe.find(s)
        if (m != null) {
            val ru = s.substring(0, m.range.first).trim()
            val hu = s.substring(m.range.last + 1).trim()
            if (ru.isNotEmpty() && hu.isNotEmpty() && cyr.containsMatchIn(ru) && !cyr.containsMatchIn(hu)) {
                return listOf(Pair(norm(ru), norm(hu)))
            }
        }
        // 2) tartalék: cirill / nem cirill szakaszok váltakozása
        return parseRuns(s)
    }

    /** Hozzáfűzi az új tételeket; a már meglévő orosz szót/kifejezést kihagyja. (új, már benne volt, kimaradt) */
    fun addFromText(ctx: Context, text: String): Triple<Int, Int, Int> {
        val keys = HashSet<String>()
        for (p in load(ctx)) keys.add(p.first.lowercase())
        var added = 0
        var dup = 0
        var bad = 0
        val sb = StringBuilder()
        for (ln in text.lines()) {
            if (ln.isBlank()) continue
            val items = parseLine(ln)
            if (items.isEmpty()) { bad++; continue }
            for (p in items) {
                if (keys.add(p.first.lowercase())) {
                    sb.append(p.first).append('\t').append(p.second).append('\n')
                    added++
                } else {
                    dup++
                }
            }
        }
        if (added > 0) file(ctx).appendText(sb.toString())
        return Triple(added, dup, bad)
    }

    /** Megjelenítéshez / másoláshoz / exporthoz: orosz + 10 szóköz + magyar. */
    fun asText(ctx: Context): String =
        load(ctx).joinToString("\n") { it.first + " ".repeat(10) + it.second }

    fun clear(ctx: Context) {
        file(ctx).delete()
    }
}
