package hu.example.captionlog

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WordListActivity : Activity() {

    private lateinit var title: TextView
    private lateinit var out: TextView

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val size = getSharedPreferences("p", MODE_PRIVATE).getFloat("size", 20f)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(24, 48, 24, 24)

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

        title = TextView(this)
        title.textSize = 18f
        root.addView(title)

        row(btn("Beillesztés a vágólapról") { paste() })
        row(
            btn("Másolás") { copy() },
            btn("TXT") { exportTxt() },
            btn("Lista törlése") { confirmClear() }
        )

        out = TextView(this)
        out.textSize = size
        val scroll = ScrollView(this)
        scroll.addView(out)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val n = WordStore.load(this).size
        title.text = "Szólista: $n tétel"
        out.text = if (n == 0) "(még üres — másold ki a ChatGPT válaszát, és nyomd meg a Beillesztés gombot)"
        else WordStore.asText(this)
    }

    private fun paste() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        val t = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this).toString() else ""
        if (t.isBlank()) { toast("A vágólap üres"); return }
        val (added, dup, bad) = WordStore.addFromText(this, t)
        var msg = "$added új tétel, $dup már benne volt"
        if (bad > 0) msg += ", $bad sort nem sikerült értelmezni"
        toast(msg)
        refresh()
    }

    private fun copy() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("szólista", WordStore.asText(this)))
        toast("Kimásolva")
    }

    private fun exportTxt() {
        val t = WordStore.asText(this)
        if (t.isBlank()) { toast("Nincs mit menteni"); return }
        if (Build.VERSION.SDK_INT < 29) { toast("Ehhez Android 10+ kell, használd a Másolást"); return }
        val name = "szolista_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"
        val v = ContentValues()
        v.put(MediaStore.Downloads.DISPLAY_NAME, name)
        v.put(MediaStore.Downloads.MIME_TYPE, "text/plain")
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
        if (uri != null) {
            contentResolver.openOutputStream(uri)?.use { it.write(t.toByteArray(Charsets.UTF_8)) }
            toast("Mentve a Letöltések mappába: $name")
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setMessage("Biztosan törlöd az egész szólistát?")
            .setPositiveButton("Törlés") { _, _ -> WordStore.clear(this); refresh() }
            .setNegativeButton("Mégse", null)
            .show()
    }
}
