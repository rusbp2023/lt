package hu.example.captionlog

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CaptionService : AccessibilityService() {

    companion object {
        const val CAPTION_ID = "com.google.android.as:id/captions_text"

        @Volatile
        var generation = 0

        fun file(ctx: Context) = File(ctx.filesDir, "transcript.txt")

        fun clear(ctx: Context) {
            file(ctx).delete()
            generation++
        }
    }

    private val words = ArrayList<String>()
    private var prior = ""
    private var seenGen = 0
    private var last = ""
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val handler = Handler(Looper.getMainLooper())
    private var scanPending = false
    private var savePending = false
    private val scanRun = Runnable { scanPending = false; scan() }
    private val saveRun = Runnable { savePending = false; save() }

    override fun onServiceConnected() {
        val f = file(this)
        prior = if (f.exists()) f.readText().trimEnd() else ""
        if (prior.isNotEmpty()) prior += "\n\n"
        seenGen = generation
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
        save()
        super.onDestroy()
    }

    private fun scan() {
        try {
            if (seenGen != generation) {
                words.clear(); prior = ""; last = ""; seenGen = generation
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
            if (cur.isEmpty()) { last = ""; return }
            if (cur == last) return
            last = cur
            merge(cur)
            if (!savePending) {
                savePending = true
                handler.postDelayed(saveRun, 2000)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * A felirat ablak csúszik és javítgatja a végét. Megkeressük, hol fedi át az
     * új szöveg az eddigi átirat végét, az átfedést az új (javított) szavakra
     * cseréljük, és csak a tényleg új szavakat fűzzük hozzá.
     */
    private fun merge(cur: String) {
        val c = cur.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (c.isEmpty()) return
        var chosen = 0
        val maxM = minOf(c.size, words.size, 80)
        for (m in maxM downTo 1) {
            val off = words.size - m
            var eq = 0
            for (i in 0 until m) if (words[off + i] == c[i]) eq++
            val ok = (eq == m && (m >= 2 || c[0].length >= 4)) ||
                (m >= 4 && eq * 4 >= m * 3)
            if (ok) { chosen = m; break }
        }
        if (chosen > 0) {
            repeat(chosen) { words.removeAt(words.size - 1) }
        } else {
            words.add("\n[" + fmt.format(Date()) + "]")
        }
        words.addAll(c)
    }

    private fun save() {
        try {
            val body = words.joinToString(" ").replace(" \n", "\n").trim()
            file(this).writeText(prior + body)
        } catch (_: Exception) {
        }
    }
}
