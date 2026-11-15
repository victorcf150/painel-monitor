package com.painel.monitor

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs

val GREEN = Color.rgb(46, 204, 113)
val YELLOW = Color.rgb(241, 196, 15)
val ORANGE = Color.rgb(230, 126, 34)
val RED = Color.rgb(231, 76, 60)
val GRAY = Color.rgb(90, 98, 110)

data class T(val key: String, val title: String, val value: String, val sub: String = "", val color: Int = GREEN)
class TV(val box: LinearLayout, val title: TextView, val value: TextView, val sub: TextView)

fun readFile(p: String): String? = try { File(p).readText().trim() } catch (e: Exception) { null }

fun tempColor(c: Double) = when {
    c < 45 -> GREEN
    c < 60 -> YELLOW
    c < 72 -> ORANGE
    else -> RED
}

fun friendly(type: String): String {
    val t = type.lowercase()
    return when {
        "gpu" in t -> "GPU"
        "npu" in t || "nsp" in t -> "NPU"
        "cpu" in t || "big" in t || "little" in t || "mid" in t || "tsens" in t -> "CPU ($type)"
        "batt" in t -> "Bateria"
        "skin" in t || "xo_therm" in t || "quiet" in t || "sys_therm" in t || "case" in t -> "Superfície ($type)"
        "pa_therm" in t || "modem" in t || "mdm" in t || "qmi" in t -> "Modem/Rádio ($type)"
        "usb" in t || "chg" in t || "charg" in t -> "Carregador/USB ($type)"
        "ddr" in t || "mem" in t -> "Memória ($type)"
        "wlan" in t || "wifi" in t -> "Wi-Fi ($type)"
        else -> type
    }
}

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val views = LinkedHashMap<String, TV>()
    private lateinit var grid: GridLayout
    private val running = AtomicBoolean(false)
    private val pings = ArrayList<Double?>()
    private var partial = ArrayList<T>()
    private var lastRx = 0L
    private var lastTx = 0L
    private var lastT = 0L
    private var prevCpu: LongArray? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val sv = ScrollView(this)
        sv.setBackgroundColor(Color.rgb(10, 12, 16))
        grid = GridLayout(this)
        grid.setPadding(dp(6), dp(6), dp(6), dp(6))
        sv.addView(grid)
        setContentView(sv)
        apply(listOf(T("init", "Painel", "iniciando…", "aguardando primeira leitura", GRAY)))
    }

    override fun onStart() {
        super.onStart()
        running.set(true)
        thread {
            while (running.get()) {
                try {
                    sample()
                } catch (e: Throwable) {
                    val list = ArrayList(partial)
                    list.add(T("err", "Erro", e.javaClass.simpleName, (e.message ?: "").take(120), RED))
                    ui.post { apply(list) }
                }
                Thread.sleep(1000)
            }
        }
        thread {
            while (running.get()) {
                try { pingOnce() } catch (e: Throwable) { }
                Thread.sleep(1500)
            }
        }
    }

    override fun onStop() {
        running.set(false)
        super.onStop()
    }

    override fun onConfigurationChanged(c: android.content.res.Configuration) {
        super.onConfigurationChanged(c)
        relayout()
    }

    private fun pingOnce() {
        var ms: Double? = null
        val s = Socket()
        try {
            val t0 = System.nanoTime()
            s.connect(InetSocketAddress("1.1.1.1", 443), 1500)
            ms = (System.nanoTime() - t0) / 1e6
        } catch (e: Exception) {
        } finally {
            try { s.close() } catch (e: Exception) { }
        }
        synchronized(pings) {
            pings.add(ms)
            if (pings.size > 20) pings.removeAt(0)
        }
    }

    private fun speed(b: Double) = if (b >= 1e6) "%.1f MB/s".format(b / 1e6) else "%.0f KB/s".format(b / 1e3)

    private fun sample() {
        val out = ArrayList<T>()
        partial = out

        // Rede: velocidade
        val now = System.currentTimeMillis()
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        if (lastT > 0 && now > lastT) {
            val dt = (now - lastT) / 1000.0
            out.add(T("net", "Rede", "↓ " + speed((rx - lastRx) / dt), "↑ " + speed((tx - lastTx) / dt), GREEN))
        } else out.add(T("net", "Rede", "…", "", GRAY))
        lastRx = rx; lastTx = tx; lastT = now

        // Rede: ping/jitter/perda
        val p: List<Double?> = synchronized(pings) { ArrayList(pings) }
        if (p.isNotEmpty()) {
            val ok = p.filterNotNull()
            val loss = 100.0 * (p.size - ok.size) / p.size
            var jit = 0.0
            if (ok.size > 1) jit = ok.zipWithNext { a, b -> abs(a - b) }.average()
            val last = p.last()
            val col = when {
                last == null || loss > 5 -> RED
                last > 150 -> ORANGE
                last > 60 || loss > 0 -> YELLOW
                else -> GREEN
            }
            out.add(T("ping", "Latência (1.1.1.1)", if (last == null) "falhou" else "%.0f ms".format(last),
                "jitter %.0f ms · perda %.0f%%".format(jit, loss), col))
        }

        // CPU: uso
        var usage: Double? = null
        try {
            val l = readFile("/proc/stat")?.lines()?.firstOrNull()?.split(Regex("\\s+"))
            if (l != null && l.size > 8 && l[0] == "cpu") {
                val v = l.drop(1).take(8).map { it.toLong() }.toLongArray()
                val prev = prevCpu
                if (prev != null) {
                    val idle = (v[3] + v[4]) - (prev[3] + prev[4])
                    val total = v.sum() - prev.sum()
                    if (total > 0) usage = 100.0 * (total - idle) / total
                }
                prevCpu = v
            }
        } catch (e: Exception) { }

        // CPU: frequências
        val freqs = ArrayList<Pair<Double, Double>>()
        for (i in 0 until 16) {
            val d = "/sys/devices/system/cpu/cpu$i/cpufreq"
            val cur = readFile("$d/scaling_cur_freq")?.toDoubleOrNull() ?: continue
            val max = readFile("$d/cpuinfo_max_freq")?.toDoubleOrNull() ?: cur
            freqs.add(Pair(cur, max))
        }
        if (usage == null && freqs.isNotEmpty()) usage = 100.0 * freqs.map { it.first / it.second }.average()
        if (usage != null) {
            val col = if (usage < 60) GREEN else if (usage < 85) YELLOW else RED
            out.add(T("cpu", "CPU uso", "%.0f%%".format(usage), "", col))
        }
        if (freqs.isNotEmpty()) {
            val top = freqs.maxOf { it.first } / 1e6
            out.add(T("cpufreq", "CPU frequência", "%.2f GHz".format(top),
                freqs.mapIndexed { i, f -> "$i:%.1f".format(f.first / 1e6) }.joinToString(" "), GREEN))
        }

        // GPU
        var gpuInfo: String? = null
        var gpuBusy: Double? = null
        val kg = "/sys/class/kgsl/kgsl-3d0"
        readFile("$kg/gpuclk")?.toDoubleOrNull()?.let { gpuInfo = "%.0f MHz".format(it / 1e6) }
        readFile("$kg/gpu_busy_percentage")?.replace("%", "")?.trim()?.toDoubleOrNull()?.let { gpuBusy = it }
        if (gpuInfo == null) {
            File("/sys/devices/platform").listFiles()?.filter { "mali" in it.name }?.forEach { d ->
                val f = readFile(d.path + "/cur_freq")?.toDoubleOrNull()
                if (f != null && gpuInfo == null) gpuInfo = "%.0f MHz".format(if (f > 1e6) f / 1e6 else f / 1e3)
                val u = readFile(d.path + "/utilization")?.toDoubleOrNull()
                if (u != null) gpuBusy = u
            }
        }
        if (gpuInfo != null || gpuBusy != null) {
            val g = gpuBusy
            out.add(T("gpu", "GPU", if (g != null) "%.0f%%".format(g) else gpuInfo!!, gpuInfo ?: "",
                if (g == null || g < 85) GREEN else YELLOW))
        } else out.add(T("gpu", "GPU uso/freq", "sem acesso", "bloqueado sem Shizuku", GRAY))

        // RAM
        val mi = ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        val usedPct = 100.0 * (mi.totalMem - mi.availMem) / mi.totalMem
        out.add(T("ram", "RAM", "%.0f%%".format(usedPct),
            "%.1f / %.1f GB".format((mi.totalMem - mi.availMem) / 1e9, mi.totalMem / 1e9),
            if (usedPct < 75) GREEN else if (usedPct < 90) YELLOW else RED))

        // Bateria
        val bi = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (bi != null) {
            val bt = bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
            val lvl = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
            val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
            var cur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).toDouble()
            if (abs(cur) > 10000) cur /= 1000
            out.add(T("bat", "Bateria", "%.1f°C".format(bt), "$lvl% · %.0f mA".format(cur), tempColor(bt)))
        }

        // Engasgos / FPS (fase 2)
        out.add(T("jank", "Engasgos / FPS", "em breve", "requer Shizuku (fase 2)", GRAY))

        // Todos os sensores térmicos
        var found = 0
        for (i in 0 until 80) {
            val d = "/sys/class/thermal/thermal_zone$i"
            val type = readFile("$d/type") ?: continue
            val raw = readFile("$d/temp")?.toDoubleOrNull() ?: continue
            val c = if (abs(raw) >= 1000) raw / 1000 else raw
            if (c <= -40 || c > 150 || c == 0.0) continue
            found++
            out.add(T("tz$i", friendly(type), "%.1f°C".format(c), "zona $i · $type", tempColor(c)))
        }
        if (found == 0) out.add(T("tzx", "Sensores térmicos", "sem acesso", "o Android bloqueou /sys/class/thermal", YELLOW))

        ui.post { apply(out) }
    }

    private fun apply(list: List<T>) {
        var created = false
        for (t in list) {
            var v = views[t.key]
            if (v == null) {
                v = makeTile(); views[t.key] = v; created = true
            }
            v.title.text = t.title
            v.value.text = t.value
            v.sub.text = t.sub
            val bg = GradientDrawable()
            bg.cornerRadius = dp(12).toFloat()
            bg.setColor(Color.rgb(22, 26, 34))
            bg.setStroke(dp(2), t.color)
            v.box.background = bg
        }
        if (created) relayout()
    }

    private fun makeTile(): TV {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(10), dp(10), dp(10), dp(10))
        val title = TextView(this); title.textSize = 12f; title.setTextColor(Color.rgb(160, 168, 180))
        val value = TextView(this); value.textSize = 26f; value.setTextColor(Color.WHITE); value.setTypeface(null, Typeface.BOLD)
        val sub = TextView(this); sub.textSize = 11f; sub.setTextColor(Color.rgb(130, 138, 150))
        box.addView(title); box.addView(value); box.addView(sub)
        return TV(box, title, value, sub)
    }

    private fun relayout() {
        val wDp = resources.configuration.screenWidthDp
        val cols = maxOf(2, wDp / 170)
        val tileW = (dp(wDp) - dp(12)) / cols - dp(8)
        grid.removeAllViews()
        grid.columnCount = cols
        for (v in views.values) {
            val lp = GridLayout.LayoutParams()
            lp.width = tileW
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            lp.setMargins(dp(4), dp(4), dp(4), dp(4))
            grid.addView(v.box, lp)
        }
    }
}
