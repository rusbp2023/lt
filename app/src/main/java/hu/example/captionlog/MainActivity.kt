package hu.example.captionlog

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.Spanned
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashSet
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var out: TextView
    private lateinit var status: TextView
    private lateinit var scroll: ScrollView
    private lateinit var sendBtn: Button

    private val prefs by lazy { getSharedPreferences("p", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 2000) }
    }

    private val selected = LinkedHashSet<String>()
    private val wordRe = Regex("[\\p{L}\\p{N}]+(?:-[\\p{L}\\p{N}]+)*")
    private val stampRe = Regex("^\\[(?:\\d{4}-\\d\\d-\\d\\d )?\\d\\d:\\d\\d:\\d\\d]\\s*")
    private var textSizeSp = 20f
    private var lastShown = ""
    private var forceRender = false

    private fun text(): String {
        val f = CaptionService.file(this)
        return if (f.exists()) f.readText() else ""
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        textSizeSp = prefs.getFloat("size", 20f)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
        }
        fun btn(label: String, action: () -> Unit): Button {
            val b = Button(this)
            b.text = label
            b.isAllCaps = false
            b.setOnClickListener { action() }
            return b
        }
        fun row(vararg bs: Button) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            for (b in bs) r.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
            root.addView(r)
        }

        status = TextView(this)
        status.textSize = 18f
        root.addView(status)

        row(btn("Kisegítő beállítások") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        row(
            btn("A−") { setSize(textSizeSp - 2f) },
            btn("A+") { setSize(textSizeSp + 2f) },
            btn("Kijelölés törlése") { selected.clear(); forceRender = true; refresh() }
        )
        sendBtn = btn("") { sendToChatGpt() }
        row(sendBtn)
        row(
            btn("Másolás") { copy() },
            btn("Megosztás") { share() },
            btn("TXT") { exportTxt() }
        )
        row(btn("Törlés") {
            CaptionService.clear(this)
            selected.clear()
            forceRender = true
            refresh()
        })

        out = TextView(this)
        out.textSize = textSizeSp
        out.movementMethod = LinkMovementMethod.getInstance()
        out.highlightColor = Color.TRANSPARENT
        scroll = ScrollView(this)
        scroll.addView(out)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onResume() { super.onResume(); handler.post(tick) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(tick) }

    private fun setSize(v: Float) {
        textSizeSp = v.coerceIn(12f, 44f)
        prefs.edit().putFloat("size", textSizeSp).apply()
        out.textSize = textSizeSp
    }

    private fun serviceOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return s.contains("$packageName/") && s.contains("CaptionService")
    }

    private fun toggle(k: String) {
        if (!selected.add(k)) selected.remove(k)
        forceRender = true
        refresh()
    }

    private fun render(t: String) {
        val sb = SpannableStringBuilder(t)
        for (m in wordRe.findAll(t)) {
            val k = m.value.lowercase()
            val s = m.range.first
            val e = m.range.last + 1
            sb.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) { toggle(k) }
                override fun updateDrawState(ds: TextPaint) { ds.isUnderlineText = false }
            }, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (k in selected) {
                sb.setSpan(BackgroundColorSpan(Color.parseColor("#FFE082")), s, e,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        out.text = sb
    }

    private fun refresh() {
        status.text = if (serviceOn()) "● Szolgáltatás: BE" else "○ Szolgáltatás: KI"
        sendBtn.text = "Küldés ChatGPT-nek (" + selected.size + " szó)"
        val t = text()
        val shown = if (t.isBlank()) "(még üres — kapcsold be a szolgáltatást és az Élő feliratot)"
        else t.takeLast(30000)
        if (shown == lastShown && !forceRender) return
        forceRender = false
        val child = scroll.getChildAt(0)
        val atBottom = child == null || scroll.scrollY + scroll.height >= child.height - 120
        lastShown = shown
        render(shown)
        if (atBottom) scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun isEnd(w: String): Boolean =
        w.endsWith(".") || w.endsWith("?") || w.endsWith("!") || w.endsWith("…")

    private fun tokKey(w: String): String = wordRe.find(w)?.value?.lowercase() ?: ""

    /**
     * Minden kijelölt szónak a legutóbbi 2 előfordulásához: a mondata, az előtte
     * és az utána lévő mondat, de a szó előtt és után legfeljebb 30-30 szó.
     * Az átfedő részeket összevonja.
     */
    private fun buildContext(): String {
        val toks = text().lines()
            .map { it.replace(stampRe, "").trim() }
            .filter { it.isNotEmpty() }
            .map { e -> e.split(Regex("\\s+")) }
        val occ = HashMap<Int, ArrayList<Int>>()
        for (k in selected) {
            var found = 0
            var ei = toks.size - 1
            while (ei >= 0 && found < 2) {
                val t = toks[ei]
                var ti = t.size - 1
                while (ti >= 0 && found < 2) {
                    if (tokKey(t[ti]) == k) {
                        occ.getOrPut(ei) { ArrayList() }.add(ti)
                        found++
                    }
                    ti--
                }
                ei--
            }
        }
        val snippets = ArrayList<String>()
        for (ei in occ.keys.sorted()) {
            val t = toks[ei]
            val ranges = ArrayList<IntArray>()
            for (i in occ[ei]!!) {
                var s = i
                while (s > 0 && !isEnd(t[s - 1])) s--
                var ps = s
                if (s > 0) {
                    ps = s - 1
                    while (ps > 0 && !isEnd(t[ps - 1])) ps--
                }
                var e = i
                while (e < t.size - 1 && !isEnd(t[e])) e++
                var ne = e
                if (e < t.size - 1) {
                    ne = e + 1
                    while (ne < t.size - 1 && !isEnd(t[ne])) ne++
                }
                ranges.add(intArrayOf(maxOf(ps, i - 30), minOf(ne, i + 30)))
            }
            ranges.sortBy { it[0] }
            val merged = ArrayList<IntArray>()
            for (r in ranges) {
                val l = if (merged.isEmpty()) null else merged[merged.size - 1]
                if (l != null && r[0] <= l[1] + 1) {
                    l[1] = maxOf(l[1], r[1])
                } else {
                    merged.add(r)
                }
            }
            for (r in merged) {
                val pre = if (r[0] > 0 && !isEnd(t[r[0] - 1])) "… " else ""
                val post = if (r[1] < t.size - 1 && !isEnd(t[r[1]])) " …" else ""
                snippets.add(pre + t.subList(r[0], r[1] + 1).joinToString(" ") + post)
            }
        }
        return snippets.joinToString("\n")
    }

    private fun isBreak(w: String): Boolean =
        isEnd(w) || w.endsWith(",") || w.endsWith(";") || w.endsWith(":")

    /**
     * Szétválogatja a kijelölést: az egymás mellett (mondat- vagy tagmondathatár
     * nélkül) kijelölt szavak egy-egy szókapcsolatot alkotnak, a többi önálló szó.
     */
    private fun splitSelection(): Pair<List<String>, List<String>> {
        val entries = text().lines()
            .map { it.replace(stampRe, "").trim() }
            .filter { it.isNotEmpty() }
        val phrases = LinkedHashSet<String>()
        val phraseKeys = HashSet<String>()
        val single = HashSet<String>()
        for (e in entries) {
            val t = e.split(Regex("\\s+"))
            var i = 0
            while (i < t.size) {
                val k = tokKey(t[i])
                if (k in selected) {
                    val run = ArrayList<String>()
                    run.add(k)
                    var j = i
                    while (!isBreak(t[j]) && j + 1 < t.size && tokKey(t[j + 1]) in selected) {
                        j++
                        run.add(tokKey(t[j]))
                    }
                    if (run.size >= 2) {
                        phrases.add(run.joinToString(" "))
                        phraseKeys.addAll(run)
                    } else {
                        single.add(k)
                    }
                    i = j + 1
                } else {
                    i++
                }
            }
        }
        val singles = selected.filter { it !in phraseKeys || it in single }
        return Pair(singles, phrases.toList())
    }

    private fun buildPrompt(): String {
        val (singles, phrases) = splitSelection()
        val context = buildContext()
        val sb = StringBuilder()
        sb.append("Egy orosz nyelvű, automatikus beszédfelismerésből származó szövegből " +
            "(lehetnek benne hibák) kijelöltem néhány szót. Add meg mindegyiknek az orosz " +
            "szótári alakját (főnév: alanyeset egyes szám; ige: infinitívusz; melléknév: " +
            "hímnemű alanyeset egyes szám) és a magyar szótári alakját a szövegkörnyezet " +
            "alapján. Ha több kijelölt szó ugyanannak a szónak a ragozott alakja, az csak " +
            "egyszer szerepeljen. ")
        if (phrases.isNotEmpty()) {
            sb.append("Az egymás mellett kijelölt szavaknál vizsgáld meg, hogy állandósult " +
                "kifejezést (szókapcsolatot) alkotnak-e: ha igen, a kifejezést egy tételként add " +
                "meg (az orosz szótári alakját és a magyar megfelelőjét, az orosz kifejezésen " +
                "belül a szavak között egy szóközzel); ha nem alkotnak kifejezést, a szavakat " +
                "add meg külön-külön. ")
        }
        sb.append("Csak egy egyszerű listát adj vissza, soronként egy tétellel, " +
            "ebben a formában: orosz szótári alak magyar szótári alak (a kettő között " +
            "pontosan 10 szóköz legyen, kötőjel, gondolatjel vagy más jel nélkül). Ne írj hozzá " +
            "magyarázatot, táblázatot vagy más szöveget.\n\n")
        if (singles.isNotEmpty()) {
            sb.append("Kijelölt szavak:\n")
            sb.append(singles.joinToString("\n") { "- $it" })
            sb.append("\n\n")
        }
        if (phrases.isNotEmpty()) {
            sb.append("Egymás mellett kijelölt szavak (lehet, hogy kifejezés):\n")
            sb.append(phrases.joinToString("\n") { "- $it" })
            sb.append("\n\n")
        }
        sb.append("Szövegkörnyezet (a kijelölt szavak körüli mondatok):\n")
        sb.append(context)
        return sb.toString()
    }

    private fun sendToChatGpt() {
        if (selected.isEmpty()) { toast("Előbb koppints rá néhány szóra"); return }
        val prompt = buildPrompt()
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("kérés", prompt))
        val i = Intent(Intent.ACTION_SEND)
        i.type = "text/plain"
        i.putExtra(Intent.EXTRA_TEXT, prompt)
        startActivity(Intent.createChooser(i, "Küldés ChatGPT-nek"))
    }

    private fun copy() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("felirat", text()))
        toast("Kimásolva")
    }

    private fun share() {
        val t = text()
        if (t.isBlank()) return
        val i = Intent(Intent.ACTION_SEND)
        i.type = "text/plain"
        i.putExtra(Intent.EXTRA_TEXT, t.takeLast(100000))
        startActivity(Intent.createChooser(i, "Megosztás"))
    }

    private fun exportTxt() {
        val t = text()
        if (t.isBlank()) { toast("Nincs mit menteni"); return }
        if (Build.VERSION.SDK_INT < 29) {
            Toast.makeText(this, "Ehhez Android 10+ kell, használd a Megosztást", Toast.LENGTH_LONG).show()
            return
        }
        val name = "felirat_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"
        val v = ContentValues()
        v.put(MediaStore.Downloads.DISPLAY_NAME, name)
        v.put(MediaStore.Downloads.MIME_TYPE, "text/plain")
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
        if (uri != null) {
            contentResolver.openOutputStream(uri)?.use { it.write(t.toByteArray(Charsets.UTF_8)) }
            Toast.makeText(this, "Mentve a Letöltések mappába: $name", Toast.LENGTH_LONG).show()
        }
    }
}
