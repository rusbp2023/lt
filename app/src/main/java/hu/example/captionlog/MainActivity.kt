package hu.example.captionlog

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

class MainActivity : Activity() {

    private lateinit var out: TextView
    private val logFile get() = File(filesDir, "log.txt")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 32)
        }
        fun button(label: String, action: () -> Unit) {
            root.addView(Button(this).apply {
                text = label
                setOnClickListener { action() }
            })
        }
        root.addView(TextView(this).apply {
            text = "1) Kisegítő lehetőségek bekapcsolása: CaptionLog\n" +
                "2) Live Caption be, nyisd meg a TikTok live-ot ~1 percre\n" +
                "3) Vissza ide: Frissítés, majd Napló megosztása"
        })
        button("Kisegítő beállítások megnyitása") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button("Frissítés") { refresh() }
        button("Napló megosztása") { share() }
        button("Napló törlése") {
            logFile.delete()
            CaptionService.seen.clear()
            refresh()
        }
        out = TextView(this).apply { textSize = 12f }
        root.addView(ScrollView(this).apply { addView(out) },
            LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        if (!logFile.exists()) { out.text = "(még üres)"; return }
        val rows = logFile.readLines().map { it.split('\t') }.filter { it.size >= 4 }
        val sb = StringBuilder()
        rows.groupBy { it[1] }.entries.sortedByDescending { it.value.size }.forEach { (pkg, list) ->
            sb.append("■ $pkg  (${list.size} sor)\n")
            list.takeLast(3).forEach { sb.append("   [${it[2]}] ${it[3].take(80)}\n") }
            sb.append('\n')
        }
        out.text = sb.toString()
    }

    private fun share() {
        if (!logFile.exists()) return
        val text = logFile.readText().takeLast(60000)
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }, "Napló megosztása"))
    }
}
