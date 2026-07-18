package com.example.nsobd2.ui.ecu

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.nsobd2.data.AppPrefs
import com.example.nsobd2.data.ReportStore
import com.example.nsobd2.network.Elm327Connection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

/**
 * BEYİN LAB — ECU keşif ve derin teşhis.
 *
 * 18.07.2026 araç testinden sonra güncellendi:
 *  - Beyin 7F ile "desteklemiyorum" diyor; artık doğru algılanıyor
 *  - Mod 05 / 06 / 07 / 08 / 09 bu araçta YOK, boşuna taranmıyor
 *  - Yerine Mod 01 canlı veriden çalışan TEŞHİS MOTORU eklendi
 */
class EcuLabActivity : AppCompatActivity() {

    private val conn = Elm327Connection()

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var etIp: EditText
    private lateinit var etPort: EditText
    private lateinit var etCmd: EditText

    private val logBuffer = StringBuilder()
    private var busy = false

    // ---------------------------------------------------------------- ARAYÜZ

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "🧠 Beyin Lab"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D1117"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        tvStatus = TextView(this).apply {
            text = "⚪ Bağlı değil"
            setTextColor(Color.parseColor("#E6EDF3"))
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(tvStatus)

        root.addView(TextView(this).apply {
            text = "ℹ️ Teşhis için motor sıcak ve RÖLANTİDE olmalı. Çıkınca Bağlantı sekmesinden tekrar bağlan."
            setTextColor(Color.parseColor("#F0A030"))
            textSize = 12f
            setPadding(0, 0, 0, dp(10))
        })

        val rowConn = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        etIp = EditText(this).apply {
            setText("192.168.0.10")
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f)
        }
        etPort = EditText(this).apply {
            setText("35000")
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        rowConn.addView(etIp); rowConn.addView(etPort)
        root.addView(rowConn)

        root.addView(bigButton("🔌 BAĞLAN", "#1F6FEB") { doConnect() })
        root.addView(bigButton("🩺 TEŞHİS ET (30 sn)", "#238636") { runDiagnosis() })
        root.addView(bigButton("🚀 TAM TARAMA", "#1A7F37") { runAll() })

        root.addView(twoButtons(
            "🔍 Beyin Tarayıcı", "#30363D", { runScan() },
            "📸 Arıza Anı (Mod 02)", "#30363D", { runMode02() }
        ))
        root.addView(twoButtons(
            "💾 Raporu Paylaş", "#8957E5", { shareReport() },
            "🧹 Ekranı Temizle", "#6E7681", { logBuffer.setLength(0); tvLog.text = "" }
        ))

        val rowCmd = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(4))
        }
        etCmd = EditText(this).apply {
            hint = "Komut yaz (örn: 0100, ATRV, 03)"
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f)
        }
        rowCmd.addView(etCmd)
        rowCmd.addView(Button(this).apply {
            text = "GÖNDER"; textSize = 12f; setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1F6FEB"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { sendManual() }
        })
        root.addView(rowCmd)

        tvLog = TextView(this).apply {
            setTextColor(Color.parseColor("#7EE787"))
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        scrollLog = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#010409"))
            addView(tvLog)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(scrollLog)

        setContentView(root)

        try {
            etIp.setText(AppPrefs.getSavedIp(this))
            etPort.setText(AppPrefs.getSavedPort(this).toString())
        } catch (_: Exception) {}

        log("🧠 BEYİN LAB hazır.")
        log("Peugeot 206 1.1 • ISO 14230-4 KWP FAST")
        log("──────────────────────────────")
        doConnect()
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleScope.launch { try { conn.disconnect() } catch (_: Exception) {} }
    }

    // ------------------------------------------------------------ YARDIMCILAR

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun bigButton(label: String, color: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label; textSize = 14f; setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(color))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            setOnClickListener { action() }
        }

    private fun twoButtons(
        l1: String, c1: String, a1: () -> Unit,
        l2: String, c2: String, a2: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        fun mk(l: String, c: String, a: () -> Unit) = Button(this).apply {
            text = l; textSize = 11f; setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(c))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { a() }
        }
        row.addView(mk(l1, c1, a1)); row.addView(mk(l2, c2, a2))
        return row
    }

    private fun log(line: String) {
        logBuffer.append(line).append("\n")
        runOnUiThread {
            tvLog.text = logBuffer.toString()
            scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun status(s: String) = runOnUiThread { tvStatus.text = s }

    private fun toast(s: String) = runOnUiThread {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    private fun guard(): Boolean {
        if (busy) { toast("Meşgul, bekle..."); return false }
        if (!conn.isConnected) { toast("Önce BAĞLAN"); return false }
        return true
    }

    private fun hexOnly(raw: String): String =
        raw.uppercase().filter { it in "0123456789ABCDEF" }

    /** 7F = beyin "bu servisi desteklemiyorum" diyor. */
    private fun isNegative(raw: String): Boolean = hexOnly(raw).startsWith("7F")

    private fun negativeReason(raw: String): String {
        val h = hexOnly(raw)
        val nrc = if (h.length >= 6) h.substring(4, 6) else "??"
        return com.example.nsobd2.data.DtcParser.nrcText(nrc)
    }

    private fun isBad(raw: String): Boolean {
        val u = raw.uppercase()
        return u.isBlank() || u.contains("NODATA") || u.contains("NO DATA") ||
                u.contains("ERROR") || u.contains("UNABLE") || u.contains("?") ||
                u.contains("STOPPED") || u.contains("BUFFER") || isNegative(raw)
    }

    private fun payloadAfter(raw: String, header: String): String? {
        val h = hexOnly(raw)
        val idx = h.indexOf(header.uppercase())
        if (idx < 0) return null
        return h.substring(idx + header.length)
    }

    private suspend fun ask(cmd: String, label: String = ""): String {
        val r = conn.sendCommand(cmd)
        log("→ ${if (label.isEmpty()) cmd else "$cmd  ($label)"}")
        log("← ${if (r.isBlank()) "(cevap yok)" else r}")
        return r
    }

    /** Tek byte'lık Mod 01 PID okur. */
    private suspend fun pid1(pid: String): Int? {
        val raw = conn.sendCommand("01$pid")
        if (isBad(raw)) return null
        val p = payloadAfter(raw, "41$pid") ?: return null
        if (p.length < 2) return null
        return p.substring(0, 2).toIntOrNull(16)
    }

    /** İki byte'lık Mod 01 PID okur (A*256+B). */
    private suspend fun pid2(pid: String): Int? {
        val raw = conn.sendCommand("01$pid")
        if (isBad(raw)) return null
        val p = payloadAfter(raw, "41$pid") ?: return null
        if (p.length < 4) return null
        val a = p.substring(0, 2).toIntOrNull(16) ?: return null
        val b = p.substring(2, 4).toIntOrNull(16) ?: return null
        return a * 256 + b
    }

    private fun f(v: Double, d: Int = 1) = String.format(Locale.US, "%.${d}f", v)

    private fun avg(l: List<Double>) = if (l.isEmpty()) 0.0 else l.sum() / l.size
    private fun sd(l: List<Double>): Double {
        if (l.size < 2) return 0.0
        val m = avg(l)
        return sqrt(l.sumOf { (it - m) * (it - m) } / (l.size - 1))
    }

    // ------------------------------------------------------------- BAĞLANTI

    private fun doConnect() {
        if (busy) return
        busy = true
        status("🟡 Bağlanılıyor...")
        lifecycleScope.launch {
            val ip = etIp.text.toString().trim().ifEmpty { "192.168.0.10" }
            val port = etPort.text.toString().trim().toIntOrNull() ?: 35000
            val res = withContext(Dispatchers.IO) {
                conn.connect(ip, port) { s -> status("🟡 $s") }
            }
            log("── $res")
            status(if (conn.isConnected) "🟢 Bağlı" else "🔴 $res")
            if (conn.isConnected) {
                ask("ATI", "ELM sürümü")
                ask("ATDP", "aktif protokol")
                ask("ATRV", "akü gerilimi")
            }
            busy = false
        }
    }

    private fun sendManual() {
        val cmd = etCmd.text.toString().trim().uppercase()
        if (cmd.isEmpty()) return
        if (!guard()) return
        busy = true
        lifecycleScope.launch {
            log("──────────────────────────────")
            val r = ask(cmd)
            if (isNegative(r)) log("   ℹ️ Beyin reddetti: ${negativeReason(r)}")
            busy = false
        }
    }

    // ====================================================================
    //                        TEŞHİS MOTORU
    // ====================================================================

    private fun runDiagnosis() {
        if (!guard()) return
        busy = true
        lifecycleScope.launch { diagnosisInternal(); busy = false }
    }

    private suspend fun diagnosisInternal() {
        log("")
        log("══════ 🩺 CANLI TEŞHİS ══════")
        log("Motor rölantide ve sıcak olmalı. 30 saniye sürecek, dokunma.")
        log("")

        val rpm = mutableListOf<Double>()
        val cool = mutableListOf<Double>()
        val stft = mutableListOf<Double>()
        val ltft = mutableListOf<Double>()
        val map = mutableListOf<Double>()
        val o2a = mutableListOf<Double>()
        val o2b = mutableListOf<Double>()
        val load = mutableListOf<Double>()
        val adv = mutableListOf<Double>()
        val iat = mutableListOf<Double>()

        val samples = 25
        for (i in 1..samples) {
            pid2("0C")?.let { rpm.add(it / 4.0) }
            pid1("05")?.let { cool.add((it - 40).toDouble()) }
            pid1("06")?.let { stft.add((it - 128) * 100.0 / 128.0) }
            pid1("07")?.let { ltft.add((it - 128) * 100.0 / 128.0) }
            pid1("0B")?.let { map.add(it.toDouble()) }
            pid1("14")?.let { o2a.add(it / 200.0) }
            pid1("15")?.let { o2b.add(it / 200.0) }
            pid1("04")?.let { load.add(it * 100.0 / 255.0) }
            pid1("0E")?.let { adv.add(it / 2.0 - 64.0) }
            pid1("0F")?.let { iat.add((it - 40).toDouble()) }

            if (i % 5 == 0) log("   ölçüm $i/$samples...")
        }

        log("")
        log("── ÖLÇÜM ÖZETİ ──")
        fun row(name: String, l: List<Double>, unit: String, d: Int = 1) {
            if (l.isEmpty()) { log("  $name: okunamadı"); return }
            log("  $name: ort ${f(avg(l), d)} $unit  (en az ${f(l.min(), d)} / en çok ${f(l.max(), d)}, dalga ${f(sd(l), d)})")
        }
        row("Devir      ", rpm, "d/dk", 0)
        row("Su sıcaklığı", cool, "°C", 0)
        row("Emilen hava ", iat, "°C", 0)
        row("STFT       ", stft, "%")
        row("LTFT       ", ltft, "%")
        row("MAP        ", map, "kPa", 0)
        row("Lambda ön  ", o2a, "V", 3)
        row("Lambda arka", o2b, "V", 3)
        row("Motor yükü ", load, "%")
        row("Avans      ", adv, "°")

        // ------------------------------------------------ KURALLAR
        log("")
        log("── 🔎 TEŞHİS ──")
        val bulgular = mutableListOf<String>()

        val toplamTrim = avg(stft) + avg(ltft)
        val suOrt = avg(cool)
        val mapOrt = avg(map)
        val devirOrt = avg(rpm)
        val devirDalga = sd(rpm)
        val o2aOrt = avg(o2a)
        val o2aDalga = sd(o2a)
        val o2bDalga = sd(o2b)

        // 1) Karışım
        if (stft.isNotEmpty() && ltft.isNotEmpty()) {
            when {
                toplamTrim > 15 -> bulgular.add(
                    "🔴 ÇOK FAKİR KARIŞIM (toplam düzeltme +${f(toplamTrim)}%)\n" +
                            "   Beyin sürekli yakıt ekliyor. Sıralı şüpheliler:\n" +
                            "   1. Emme manifold contası / vakum hortumu kaçağı (206'da 1 numara)\n" +
                            "   2. Fren servosu hortumu\n" +
                            "   3. Kirli/arızalı MAP sensörü\n" +
                            "   4. Yorulmuş ön lambda sensörü")
                toplamTrim > 8 -> bulgular.add(
                    "🟠 Hafif fakir karışım (+${f(toplamTrim)}%)\n" +
                            "   Küçük bir vakum kaçağı başlangıcı olabilir. Takip et.")
                toplamTrim < -15 -> bulgular.add(
                    "🔴 ÇOK ZENGİN KARIŞIM (${f(toplamTrim)}%)\n" +
                            "   1. LPG enjektörü kaçırıyor olabilir\n" +
                            "   2. Tıkalı hava filtresi\n" +
                            "   3. Arızalı su sıcaklık sensörü (beyin motoru soğuk sanıyor)")
                toplamTrim < -8 -> bulgular.add(
                    "🟠 Hafif zengin karışım (${f(toplamTrim)}%)")
                else -> bulgular.add("🟢 Karışım normal (toplam düzeltme ${f(toplamTrim)}%)")
            }
        }

        // 2) Su sıcaklığı
        if (cool.isNotEmpty()) {
            when {
                suOrt < 70 -> bulgular.add(
                    "🔴 SU SICAKLIĞI DÜŞÜK (${f(suOrt, 0)}°C)\n" +
                            "   Motor sıcaksa bu değer 85-95°C olmalı.\n" +
                            "   1. Termostat açık kalmış\n" +
                            "   2. Su sıcaklık müşürü arızalı (senin P0115 koduyla birebir uyuşuyor)\n" +
                            "   Sonuç: fazla yakıt ve kalorifer üflememe.")
                suOrt > 100 -> bulgular.add(
                    "🔴 MOTOR HARARET YAPIYOR (${f(suOrt, 0)}°C)\n" +
                            "   Fan, termostat ve su seviyesini hemen kontrol et.")
                else -> bulgular.add("🟢 Su sıcaklığı normal (${f(suOrt, 0)}°C)")
            }
        }

        // 3) Rölanti kararlılığı
        if (rpm.isNotEmpty()) {
            if (devirDalga > 60) bulgular.add(
                "🟠 RÖLANTİ DALGALI (±${f(devirDalga, 0)} d/dk)\n" +
                        "   Kelebek boğazı kurumlanması, rölanti motoru veya vakum kaçağı.")
            else bulgular.add("🟢 Rölanti kararlı (${f(devirOrt, 0)} d/dk, dalga ±${f(devirDalga, 0)})")

            if (devirOrt > 1100) bulgular.add(
                "🟠 Rölanti yüksek (${f(devirOrt, 0)} d/dk). Normal: 750-900.\n" +
                        "   Kelebek ayarı ya da hava kaçağı.")
        }

        // 4) MAP
        if (map.isNotEmpty() && devirOrt < 1200) {
            when {
                mapOrt > 50 -> bulgular.add(
                    "🟠 RÖLANTİDE MAP YÜKSEK (${f(mapOrt, 0)} kPa)\n" +
                            "   Normal rölanti: 30-42 kPa. Yüksekse hava kaçağı ya da\n" +
                            "   supap/egzoz tıkanıklığı olabilir.")
                mapOrt < 20 -> bulgular.add(
                    "🟠 MAP çok düşük (${f(mapOrt, 0)} kPa) — sensör veya hortum sorunu.")
                else -> bulgular.add("🟢 MAP normal (${f(mapOrt, 0)} kPa)")
            }
        }

        // 5) Ön lambda
        if (o2a.size >= 5) {
            when {
                o2aDalga < 0.05 -> bulgular.add(
                    "🔴 ÖN LAMBDA SENSÖRÜ TEMBEL (dalgalanma sadece ${f(o2aDalga, 3)} V)\n" +
                            "   Sağlıklı sensör rölantide 0.1-0.9 V arasında sürekli salınır.\n" +
                            "   Sensör yorulmuş — yakıt tüketimini artırır, katalizörü öldürür.")
                o2aOrt > 0.75 -> bulgular.add(
                    "🟠 Ön lambda sürekli zengin okuyor (ort ${f(o2aOrt, 3)} V)")
                o2aOrt < 0.25 -> bulgular.add(
                    "🟠 Ön lambda sürekli fakir okuyor (ort ${f(o2aOrt, 3)} V)")
                else -> bulgular.add(
                    "🟢 Ön lambda sağlıklı salınıyor (ort ${f(o2aOrt, 3)} V, dalga ${f(o2aDalga, 3)})")
            }
        }

        // 6) Arka lambda / katalizör
        if (o2b.size >= 5 && o2a.size >= 5) {
            if (o2bDalga > o2aDalga * 0.7 && o2aDalga > 0.05) bulgular.add(
                "🟠 KATALİZÖR ZAYIFLAMIŞ OLABİLİR\n" +
                        "   Arka lambda neredeyse ön lambda kadar dalgalanıyor\n" +
                        "   (ön ${f(o2aDalga, 3)} / arka ${f(o2bDalga, 3)}).\n" +
                        "   Sağlıklı katalizörde arka sensör düz durur.\n" +
                        "   LPG kullanımında sık görülür — P0420'nin habercisi.")
            else bulgular.add("🟢 Katalizör verimi normal görünüyor")
        }

        // 7) Emilen hava sıcaklığı
        if (iat.isNotEmpty() && avg(iat) > 60) bulgular.add(
            "🟠 Emilen hava çok sıcak (${f(avg(iat), 0)}°C)\n" +
                    "   Motor sıcak hava emiyor, güç düşer. Hava kanalını kontrol et.")

        bulgular.forEach { log("\n$it") }

        log("")
        log("══════════════════════════════")
        val saved = ReportStore.save(this@EcuLabActivity, "teshis", logBuffer.toString())
        log("💾 Teşhis raporu kaydedildi: ${saved.name}")
        toast("Teşhis tamam")
    }

    // ------------------------------------------- BEYİN TARAYICI

    private fun runScan() {
        if (!guard()) return
        busy = true
        lifecycleScope.launch { scanInternal(); busy = false }
    }

    private suspend fun scanInternal() {
        log("")
        log("══════ BEYİN TARAYICI ══════")

        log("")
        log("── MOD 01 desteklenen PID'ler ──")
        val supported = mutableListOf<String>()
        for (b in listOf("00", "20", "40", "60", "80", "A0", "C0")) {
            val raw = conn.sendCommand("01$b")
            if (isBad(raw)) break
            val p = payloadAfter(raw, "41$b") ?: break
            if (p.length < 8) break
            val mask = p.substring(0, 8).toLongOrNull(16) ?: break
            val baseInt = b.toInt(16)
            for (bit in 0 until 32) {
                if ((mask shr (31 - bit)) and 1L == 1L)
                    supported.add(String.format(Locale.US, "%02X", baseInt + bit + 1))
            }
            if ((mask and 1L) != 1L) break
            delay(60)
        }
        if (supported.isEmpty()) log("  ⚠️ Hiç PID okunamadı.")
        else {
            log("  Toplam ${supported.size} PID:")
            supported.chunked(12).forEach { log("    " + it.joinToString(" ")) }
        }

        log("")
        log("── DİĞER MODLAR ──")
        for ((cmd, name) in listOf(
            "0500" to "Mod 05 — Lambda sensör testleri",
            "0600" to "Mod 06 — İç test sonuçları",
            "07" to "Mod 07 — Bekleyen arızalar",
            "0800" to "Mod 08 — Aktüatör kontrolü",
            "0900" to "Mod 09 — Araç bilgisi (VIN)",
            "0A" to "Mod 0A — Kalıcı arıza kodları"
        )) {
            val raw = conn.sendCommand(cmd)
            when {
                isNegative(raw) -> log("  ❌ $name → DESTEKLENMİYOR (${negativeReason(raw)})")
                isBad(raw) -> log("  ❌ $name → cevap yok")
                else -> log("  ✅ $name → $raw")
            }
            delay(80)
        }

        log("")
        log("── ARIZA KODLARI ──")
        val a = ask("03", "aktif arızalar")
        val dec = com.example.nsobd2.data.DtcParser.decode(a, "43")
        if (dec.codes.isEmpty()) log("   Kod yok 👍")
        else dec.codes.forEach { log("   🚨 $it — ${com.example.nsobd2.data.DtcParser.meaning(it)}") }
        delay(80)
        ask("0101", "MIL durumu + arıza sayısı")

        log("")
        log("══════ TARAMA BİTTİ ══════")
    }

    // ------------------------------------------- MOD 02 (ARIZA ANI)

    private fun runMode02() {
        if (!guard()) return
        busy = true
        lifecycleScope.launch { mode02Internal(); busy = false }
    }

    private suspend fun mode02Internal() {
        log("")
        log("══════ MOD 02 — ARIZA ANI (Freeze Frame) ══════")

        val dtcRaw = conn.sendCommand("020200")
        log("→ 020200 (arızayı tetikleyen kod)")
        log("← ${if (dtcRaw.isBlank()) "(yok)" else dtcRaw}")
        val p = payloadAfter(dtcRaw, "420200")
        if (p != null && p.length >= 4 && p.substring(0, 4) != "0000") {
            val b1 = p.substring(0, 2).toIntOrNull(16)
            if (b1 != null) {
                val letter = when ((b1 shr 6) and 0x03) { 0 -> "P"; 1 -> "C"; 2 -> "B"; else -> "U" }
                val code = "$letter${(b1 shr 4) and 0x03}${String.format("%X", b1 and 0x0F)}${p.substring(2, 4)}"
                log("   🚨 Tetikleyen kod: $code")
                log("   ${com.example.nsobd2.data.DtcParser.meaning(code)}")
            }
        }

        val items = listOf(
            Triple("04", "Motor yükü", 1),
            Triple("05", "Soğutma suyu sıcaklığı", 1),
            Triple("06", "STFT", 1),
            Triple("07", "LTFT", 1),
            Triple("0B", "Emme manifold basıncı", 1),
            Triple("0C", "Motor devri", 2),
            Triple("0D", "Araç hızı", 1),
            Triple("0F", "Emilen hava sıcaklığı", 1),
            Triple("11", "Gaz kelebeği konumu", 1)
        )

        var any = false
        for ((pid, name, len) in items) {
            val raw = conn.sendCommand("02${pid}00")
            if (isBad(raw)) { delay(60); continue }
            val body = payloadAfter(raw, "42${pid}00") ?: continue
            if (body.length < len * 2) continue
            any = true
            val a = body.substring(0, 2).toIntOrNull(16) ?: 0
            val b = if (len == 2) body.substring(2, 4).toIntOrNull(16) ?: 0 else 0
            val txt = when (pid) {
                "04" -> "${f(a * 100.0 / 255.0)} %"
                "05" -> "${a - 40} °C"
                "06", "07" -> String.format(Locale.US, "%+.1f %%", (a - 128) * 100.0 / 128.0)
                "0B" -> "$a kPa"
                "0C" -> "${f((a * 256 + b) / 4.0, 0)} d/dk"
                "0D" -> "$a km/s"
                "0F" -> "${a - 40} °C"
                "11" -> "${f(a * 100.0 / 255.0)} %"
                else -> "$a"
            }
            log("  $name = $txt")
            delay(60)
        }
        if (!any) log("ℹ️ Kayıtlı arıza anı yok.")
        log("══════════════════════════════")
    }

    // ---------------------------------------------------------- TAM TARAMA

    private fun runAll() {
        if (!guard()) return
        busy = true
        lifecycleScope.launch {
            val ts = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US).format(Date())
            log("")
            log("╔══════════════════════════════╗")
            log("║   NSOBD2 — TAM BEYİN RAPORU  ║")
            log("╚══════════════════════════════╝")
            log("Tarih: $ts")
            log("Araç : Peugeot 206 1.1")
            ask("ATRV", "akü gerilimi")
            ask("ATDP", "protokol")

            scanInternal()
            mode02Internal()
            diagnosisInternal()

            log("")
            log("✅ TAM TARAMA TAMAMLANDI.")
            val saved = ReportStore.save(this@EcuLabActivity, "beyin", logBuffer.toString())
            log("💾 Rapor kaydedildi: ${saved.name}")
            busy = false
            toast("Tarama bitti, rapor kaydedildi")
        }
    }

    // ------------------------------------------------------------- PAYLAŞIM

    private fun shareReport() {
        try {
            val file = ReportStore.save(this, "beyin", logBuffer.toString())
            ReportStore.copyToClipboard(this, logBuffer.toString())
            toast("Kaydedildi: ${file.name} (+ panoya kopyalandı)")
            ReportStore.share(this, file)
        } catch (e: Exception) {
            toast("Kaydedilemedi: ${e.message}")
        }
    }
}