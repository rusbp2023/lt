package hu.example.captionlog

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CaptionService : AccessibilityService() {

    companion object {
        val seen = HashSet<String>()
    }

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val handler = Handler(Looper.getMainLooper())
    private var pending = false
    private val scanRunnable = Runnable { pending = false; scanAll() }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!pending) {
            pending = true
            handler.postDelayed(scanRunnable, 300)
        }
    }

    override fun onInterrupt() {}

    private fun scanAll() {
        try {
            val ws = windows
            if (ws.isNullOrEmpty()) {
                rootInActiveWindow?.let { scan(it, 0) }
            } else {
                for (w in ws) w.root?.let { scan(it, 0) }
            }
        } catch (_: Exception) {
        }
    }

    private fun scan(n: AccessibilityNodeInfo, depth: Int) {
        if (depth > 40) return
        val pkg = n.packageName?.toString() ?: ""
        if (pkg != packageName) {
            val t = (n.text ?: n.contentDescription)?.toString()?.trim()
            if (!t.isNullOrEmpty()) record(pkg, n.viewIdResourceName ?: "-", t)
        }
        for (i in 0 until n.childCount) {
            n.getChild(i)?.let { scan(it, depth + 1) }
        }
    }

    private fun record(pkg: String, viewId: String, text: String) {
        val clean = text.replace('\n', ' ').replace('\t', ' ').take(500)
        if (seen.size > 20000) seen.clear()
        if (!seen.add("$pkg|$viewId|$clean")) return
        File(filesDir, "log.txt").appendText("${fmt.format(Date())}\t$pkg\t$viewId\t$clean\n")
    }
}
