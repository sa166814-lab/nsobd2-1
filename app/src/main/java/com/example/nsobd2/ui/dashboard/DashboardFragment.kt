package com.example.nsobd2.ui.dashboard

import android.app.AlertDialog
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.nsobd2.R
import com.example.nsobd2.data.AppPrefs
import com.example.nsobd2.data.BatteryHealthStore
import com.example.nsobd2.data.DriveHistoryStore
import com.example.nsobd2.data.DtcHistoryStore
import com.example.nsobd2.data.FuelTankStore
import com.example.nsobd2.data.LiveSensorCheck
import com.example.nsobd2.data.ParseResult
import com.example.nsobd2.ui.widgets.MiniLineChartView
import com.example.nsobd2.viewmodel.AppScreen
import com.example.nsobd2.viewmodel.VehicleViewModel
import com.github.anastr.speedviewlib.SpeedView
import kotlinx.coroutines.launch
import java.util.Locale

class DashboardFragment : Fragment(R.layout.fragment_dashboard), TextToSpeech.OnInitListener {
    /** Sesli uyarı tekrar tekrar çalmasın diye son durum. */
    private var sonUyariKritik = false


    private val viewModel: VehicleViewModel by activityViewModels()
    private lateinit var tts: TextToSpeech
    private var lastSpokenCommand: String = ""
    private var lastTempWarnTime: Long = 0L
    private var wasLogging: Boolean = false

    private lateinit var tvShiftIndicator: TextView
    private lateinit var tvSensorWarning: TextView
    private lateinit var tvLogStatus: TextView
    private lateinit var btnDriveLog: Button
    private lateinit var btnShareLog: Button
    private lateinit var btnDtcHistory: Button
    private lateinit var tvBatteryVoltage: TextView
    private lateinit var tvInstantFuel: TextView
    private lateinit var tvInstantFuelUnit: TextView
    private lateinit var chartRpm: MiniLineChartView
    private lateinit var chartSpeed: MiniLineChartView
    private lateinit var tvTankInfo: TextView
    private lateinit var pbTank: ProgressBar
    private lateinit var tvTankRange: TextView

    override fun onResume() {
        super.onResume()
        viewModel.setActiveScreen(AppScreen.DASHBOARD)
        refreshTankUi()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tts = TextToSpeech(requireContext(), this)

        val gaugeRpm = view.findViewById<SpeedView>(R.id.gaugeRpm)
        val gaugeSpeed = view.findViewById<SpeedView>(R.id.gaugeSpeed)
        gaugeRpm.speedTextColor = android.graphics.Color.WHITE
        gaugeRpm.textColor = android.graphics.Color.WHITE
        gaugeSpeed.speedTextColor = android.graphics.Color.WHITE
        gaugeSpeed.textColor = android.graphics.Color.WHITE

        tvShiftIndicator = view.findViewById(R.id.tvShiftIndicator)
        tvSensorWarning = view.findViewById(R.id.tvSensorWarning)
        tvLogStatus = view.findViewById(R.id.tvLogStatus)
        btnDriveLog = view.findViewById(R.id.btnDriveLog)
        btnShareLog = view.findViewById(R.id.btnShareLog)
        btnDtcHistory = view.findViewById(R.id.btnDtcHistory)
        tvBatteryVoltage = view.findViewById(R.id.tvBatteryVoltage)
        tvInstantFuel = view.findViewById(R.id.tvInstantFuel)
        tvInstantFuelUnit = view.findViewById(R.id.tvInstantFuelUnit)
        chartRpm = view.findViewById(R.id.chartRpm)
        chartSpeed = view.findViewById(R.id.chartSpeed)
        tvTankInfo = view.findViewById(R.id.tvTankInfo)
        pbTank = view.findViewById(R.id.pbTank)
        tvTankRange = view.findViewById(R.id.tvTankRange)

        val tvTemp = view.findViewById<TextView>(R.id.tvTempDetail)
        val tvFuel = view.findViewById<TextView>(R.id.tvFuelLevel)
        val tvLoad = view.findViewById<TextView>(R.id.tvEngineLoad)
        val tvMap = view.findViewById<TextView>(R.id.tvMap)
        val tvIat = view.findViewById<TextView>(R.id.tvIat)
        val tvTps = view.findViewById<TextView>(R.id.tvTps)
        val tvAvans = view.findViewById<TextView>(R.id.tvAvans)
        val tvStft = view.findViewById<TextView>(R.id.tvStft)
        val tvLtft = view.findViewById<TextView>(R.id.tvLtft)
        val tvO2 = view.findViewById<TextView>(R.id.tvO2)

        // LPG/BENZİN GEÇİŞ — akü kartına uzun basınca
        tvBatteryVoltage.setOnLongClickListener {
            viewModel.toggleLpg(requireContext())
            val mode = if (viewModel.isLpg.value) "LPG" else "BENZİN"
            Toast.makeText(requireContext(), "⛽ $mode moduna geçildi", Toast.LENGTH_SHORT).show()
            true
        }

        btnDriveLog.setOnClickListener {
            val started = viewModel.toggleDriveLog(requireContext())
            if (!started) {
                if (!viewModel.connection.isConnected) {
                    Toast.makeText(requireContext(), "Önce ELM327'ye bağlan!", Toast.LENGTH_SHORT).show()
                } else {
                    refreshTankUi()
                }
            }
        }

        btnShareLog.setOnClickListener {
            val ok = viewModel.shareDriveLog(requireContext())
            if (!ok) Toast.makeText(requireContext(), "Paylaşılacak kayıt yok.", Toast.LENGTH_SHORT).show()
        }

        btnDtcHistory.setOnClickListener { showMenuDialog() }

        view.findViewById<Button>(R.id.btnReadVin).setOnClickListener {
            if (!viewModel.connection.isConnected) {
                Toast.makeText(requireContext(), "Önce ELM327'ye bağlan!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.readVin()
            AlertDialog.Builder(requireContext())
                .setTitle("🚗 VIN / Şasi No")
                .setMessage(viewModel.vin.value + "\n\nNot: Bazı eski araçlar VIN'i OBD üzerinden vermez. Bu durumda ruhsattan bakman gerekir.")
                .setPositiveButton("Tamam", null)
                .setNeutralButton("📋 Kopyala") { _, _ ->
                    val v = viewModel.vin.value
                    val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("VIN", v))
                    Toast.makeText(requireContext(), "VIN kopyalandı", Toast.LENGTH_SHORT).show()
                }
                .show()
        }

        // DEPO BUTONLARI
        view.findViewById<Button>(R.id.btnRefillFull).setOnClickListener { showRefillFullDialog() }
        view.findViewById<Button>(R.id.btnAddFuel).setOnClickListener {
            showAddFuelDialog()
        }

        // CANLI VERİLER
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { data ->
                    var currentRpm = 0.0
                    var currentSpeed = 0.0
                    (data["RPM"] as? ParseResult.Success)?.let {
                        currentRpm = it.numericValue ?: 0.0
                        gaugeRpm.speedTo(currentRpm.toFloat(), 300)
                    }
                    (data["SPEED"] as? ParseResult.Success)?.let {
                        currentSpeed = it.numericValue ?: 0.0
                        gaugeSpeed.speedTo(currentSpeed.toFloat(), 300)
                    }
                    calculateShift(currentRpm, currentSpeed)
                    (data["TEMP"] as? ParseResult.Success)?.let {
                        tvTemp.text = "${it.value} °C"
                        val temp = it.value.toIntOrNull() ?: 0
                        if (temp >= 105) speakTempWarning()
                    }
                    (data["FUEL"] as? ParseResult.Success)?.let { tvFuel.text = "${it.value} %" }
                    (data["LOAD"] as? ParseResult.Success)?.let { tvLoad.text = "${it.value} %" }
                    (data["MAP"] as? ParseResult.Success)?.let { tvMap.text = "${it.value} kPa" }
                    (data["IAT"] as? ParseResult.Success)?.let { tvIat.text = "${it.value} °C" }
                    (data["TPS"] as? ParseResult.Success)?.let { tvTps.text = "${it.value} %" }
                    (data["AVANS"] as? ParseResult.Success)?.let { tvAvans.text = "${it.value} °" }
                    (data["STFT"] as? ParseResult.Success)?.let { tvStft.text = "${it.value} %" }
                    (data["LTFT"] as? ParseResult.Success)?.let { tvLtft.text = "${it.value} %" }
                    (data["O2"] as? ParseResult.Success)?.let { tvO2.text = "${it.value} V" }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.rpmHistory.collect { list ->
                    chartRpm.setData(list, 8000f, android.graphics.Color.parseColor("#FFCA28"))
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.speedHistory.collect { list ->
                    chartSpeed.setData(list, 200f, android.graphics.Color.parseColor("#4FC3F7"))
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.batteryVoltage.collect { v ->
                    tvBatteryVoltage.text = if (v == "--") "-- V" else "$v V"
                }
            }
        }

        // ANLIK SENSÖR SAĞLIK UYARISI
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.sensorWarnings.collect { warnings ->
                    if (warnings.isEmpty()) {
                        sonUyariKritik = false
                        tvSensorWarning.visibility = View.GONE
                    } else {
                        tvSensorWarning.visibility = View.VISIBLE
                        val critical = warnings.any { it.severity == LiveSensorCheck.Severity.CRITICAL }
                        // Kritik uyarı YENİ başladıysa sesli uyar (sürüşte ekrana bakmayabilirsin)
                        if (critical && !sonUyariKritik) {
                            try {
                                android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100)
                                    .startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1500)
                            } catch (_: Exception) {}
                        }
                        sonUyariKritik = critical
                        tvSensorWarning.setBackgroundColor(
                            android.graphics.Color.parseColor(if (critical) "#B71C1C" else "#E65100")
                        )
                        tvSensorWarning.text = warnings.joinToString("\n") {
                            "⚠️ ${it.sensor}: ${it.message}"
                        }
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.instantFuel.collect { tvInstantFuel.text = it }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.instantFuelUnit.collect { tvInstantFuelUnit.text = it }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.driveLogger.isLogging.collect { logging ->
                    if (logging) {
                        btnDriveLog.text = "■ BİTİR"
                        btnDriveLog.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#D32F2F"))
                        btnShareLog.isEnabled = false
                        tvLogStatus.visibility = View.VISIBLE
                        wasLogging = true
                    } else {
                        btnDriveLog.text = "▶ BAŞLAT"
                        btnDriveLog.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#388E3C"))
                        if (wasLogging) {
                            wasLogging = false
                            val s = viewModel.getDriveSummary(requireContext())
                            if (s.totalEntries > 0) {
                                btnShareLog.isEnabled = true
                                refreshTankUi()
                                showSummaryDialog(s)
                            } else tvLogStatus.visibility = View.GONE
                        }
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.driveLogger.logCount.collect { count ->
                    if (viewModel.driveLogger.isLogging.value) {
                        val km = viewModel.driveLogger.totalKm.value
                        val lt = viewModel.driveLogger.totalLiters.value
                        tvLogStatus.text = "🔴 $count satır • ${"%.2f".format(Locale.US, km)} km • ${"%.2f".format(Locale.US, lt)} L"
                    } else if (count > 0) {
                        tvLogStatus.text = "✅ Sürüş kaydedildi: $count satır."
                        tvLogStatus.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun refreshTankUi() {
        val cap = FuelTankStore.getCapacity(requireContext())
        val cur = FuelTankStore.getCurrentFuel(requireContext())
        val pct = FuelTankStore.getFuelPercentage(requireContext())
        tvTankInfo.text = "${"%.1f".format(Locale.US, cur)} L / ${"%.0f".format(Locale.US, cap)} L (%$pct)"
        pbTank.progress = pct
        // Menzil tahmini — son 7 gün ortalama tüketim
        val recs = DriveHistoryStore.getLastDays(requireContext(), 14)
        if (recs.isNotEmpty() && cur > 0) {
            val totalKm = recs.sumOf { it.totalKm }
            val totalLt = recs.sumOf { it.totalLiters }
            if (totalLt > 0.5) {
                val avgL100 = (totalLt / totalKm) * 100.0
                if (avgL100 > 0) {
                    val range = (cur / avgL100) * 100.0
                    tvTankRange.text = "Tahmini menzil: ${"%.0f".format(Locale.US, range)} km (ort. ${"%.1f".format(Locale.US, avgL100)} L/100km)"
                    return
                }
            }
        }
        tvTankRange.text = "Tahmini menzil: yeterli sürüş verisi yok"
    }

    /**
     * DEPO FULL + KALİBRASYON: Depoyu ağzına kadar doldurunca pompada yazan litreyi gir.
     * Uygulama son full'den beri kendi tahminiyle karşılaştırıp yakıt hesabını düzeltir.
     */
    private fun showRefillFullDialog() {
        val ctx = requireContext()
        val est = FuelTankStore.getEstimatedSinceFull(ctx)
        val edit = EditText(ctx).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "Pompada yazan litre (ör: 32.4)"
        }
        val calib = AppPrefs.getFuelCalib(ctx)
        AlertDialog.Builder(ctx)
            .setTitle("⛽ Depo Full")
            .setMessage(
                "Depoyu ağzına kadar doldurduysan pompada yazan litreyi gir. " +
                "Uygulama kendi hesabını buna göre düzeltir.\n\n" +
                "Son full'den beri uygulamanın tahmini: ${"%.1f".format(Locale.US, est)} L\n" +
                "Şu anki düzeltme katsayısı: ${"%.2f".format(Locale.US, calib)}\n\n" +
                "⚠️ Sadece iki full arasındaki TÜM sürüşleri kaydettiysen litre gir; kayıtsız sürüş varsa boş bırak.\n" +
                "İlk kez kullanıyorsan boş bırakıp sadece Full işaretle; bir sonraki full'de girersin."
            )
            .setView(edit)
            .setPositiveButton("Full işaretle") { _, _ ->
                val litre = edit.text.toString().replace(",", ".").toDoubleOrNull()
                val msg = if (litre != null) {
                    val yeni = FuelTankStore.calibrateOnFull(ctx, litre)
                    if (yeni != null) {
                        viewModel.refreshEngineParams(ctx)
                        "✅ Kalibre edildi. Yeni katsayı: ${"%.2f".format(Locale.US, yeni)}"
                    } else "ℹ️ Kalibrasyon için son full'den beri en az 5 L kayıtlı sürüş gerekli. Full işaretlendi."
                } else "✅ Depo full işaretlendi"
                FuelTankStore.refillFull(ctx)
                refreshTankUi()
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            }
            .setNeutralButton("Katsayıyı sıfırla") { _, _ ->
                AppPrefs.setFuelCalib(ctx, 1.0); viewModel.refreshEngineParams(ctx)
                Toast.makeText(ctx, "Katsayı 1.00'a döndü", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun showAddFuelDialog() {
        val edit = EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "ör: 25.5"
        }
        AlertDialog.Builder(requireContext())
            .setTitle("➕ Kısmi Yakıt Ekle (L)")
            .setMessage("Aldığın yakıt miktarını gir.")
            .setView(edit)
            .setPositiveButton("Ekle") { _, _ ->
                val v = edit.text.toString().replace(",", ".").toDoubleOrNull()
                if (v != null && v > 0) {
                    FuelTankStore.addLiters(requireContext(), v)
                    refreshTankUi()
                    Toast.makeText(requireContext(), "✅ ${"%.1f".format(Locale.US, v)} L eklendi", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun showSummaryDialog(s: com.example.nsobd2.data.DriveSummary) {
        val dakika = s.durationSec / 60
        val saniye = s.durationSec % 60
        val gpsLine = if (s.totalKmGps > 0.1) "📍 GPS Mesafe: ${"%.2f".format(Locale.US, s.totalKmGps)} km\n" else ""
        val mesaj = """
            Süre: $dakika dk $saniye sn
            Toplam Kayıt: ${s.totalEntries}
            📏 OBD Mesafe: ${"%.2f".format(Locale.US, s.totalKm)} km
            $gpsLine⛽ Toplam Yakıt: ${"%.2f".format(Locale.US, s.totalLiters)} L
            📊 Ort. Tüketim: ${"%.1f".format(Locale.US, s.avgLper100)} L/100km
            💸 Maliyet: ${"%.2f".format(Locale.US, s.cost)} ₺
            🔥 Max RPM: ${s.maxRpm}
            🚗 Max Hız: ${s.maxSpeed} km/h
            🌡 Max Hararet: ${s.maxCoolant} °C
            📊 Ortalama Hız: ${"%.1f".format(Locale.US, s.avgSpeed)} km/h
            🐎 Tahmini Max BG: ${"%.1f".format(Locale.US, s.maxHorsepower)} HP
            🌱 EKO SKOR: ${s.ecoScore} / 100
            ${s.ecoComment}
        """.trimIndent()
        AlertDialog.Builder(requireContext())
            .setTitle("Sürüş Özeti")
            .setMessage(mesaj)
            .setPositiveButton("📤 CSV Paylaş") { _, _ -> viewModel.shareDriveLog(requireContext()) }
            .setNeutralButton("🔧 Detaylı Analiz") { _, _ -> showAnalysisDialog() }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun showAnalysisDialog() {
        val items = arrayOf(
            "🔧 Parça Sağlık Skorları",
            "🏎 Sürüş Tarzı Analizi"
        )
        AlertDialog.Builder(requireContext())
            .setTitle("Detaylı Analiz")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showPartHealthDialog()
                    1 -> showDrivingStyleDialog()
                }
            }
            .show()
    }

    private fun showPartHealthDialog() {
        val parts = viewModel.driveLogger.getPartHealthScores()
        if (parts.isEmpty()) {
            AlertDialog.Builder(requireContext())
                .setTitle("🔧 Parça Sağlığı")
                .setMessage("Yeterli veri yok. Daha uzun bir sürüş yap.")
                .setPositiveButton("Tamam", null)
                .show()
            return
        }
        val sb = StringBuilder()
        sb.append("⚠️ Bu skorlar OBD verisinden ÇIKARIM yoluyla üretilir, kesin teşhis değildir.\n\n")
        for (p in parts) {
            sb.append("${p.emoji} ${p.name}: ${p.score}/100 (${p.status})\n")
            sb.append("   ${p.advice}\n\n")
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("🔧 Parça Sağlık Skorları")
            .setMessage(sb.toString())
            .setPositiveButton("Tamam", null)
            .setNeutralButton("🛠 Tamir Rehberi") { _, _ -> showFixGuide(parts) }
            .create()
        dialog.show()
    }

    private fun showFixGuide(parts: List<com.example.nsobd2.data.PartHealthAnalyzer.PartScore>) {
        val needFix = parts.filter { it.score < 70 && it.howToFix.isNotBlank() }
        if (needFix.isEmpty()) {
            AlertDialog.Builder(requireContext())
                .setTitle("🛠 Tamir Rehberi")
                .setMessage("Tüm parçalar iyi durumda görünüyor. Acil tamir gerekmez.")
                .setPositiveButton("Süper", null)
                .show()
            return
        }
        val titles = needFix.map { "${it.emoji} ${it.name} (${it.score}/100)" }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("🛠 Hangi parça için rehber?")
            .setItems(titles) { _, which ->
                val p = needFix[which]
                AlertDialog.Builder(requireContext())
                    .setTitle("${p.emoji} ${p.name}")
                    .setMessage(p.howToFix)
                    .setPositiveButton("Tamam", null)
                    .show()
            }
            .show()
    }

    private fun showDrivingStyleDialog() {
        val insights = viewModel.driveLogger.getDrivingInsights()
        if (insights.isEmpty()) {
            AlertDialog.Builder(requireContext())
                .setTitle("🏎 Sürüş Tarzı")
                .setMessage("Yeterli veri yok.")
                .setPositiveButton("Tamam", null)
                .show()
            return
        }
        val sb = StringBuilder()
        for (i in insights) {
            sb.append("${i.emoji} ${i.message}\n")
            sb.append("   ↳ ${i.impact}\n\n")
        }
        AlertDialog.Builder(requireContext())
            .setTitle("🏎 Sürüş Tarzı Analizi")
            .setMessage(sb.toString())
            .setPositiveButton("Tamam", null)
            .show()
    }

    private fun showMenuDialog() {
        val items = arrayOf(
            "📋 Hata Kodu Geçmişi",
            "📅 Geçmiş Sürüşler",
            "📈 Haftalık Rapor",
            "🔋 Akü Sağlığı",
            "💰 Yakıt Fiyatı Ayarla",
            "🛢 Depo Kapasitesi Ayarla"
        )
        AlertDialog.Builder(requireContext())
            .setTitle("Geçmiş & Ayarlar")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showDtcHistoryDialog()
                    1 -> showDriveHistoryDialog()
                    2 -> showWeeklyReportDialog()
                    3 -> showBatteryHealthDialog()
                    4 -> showFuelPriceDialog()
                    5 -> showTankCapacityDialog()
                }
            }
            .show()
    }

    private fun showDtcHistoryDialog() {
        val records = DtcHistoryStore.getAll(requireContext())
        if (records.isEmpty()) {
            AlertDialog.Builder(requireContext())
                .setTitle("📋 Hata Kodu Geçmişi")
                .setMessage("Henüz silinmiş hata kodu yok.")
                .setPositiveButton("Tamam", null).show()
            return
        }
        val sb = StringBuilder()
        for (r in records) {
            sb.append("📅 ${r.date}\n")
            for (c in r.codes) sb.append("   • $c\n")
            sb.append("\n")
        }
        AlertDialog.Builder(requireContext())
            .setTitle("📋 Hata Kodu Geçmişi (${records.size})")
            .setMessage(sb.toString())
            .setPositiveButton("Tamam", null)
            .setNeutralButton("🗑 Tümünü Sil") { _, _ ->
                DtcHistoryStore.clear(requireContext())
                Toast.makeText(requireContext(), "Geçmiş silindi", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun showDriveHistoryDialog() {
        val records = DriveHistoryStore.getAll(requireContext())
        if (records.isEmpty()) {
            AlertDialog.Builder(requireContext())
                .setTitle("📅 Geçmiş Sürüşler")
                .setMessage("Henüz kaydedilmiş sürüş yok.")
                .setPositiveButton("Tamam", null).show()
            return
        }
        val items = records.map {
            "${it.date}\n${"%.1f".format(Locale.US, it.totalKm)} km • ${"%.2f".format(Locale.US, it.totalLiters)} L • ${"%.0f".format(Locale.US, it.cost)} ₺ • Skor ${it.ecoScore}"
        }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("📅 Geçmiş Sürüşler (${records.size})")
            .setItems(items) { _, which ->
                val r = records[which]
                val dakika = r.durationSec / 60
                val saniye = r.durationSec % 60
                val mesaj = """
                    📅 ${r.date}
                    Süre: $dakika dk $saniye sn
                    📏 Mesafe: ${"%.2f".format(Locale.US, r.totalKm)} km
                    ⛽ Yakıt: ${"%.2f".format(Locale.US, r.totalLiters)} L
                    📊 Tüketim: ${"%.1f".format(Locale.US, r.avgL100)} L/100km
                    💸 Maliyet: ${"%.2f".format(Locale.US, r.cost)} ₺
                    🔥 Max RPM: ${r.maxRpm}
                    🚗 Max Hız: ${r.maxSpeed} km/h
                    🌡 Max Hararet: ${r.maxCoolant} °C
                    📊 Ort. Hız: ${"%.1f".format(Locale.US, r.avgSpeed)} km/h
                    🌱 Eko Skor: ${r.ecoScore}/100
                """.trimIndent()
                AlertDialog.Builder(requireContext())
                    .setTitle("Sürüş Detayı")
                    .setMessage(mesaj)
                    .setPositiveButton("Tamam", null).show()
            }
            .setNeutralButton("🗑 Hepsini Sil") { _, _ ->
                DriveHistoryStore.clear(requireContext())
                Toast.makeText(requireContext(), "Sürüş geçmişi silindi", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun showWeeklyReportDialog() {
        val recs = DriveHistoryStore.getLastDays(requireContext(), 7)
        if (recs.isEmpty()) {
            AlertDialog.Builder(requireContext())
                .setTitle("📈 Haftalık Rapor")
                .setMessage("Son 7 günde kayıt yok.")
                .setPositiveButton("Tamam", null).show()
            return
        }
        val toplamKm = recs.sumOf { it.totalKm }
        val toplamLt = recs.sumOf { it.totalLiters }
        val toplamSn = recs.sumOf { it.durationSec }
        val toplamMaliyet = recs.sumOf { it.cost }
        val ortL100 = if (toplamKm > 0.5) (toplamLt / toplamKm) * 100.0 else 0.0
        val ortSkor = recs.map { it.ecoScore }.average().toInt()
        val maxHiz = recs.maxOf { it.maxSpeed }
        val saat = toplamSn / 3600
        val dk = (toplamSn % 3600) / 60
        val mesaj = """
            Son 7 gün, ${recs.size} sürüş
            📏 Toplam Mesafe: ${"%.1f".format(Locale.US, toplamKm)} km
            ⏱ Toplam Süre: $saat sa $dk dk
            ⛽ Toplam Yakıt: ${"%.2f".format(Locale.US, toplamLt)} L
            💸 Toplam Maliyet: ${"%.2f".format(Locale.US, toplamMaliyet)} ₺
            📊 Ort. Tüketim: ${"%.1f".format(Locale.US, ortL100)} L/100km
            🚗 Bu Hafta Max Hız: $maxHiz km/h
            🌱 Ortalama Eko Skor: $ortSkor/100
        """.trimIndent()
        AlertDialog.Builder(requireContext())
            .setTitle("📈 Haftalık Rapor")
            .setMessage(mesaj)
            .setPositiveButton("Tamam", null).show()
    }

    private fun showBatteryHealthDialog() {
        val (score, comment) = BatteryHealthStore.calculateHealthScore(requireContext())
        val hist = BatteryHealthStore.getAll(requireContext()).take(5)
        val histText = if (hist.isEmpty()) "" else "\n\nSon 5 marş:\n" + hist.joinToString("\n") {
            "   ${it.date} → min ${"%.2f".format(Locale.US, it.minVoltage)}V"
        }
        AlertDialog.Builder(requireContext())
            .setTitle("🔋 Akü Sağlığı: $score/100")
            .setMessage(comment + histText)
            .setPositiveButton("Tamam", null)
            .setNeutralButton("🗑 Sıfırla") { _, _ ->
                BatteryHealthStore.clear(requireContext())
                Toast.makeText(requireContext(), "Akü geçmişi sıfırlandı", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun showFuelPriceDialog() {
        val isLpg = AppPrefs.isLpgMode(requireContext())
        val modeText = if (isLpg) "LPG" else "BENZİN"
        val current = AppPrefs.getActiveFuelPrice(requireContext())
        val edit = EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(String.format(Locale.US, "%.2f", current))
        }
        AlertDialog.Builder(requireContext())
            .setTitle("💰 $modeText Fiyatı (₺/L)")
            .setView(edit)
            .setPositiveButton("Kaydet") { _, _ ->
                val v = edit.text.toString().replace(",", ".").toDoubleOrNull() ?: current
                if (isLpg) {
                    AppPrefs.setLpgPrice(requireContext(), v)
                } else {
                    AppPrefs.setBenzinPrice(requireContext(), v)
                }
                Toast.makeText(requireContext(), "$modeText Fiyatı: ${"%.2f".format(Locale.US, v)} ₺/L", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Vazgeç", null).show()
    }

    private fun showTankCapacityDialog() {
        val current = FuelTankStore.getCapacity(requireContext())
        val edit = EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(String.format(Locale.US, "%.0f", current))
        }
        AlertDialog.Builder(requireContext())
            .setTitle("🛢 Depo Kapasitesi (L)")
            .setMessage("Peugeot 206 deposu 50 L'dir.")
            .setView(edit)
            .setPositiveButton("Kaydet") { _, _ ->
                val v = edit.text.toString().replace(",", ".").toDoubleOrNull() ?: current
                FuelTankStore.setCapacity(requireContext(), v)
                refreshTankUi()
                Toast.makeText(requireContext(), "Depo: ${"%.0f".format(Locale.US, v)} L", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Vazgeç", null).show()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val localeTR = Locale("tr", "TR")
            if (tts.isLanguageAvailable(localeTR) >= TextToSpeech.LANG_AVAILABLE) tts.language = localeTR
        }
    }

    private fun calculateShift(rpm: Double, speed: Double) {
        if (rpm > 2950) {
            val nextGear = when (speed) {
                in 0.0..30.0 -> "2. vitese at"
                in 30.0..50.0 -> "3. vitese at"
                in 50.0..75.0 -> "4. vitese at"
                else -> "5. vitese at"
            }
            showAndSpeakWarning(nextGear, "#D32F2F")
        } else if (rpm in 500.0..1400.0 && speed > 15.0) {
            showAndSpeakWarning("Vites düşür", "#FF9800")
        } else {
            tvShiftIndicator.visibility = View.GONE
            lastSpokenCommand = ""
        }
    }

    private fun showAndSpeakWarning(message: String, colorHex: String) {
        tvShiftIndicator.visibility = View.VISIBLE
        tvShiftIndicator.text = message.uppercase()
        tvShiftIndicator.setBackgroundColor(android.graphics.Color.parseColor(colorHex))
        if (message != lastSpokenCommand) {
            tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, null)
            lastSpokenCommand = message
        }
    }

    private fun speakTempWarning() {
        val now = System.currentTimeMillis()
        if (now - lastTempWarnTime > 30_000) {
            tts.speak("Dikkat motor ısınıyor", TextToSpeech.QUEUE_ADD, null, null)
            lastTempWarnTime = now
        }
    }

    override fun onDestroy() {
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
        viewModel.saveCrankingEventIfApplicable(requireContext())
        super.onDestroy()
    }
}
