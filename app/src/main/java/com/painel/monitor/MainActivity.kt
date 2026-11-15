package com.painel.monitor

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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
val BG = Color.rgb(0, 0, 0)
val CARD = Color.rgb(15, 17, 23)

const val S_PERF = "DESEMPENHO"
const val S_STO = "ARMAZENAMENTO E BATERIA"
const val S_NET = "REDE"
const val S_TMP = "TEMPERATURAS"
val SECTIONS = listOf(S_PERF, S_STO, S_NET, S_TMP)

data class T(
    val key: String, val sec: String, val title: String, val value: String,
    val sub: String = "", val color: Int = GREEN, val frac: Float? = null, val num: Double? = null
)

class Spark(ctx: Context) : View(ctx) {
    var data: List<Float> = emptyList()
    var color = GREEN
    var fixed = true
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    override fun onDraw(c: Canvas) {
        val n = data.size
        if (n < 2) return
        val w = width.toFloat()
        val h = height.toFloat()
        var lo = 0f
        var hi = 1f
        if (!fixed) {
            lo = data.min(); hi = data.max()
            if (hi - lo < 1f) hi = lo + 1f
        }
        val path = Path()
        for (i in 0 until n) {
            val x = w * (i + (60 - n)) / 59f
            val y = h - 3f - (h - 6f) * ((data[i] - lo) / (hi - lo)).coerceIn(0f, 1f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        line.color = color
        c.drawPath(path, line)
        val fp = Path(path)
        fp.lineTo(w, h); fp.lineTo(w * (60 - n) / 59f, h); fp.close()
        fill.color = Color.argb(40, Color.red(color), Color.green(color), Color.blue(color))
        c.drawPath(fp, fill)
    }
}

class Bar(ctx: Context) : View(ctx) {
    var frac = 0f
    var color = GREEN
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        p.color = Color.rgb(38, 42, 54)
        c.drawRoundRect(0f, 0f, w, h, h / 2, h / 2, p)
        p.color = color
        c.drawRoundRect(0f, 0f, w * frac.coerceIn(0f, 1f), h, h / 2, h / 2, p)
    }
}

class TV(
    val sec: String, val box: LinearLayout, val title: TextView, val value: TextView,
    val sub: TextView, val bar: Bar, val spark: Spark, val compact: Boolean
)

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
        "cpu" in t || "big" in t || "little" in t || "mid" in t || "tsens" in t -> "CPU"
        "batt" in t -> "Bateria"
        "skin" in t || "xo_therm" in t || "quiet" in t || "sys_therm" in t || "case" in t -> "Superfície"
        "pa_therm" in t || "modem" in t || "mdm" in t || "qmi" in t -> "Modem/Rádio"
        "usb" in t || "chg" in t || "charg" in t -> "Carregador/USB"
        "ddr" in t || "mem" in t -> "Memória"
        "wlan" in t || "wifi" in t -> "Wi-Fi"
        else -> type
    }
}

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val views = LinkedHashMap<String, TV>()
    private val hist = HashMap<String, ArrayDeque<Float>>()
    private val grids = LinkedHashMap<String, GridLayout>()
    private lateinit var cleanBtn: TextView
    private val running = AtomicBoolean(false)
    private val pings = ArrayList<Double?>()
    private var partial = ArrayList<T>()
    private var lastRx = 0L
    private var lastTx = 0L
    private var lastT = 0L
    private var prevCpu: LongArray? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(size: Float, color: Int): TextView {
        val t = TextView(this)
        t.textSize = size
        t.setTextColor(color)
        return t
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.BLACK
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG)

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(dp(16), dp(12), dp(16), dp(8))
        val title = tv(18f, Color.WHITE)
        title.text = "PAINEL"
        title.setTypeface(null, Typeface.BOLD)
        title.letterSpacing = 0.15f
        head.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        cleanBtn = tv(13f, Color.WHITE)
        cleanBtn.text = "Limpar RAM"
        cleanBtn.setPadding(dp(14), dp(8), dp(14), dp(8))
        val bb = GradientDrawable()
        bb.cornerRadius = dp(20).toFloat()
        bb.setColor(Color.rgb(30, 34, 44))
        cleanBtn.background = bb
        cleanBtn.setOnClickListener { cleanRam() }
        head.addView(cleanBtn)
        root.addView(head)

        val sv = ScrollView(this)
        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(12), 0, dp(12), dp(16))
        for (s in SECTIONS) {
            val h = tv(11f, Color.rgb(110, 118, 132))
            h.text = s
            h.letterSpacing = 0.12f
            h.setPadding(dp(4), dp(14), 0, dp(4))
            content.addView(h)
            val g = GridLayout(this)
            grids[s] = g
            content.addView(g)
        }
        sv.addView(content)
        root.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        apply(listOf(T("init", S_PERF, "Painel", "iniciando…", "aguardando primeira leitura", GRAY)))
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
                    list.add(T("err", S_PERF, "Erro", e.javaClass.simpleName, (e.message ?: "").take(120), RED))
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

    private fun availMem(): Long {
        val mi = ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        return mi.availMem
    }

    private fun cleanRam() {
        cleanBtn.text = "Limpando…"
        cleanBtn.isEnabled = false
        thread {
            var n = 0
            try {
                val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
                val before = availMem()
                for (ai in packageManager.getInstalledApplications(0)) {
                    if (ai.packageName == packageName) continue
                    if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue
                    if ("shizuku" in ai.packageName) continue
                    try { am.killBackgroundProcesses(ai.packageName); n++ } catch (e: Exception) { }
                }
                Thread.sleep(1200)
                val freed = (availMem() - before) / 1048576
                val msg = if (freed > 0) "Pedido enviado a $n apps · +$freed MB livres"
                else "Pedido enviado a $n apps · pouca diferença"
                ui.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
            } catch (e: Throwable) {
                ui.post { Toast.makeText(this, "Não foi possível limpar", Toast.LENGTH_SHORT).show() }
            }
            ui.post { cleanBtn.text = "Limpar RAM"; cleanBtn.isEnabled = true }
        }
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

        // Rede
        val now = System.currentTimeMillis()
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        if (lastT > 0 && now > lastT) {
            val dt = (now - lastT) / 1000.0
            val down = (rx - lastRx) / dt
            out.add(T("net", S_NET, "Velocidade", "↓ " + speed(down), "↑ " + speed((tx - lastTx) / dt), GREEN, num = down / 1e3))
        } else out.add(T("net", S_NET, "Velocidade", "…", "", GRAY))
        lastRx = rx; lastTx = tx; lastT = now

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
            out.add(T("ping", S_NET, "Latência (1.1.1.1)", if (last == null) "falhou" else "%.0f ms".format(last),
                "jitter %.0f ms · perda %.0f%%".format(jit, loss), col, num = last))
        }

        // CPU
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
            out.add(T("cpu", S_PERF, "CPU uso", "%.0f%%".format(usage), "", col, frac = (usage / 100).toFloat()))
        }
        if (freqs.isNotEmpty()) {
            val top = freqs.maxOf { it.first } / 1e6
            out.add(T("cpufreq", S_PERF, "CPU frequência", "%.2f GHz".format(top),
                freqs.mapIndexed { i, f -> "$i:%.1f".format(f.first / 1e6) }.joinToString(" "), GREEN, num = top))
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
            out.add(T("gpu", S_PERF, "GPU", if (g != null) "%.0f%%".format(g) else gpuInfo!!, gpuInfo ?: "",
                if (g == null || g < 85) GREEN else YELLOW, frac = g?.let { (it / 100).toFloat() }))
        } else out.add(T("gpu", S_PERF, "GPU uso/freq", "sem acesso", "bloqueado sem Shizuku", GRAY))

        // RAM
        val mi = ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        val usedPct = 100.0 * (mi.totalMem - mi.availMem) / mi.totalMem
        out.add(T("ram", S_PERF, "RAM", "%.0f%%".format(usedPct),
            "%.1f / %.1f GB".format((mi.totalMem - mi.availMem) / 1e9, mi.totalMem / 1e9),
            if (usedPct < 75) GREEN else if (usedPct < 90) YELLOW else RED, frac = (usedPct / 100).toFloat()))

        out.add(T("jank", S_PERF, "Engasgos / FPS", "em breve", "requer Shizuku (fase 2)", GRAY))

        // Armazenamento interno
        val sf = StatFs(Environment.getDataDirectory().path)
        val tot = sf.blockCountLong * sf.blockSizeLong
        val free = sf.availableBlocksLong * sf.blockSizeLong
        val usedF = 1.0 - free.toDouble() / tot
        out.add(T("sto", S_STO, "Armazenamento interno", "%.0f%%".format(usedF * 100),
            if (usedF >= 0.9) "quase cheio! %.1f GB livres".format(free / 1e9)
            else "%.1f GB livres de %.0f GB".format(free / 1e9, tot / 1e9),
            if (usedF < 0.8) GREEN else if (usedF < 0.9) YELLOW else RED, frac = usedF.toFloat()))

        // Bateria
        val bi = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (bi != null) {
            val bt = bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
            val lvl = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
            val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
            var cur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).toDouble()
            if (abs(cur) > 10000) cur /= 1000
            out.add(T("bat", S_STO, "Bateria", "%.1f°C".format(bt), "$lvl% · %.0f mA".format(cur), tempColor(bt), frac = lvl / 100f))
        }

        // Sensores térmicos
        var found = 0
        for (i in 0 until 80) {
            val d = "/sys/class/thermal/thermal_zone$i"
            val type = readFile("$d/type") ?: continue
            val raw = readFile("$d/temp")?.toDoubleOrNull() ?: continue
            val c = if (abs(raw) >= 1000) raw / 1000 else raw
            if (c <= -40 || c > 150 || c == 0.0) continue
            found++
            out.add(T("tz$i", S_TMP, friendly(type), "%.1f°C".format(c), "zona $i · $type", tempColor(c)))
        }
        if (found == 0) out.add(T("tzx", S_TMP, "Sensores térmicos", "sem acesso", "o Android bloqueou /sys/class/thermal", YELLOW))

        ui.post { apply(out) }
    }

    private fun apply(list: List<T>) {
        var changed = false
        for (k in listOf("init", "err")) {
            if (views.containsKey(k) && list.none { it.key == k }) { views.remove(k); changed = true }
        }
        for (t in list) {
            var v = views[t.key]
            if (v == null) {
                v = makeTile(t.sec); views[t.key] = v; changed = true
            }
            v.title.text = t.title
            v.value.text = t.value
            v.sub.text = t.sub
            val bg = GradientDrawable()
            bg.cornerRadius = dp(14).toFloat()
            bg.setColor(CARD)
            bg.setStroke(dp(1), Color.argb(150, Color.red(t.color), Color.green(t.color), Color.blue(t.color)))
            v.box.background = bg
            if (!v.compact) {
                v.bar.visibility = if (t.frac != null) View.VISIBLE else View.GONE
                v.bar.frac = t.frac ?: 0f
                v.bar.color = t.color
                v.bar.invalidate()
                val pt = t.frac ?: t.num?.toFloat()
                if (pt != null) {
                    val h = hist.getOrPut(t.key) { ArrayDeque() }
                    h.addLast(pt)
                    if (h.size > 60) h.removeFirst()
                    v.spark.data = h.toList()
                    v.spark.color = t.color
                    v.spark.fixed = t.frac != null
                    v.spark.visibility = View.VISIBLE
                    v.spark.invalidate()
                } else v.spark.visibility = View.GONE
            }
        }
        if (changed) relayout()
    }

    private fun makeTile(sec: String): TV {
        val compact = sec == S_TMP
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val pad = if (compact) dp(9) else dp(12)
        box.setPadding(pad, pad, pad, pad)
        val title = tv(11f, Color.rgb(140, 148, 162))
        title.maxLines = 1
        val value = tv(if (compact) 17f else 26f, Color.WHITE)
        value.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val sub = tv(10.5f, Color.rgb(120, 128, 142))
        sub.maxLines = if (compact) 1 else 2
        sub.ellipsize = android.text.TextUtils.TruncateAt.END
        val bar = Bar(this)
        val spark = Spark(this)
        box.addView(title)
        box.addView(value)
        box.addView(sub)
        if (!compact) {
            val lb = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5))
            lb.topMargin = dp(6)
            box.addView(bar, lb)
            val ls = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30))
            ls.topMargin = dp(6)
            box.addView(spark, ls)
        }
        return TV(sec, box, title, value, sub, bar, spark, compact)
    }

    private fun relayout() {
        val wDp = resources.configuration.screenWidthDp
        for ((sec, grid) in grids) {
            val compact = sec == S_TMP
            val cols = if (compact) maxOf(3, wDp / 125) else maxOf(2, wDp / 175)
            val tileW = (dp(wDp) - dp(24)) / cols - dp(8)
            grid.removeAllViews()
            grid.columnCount = cols
            for (v in views.values) {
                if (v.sec != sec) continue
                val lp = GridLayout.LayoutParams()
                lp.width = tileW
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                lp.setMargins(dp(4), dp(4), dp(4), dp(4))
                grid.addView(v.box, lp)
            }
        }
    }
}
