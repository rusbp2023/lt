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
    private var prior = ""
    private var seenGen = 0
    private var last = ""
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
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

    override fun onServiceConnected() {
        val f = file(this)
        prior = if (f.exists()) f.readText().trimEnd() else ""
        if (prior.isNotEmpty()) prior += "\n\n"
        seenGen = generation
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

    private fun scan() {
        try {
            if (seenGen != generation) {
                words.clear(); keys.clear(); prior = ""; last = ""; seenGen = generation
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
            if (bracketOnly.matches(cur)) return  // pl. [no English speech detected]
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

    private fun key(w: String): String {
        val k = w.lowercase().filter { it.isLetterOrDigit() }
        return if (k.isEmpty()) w else k
    }

    /**
     * A felirat szövege az egész megszólalást tartalmazza, és a vége (néha az
     * eleje is) javítgatódik. Az összehasonlításnál a kis/nagybetűt és az
     * írásjeleket figyelmen kívül hagyjuk. Megkeressük, hol fedi át az új szöveg
     * az átirat végét, az átfedést az új szavakra cseréljük, és csak az újat
     * fűzzük hozzá.
     */
    private fun merge(cur: String) {
        val c = cur.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (c.isEmpty()) return
        val ck = c.map { key(it) }
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
            words.add("\n[" + fmt.format(Date()) + "]")
            keys.add("\n")
        }
        words.addAll(c)
        keys.addAll(ck)
    }

    private fun save() {
        try {
            val body = words.joinToString(" ").replace(" \n", "\n").trim()
            file(this).writeText(prior + body)
        } catch (_: Exception) {
        }
    }
}
