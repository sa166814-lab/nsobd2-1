package com.example.nsobd2.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nsobd2.data.AppPrefs
import com.example.nsobd2.data.BatteryHealthStore
import com.example.nsobd2.data.DriveHistoryStore
import com.example.nsobd2.data.DriveLogger
import com.example.nsobd2.network.ElmShared
import com.example.nsobd2.data.DriveSummary
import com.example.nsobd2.data.DtcHistoryStore
import com.example.nsobd2.data.GpsTracker
import com.example.nsobd2.data.LiveSensorCheck
import com.example.nsobd2.data.ObdCommand
import com.example.nsobd2.data.ParseResult
import com.example.nsobd2.network.Elm327Connection
import com.example.nsobd2.repository.ObdRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AppScreen { CONNECTION, DASHBOARD, DIAGNOSTICS, INSPECTION, TESTS }

class VehicleViewModel : ViewModel() {

    val connection = ElmShared.connection
    private val repository = ObdRepository(connection)
    val driveLogger = DriveLogger(connection)
    var gpsTracker: GpsTracker? = null

    private val _currentScreen = MutableStateFlow(AppScreen.CONNECTION)
    val currentScreen = _currentScreen.asStateFlow()

    // TEK BİR UI STATE — hem polling hem logger buraya yazar
    private val _uiState = MutableStateFlow<Map<String, ParseResult>>(emptyMap())
    val uiState = _uiState.asStateFlow()

    private val _dtcResult = MutableStateFlow<ParseResult>(ParseResult.Success("-"))
    val dtcResult = _dtcResult.asStateFlow()
    private val _pendingDtcResult = MutableStateFlow<ParseResult>(ParseResult.Success("-"))
    val pendingDtcResult = _pendingDtcResult.asStateFlow()
    private val _readinessResult = MutableStateFlow<ParseResult>(ParseResult.Success("-"))
    val readinessResult = _readinessResult.asStateFlow()
    private val _connectionStatus = MutableStateFlow("Bağlı Değil")
    val connectionStatus = _connectionStatus.asStateFlow()

    private val _batteryVoltage = MutableStateFlow("--")
    val batteryVoltage = _batteryVoltage.asStateFlow()
    private val _instantFuel = MutableStateFlow("--")
    val instantFuel = _instantFuel.asStateFlow()
    private val _instantFuelUnit = MutableStateFlow("L/100km")
    val instantFuelUnit = _instantFuelUnit.asStateFlow()

    private val _vin = MutableStateFlow("Henüz okunmadı")
    val vin = _vin.asStateFlow()

    // Anlık sensör arıza uyarıları
    private val _sensorWarnings = MutableStateFlow<List<LiveSensorCheck.Warning>>(emptyList())
    val sensorWarnings = _sensorWarnings.asStateFlow()

    // O2 salınım takibi için son okumalar
    private val o2Samples = ArrayDeque<Double>()

    private val _rpmHistory = MutableStateFlow<List<Float>>(emptyList())
    val rpmHistory = _rpmHistory.asStateFlow()
    private val _speedHistory = MutableStateFlow<List<Float>>(emptyList())
    val speedHistory = _speedHistory.asStateFlow()

    private val _isLpg = MutableStateFlow(false)
    val isLpg = _isLpg.asStateFlow()

    enum class AccelTestState { IDLE, WAITING_ZERO, RUNNING, FINISHED }
    private val _accelTestState = MutableStateFlow(AccelTestState.IDLE)
    val accelTestState = _accelTestState.asStateFlow()
    private val _accelTestTime = MutableStateFlow(0.0)
    val accelTestTime = _accelTestTime.asStateFlow()
    private val _accelTestBest = MutableStateFlow(0.0)
    val accelTestBest = _accelTestBest.asStateFlow()

    // MOTOR HACMİ — yakıt hesabının en kritik girdisi.
    // Eskiden formüle 0.0011 (1.1 L) gömülüydü; araç 1.4 çıkınca tüm
    // tüketim ve maliyet hesapları ~%27 DÜŞÜK gösteriyordu.
    private var engineM3: Double = 0.0014
    private var volEff: Double = 0.85

    private var crankingMonitorActive = false
    private var lowestCrankingVoltage = 99.0

    fun setActiveScreen(screen: AppScreen) { _currentScreen.value = screen }

    fun initFromPrefs(ctx: Context) {
        _isLpg.value = AppPrefs.isLpgMode(ctx)
        engineM3 = AppPrefs.getEngineM3(ctx)
        volEff = AppPrefs.getVolumetricEfficiency(ctx)
    }

    /** Ayarlardan motor hacmi değiştirildiğinde çağrılır. */
    fun refreshEngineParams(ctx: Context) {
        engineM3 = AppPrefs.getEngineM3(ctx)
        volEff = AppPrefs.getVolumetricEfficiency(ctx)
    }

    fun toggleLpg(ctx: Context) {
        val newVal = !_isLpg.value
        _isLpg.value = newVal
        AppPrefs.setLpgMode(ctx, newVal)
    }

    fun connectToVehicle(ip: String, port: Int) {
        viewModelScope.launch {
            _connectionStatus.value = "Başlatılıyor..."
            val result = connection.connect(ip, port) { step -> _connectionStatus.value = step }
            _connectionStatus.value = result
            if (connection.isConnected) {
                startPolling()
                startCrankingMonitor()
            }
        }
    }

    /** "Bağlantıyı Kes" butonu: kaydı bitirir, soketi kapatır, durumu günceller. */
    fun disconnectFromVehicle(ctx: Context) {
        viewModelScope.launch {
            if (driveLogger.isLogging.value) toggleDriveLog(ctx)
            crankingMonitorActive = false
            connection.disconnectByUser()
            _connectionStatus.value = "Bağlantı kesildi"
        }
    }

    /** Beyin Lab'dan dönünce: Lab bağlantıyı açık bıraktıysa ana ekran sorgusunu yeniden başlat. */
    fun onReturnFromLab() {
        if (connection.isConnected) {
            if (!_connectionStatus.value.contains("Başarılı")) _connectionStatus.value = "Bağlantı Başarılı! (Beyin Lab sonrası)"
            startPolling()
        } else if (!connection.manualDisconnect && _connectionStatus.value.contains("Başarılı")) {
            _connectionStatus.value = "Bağlantı koptu — tekrar bağlan"
        }
    }

    // DONMA DÜZELTME: Logger açıkken de polling devam eder ama LOGGER KENDİSİ veriyi üretir
    // Logger liveData'yı _uiState'e yazar
    init {
        viewModelScope.launch {
            driveLogger.liveData.collect { data ->
                if (driveLogger.isLogging.value && data.isNotEmpty()) {
                    _uiState.value = data
                    // Logger açıkken de anlık sensör kontrolü çalışsın
                    (data["O2"] as? ParseResult.Success)?.numericValue?.let { o2v ->
                        o2Samples.addLast(o2v)
                        while (o2Samples.size > 20) o2Samples.removeFirst()
                    }
                    _sensorWarnings.value = LiveSensorCheck.check(data, o2Samples.toList())
                }
            }
        }
    }

    private var pollingJob: kotlinx.coroutines.Job? = null
    @Volatile private var latestSample: Map<String, ParseResult> = emptyMap()
    @Volatile private var latestSampleMs: Long = 0L
    @Volatile private var lastBadRpmRaw: String = ""
    private fun lastRawOf(@Suppress("UNUSED_PARAMETER") pid: String): String = connection.lastRaw

    init {
        driveLogger.sampleSource = { latestSampleMs to latestSample }
        driveLogger.lastBadRaw = { lastBadRpmRaw }
    }

    private fun startPolling() {
        if (pollingJob?.isActive == true) return   // ikinci döngü açılmasın
        pollingJob = viewModelScope.launch(Dispatchers.IO) {
            var loopCounter = 0
            val dataCache = mutableMapOf<String, ParseResult>()
            // Kayıt sürerken bağlantı koparsa logger yeniden bağlar; polling döngüsü ölmesin
            while (isActive && (connection.isConnected || driveLogger.isLogging.value || ElmShared.labActive)) {
                try {
                    // Beyin Lab açıkken ELM onun — araya komut sokma
                    if (ElmShared.labActive || !connection.isConnected) { delay(500); continue }
                    // Kayıt açıkken hangi sekmede olursan ol panel verisi okunmaya devam eder;
                    // kayıt bu verileri alıp yazar (ELM'ye ikinci kez sorulmaz → ekran donmaz)
                    val ekran = if (driveLogger.isLogging.value) AppScreen.DASHBOARD else _currentScreen.value
                    when (ekran) {
                        AppScreen.DASHBOARD -> {
                            val rpmR = repository.getRpm()
                            if (rpmR !is ParseResult.Success) lastBadRpmRaw = lastRawOf("010C")
                            dataMapSafePut(dataCache, "RPM", rpmR)
                            val spdR = repository.getSpeed()
                            dataMapSafePut(dataCache, "SPEED", spdR)
                            if (rpmR is ParseResult.Success || spdR is ParseResult.Success) latestSampleMs = System.currentTimeMillis()
                            when (loopCounter % 6) {
                                0 -> { dataMapSafePut(dataCache, "TEMP", repository.getCoolantTemp()); dataMapSafePut(dataCache, "LOAD", repository.getEngineLoad()) }
                                1 -> { dataMapSafePut(dataCache, "MAP", repository.getMap()); dataMapSafePut(dataCache, "IAT", repository.getIat()) }
                                2 -> { dataMapSafePut(dataCache, "TPS", repository.getTps()); dataMapSafePut(dataCache, "AVANS", repository.getTimingAdvance()) }
                                3 -> { dataMapSafePut(dataCache, "STFT", repository.getStft()); dataMapSafePut(dataCache, "LTFT", repository.getLtft()) }
                                4 -> { dataMapSafePut(dataCache, "O2", repository.getO2Voltage()); dataMapSafePut(dataCache, "FUEL", repository.getFuelLevel()) }
                                5 -> { readBatteryVoltage() }
                            }
                            calculateInstantFuel(dataCache)
                            _uiState.value = HashMap(dataCache)
                            latestSample = HashMap(dataCache)

                            // O2 örneğini biriktir (salınım kontrolü için)
                            (dataCache["O2"] as? ParseResult.Success)?.numericValue?.let { o2v ->
                                o2Samples.addLast(o2v)
                                while (o2Samples.size > 20) o2Samples.removeFirst()
                            }
                            // Anlık sensör sağlık kontrolü
                            _sensorWarnings.value = LiveSensorCheck.check(dataCache, o2Samples.toList())

                            val rpmVal = (dataCache["RPM"] as? ParseResult.Success)?.numericValue?.toFloat() ?: 0f
                            val spdVal = (dataCache["SPEED"] as? ParseResult.Success)?.numericValue?.toFloat() ?: 0f
                            pushHistorySample(rpmVal, spdVal)
                            loopCounter++
                            delay(300)
                        }
                        AppScreen.TESTS -> {
                            dataMapSafePut(dataCache, "RPM", repository.getRpm())
                            dataMapSafePut(dataCache, "SPEED", repository.getSpeed())
                            _uiState.value = HashMap(dataCache)
                            delay(300)
                        }
                        else -> delay(1000)
                    }
                } catch (_: Exception) { delay(1000) }
            }
        }
    }

    private fun startCrankingMonitor() {
        crankingMonitorActive = true; lowestCrankingVoltage = 99.0
        viewModelScope.launch(Dispatchers.IO) {
            val startMs = System.currentTimeMillis()
            while (crankingMonitorActive && System.currentTimeMillis() - startMs < 8000) {
                try {
                    val raw = connection.sendCommand("ATRV")
                    val r = ObdCommand.BatteryVoltage.parse(raw)
                    if (r is ParseResult.Success) r.numericValue?.let { if (it < lowestCrankingVoltage) lowestCrankingVoltage = it }
                } catch (_: Exception) {}
                delay(200)
            }
            crankingMonitorActive = false
        }
    }

    fun saveCrankingEventIfApplicable(ctx: Context) {
        if (lowestCrankingVoltage in 5.0..14.0) BatteryHealthStore.addCrankingEvent(ctx, lowestCrankingVoltage)
    }

    private suspend fun readBatteryVoltage() {
        try {
            val raw = connection.sendCommand("ATRV")
            val r = ObdCommand.BatteryVoltage.parse(raw)
            if (r is ParseResult.Success) _batteryVoltage.value = r.value
        } catch (_: Exception) {}
    }

    private fun calculateInstantFuel(data: Map<String, ParseResult>) {
        val rpm = (data["RPM"] as? ParseResult.Success)?.numericValue ?: return
        val speed = (data["SPEED"] as? ParseResult.Success)?.numericValue ?: (data["SPEED"] as? ParseResult.Success)?.value?.toDoubleOrNull() ?: return
        val mapKpa = (data["MAP"] as? ParseResult.Success)?.numericValue ?: (data["MAP"] as? ParseResult.Success)?.value?.toDoubleOrNull() ?: return
        val iat = (data["IAT"] as? ParseResult.Success)?.numericValue ?: (data["IAT"] as? ParseResult.Success)?.value?.toDoubleOrNull() ?: 25.0
        if (rpm <= 0 || mapKpa <= 0) return

        val afr = if (_isLpg.value) 15.5 else 14.7
        val fuelDensity = if (_isLpg.value) 0.55 else 0.74
        val iatK = iat + 273.15
        val mafKgS = (mapKpa * 1000.0 * engineM3 * volEff * (rpm / 120.0) * 0.02897) / (8.314 * iatK)
        val fuelLh = (mafKgS / afr / fuelDensity) * 3600.0

        if (speed > 5) {
            _instantFuel.value = String.format(Locale.US, "%.1f", fuelLh * 100.0 / speed)
            _instantFuelUnit.value = "L/100km"
        } else {
            _instantFuel.value = String.format(Locale.US, "%.2f", fuelLh)
            _instantFuelUnit.value = "L/saat"
        }
    }

    private fun dataMapSafePut(map: MutableMap<String, ParseResult>, key: String, result: ParseResult) {
        if (result is ParseResult.Success) map[key] = result
    }

    fun toggleDriveLog(ctx: Context): Boolean {
        if (driveLogger.isLogging.value) {
            driveLogger.stopLogging()
            gpsTracker?.stop()
            driveLogger.applyFuelConsumptionToTank(ctx)
            val price = AppPrefs.getActiveFuelPrice(ctx)
            val s = driveLogger.getSummary(price)
            if (s.totalEntries > 0) {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                DriveHistoryStore.add(ctx, DriveHistoryStore.Record(
                    date = sdf.format(Date()), timestamp = System.currentTimeMillis(),
                    durationSec = s.durationSec, totalKm = s.totalKm, totalLiters = s.totalLiters,
                    avgL100 = s.avgLper100, maxRpm = s.maxRpm, maxSpeed = s.maxSpeed,
                    maxCoolant = s.maxCoolant, avgSpeed = s.avgSpeed, ecoScore = s.ecoScore, cost = s.cost
                ))
            }
            return false
        }
        if (!connection.isConnected) { _connectionStatus.value = "Önce ELM327'ye bağlan!"; return false }
        gpsTracker?.let { tr ->
            if (tr.hasPermission()) {
                tr.start()
                viewModelScope.launch { tr.totalKmGps.collect { driveLogger.gpsTotalKm = it } }
            }
        }
        // Yakıt hesabı için motor parametrelerini loggera aktar
        driveLogger.engineM3 = AppPrefs.getEngineM3(ctx)
        driveLogger.volEff = AppPrefs.getVolumetricEfficiency(ctx)
        driveLogger.startLogging(viewModelScope, isLpg = _isLpg.value)
        return true
    }

    fun shareDriveLog(ctx: Context): Boolean = driveLogger.shareCsv(ctx, AppPrefs.getActiveFuelPrice(ctx))
    fun getDriveSummary(ctx: Context): DriveSummary = driveLogger.getSummary(AppPrefs.getActiveFuelPrice(ctx))

    fun pushHistorySample(rpm: Float, speed: Float) {
        _rpmHistory.value = (_rpmHistory.value + rpm).takeLast(60)
        _speedHistory.value = (_speedHistory.value + speed).takeLast(60)
    }

    fun readVin() {
        viewModelScope.launch {
            if (!connection.isConnected) { _vin.value = "Önce bağlan!"; return@launch }
            _vin.value = "Okunuyor..."
            val r = repository.getVin()
            _vin.value = when (r) { is ParseResult.Success -> r.value; is ParseResult.NoData -> "Araç desteklemiyor"; is ParseResult.Error -> "Hata" }
        }
    }

    fun startAccelTest() {
        if (!connection.isConnected) return
        _accelTestState.value = AccelTestState.WAITING_ZERO; _accelTestTime.value = 0.0
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive && _accelTestState.value == AccelTestState.WAITING_ZERO) {
                val sp = (repository.getSpeed() as? ParseResult.Success)?.numericValue ?: -1.0
                if (sp == 0.0) { _accelTestState.value = AccelTestState.RUNNING; break }
                delay(200)
            }
            val startMs = System.currentTimeMillis(); var triggered = false
            while (isActive && _accelTestState.value == AccelTestState.RUNNING) {
                val sp = (repository.getSpeed() as? ParseResult.Success)?.numericValue ?: 0.0
                if (!triggered && sp > 0) triggered = true
                if (triggered) {
                    val elapsed = (System.currentTimeMillis() - startMs) / 1000.0
                    _accelTestTime.value = elapsed
                    if (sp >= 100) { if (_accelTestBest.value == 0.0 || elapsed < _accelTestBest.value) _accelTestBest.value = elapsed; _accelTestState.value = AccelTestState.FINISHED; break }
                    if (elapsed > 60) { _accelTestState.value = AccelTestState.FINISHED; break }
                }
                delay(150)
            }
        }
    }
    fun cancelAccelTest() { _accelTestState.value = AccelTestState.IDLE; _accelTestTime.value = 0.0 }

    fun readAllFaults() {
        viewModelScope.launch {
            _dtcResult.value = ParseResult.Success("Okunuyor...")
            _pendingDtcResult.value = ParseResult.Success("Okunuyor...")
            _dtcResult.value = repository.readDiagnostics()
            _pendingDtcResult.value = repository.readPendingDiagnostics()
        }
    }
    fun readReadinessForInspection() {
        viewModelScope.launch {
            _readinessResult.value = ParseResult.Success("Sorgulanıyor...")
            _readinessResult.value = repository.readReadiness()
        }
    }
    fun clearFaults(ctx: Context) {
        viewModelScope.launch {
            val current = (_dtcResult.value as? ParseResult.Success)?.value
            if (current != null && !current.contains("Arıza Kodu Yok") && !current.contains("Silme")) {
                val codes = Regex("[PCBU][0-9A-F]{4}").findAll(current).map { it.value }.distinct().toList()
                if (codes.isNotEmpty()) DtcHistoryStore.add(ctx, codes)
            }
            _dtcResult.value = ParseResult.Success("Siliniyor...")
            _dtcResult.value = repository.clearDiagnostics()
        }
    }
    fun clearFaults() { viewModelScope.launch { _dtcResult.value = repository.clearDiagnostics() } }

    override fun onCleared() {
        super.onCleared(); crankingMonitorActive = false; gpsTracker?.stop()
        viewModelScope.launch { connection.disconnect() }
    }
}