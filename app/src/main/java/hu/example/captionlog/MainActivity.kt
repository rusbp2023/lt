package hu.example.captionlog

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var out: TextView
    private lateinit var status: TextView
    private lateinit var scroll: ScrollView
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 2000) }
    }

    private fun text(): String {
        val f = CaptionService.file(this)
        return if (f.exists()) f.readText() else ""
    }

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
        status = TextView(this).apply { textSize = 18f; setPadding(0, 0, 0, 24) }
        root.addView(status)
        button("Kisegítő beállítások megnyitása") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button("Másolás vágólapra") { copy() }
        button("Megosztás") { share() }
        button("Mentés TXT-be (Letöltések)") { exportTxt() }
        button("Törlés") { CaptionService.clear(this); refresh() }
        out = TextView(this).apply { textSize = 14f; setTextIsSelectable(true) }
        scroll = ScrollView(this).apply { addView(out) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onResume() { super.onResume(); handler.post(tick) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(tick) }

    private fun serviceOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return s.contains("$packageName/") && s.contains("CaptionService")
    }

    private fun refresh() {
        status.text = if (serviceOn()) "● Szolgáltatás: BE" else "○ Szolgáltatás: KI"
        val t = text()
        out.text = if (t.isBlank()) "(még üres — kapcsold be a szolgáltatást és az Élő feliratot)" else t.takeLast(6000)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun copy() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("felirat", text()))
        Toast.makeText(this, "Kimásolva", Toast.LENGTH_SHORT).show()
    }

    private fun share() {
        val t = text()
        if (t.isBlank()) return
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, t.takeLast(100000))
            }, "Megosztás"))
    }

    private fun exportTxt() {
        val t = text()
        if (t.isBlank()) { Toast.makeText(this, "Nincs mit menteni", Toast.LENGTH_SHORT).show(); return }
        if (Build.VERSION.SDK_INT < 29) {
            Toast.makeText(this, "Ehhez Android 10+ kell, használd a Megosztást", Toast.LENGTH_LONG).show()
            return
        }
        val name = "felirat_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
        if (uri != null) {
            contentResolver.openOutputStream(uri)?.use { it.write(t.toByteArray(Charsets.UTF_8)) }
            Toast.makeText(this, "Mentve a Letöltések mappába: $name", Toast.LENGTH_LONG).show()
        }
    }
}
