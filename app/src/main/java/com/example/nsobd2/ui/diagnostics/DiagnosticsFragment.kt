package com.example.nsobd2.ui.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.example.nsobd2.R
import com.example.nsobd2.data.DtcHistoryStore
import com.example.nsobd2.data.DtcParser
import com.example.nsobd2.data.ReportStore
import com.example.nsobd2.ui.ecu.EcuLabActivity
import com.example.nsobd2.viewmodel.AppScreen
import com.example.nsobd2.viewmodel.VehicleViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DiagnosticsFragment : Fragment(R.layout.fragment_diagnostics) {

    private val viewModel: VehicleViewModel by activityViewModels()

    private lateinit var tvReadiness: TextView
    private lateinit var tvActiveDtc: TextView
    private lateinit var tvPendingDtc: TextView
    private lateinit var tvRaw: TextView
    private lateinit var tvVin: TextView
    private lateinit var tvHistory: TextView

    private var lastReportText: String = ""
    private var busy = false

    override fun onResume() {
        super.onResume()
        viewModel.setActiveScreen(AppScreen.DIAGNOSTICS)
        viewModel.onReturnFromLab()
        refreshHistory()
        showSavedVin()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvReadiness = view.findViewById(R.id.tvReadiness)
        tvActiveDtc = view.findViewById(R.id.tvActiveDtc)
        tvPendingDtc = view.findViewById(R.id.tvPendingDtc)
        tvRaw = view.findViewById(R.id.tvRawResponse)
        tvVin = view.findViewById(R.id.tvVin)
        tvHistory = view.findViewById(R.id.tvHistory)

        view.findViewById<Button>(R.id.btnReadDtc).setOnClickListener { readAll() }
        view.findViewById<Button>(R.id.btnClearDtc).setOnClickListener { confirmClear() }
        view.findViewById<Button>(R.id.btnReadVin).setOnClickListener { readVin() }
        view.findViewById<Button>(R.id.btnCopyVin).setOnClickListener { copyVin() }
        view.findViewById<Button>(R.id.btnReadVin).setOnLongClickListener { askManualVin(); true }
        view.findViewById<Button>(R.id.btnShareHistory).setOnClickListener { shareHistory() }
        view.findViewById<Button>(R.id.btnClearHistory).setOnClickListener { confirmClearHistory() }
        view.findViewById<Button>(R.id.btnReports).setOnClickListener { showReports() }
        view.findViewById<Button>(R.id.btnEcuLab).setOnClickListener { openEcuLab() }

        refreshHistory()
        showSavedVin()
    }

    // ---------------------------------------------------------- YARDIMCILAR

    private fun prefs() = requireContext()
        .getSharedPreferences("vehicle_id_prefs", Context.MODE_PRIVATE)

    private fun toast(s: String) =
        Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()

    private fun connected(): Boolean {
        if (!viewModel.connection.isConnected) {
            toast("Önce araca bağlan!")
            return false
        }
        if (busy) { toast("Bekle, işlem sürüyor..."); return false }
        return true
    }

    private fun now(): String =
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US).format(Date())

    // ------------------------------------------------------ ARIZA OKUMA

    private fun readAll() {
        if (!connected()) return
        busy = true
        tvActiveDtc.text = "Okunuyor..."
        tvPendingDtc.text = "Okunuyor..."
        tvReadiness.text = "Sorgulanıyor..."
        tvRaw.text = "..."

        viewLifecycleOwner.lifecycleScope.launch {
            val conn = viewModel.connection
            val rawStat = conn.sendCommand("0101"); delay(120)
            val rawActive = conn.sendCommand("03");  delay(120)
            val rawPending = conn.sendCommand("07")

            // --- Durum / MIL
            val statHex = rawStat.uppercase().filter { it in "0123456789ABCDEF" }
            val statIdx = statHex.indexOf("4101")
            var milText = "Okunamadı"
            var dtcCount = -1
            if (statIdx >= 0 && statHex.length >= statIdx + 6) {
                val a = statHex.substring(statIdx + 4, statIdx + 6).toIntOrNull(16)
                if (a != null) {
                    val milOn = (a and 0x80) != 0
                    dtcCount = a and 0x7F
                    milText = if (milOn)
                        "🔴 Motor arıza lambası YANIYOR\nBeyinde $dtcCount kayıtlı arıza var."
                    else
                        "🟢 Motor arıza lambası SÖNÜK\nKayıtlı arıza sayısı: $dtcCount"
                }
            }
            tvReadiness.text = milText

            // --- Aktif kodlar
            val dActive = DtcParser.decode(rawActive, "43")
            tvActiveDtc.text = DtcParser.format(dActive, "AKTİF")

            // --- Bekleyen kodlar
            val dPending = DtcParser.decode(rawPending, "47")
            tvPendingDtc.text = DtcParser.format(dPending, "BEKLEYEN")

            // --- Ham cevap (yanlış çözüm olursa buradan anlaşılır)
            val sbRaw = StringBuilder()
            sbRaw.append("0101 → ").append(rawStat.ifBlank { "(boş)" }).append("\n")
            sbRaw.append("03   → ").append(rawActive.ifBlank { "(boş)" }).append("\n")
            sbRaw.append("07   → ").append(rawPending.ifBlank { "(boş)" }).append("\n")
            sbRaw.append("\nÇözüm yöntemi: ").append(dActive.method)
            if (dActive.alternative.isNotEmpty() && dActive.alternative != dActive.codes) {
                sbRaw.append("\nAlternatif okuma: ").append(dActive.alternative.joinToString(", "))
                sbRaw.append("\n(Kodlar tuhaf görünüyorsa alternatif doğru olabilir — bunu paylaş.)")
            }
            tvRaw.text = sbRaw.toString()

            // --- Geçmişe yaz
            DtcHistoryStore.add(requireContext(), DtcHistoryStore.TYPE_READ, dActive.codes, rawActive)
            if (dPending.codes.isNotEmpty()) {
                DtcHistoryStore.add(requireContext(), DtcHistoryStore.TYPE_PENDING, dPending.codes, rawPending)
            }

            // --- Raporu kalıcı kaydet
            lastReportText = buildReport(
                milText, dActive, dPending, rawStat, rawActive, rawPending, dtcCount
            )
            val f = ReportStore.save(requireContext(), "ariza", lastReportText)
            toast("Rapor kaydedildi: ${f.name}")

            refreshHistory()
            busy = false
        }
    }

    private fun buildReport(
        milText: String,
        dActive: DtcParser.Decoded,
        dPending: DtcParser.Decoded,
        rawStat: String, rawActive: String, rawPending: String,
        dtcCount: Int
    ): String {
        val sb = StringBuilder()
        sb.append("NSOBD2 — ARIZA OKUMA RAPORU\n")
        sb.append("Araç : ").append(com.example.nsobd2.data.AppPrefs.aracEtiketi(requireContext())).append("\n")
        sb.append("Tarih: ").append(now()).append("\n")
        sb.append("VIN  : ").append(prefs().getString("vin", "okunmadı")).append("\n")
        sb.append("\n── DURUM ──\n").append(milText).append("\n")
        sb.append("Beyindeki arıza sayısı: ").append(if (dtcCount >= 0) dtcCount else "?").append("\n")
        sb.append("\n── AKTİF KODLAR ──\n")
        sb.append(if (dActive.codes.isEmpty()) "Yok\n" else dActive.codes.joinToString("\n") { "$it — ${DtcParser.meaning(it)}" } + "\n")
        sb.append("\n── BEKLEYEN KODLAR ──\n")
        sb.append(if (dPending.codes.isEmpty()) "Yok\n" else dPending.codes.joinToString("\n") { "$it — ${DtcParser.meaning(it)}" } + "\n")
        sb.append("\n── HAM ECU CEVAPLARI ──\n")
        sb.append("0101 → ").append(rawStat).append("\n")
        sb.append("03   → ").append(rawActive).append("\n")
        sb.append("07   → ").append(rawPending).append("\n")
        sb.append("Çözüm yöntemi: ").append(dActive.method).append("\n")
        sb.append("Alternatif okuma: ").append(dActive.alternative.joinToString(", ")).append("\n")
        sb.append("\n── GEÇMİŞ ──\n")
        sb.append(DtcHistoryStore.exportText(requireContext()))
        return sb.toString()
    }

    // ------------------------------------------------------ ARIZA SİLME

    private fun confirmClear() {
        if (!connected()) return
        AlertDialog.Builder(requireContext())
            .setTitle("Arıza kodlarını sil")
            .setMessage(
                "Silmeden önce kodlar geçmişe kaydedilecek.\n\n" +
                        "⚠️ Kontak AÇIK olsun ama motor ÇALIŞMASIN.\n\n" +
                        "Devam edilsin mi?"
            )
            .setPositiveButton("SİL") { _, _ -> doClear() }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun doClear() {
        busy = true
        viewLifecycleOwner.lifecycleScope.launch {
            val conn = viewModel.connection

            // Önce mevcut kodları kaydet (silinince kaybolmasın)
            val before = DtcParser.decode(conn.sendCommand("03"), "43")
            DtcHistoryStore.add(
                requireContext(), DtcHistoryStore.TYPE_CLEAR, before.codes, before.rawResponse
            )
            val snapshot = StringBuilder()
            snapshot.append("NSOBD2 — SİLME ÖNCESİ KAYIT\n")
            snapshot.append("Tarih: ").append(now()).append("\n\n")
            snapshot.append(
                if (before.codes.isEmpty()) "Kod yoktu.\n"
                else before.codes.joinToString("\n") { "$it — ${DtcParser.meaning(it)}" }
            )
            snapshot.append("\n\nHam cevap: ").append(before.rawResponse)
            ReportStore.save(requireContext(), "ariza_silme_oncesi", snapshot.toString())

            delay(150)
            val res = conn.sendCommand("04")
            val ok = res.uppercase().contains("OK") || res.contains("44")

            tvActiveDtc.text = if (ok)
                "🧹 Silme komutu gönderildi.\n\nIşık sönmediyse kontağı tamamen kapat, 10 saniye bekle, tekrar aç ve KODLARI OKU'ya bas."
            else
                "❌ Silme komutu iletilemedi.\nHam cevap: $res"

            tvRaw.text = "04 → ${res.ifBlank { "(boş)" }}"
            refreshHistory()
            toast(if (ok) "Silindi (geçmişe kaydedildi)" else "Silinemedi")
            busy = false
        }
    }

    // ---------------------------------------------------------------- VIN

    private fun showSavedVin() {
        val v = prefs().getString("vin", null)
        if (!v.isNullOrBlank()) tvVin.text = v
    }

    private fun readVin() {
        if (!connected()) return
        busy = true
        tvVin.text = "Okunuyor..."
        viewLifecycleOwner.lifecycleScope.launch {
            val conn = viewModel.connection
            val attempts = listOf("0902", "09021", "0900")
            val log = StringBuilder()
            var vin: String? = null

            for (cmd in attempts) {
                val raw = conn.sendCommand(cmd)
                log.append("$cmd → ${raw.ifBlank { "(boş)" }}\n")
                if (cmd == "0900") break
                val parsed = decodeVin(raw)
                if (parsed != null) { vin = parsed; break }
                delay(200)
            }

            if (vin != null) {
                tvVin.text = vin
                prefs().edit().putString("vin", vin).apply()
                toast("Şasi numarası kaydedildi")
            } else {
                tvVin.text = "Araç Mod 09'u desteklemiyor (beyin 7F ile reddetti).\n" +
                        "Ruhsattan elle girebilirsin."
                askManualVin()
            }
            tvRaw.text = log.toString()
            busy = false
        }
    }

    private fun decodeVin(raw: String): String? {
        val hex = raw.uppercase().filter { it in "0123456789ABCDEF" }
        val idx = hex.indexOf("4902")
        if (idx < 0) return null
        var body = hex.substring(idx + 4)
        // İlk byte mesaj sayısı ise (01) atla
        if (body.length >= 2 && body.substring(0, 2) == "01") body = body.substring(2)
        val sb = StringBuilder()
        var i = 0
        while (i + 2 <= body.length) {
            val b = body.substring(i, i + 2).toIntOrNull(16) ?: 0
            if (b in 32..126) sb.append(b.toChar())
            i += 2
        }
        val vin = sb.toString().filter { it.isLetterOrDigit() }.take(17)
        return if (vin.length >= 10) vin else null
    }

    /** Araç VIN vermiyorsa ruhsattan elle girilir. */
    private fun askManualVin() {
        val input = android.widget.EditText(requireContext()).apply {
            hint = "VF32... (17 hane)"
            setSingleLine()
            filters = arrayOf(android.text.InputFilter.AllCaps(),
                android.text.InputFilter.LengthFilter(17))
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Şasi numarasını elle gir")
            .setMessage("Ruhsatının E satırında yazıyor. Bir kere gir, kalıcı kaydedilir.")
            .setView(input)
            .setPositiveButton("Kaydet") { _, _ ->
                val v = input.text.toString().trim().uppercase()
                if (v.length in 10..17) {
                    prefs().edit().putString("vin", v).apply()
                    tvVin.text = v
                    toast("Şasi numarası kaydedildi")
                } else toast("Geçersiz — en az 10 hane olmalı")
            }
            .setNegativeButton("Sonra", null)
            .show()
    }

    private fun copyVin() {
        val v = tvVin.text.toString()
        if (v.isBlank() || v.contains("Okunamadı") || v.contains("Henüz")) {
            toast("Önce şasi numarasını oku"); return
        }
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("VIN", v))
        toast("Panoya kopyalandı")
    }

    // ------------------------------------------------------------ GEÇMİŞ

    private fun refreshHistory() {
        if (::tvHistory.isInitialized) {
            tvHistory.text = DtcHistoryStore.formatted(requireContext())
        }
    }

    private fun shareHistory() {
        val text = DtcHistoryStore.exportText(requireContext())
        val f = ReportStore.save(requireContext(), "ariza_gecmisi", text)
        ReportStore.copyToClipboard(requireContext(), text)
        ReportStore.share(requireContext(), f)
        toast("Kaydedildi + panoya kopyalandı")
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(requireContext())
            .setTitle("Geçmişi sil")
            .setMessage("Tüm arıza geçmişi silinsin mi? Kayıtlı raporlar silinmez.")
            .setPositiveButton("Sil") { _, _ ->
                DtcHistoryStore.clear(requireContext())
                refreshHistory()
                toast("Geçmiş silindi")
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    // --------------------------------------------------- KAYITLI RAPORLAR

    private fun showReports() {
        val files = ReportStore.list(requireContext())
        if (files.isEmpty()) { toast("Kayıtlı rapor yok"); return }
        val labels = files.map { ReportStore.label(it) }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("Kayıtlı raporlar (${files.size})")
            .setItems(labels) { _, which ->
                val f = files[which]
                val content = ReportStore.read(f)
                AlertDialog.Builder(requireContext())
                    .setTitle(f.name)
                    .setMessage(content.take(4000))
                    .setPositiveButton("Paylaş") { _, _ -> ReportStore.share(requireContext(), f) }
                    .setNeutralButton("Panoya kopyala") { _, _ ->
                        ReportStore.copyToClipboard(requireContext(), content)
                        toast("Panoya kopyalandı")
                    }
                    .setNegativeButton("Kapat", null)
                    .show()
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    // ---------------------------------------------------------- BEYİN LAB

    private fun openEcuLab() {
        AlertDialog.Builder(requireContext())
            .setTitle("🧠 Beyin Lab")
            .setMessage(
                "Beyin Lab derin tarama yapar (Mod 05 / 06 / 02, PID taraması).\n\n" +
                        "Ana bağlantıyı kullanır; bağlı değilsen kendisi bağlanır. " +
                        "Lab açıkken ana ekran verisi durur, çıkınca kendiliğinden devam eder.\n\n" +
                        "Motor sıcakken en iyi sonucu verir."
            )
            .setPositiveButton("Aç") { _, _ ->
                startActivity(Intent(requireContext(), EcuLabActivity::class.java))
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }
}