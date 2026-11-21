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
import com.example.nsobd2.data.DtcDatabase
import com.example.nsobd2.data.EcuDiscovery
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

    // Ana uygulamayla AYNI baglanti — ikinci soket ELM'yi kilitliyordu
    private val conn = com.example.nsobd2.network.ElmShared.connection
    private var labOturum = 0
    companion object { @Volatile private var labOturumSayaci = 0 }

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
        root.addView(bigButton("⚙️ ARAÇ AYARLARI (motor hacmi)", "#484F58") { aracAyarlari() })
        root.addView(bigButton("🔬 LAMBDA TESTİ (gaz ver-bırak)", "#0E7490") { lambdaTesti() })
        root.addView(bigButton("🧪 PİN TESTİ (tam teşhis)", "#B91C1C") { pinTesti() })
        root.addView(bigButton("⛔ TESTİ DURDUR", "#6E7681") {
            com.example.nsobd2.data.PinTesti.iptal = true
            toast("Durduruluyor... (birkaç saniye sürebilir)")
        })

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
        log("${AppPrefs.aracEtiketi(this)} • ISO 14230-4 KWP FAST")
        log("──────────────────────────────")
        labOturumSayaci++; labOturum = labOturumSayaci
        com.example.nsobd2.network.ElmShared.labActive = true
        doConnect()
    }

    override fun onDestroy() {
        super.onDestroy()
        // lifecycleScope burada iptal edilmis olur (eskiden kapatma hic calismiyordu).
        // Testler ELM ayarlarini (header, adres, baud) degistirmis olabilir: temiz ayarla yeniden kur.
        val benim = labOturum
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try { conn.restoreDefaults() } catch (_: Exception) {}
            // Bu arada yeni bir Lab acildiysa onun bayragini silme
            if (benim == labOturumSayaci) com.example.nsobd2.network.ElmShared.labActive = false
        }
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

    /**
     * Bağlantı canlı mı? Koptuysa otomatik yeniden bağlanır.
     * (TAM TARAMA sırasında ELM sessizleşip tüm rapor boş çıkmıştı.)
     */
    private suspend fun ensureConnected(): Boolean {
        val test = conn.sendCommand("ATRV")
        if (test.isNotBlank() && test.uppercase().contains("V")) return true

        log("")
        log("⚠️ Bağlantı koptu — yeniden bağlanılıyor...")
        status("🟡 Yeniden bağlanılıyor...")
        try { conn.disconnect() } catch (_: Exception) {}
        delay(700)
        val ip = etIp.text.toString().trim().ifEmpty { "192.168.0.10" }
        val port = etPort.text.toString().trim().toIntOrNull() ?: 35000
        val res = withContext(Dispatchers.IO) { conn.connect(ip, port) { } }
        log("   $res")
        status(if (conn.isConnected) "🟢 Bağlı" else "🔴 Bağlantı yok")
        if (!conn.isConnected) {
            log("❌ Bağlanamadı. ELM327'nin Wi-Fi'ına bağlı mısın? Kontak açık mı?")
            return false
        }
        log("✅ Bağlantı geri geldi, devam ediliyor.")
        return true
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
            try {
                val ip = etIp.text.toString().trim().ifEmpty { "192.168.0.10" }
                val port = etPort.text.toString().trim().toIntOrNull() ?: 35000
                // Ana ekran zaten bagliysa ve ELM cevap veriyorsa yeniden baglanma
                val zatenBagli = conn.isConnected && conn.sendCommand("ATRV").uppercase().contains("V")
                val res = if (zatenBagli) "Ana bağlantı kullanılıyor" else {
                    log("── $ip:$port bağlanılıyor...")
                    kotlinx.coroutines.withTimeoutOrNull(40_000) {
                        withContext(Dispatchers.IO) {
                            conn.connect(ip, port) { s -> status("🟡 $s"); log("   $s") }
                        }
                    } ?: "HATA: 40 sn içinde bağlanamadı (ELM'yi çıkar-tak, Wi-Fi'yi kontrol et)"
                }
                log("── $res")
                status(if (conn.isConnected) "🟢 Bağlı" else "🔴 $res")
                if (conn.isConnected) {
                    ask("ATI", "ELM sürümü")
                    ask("ATDP", "aktif protokol")
                    ask("ATRV", "akü gerilimi")
                }
            } catch (e: Exception) {
                log("── HATA: ${e.message}")
                status("🔴 Bağlantı hatası — BAĞLAN'a tekrar bas")
            } finally {
                busy = false   // eskiden hata olursa busy true kalip BAGLAN butonu olu kaliyordu
            }
        }
    }

    private fun sendManual() {
        val cmd = etCmd.text.toString().trim().uppercase()
        if (cmd.isEmpty()) return
        if (!guard()) return
        // GÜVENLİK SÜZGECİ — riskli servisler elle de gönderilemez
        val izin = EcuDiscovery.izinKontrol(cmd)
        if (!izin.izinli) {
            log("")
            log("⛔ KOMUT ENGELLENDİ: $cmd")
            log("   ${izin.sebep}")
            toast("Bu komut güvenlik nedeniyle engelli")
            return
        }

        busy = true
        lifecycleScope.launch {
            log("──────────────────────────────")
            val r = ask(cmd)
            etCmd.setText("")
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
        if (!ensureConnected()) return

        log("")
        log("══════ 🩺 CANLI TEŞHİS ══════")
        log("Motor rölantide ve SICAK olmalı. 30 saniye sürecek, dokunma.")
        log("")

        val rpm = mutableListOf<Double>(); val cool = mutableListOf<Double>()
        val stft = mutableListOf<Double>(); val ltft = mutableListOf<Double>()
        val map = mutableListOf<Double>(); val o2a = mutableListOf<Double>()
        val o2b = mutableListOf<Double>(); val load = mutableListOf<Double>()
        val adv = mutableListOf<Double>(); val iat = mutableListOf<Double>()

        val samples = 25
        var basarisiz = 0
        var gecersizSu: Double? = null

        for (i in 1..samples) {
            val r = pid2("0C")
            if (r == null) basarisiz++ else rpm.add(r / 4.0)

            pid1("05")?.let {
                val c = (it - 40).toDouble()
                if (AppPrefs.isCoolantValid(c)) cool.add(c) else gecersizSu = c
            }
            pid1("06")?.let { stft.add((it - 128) * 100.0 / 128.0) }
            pid1("07")?.let { ltft.add((it - 128) * 100.0 / 128.0) }
            pid1("0B")?.let { map.add(it.toDouble()) }
            pid1("14")?.let { o2a.add(it / 200.0) }
            pid1("15")?.let { o2b.add(it / 200.0) }
            pid1("04")?.let { load.add(it * 100.0 / 255.0) }
            pid1("0E")?.let { adv.add(it / 2.0 - 64.0) }
            pid1("0F")?.let { iat.add((it - 40).toDouble()) }

            if (basarisiz >= 4) {
                log("⚠️ Veri gelmiyor, bağlantı kontrol ediliyor...")
                if (!ensureConnected()) { log("❌ Teşhis iptal edildi."); return }
                basarisiz = 0
            }
            if (i % 5 == 0) log("   ölçüm $i/$samples...")
        }

        if (rpm.isEmpty()) {
            log("❌ Hiç veri okunamadı. Motor çalışıyor mu? Kontak açık mı?")
            return
        }

        log("")
        log("── ÖLÇÜM ÖZETİ ──")
        fun row(name: String, l: List<Double>, unit: String, d: Int = 1) {
            if (l.isEmpty()) { log("  $name: okunamadı"); return }
            log("  $name: ort ${f(avg(l), d)} $unit  (en az ${f(l.min(), d)} / en çok ${f(l.max(), d)}, dalga ${f(sd(l), d + 1)})")
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

        // =============================== KURALLAR
        log("")
        log("── 🔎 TEŞHİS ──")
        val bulgular = mutableListOf<String>()

        val suOrt = avg(cool)
        val motorSicak = cool.isNotEmpty() && suOrt >= 82
        val acikCevrim = stft.size >= 10 && sd(stft) < 0.01
        val stftTavan = stft.isNotEmpty() && stft.any { kotlin.math.abs(it) >= 24.0 }

        // 0) SENSÖR SÖKÜK / KOPUK
        val gs = gecersizSu
        if (gs != null) {
            bulgular.add(
                "🔴 SU SICAKLIK SENSÖRÜ OKUNMUYOR (${f(gs, 0)} °C)\n" +
                        "   ${AppPrefs.sensorFaultText(gs)}\n" +
                        "   Beyin motorun sıcaklığını bilmiyor:\n" +
                        "   • Fan devreye girmez → HARARET RİSKİ\n" +
                        "   • Soğuk çalıştırma karışımı basar → zengin, tekleme, fazla yakıt\n" +
                        "   Soketi tak ya da sensörü değiştir. Bu hâlde uzun sürme.")
        }

        // 0b) ISINMA KAPISI
        if (cool.isNotEmpty() && !motorSicak) {
            bulgular.add(
                "⚠️ MOTOR YETERİNCE SICAK DEĞİL (${f(suOrt, 0)} °C)\n" +
                        "   Güvenilir teşhis için 85-95 °C gerekir.\n" +
                        "   Aşağıdaki karışım ve lambda sonuçlarını KESİN kabul etme.")
        }

        // 1) ÖN LAMBDA
        var onLambdaSaglikli = false
        if (o2a.size >= 5) {
            val ort = avg(o2a); val dalga = sd(o2a)
            when {
                ort in 0.40..0.50 && dalga < 0.03 && acikCevrim ->
                    bulgular.add(
                        "🟠 ÖN LAMBDA DEĞERLENDİRİLEMİYOR\n" +
                                "   Değer ${f(ort, 3)} V'ta çakılı ama beyin de açık çevrimde.\n" +
                                "   Beyin sensörü kullanmadığı için kabloda sadece 0.45 V referans\n" +
                                "   gerilimi görünüyor. Sensör ölü OLABİLİR ama kanıt değil.\n" +
                                "   Önce açık çevrimin sebebini çöz, sonra tekrar ölç.")
                ort in 0.40..0.50 && dalga < 0.03 ->
                    bulgular.add(
                        "🔴 ÖN LAMBDA SENSÖRÜ ÖLÜ / KOPUK\n" +
                                "   Değer ${f(ort, 3)} V'ta çakılı, oynama ${f(dalga, 3)} V.\n" +
                                "   Kontrol sırası:\n" +
                                "   1. Soket (egzoz manifoldu üstü, 4 kablolu) — oksit/yanık\n" +
                                "   2. Isıtıcı sigortası\n" +
                                "   3. Egzoz kaçağı\n" +
                                "   4. Sensör değişimi")
                o2a.max() < 0.60 ->
                    bulgular.add(
                        "🔴 ÖN LAMBDA YORULMUŞ\n" +
                                "   Hiç ${f(o2a.max(), 2)} V'un üstüne çıkmamış.\n" +
                                "   Sağlıklı sensör 0.85 V'a kadar çıkar. Yakıt tüketimini artırır.")
                dalga < 0.05 ->
                    bulgular.add("🔴 ÖN LAMBDA TEMBEL (oynama ${f(dalga, 3)} V) — sensör yorulmuş.")
                else -> {
                    onLambdaSaglikli = true
                    bulgular.add("🟢 Ön lambda sağlıklı salınıyor (ort ${f(ort, 3)} V, oynama ${f(dalga, 3)})")
                }
            }
        }

        // 2) AÇIK ÇEVRİM
        if (stft.size >= 10) {
            if (acikCevrim) {
                bulgular.add(
                    "🔴 BEYİN AÇIK ÇEVRİMDE (kapalı çevrime geçemiyor)\n" +
                            "   STFT ${stft.size} ölçümün hepsinde ${f(avg(stft))}% — hiç oynamamış.\n" +
                            "   Beyin lambda sensörünü kullanmıyor, haritadan sabit yakıt basıyor.\n" +
                            "   Sonuç: fazla yakıt, katalizör ömrü kısalması.\n" +
                            "   Sebep: su sıcaklık sensörü arızası ya da ölü ön lambda.")
            } else if (stftTavan) {
                val yon = if (avg(stft) > 0) "EKLİYOR" else "KISIYOR"
                bulgular.add(
                    "🔴 BEYİN DÜZELTMEYİ TAVANA DAYAMIŞ (${f(stft.maxOf { kotlin.math.abs(it) })}%)\n" +
                            "   Beyin yapabileceği en fazla yakıtı $yon ama yetmiyor.\n" +
                            (if (avg(stft) > 0)
                                "   FAKİR tarafta: vakum kaçağı (emme contası, fren servosu hortumu),\n" +
                                        "   tıkalı enjektör, düşük yakıt basıncı, yorulmuş lambda."
                            else
                                "   ZENGİN tarafta: kaçıran enjektör, tıkalı hava filtresi,\n" +
                                        "   yüksek yakıt basıncı, arızalı su sıcaklık sensörü."))
            } else {
                bulgular.add("🟢 Kapalı çevrim çalışıyor (STFT oynaması ${f(sd(stft), 2)}%)")
            }
        }

        // 3) KARIŞIM
        if (stft.isNotEmpty() && ltft.isNotEmpty() && !acikCevrim) {
            val toplam = avg(stft) + avg(ltft)
            when {
                toplam > 15 -> bulgular.add(
                    "🔴 ÇOK FAKİR KARIŞIM (+${f(toplam)}%)\n" +
                            "   1. Emme manifold contası kaçağı (206'da 1 numara)\n" +
                            "   2. Fren servosu hortumu\n3. Kirli MAP\n4. Tıkalı enjektör")
                toplam > 8 -> bulgular.add("🟠 Hafif fakir karışım (+${f(toplam)}%)")
                toplam < -15 -> bulgular.add(
                    "🔴 ÇOK ZENGİN KARIŞIM (${f(toplam)}%)\n" +
                            "   1. LPG enjektörü kaçırıyor\n2. Tıkalı hava filtresi\n" +
                            "   3. Arızalı su sıcaklık sensörü")
                toplam < -8 -> bulgular.add("🟠 Hafif zengin karışım (${f(toplam)}%)")
                else -> bulgular.add("🟢 Karışım normal (${f(toplam)}%)")
            }
        } else if (stft.isNotEmpty()) {
            bulgular.add("⚪ Karışım değerlendirilemedi — beyin açık çevrimde.")
        }

        // 4) ARKA LAMBDA
        if (o2b.size >= 5) {
            val ortB = avg(o2b)
            when {
                ortB > 0.70 && sd(o2b) < 0.05 -> bulgular.add(
                    "🔴 MOTOR ZENGİN ÇALIŞIYOR (arka lambda ${f(ortB, 3)} V'ta sabit)\n" +
                            "   Egzozda oksijen kalmıyor — katalizöre sürekli fazla yakıt gidiyor.\n" +
                            "   Bu aynı zamanda ARKA sensörün ve tesisatın SAĞLAM olduğunu gösterir;\n" +
                            "   sorun ön sensörde. Uzun sürerse katalizör ölür (P0420).")
                ortB < 0.15 && sd(o2b) < 0.05 -> bulgular.add(
                    "🟠 Arka lambda çok düşükte sabit (${f(ortB, 3)} V) — fakir çalışma ya da egzoz kaçağı.")
                else -> bulgular.add("🟢 Arka lambda normal (${f(ortB, 3)} V)")
            }
        }

        // 5) KATALİZÖR
        if (o2b.size >= 5 && o2a.size >= 5) {
            if (!onLambdaSaglikli) {
                bulgular.add("⚪ Katalizör değerlendirilemedi — önce ön lambdayı çöz.")
            } else if (sd(o2b) > sd(o2a) * 0.7 && sd(o2a) > 0.05) {
                bulgular.add(
                    "🟠 KATALİZÖR ZAYIFLAMIŞ OLABİLİR\n" +
                            "   Arka lambda neredeyse ön kadar oynuyor\n" +
                            "   (ön ${f(sd(o2a), 3)} / arka ${f(sd(o2b), 3)}). P0420'nin habercisi.")
            } else bulgular.add("🟢 Katalizör verimi normal görünüyor")
        }

        // 6) SU SICAKLIĞI
        if (cool.isNotEmpty()) {
            when {
                suOrt < 70 -> bulgular.add(
                    "🔴 SU SICAKLIĞI ÇOK DÜŞÜK (${f(suOrt, 0)} °C)\n" +
                            "   1. Termostat açık kalmış  2. Su müşürü arızalı")
                suOrt < 82 -> bulgular.add("🟠 Su sıcaklığı düşük (${f(suOrt, 0)} °C) — motor tam ısınmamış olabilir.")
                suOrt > 100 -> bulgular.add(
                    "🔴 MOTOR HARARET YAPIYOR (${f(suOrt, 0)} °C)\n" +
                            "   FANI, termostatı ve su seviyesini HEMEN kontrol et. Yola çıkma.")
                else -> bulgular.add("🟢 Su sıcaklığı normal (${f(suOrt, 0)} °C)")
            }
        }

        // 7) RÖLANTİ
        if (rpm.isNotEmpty()) {
            val devirOrt = avg(rpm); val devirDalga = sd(rpm)
            if (devirDalga > 60) bulgular.add(
                "🟠 RÖLANTİ DALGALI (±${f(devirDalga, 0)} d/dk)\n" +
                        "   Kelebek boğazı kurumu, rölanti motoru ya da vakum kaçağı.")
            else bulgular.add("🟢 Rölanti kararlı (${f(devirOrt, 0)} d/dk, ±${f(devirDalga, 0)})")
            if (devirOrt > 1100) bulgular.add("🟠 Rölanti yüksek (${f(devirOrt, 0)} d/dk). Normal: 750-900.")
        }

        // 8) MAP
        if (map.isNotEmpty() && avg(rpm) < 1200) {
            val m = avg(map)
            when {
                m > 50 -> bulgular.add("🟠 Rölantide MAP yüksek (${f(m, 0)} kPa). Normal 30-45. Hava kaçağı olabilir.")
                m < 20 -> bulgular.add("🟠 MAP çok düşük (${f(m, 0)} kPa) — sensör veya hortum sorunu.")
                else -> bulgular.add("🟢 MAP normal (${f(m, 0)} kPa)")
            }
        }

        bulgular.forEach { log("\n$it") }

        val kirmizi = bulgular.filter { it.startsWith("🔴") }
        if (kirmizi.isNotEmpty()) {
            log("")
            log("── ⚡ ÖNCELİK SIRASI ──")
            kirmizi.forEachIndexed { i, b ->
                log("  ${i + 1}. " + b.lines().first().removePrefix("🔴 "))
            }
        }

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
        if (!ensureConnected()) return
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
        else dec.codes.forEach { log("   🚨 $it — ${DtcDatabase.tam(it)}") }
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
        if (!ensureConnected()) return
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
                log("   ${DtcDatabase.tam(code)}")
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
            log("Araç : ${AppPrefs.aracEtiketi(this@EcuLabActivity)}")
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

    /**
     * Motor hacmi yakıt hesabının en kritik girdisi.
     * Eskiden koda 1.1 L gömülüydü; araç 1.4 çıkınca tüm tüketim
     * hesapları %27 düşük çıkıyordu.
     */
    private fun aracAyarlari() {
        val kutu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        kutu.addView(TextView(this).apply {
            text = "Motor hacmi (litre)"; setTypeface(null, Typeface.BOLD)
        })
        val etHacim = EditText(this).apply {
            setText(f(AppPrefs.getEngineLiters(this@EcuLabActivity), 1))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "örn: 1.4"
        }
        kutu.addView(etHacim)
        kutu.addView(TextView(this).apply {
            text = "\nVolumetrik verim (0.75 - 0.95)"; setTypeface(null, Typeface.BOLD)
        })
        val etVe = EditText(this).apply {
            setText(f(AppPrefs.getVolumetricEfficiency(this@EcuLabActivity), 2))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "örn: 0.85"
        }
        kutu.addView(etVe)
        kutu.addView(TextView(this).apply {
            text = "\nMotor hacmi yakıt hesabını doğrudan etkiler.\n" +
                    "Ruhsatın P.1 satırında cm³ olarak yazar: 1400 cm³ = 1.4 L"
            textSize = 12f
        })

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("⚙️ Araç Ayarları")
            .setView(kutu)
            .setPositiveButton("Kaydet") { _, _ ->
                val h = etHacim.text.toString().replace(',', '.').toDoubleOrNull()
                val v = etVe.text.toString().replace(',', '.').toDoubleOrNull()
                if (h != null && h in 0.5..8.0) {
                    AppPrefs.setEngineLiters(this, h)
                    if (v != null && v in 0.5..1.0) AppPrefs.setVolumetricEfficiency(this, v)
                    log("")
                    log("⚙️ Motor hacmi ${f(h, 1)} L kaydedildi. Yakıt hesabı buna göre çalışacak.")
                    toast("Kaydedildi: ${f(h, 1)} L")
                } else toast("Geçersiz hacim")
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    // ==================================================== AŞAMA 5A — KEŞİF

    // =================================================================
    //  LAMBDA TESTİ — rölanti + gaz ver/bırak
    // =================================================================
    // Teşhis Et sadece rölantiye bakıyor. Rölantide lambda düşük kalıyorsa
    // iki ihtimal var: sensör ölü mü, yoksa motor gerçekten fakir mi?
    // Gaz verince her sağlam sensör 0.8 V üstüne çıkar (zengin karışım),
    // gazı bırakınca 0.2 V altına iner (yakıt kesme). Bu test ikisini ayırır.

    private fun lambdaTesti() {
        if (!guard()) return
        val yakit = if (AppPrefs.isLpgMode(this)) "LPG" else "BENZİN"
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🔬 Lambda Testi")
            .setMessage(
                "Şu an yakıt modu: $yakit (ayarlardan değişir)\n\n" +
                        "• Motor ÇALIŞIYOR ve SICAK olmalı (fan bir kez çalışmış olsun)\n" +
                        "• Araç boşta, el freni çekili\n\n" +
                        "1) 15 sn rölanti ölçümü — dokunma\n" +
                        "2) Bip sesinden sonra: gaza SONUNA kadar bas, 2 sn tut, BIRAK.\n" +
                        "   Bunu 3 kez tekrarla (20 sn).\n\n" +
                        "LPG'li araçta ikisini de ayrı ayrı yapmak en iyisi."
            )
            .setPositiveButton("Başlat") { _, _ ->
                busy = true
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                lifecycleScope.launch {
                    try { lambdaTestiIc(yakit) } catch (e: Exception) { log("❌ Lambda testi hatası: ${e.message}") }
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    busy = false
                }
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun bip() {
        try {
            android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100)
                .startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 600)
        } catch (_: Exception) {}
    }

    /** 0.45 V çizgisini kaç kez geçti (zengin<->fakir salınım sayısı). */
    private fun gecisSayisi(l: List<Double>): Int {
        var n = 0
        for (i in 1 until l.size) if ((l[i - 1] < 0.45) != (l[i] < 0.45)) n++
        return n
    }

    private suspend fun lambdaTestiIc(yakit: String) {
        if (!ensureConnected()) return
        log("")
        log("══════ 🔬 LAMBDA TESTİ ($yakit) ══════")

        val rpm0 = pid2("0C")?.let { it / 4.0 }
        if (rpm0 == null || rpm0 < 400) {
            log("❌ Motor çalışmıyor (devir: ${rpm0?.toInt() ?: "okunamadı"}). Motoru çalıştırıp tekrar dene.")
            return
        }
        val su = pid1("05")?.let { it - 40 }
        log("Devir: ${rpm0.toInt()} d/dk, Su: ${su ?: "?"} °C")
        if (su != null && su < 70) {
            log("⚠️ Motor henüz soğuk ($su °C). Lambda 70°C altında güvenilir değil.")
            log("   Biraz ısınsın, sonra tekrar dene.")
            return
        }

        // ---- 1) RÖLANTİ
        log("")
        log("── 1/2  Rölanti ölçümü (15 sn) — dokunma ──")
        val onR = mutableListOf<Double>(); val arkaR = mutableListOf<Double>()
        val stftR = mutableListOf<Double>(); val ltftR = mutableListOf<Double>()
        val durumlar = mutableListOf<Int>()
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 15_000) {
            pid1("14")?.let { onR.add(it / 200.0) }
            pid1("15")?.let { arkaR.add(it / 200.0) }
            pid1("06")?.let { stftR.add((it - 128) * 100.0 / 128.0) }
            if (durumlar.size < 5) pid1("03")?.let { durumlar.add(it) }
        }
        pid1("07")?.let { ltftR.add((it - 128) * 100.0 / 128.0) }
        if (onR.size < 5) {
            log("❌ Ön lambda (PID 14) okunamadı. Bağlantıyı kontrol et.")
            return
        }
        val kapaliCevrim = durumlar.any { it == 2 }
        log("   Ön lambda : ort ${f(avg(onR), 2)} V (${f(onR.min(), 2)}–${f(onR.max(), 2)}), ${gecisSayisi(onR)} salınım")
        if (arkaR.isNotEmpty())
            log("   Arka lambda: ort ${f(avg(arkaR), 2)} V (${f(arkaR.min(), 2)}–${f(arkaR.max(), 2)}), ${gecisSayisi(arkaR)} salınım")
        if (stftR.isNotEmpty()) log("   Kısa düzeltme (STFT): ort ${f(avg(stftR))} %")
        if (ltftR.isNotEmpty()) log("   Uzun düzeltme (LTFT): ${f(ltftR.first())} %")
        log("   Yakıt sistemi: ${when {
            durumlar.isEmpty() -> "okunamadı"
            kapaliCevrim -> "kapalı çevrim (lambdayı kullanıyor) ✅"
            durumlar.any { it == 1 } -> "açık çevrim — motor soğuk sayılıyor"
            durumlar.any { it == 8 || it == 16 } -> "ARIZA yüzünden açık çevrim ⚠️"
            else -> "açık çevrim (kod ${durumlar.first()})"
        }}")

        // ---- 2) GAZ VER / BIRAK
        log("")
        log("── 2/2  BİP'ten sonra: gaza SONUNA kadar bas, 2 sn tut, BIRAK. 3 kez. ──")
        delay(1500); bip()
        val onG = mutableListOf<Pair<Double, Double>>()   // (devir, voltaj)
        val t1 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t1 < 20_000) {
            val r = pid2("0C")?.let { it / 4.0 } ?: continue
            val v = pid1("14")?.let { it / 200.0 } ?: continue
            onG.add(r to v)
        }
        bip()
        log("   Bitti, gazı bırakabilirsin. (${onG.size} ölçüm)")

        val maxDevir = onG.maxOfOrNull { it.first } ?: 0.0
        val gazdaMax = onG.filter { it.first > 2000 }.maxOfOrNull { it.second }
        val tumMax = onG.maxOfOrNull { it.second } ?: 0.0
        val tumMin = onG.minOfOrNull { it.second } ?: 1.0
        log("   En yüksek devir: ${maxDevir.toInt()} d/dk")
        log("   Ön lambda en yüksek: ${f(tumMax, 2)} V, en düşük: ${f(tumMin, 2)} V")

        // ---- HÜKÜM
        log("")
        log("══════ SONUÇ ══════")
        val sonuc = mutableListOf<String>()
        var sensorSaglam = false

        if (maxDevir < 2000) {
            sonuc += "⚪ Gaz testi geçersiz: devir 2000'i geçmedi (${maxDevir.toInt()}). Gaza daha sert bas, tekrar dene."
        } else {
            val zirve = gazdaMax ?: tumMax
            when {
                zirve >= 0.75 -> {
                    sensorSaglam = true
                    sonuc += "🟢 Ön lambda SAĞLAM — gaz verince ${f(zirve, 2)} V'a çıktı (zengini görüyor)."
                }
                zirve >= 0.60 -> sonuc += "🟠 Ön lambda YORGUN — gazda sadece ${f(zirve, 2)} V (sağlam sensör 0.8 V üstü). Yakında değişim."
                else -> sonuc += "🔴 Ön lambda ÖLÜ — gaz verince bile ${f(zirve, 2)} V. Zengin karışımı göremiyor, DEĞİŞMELİ."
            }
            if (tumMin <= 0.20) sonuc += "🟢 Gazı bırakınca ${f(tumMin, 2)} V'a indi (yakıt kesme doğru algılandı)."
            else sonuc += "🟠 Gazı bırakınca sadece ${f(tumMin, 2)} V'a indi (0.2 V altı beklenir) — sensör yavaş."
        }

        val onOrt = avg(onR)
        val stftOrt = if (stftR.isEmpty()) 0.0 else avg(stftR)
        val toplamDuzeltme = stftOrt + (ltftR.firstOrNull() ?: 0.0)
        if (sensorSaglam && onOrt < 0.35 && toplamDuzeltme > 10) {
            sonuc += "🟠 Sensör sağlam ama rölantide motor FAKİR (düzeltme +${f(toplamDuzeltme)}%).\n" +
                    "   Sensörü değiştirmek çözmez. Bakılacaklar: emme manifoldu/vakum hortumu kaçağı" +
                    (if (yakit == "LPG") ",\n   LPG beyni/regülatör ayarı (benzinde de test et, benzinde normalse sorun LPG ayarında)." else
                        ", yakıt basıncı, enjektörler.")
        } else if (toplamDuzeltme < -10) {
            sonuc += "🟠 Motor ZENGİN çalışıyor (düzeltme ${f(toplamDuzeltme)}%). Hava filtresi, kaçıran enjektör" +
                    (if (yakit == "LPG") ", LPG fazla gaz veriyor olabilir." else ", su sıcaklık sensörü.")
        } else if (sensorSaglam) {
            sonuc += "🟢 Karışım dengeli (toplam düzeltme ${f(toplamDuzeltme)}%)."
        }

        val onGecis = gecisSayisi(onR)
        if (kapaliCevrim && onGecis < 2)
            sonuc += "🟠 Rölantide ön lambda neredeyse hiç salınmadı ($onGecis kez/15 sn) — tembel sensör ya da sürekli fakir/zengin."

        if (arkaR.size >= 10 && onGecis >= 4) {
            val arkaGecis = gecisSayisi(arkaR)
            if (arkaGecis >= onGecis * 0.7)
                sonuc += "🔴 Arka lambda ön kadar oynuyor ($arkaGecis/$onGecis) — KATALİZÖR verimi düşük."
            else
                sonuc += "🟢 Katalizör çalışıyor (arka lambda sakin: ${f(avg(arkaR), 2)} V)."
        }

        sonuc.forEach { log(it) }
        val saved = ReportStore.save(this, "lambda", logBuffer.toString())
        log("💾 Kaydedildi: ${saved.name}")
    }

    /** Tum baglanti testleri tek butonda: hizli/yavas init, baud, anahtar byte. */
    private fun pinTesti() {
        if (!guard()) return
        val etPin = EditText(this).apply {
            hint = "Araç pini (köprü yoksa 7)"
            setText("7")
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🧪 Pin Testi")
            .setMessage(
                "ELM'nin 7. pini şu an aracın hangi pinine bağlı?\n" +
                        "(Köprü yapmadıysan 7 kalsın.)\n\n" +
                        "• Kontak AÇIK, motor KAPALI\n" +
                        "• Akü sağlam, telefon şarjda olsun\n\n" +
                        "Normal: ~15 dk\nDerin (3 baud hızı): ~40 dk"
            )
            .setView(etPin)
            .setPositiveButton("Normal") { _, _ -> pinTestiBaslat(etPin.text.toString(), false) }
            .setNeutralButton("Derin") { _, _ -> pinTestiBaslat(etPin.text.toString(), true) }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun pinTestiBaslat(pinGirdi: String, derin: Boolean) {
        val pin = pinGirdi.trim().ifEmpty { "7" }
        busy = true
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lifecycleScope.launch {
            try {
                if (ensureConnected()) {
                    com.example.nsobd2.data.PinTesti.calistir(
                        pin = pin,
                        derin = derin,
                        // Pin testinde init komutlari uzun surebilir: '>' gelene kadar bekle
                        gonder = { cmd -> conn.sendCommandLong(cmd) },
                        yaz = { satir -> log(satir) }
                    )
                    val saved = ReportStore.save(this@EcuLabActivity, "pin$pin", logBuffer.toString())
                    log("💾 Pin testi raporu kaydedildi: ${saved.name}")
                    toast("Pin $pin testi tamam")
                }
            } catch (e: Exception) {
                log("❌ Pin testi hatası: ${e.message}")
            }
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            busy = false
        }
    }

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