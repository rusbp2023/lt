package hu.example.captionlog

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CaptionService : AccessibilityService() {

    companion object {
        const val CAPTION_ID = "com.google.android.as:id/captions_text"
        const val KEEP_DAYS = 7

        @Volatile
        var generation = 0

        fun file(ctx: Context) = File(ctx.filesDir, "transcript.txt")

        fun clear(ctx: Context) {
            file(ctx).delete()
            generation++
        }
    }

    private val words = ArrayList<String>()
    private val keys = ArrayList<String>()
    private val bracketOnly = Regex("^\\[[^\\]]*]$")
    private val dateRe = Regex("^\\[(\\d{4}-\\d\\d-\\d\\d) \\d\\d:\\d\\d:\\d\\d]")
    private val splitRe = Regex("\\s+")
    private var prior = ""
    private var seenGen = 0
    private var last = ""
    private var baseline: List<String> = emptyList()
    private var lastPrune = 0L
    private val stampFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val handler = Handler(Looper.getMainLooper())
    private var scanPending = false
    private var savePending = false
    private val scanRun = Runnable { scanPending = false; scan() }
    private val saveRun = Runnable { savePending = false; save() }
    private val pollRun = object : Runnable {
        override fun run() {
            scan()
            handler.postDelayed(this, 1000)
        }
    }

    private fun cutoff(): String =
        dayFmt.format(Date(System.currentTimeMillis() - KEEP_DAYS * 24L * 3600L * 1000L))

    /** A hétnél régebbi (vagy dátum nélküli, régi formátumú) sorok eldobása. */
    private fun pruneText(t: String, cutoff: String): String {
        val keep = ArrayList<String>()
        for (ln in t.lines()) {
            if (ln.isBlank()) continue
            val m = dateRe.find(ln) ?: continue
            if (m.groupValues[1] >= cutoff) keep.add(ln)
        }
        return if (keep.isEmpty()) "" else keep.joinToString("\n") + "\n\n"
    }

    private fun pruneWords(cutoff: String) {
        var p = -1
        for (i in words.indices) {
            val w = words[i]
            if (w.length >= 12 && w.startsWith("\n[") && w.substring(2, 12) >= cutoff) {
                p = i
                break
            }
        }
        if (p > 0) {
            words.subList(0, p).clear()
            keys.subList(0, p).clear()
        }
    }

    override fun onServiceConnected() {
        val f = file(this)
        prior = if (f.exists()) pruneText(f.readText(), cutoff()) else ""
        lastPrune = System.currentTimeMillis()
        seenGen = generation
        save()
        handler.removeCallbacks(pollRun)
        handler.postDelayed(pollRun, 1000)
        Toast.makeText(this, "CaptionLog: BE", Toast.LENGTH_SHORT).show()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        handler.removeCallbacks(pollRun)
        save()
        Toast.makeText(this, "CaptionLog: KI", Toast.LENGTH_SHORT).show()
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!scanPending) {
            scanPending = true
            handler.postDelayed(scanRun, 150)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacks(scanRun)
        handler.removeCallbacks(saveRun)
        handler.removeCallbacks(pollRun)
        save()
        super.onDestroy()
    }

    private fun scheduleSave() {
        if (!savePending) {
            savePending = true
            handler.postDelayed(saveRun, 2000)
        }
    }

    private fun scan() {
        try {
            val now = System.currentTimeMillis()
            if (now - lastPrune > 6L * 3600L * 1000L) {
                lastPrune = now
                val c = cutoff()
                prior = pruneText(prior, c)
                pruneWords(c)
                scheduleSave()
            }

            val ws = windows
            val roots = if (ws.isNullOrEmpty()) listOfNotNull(rootInActiveWindow)
            else ws.mapNotNull { it.root }
            val texts = ArrayList<String>()
            for (r in roots) {
                r.findAccessibilityNodeInfosByViewId(CAPTION_ID)?.forEach { n ->
                    n.text?.toString()?.let { texts.add(it) }
                }
            }
            val cur = texts.joinToString(" ").trim()
            val blank = cur.isEmpty() || bracketOnly.matches(cur)

            // Törlés történt: ami most a képernyőn van, az "régi", nem vesszük át újra.
            if (seenGen != generation) {
                words.clear(); keys.clear(); prior = ""
                seenGen = generation
                baseline = if (blank) emptyList()
                else cur.split(splitRe).filter { it.isNotEmpty() }.map { key(it) }
                last = cur
                return
            }

            if (cur.isEmpty()) { last = ""; baseline = emptyList(); return }
            if (bracketOnly.matches(cur)) return  // pl. [no English speech detected]
            if (cur == last) return
            last = cur
            merge(cur)
            scheduleSave()
        } catch (_: Exception) {
        }
    }

    private fun key(w: String): String {
        val k = w.lowercase().filter { it.isLetterOrDigit() }
        return if (k.isEmpty()) w else k
    }

    private fun startsLike(ck: List<String>, base: List<String>): Boolean {
        val n = base.size
        if (ck.size < n) return false
        val allowed = if (n >= 4) n / 4 else 0
        var miss = 0
        for (i in 0 until n) {
            if (ck[i] != base[i]) {
                miss++
                if (miss > allowed) return false
            }
        }
        return true
    }

    /**
     * A felirat szövege az egész megszólalást tartalmazza, és a vége (néha az
     * eleje is) javítgatódik. Az összehasonlításnál a kis/nagybetűt és az
     * írásjeleket figyelmen kívül hagyjuk. Megkeressük, hol fedi át az új szöveg
     * az átirat végét, az átfedést az új szavakra cseréljük, és csak az újat
     * fűzzük hozzá. Törlés után az akkor látott szöveget (baseline) kihagyjuk.
     */
    private fun merge(cur: String) {
        var c = cur.split(splitRe).filter { it.isNotEmpty() }
        if (c.isEmpty()) return
        var ck = c.map { key(it) }

        if (baseline.isNotEmpty()) {
            val n = baseline.size
            if (startsLike(ck, baseline)) {
                if (c.size <= n) return
                c = c.subList(n, c.size)
                ck = ck.subList(n, ck.size)
            } else {
                baseline = emptyList()
            }
        }

        var chosen = 0
        val maxM = minOf(c.size, words.size, 1000)
        for (m in maxM downTo 1) {
            val off = words.size - m
            val allowed = if (m >= 4) m / 4 else 0
            var miss = 0
            for (i in 0 until m) {
                if (keys[off + i] != ck[i]) {
                    miss++
                    if (miss > allowed) break
                }
            }
            if (miss <= allowed && (m >= 2 || ck[0].length >= 4)) { chosen = m; break }
        }
        if (chosen > 0) {
            repeat(chosen) {
                words.removeAt(words.size - 1)
                keys.removeAt(keys.size - 1)
            }
        } else {
            words.add("\n[" + stampFmt.format(Date()) + "]")
            keys.add("\n")
        }
        words.addAll(c)
        keys.addAll(ck)
    }

    private fun save() {
        if (seenGen != generation) return  // törlés után ne írjuk vissza a régi szöveget
        try {
            val body = words.joinToString(" ").replace(" \n", "\n").trim()
            file(this).writeText(prior + body)
        } catch (_: Exception) {
        }
    }
}
