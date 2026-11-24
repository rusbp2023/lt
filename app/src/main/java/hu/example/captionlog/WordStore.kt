package hu.example.captionlog

import android.content.Context
import java.io.File

/** A tartós szólista: soronként "orosz<TAB>magyar", külön fájlban, a törlés nem érinti. */
object WordStore {
    private val sepRe = Regex("\\s{2,}|\\t|\\s*\\|\\s*|\\s+[–—-]\\s+")
    private val bulletRe = Regex("^\\s*(?:[-•–·*]+|\\d+[.)])\\s*")
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

    /** Egy sor szétszedése: orosz rész + magyar rész. Nem értelmezhető sorra null. */
    fun parseLine(raw: String): Pair<String, String>? {
        var s = raw.replace("*", "").replace("`", "").trim()
        s = s.replace(bulletRe, "").trim()
        if (s.isEmpty()) return null

        // 1) elválasztó alapján (több szóköz, tabulátor, |, szóközök közti kötőjel)
        val m = sepRe.find(s)
        if (m != null) {
            val ru = s.substring(0, m.range.first).trim()
            val hu = s.substring(m.range.last + 1).trim()
            if (ru.isNotEmpty() && hu.isNotEmpty() && cyr.containsMatchIn(ru)) {
                return Pair(norm(ru), norm(hu))
            }
        }
        // 2) tartalék: az orosz rész a cirill szavak sora, utána jön a magyar
        val toks = s.split(spaces).filter { it.isNotEmpty() }
        var n = 0
        while (n < toks.size && cyr.containsMatchIn(toks[n])) n++
        if (n >= 1 && n < toks.size) {
            return Pair(toks.subList(0, n).joinToString(" "), toks.subList(n, toks.size).joinToString(" "))
        }
        return null
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
            val p = parseLine(ln)
            if (p == null) { bad++; continue }
            if (keys.add(p.first.lowercase())) {
                sb.append(p.first).append('\t').append(p.second).append('\n')
                added++
            } else {
                dup++
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
