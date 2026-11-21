package com.example.nsobd2.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.nsobd2.network.Elm327Connection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DriveLogEntry(
    val timestamp: Long, val rpm: String, val speed: String, val coolant: String,
    val engineLoad: String, val map: String, val iat: String, val tps: String,
    val timingAdvance: String, val stft: String, val ltft: String, val lambda: String
)

data class DriveSummary(
    val totalEntries: Int, val durationSec: Long, val maxRpm: Int, val maxSpeed: Int,
    val maxCoolant: Int, val maxLoad: Double, val avgSpeed: Double, val ecoScore: Int,
    val ecoComment: String, val totalKm: Double, val totalLiters: Double, val avgLper100: Double,
    val totalKmGps: Double, val cost: Double, val maxHorsepower: Double
)

class DriveLogger(private val connection: Elm327Connection) {

    private val _entries = mutableListOf<DriveLogEntry>()
    val entries: List<DriveLogEntry> get() = _entries.toList()
    private var loggingJob: Job? = null
    private var startTimeMs: Long = 0L

    private val _isLogging = MutableStateFlow(false)
    val isLogging = _isLogging.asStateFlow()
    private val _logCount = MutableStateFlow(0)
    val logCount = _logCount.asStateFlow()
    private val _maxRpm = MutableStateFlow(0)
    val maxRpm = _maxRpm.asStateFlow()
    private val _maxSpeed = MutableStateFlow(0)
    val maxSpeed = _maxSpeed.asStateFlow()
    private val _maxCoolant = MutableStateFlow(0)
    val maxCoolant = _maxCoolant.asStateFlow()
    private val _totalKm = MutableStateFlow(0.0)
    val totalKm = _totalKm.asStateFlow()
    private val _totalLiters = MutableStateFlow(0.0)
    val totalLiters = _totalLiters.asStateFlow()
    private val _maxHp = MutableStateFlow(0.0)

    // OBD BAGLANTI DURUMU — null = veri geliyor; dolu = ekranda gosterilecek uyari
    private val _obdStatus = MutableStateFlow<String?>(null)
    val obdStatus = _obdStatus.asStateFlow()
    var obdDropCount: Int = 0; private set
    var obdLostSec: Long = 0L; private set
    // Teshis: okunamayan ilk RPM cevabi (ham) — CSV ozetine yazilir
    var firstBadRaw: String = ""; private set

    var gpsTotalKm: Double = 0.0

    // MOTOR HACMİ — ViewModel, kayıt başlarken AppPrefs'ten doldurur.
    // Varsayılan 1.4 L. Eskiden formüle 1.1 L gömülüydü.
    var engineM3: Double = 0.0014
    var volEff: Double = 0.85
    private var logIsLpg: Boolean = false

    private val _liveData = MutableStateFlow<Map<String, ParseResult>>(emptyMap())
    val liveData = _liveData.asStateFlow()

    private val commands = listOf(
        ObdCommand.RPM, ObdCommand.Speed, ObdCommand.CoolantTemp, ObdCommand.EngineLoad,
        ObdCommand.MAP, ObdCommand.IAT, ObdCommand.TPS, ObdCommand.TimingAdvance,
        ObdCommand.ShortTermFuelTrim, ObdCommand.LongTermFuelTrim, ObdCommand.O2Voltage
    )

    fun startLogging(scope: CoroutineScope, intervalMs: Long = 3000L, isLpg: Boolean = false) {
        if (_isLogging.value) return
        _entries.clear(); _logCount.value = 0; _maxRpm.value = 0; _maxSpeed.value = 0
        _maxCoolant.value = 0; _totalKm.value = 0.0; _totalLiters.value = 0.0; _maxHp.value = 0.0
        gpsTotalKm = 0.0; logIsLpg = isLpg
        _obdStatus.value = null; obdDropCount = 0; obdLostSec = 0L; firstBadRaw = ""
        startTimeMs = System.currentTimeMillis(); _isLogging.value = true

        loggingJob = scope.launch(Dispatchers.IO) {
            var lastSampleMs = System.currentTimeMillis()
            var lastSpeedKmh = 0.0

            var emptyStreak = 0
            var lostSinceMs = 0L

            // Eskiden "while (connection.isConnected)" idi; WiFi/ELM kopunca bayrak true kaldigi icin
            // kayit bos satirlarla devam ediyordu. Artik kopmayi algilayip yeniden baglaniyoruz.
            while (isActive) {
                val tick = System.currentTimeMillis()

                if (!connection.isConnected || emptyStreak >= 2) {
                    if (lostSinceMs == 0L) { lostSinceMs = tick; obdDropCount++ }
                    _obdStatus.value = "⚠️ OBD veri gelmiyor — yeniden bağlanılıyor..."
                    val ok = try { connection.reconnect() } catch (_: Exception) { false }
                    if (!ok) {
                        _obdStatus.value = "🔴 OBD bağlantısı yok — tekrar denenecek (GPS kaydı sürüyor)"
                        delay(3000); continue
                    }
                    emptyStreak = 0
                    _obdStatus.value = "🟢 OBD yeniden bağlandı"
                }

                val data = HashMap<String, String>()
                val parsed = HashMap<String, ParseResult>()

                for (cmd in commands) {
                    if (!isActive) break
                    val raw = connection.sendCommand(cmd.pid)
                    val r = cmd.parse(raw)
                    if (r !is ParseResult.Success && firstBadRaw.isEmpty() && cmd.pid == "010C")
                        firstBadRaw = raw.ifEmpty { "(bos cevap)" }.replace(";", ",").take(80)
                    parsed[cmd.pid] = r
                    data[cmd.pid] = when (r) { is ParseResult.Success -> r.value; else -> "" }
                }

                // CANLI VERİ YAYINI → ViewModel._uiState'e yazar (init bloğundan)
                val live = HashMap<String, ParseResult>()
                parsed["010C"]?.let { live["RPM"] = it }
                parsed["010D"]?.let { live["SPEED"] = it }
                parsed["0105"]?.let { live["TEMP"] = it }
                parsed["0104"]?.let { live["LOAD"] = it }
                parsed["010B"]?.let { live["MAP"] = it }
                parsed["010F"]?.let { live["IAT"] = it }
                parsed["0111"]?.let { live["TPS"] = it }
                parsed["010E"]?.let { live["AVANS"] = it }
                parsed["0106"]?.let { live["STFT"] = it }
                parsed["0107"]?.let { live["LTFT"] = it }
                parsed["0114"]?.let { live["O2"] = it }
                // HİÇBİR PID cevap vermediyse: boş satır YAZMA, kopma say
                val anySuccess = parsed.values.any { it is ParseResult.Success }
                if (!anySuccess) {
                    emptyStreak++
                    val elapsedE = System.currentTimeMillis() - tick
                    delay((intervalMs - elapsedE).coerceAtLeast(0L))
                    continue
                }
                if (lostSinceMs > 0L) {
                    obdLostSec += (System.currentTimeMillis() - lostSinceMs) / 1000
                    lostSinceMs = 0L
                    // Kopma süresince mesafe/yakıt sıçramasın
                    lastSampleMs = System.currentTimeMillis(); lastSpeedKmh = 0.0
                }
                emptyStreak = 0
                if (_obdStatus.value != null) _obdStatus.value = null

                _liveData.value = live

                val entry = DriveLogEntry(
                    tick, data["010C"].orEmpty(), data["010D"].orEmpty(), data["0105"].orEmpty(),
                    data["0104"].orEmpty(), data["010B"].orEmpty(), data["010F"].orEmpty(),
                    data["0111"].orEmpty(), data["010E"].orEmpty(), data["0106"].orEmpty(),
                    data["0107"].orEmpty(), data["0114"].orEmpty()
                )
                _entries.add(entry); _logCount.value = _entries.size

                entry.rpm.toIntOrNull()?.let { if (it > _maxRpm.value) _maxRpm.value = it }
                entry.speed.toIntOrNull()?.let { if (it > _maxSpeed.value) _maxSpeed.value = it }
                entry.coolant.toIntOrNull()?.let { if (it > _maxCoolant.value) _maxCoolant.value = it }

                // MESAFE — ortalama hız yöntemi (daha doğru)
                val nowMs = System.currentTimeMillis()
                val deltaSec = (nowMs - lastSampleMs) / 1000.0
                val speedKmh = entry.speed.toDoubleOrNull() ?: 0.0
                val avgSpeedKmh = (speedKmh + lastSpeedKmh) / 2.0
                val deltaKm = (avgSpeedKmh / 3600.0) * deltaSec
                _totalKm.value = _totalKm.value + deltaKm

                // YAKIT
                val rpmV = entry.rpm.toDoubleOrNull() ?: 0.0
                val mapKpa = entry.map.toDoubleOrNull() ?: 0.0
                val iatC = entry.iat.toDoubleOrNull() ?: 25.0
                if (rpmV > 0 && mapKpa > 0) {
                    val afr = if (logIsLpg) 15.5 else 14.7
                    val fuelDensity = if (logIsLpg) 0.55 else 0.74
                    val iatK = iatC + 273.15
                    val mafKgS = (mapKpa * 1000.0 * engineM3 * volEff * (rpmV / 120.0) * 0.02897) / (8.314 * iatK)
                    val fuelLh = (mafKgS / afr / fuelDensity) * 3600.0
                    val deltaLiters = fuelLh * (deltaSec / 3600.0)
                    _totalLiters.value = _totalLiters.value + deltaLiters
                }

                // BEYGİR
                val speedMs = speedKmh / 3.6
                if (deltaSec > 0.1 && speedMs > 1) {
                    val accel = (speedMs - lastSpeedKmh / 3.6) / deltaSec
                    if (accel > 0.5) {
                        val pw = (0.5 * 1.225 * 0.65 * speedMs * speedMs + 0.013 * 950.0 * 9.81 + 950.0 * accel) * speedMs
                        val hp = pw / 745.7
                        if (hp in 1.0..200.0 && hp > _maxHp.value) _maxHp.value = hp
                    }
                }
                lastSpeedKmh = speedKmh; lastSampleMs = nowMs

                val elapsed = System.currentTimeMillis() - tick
                delay((intervalMs - elapsed).coerceAtLeast(0L))
            }
            if (lostSinceMs > 0L) obdLostSec += (System.currentTimeMillis() - lostSinceMs) / 1000
            _obdStatus.value = null
            _isLogging.value = false
        }
    }

    fun stopLogging() { _isLogging.value = false; _obdStatus.value = null; loggingJob?.cancel(); loggingJob = null }

    fun applyFuelConsumptionToTank(ctx: Context) {
        if (_totalLiters.value > 0.01) FuelTankStore.consume(ctx, _totalLiters.value)
    }

    fun getPartHealthScores(): List<PartHealthAnalyzer.PartScore> = PartHealthAnalyzer.analyze(_entries.toList())
    fun getDrivingInsights(): List<DrivingStyleAnalyzer.StyleInsight> = DrivingStyleAnalyzer.analyze(_entries.toList())

    private fun calculateEcoScore(): Pair<Int, String> {
        if (_entries.size < 3) return 100 to "Yeterli veri yok"
        var score = 100.0; var prevTps: Double? = null
        var hRpm = 0; var hSpd = 0; var hGas = 0; var hHeat = 0
        for (e in _entries) {
            val rpm = e.rpm.toDoubleOrNull() ?: 0.0; val spd = e.speed.toDoubleOrNull() ?: 0.0
            val cool = e.coolant.toIntOrNull() ?: 0; val tps = e.tps.toDoubleOrNull()
            if (rpm > 3500) { score -= 1.0; hRpm++ }
            if (spd > 110) { score -= 0.5; hSpd++ }
            if (cool > 105) { score -= 2.0; hHeat++ }
            if (prevTps != null && tps != null && (tps - prevTps) > 35) { score -= 3.0; hGas++ }
            if (tps != null) prevTps = tps
        }
        val fs = score.coerceIn(0.0, 100.0).toInt()
        val c = when { fs >= 90 -> "🏆 Mükemmel!"; fs >= 75 -> "👍 İyi"; fs >= 60 -> "🟡 Orta"; fs >= 40 -> "🟠 Sportif"; else -> "🔴 Sert sürüş!" }
        val d = buildString {
            append(c).append("\n")
            if (hRpm > 0) append("• Yüksek RPM: $hRpm kez\n")
            if (hSpd > 0) append("• Yüksek Hız: $hSpd kez\n")
            if (hGas > 0) append("• Sert Gaz: $hGas kez\n")
            if (hHeat > 0) append("• Hararet: $hHeat kez\n")
        }
        return fs to d
    }

    fun getSummary(fuelPrice: Double = 42.50): DriveSummary {
        val dur = if (startTimeMs > 0) (System.currentTimeMillis() - startTimeMs) / 1000 else 0
        var maxL = 0.0; var sumS = 0.0; var sC = 0
        for (e in _entries) {
            e.engineLoad.toDoubleOrNull()?.let { if (it > maxL) maxL = it }
            e.speed.toDoubleOrNull()?.let { sumS += it; sC++ }
        }
        val avgS = if (sC > 0) sumS / sC else 0.0
        val (eco, ecoC) = calculateEcoScore()
        val km = _totalKm.value; val lt = _totalLiters.value
        val al100 = if (km > 0.5) (lt / km) * 100.0 else 0.0
        return DriveSummary(_entries.size, dur, _maxRpm.value, _maxSpeed.value, _maxCoolant.value, maxL, avgS, eco, ecoC, km, lt, al100, gpsTotalKm, lt * fuelPrice, _maxHp.value)
    }

    fun shareCsv(ctx: Context, fuelPrice: Double = 42.50): Boolean {
        if (_entries.isEmpty()) return false
        val sdfF = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val sdfT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val dir = File(ctx.cacheDir, "drive_logs").apply { mkdirs() }
        val file = File(dir, "surus_log_${sdfF.format(Date())}.csv")
        val s = getSummary(fuelPrice)
        try {
            file.bufferedWriter(Charsets.UTF_8).use { w ->
                w.write("\uFEFF")
                w.write("Zaman;RPM;Hiz;Su;Yuk;MAP;IAT;TPS;Avans;STFT;LTFT;Lambda\n")
                for (e in _entries) w.write("${sdfT.format(Date(e.timestamp))};${e.rpm};${e.speed};${e.coolant};${e.engineLoad};${e.map};${e.iat};${e.tps};${e.timingAdvance};${e.stft};${e.ltft};${e.lambda}\n")
                w.write("\n=== OZET ===\n")
                w.write("Kayit;${s.totalEntries}\nSure;${s.durationSec}sn\n")
                w.write("OBD km;${"%.2f".format(Locale.US, s.totalKm)}\nGPS km;${"%.2f".format(Locale.US, s.totalKmGps)}\n")
                w.write("Yakit L;${"%.2f".format(Locale.US, s.totalLiters)}\nL/100km;${"%.1f".format(Locale.US, s.avgLper100)}\n")
                w.write("Maliyet;${"%.2f".format(Locale.US, s.cost)} TL\nMax BG;${"%.1f".format(Locale.US, s.maxHorsepower)}\n")
                w.write("Max RPM;${s.maxRpm}\nMax Hiz;${s.maxSpeed}\nMax Hararet;${s.maxCoolant}\nEko;${s.ecoScore}/100\n")
                w.write("Yakit Tipi;${if (logIsLpg) "LPG" else "Benzin"}\n")
                w.write("OBD kopma;$obdDropCount\nOBD verisiz sure;${obdLostSec}sn\n")
                if (firstBadRaw.isNotEmpty()) w.write("Okunamayan RPM cevabi;$firstBadRaw\n")
            }
        } catch (_: Exception) { return false }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"; putExtra(Intent.EXTRA_STREAM, uri); putExtra(Intent.EXTRA_SUBJECT, "Sürüş Logu"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Paylaş").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}