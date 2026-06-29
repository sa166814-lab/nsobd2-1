package com.example.nsobd2.repository

import com.example.nsobd2.data.ObdCommand
import com.example.nsobd2.data.ParseResult
import com.example.nsobd2.network.Elm327Connection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ObdRepository(private val connection: Elm327Connection) {

    // Canlı Veriler
    suspend fun getRpm() = fetch(ObdCommand.RPM)
    suspend fun getSpeed() = fetch(ObdCommand.Speed)
    suspend fun getCoolantTemp() = fetch(ObdCommand.CoolantTemp)
    suspend fun getEngineLoad() = fetch(ObdCommand.EngineLoad)
    suspend fun getFuelLevel() = fetch(ObdCommand.FuelLevel)
    suspend fun getMap() = fetch(ObdCommand.MAP)
    suspend fun getIat() = fetch(ObdCommand.IAT)
    suspend fun getTps() = fetch(ObdCommand.TPS)
    suspend fun getTimingAdvance() = fetch(ObdCommand.TimingAdvance)
    suspend fun getStft() = fetch(ObdCommand.ShortTermFuelTrim)
    suspend fun getLtft() = fetch(ObdCommand.LongTermFuelTrim)
    suspend fun getO2Voltage() = fetch(ObdCommand.O2Voltage)

    // Arıza İşlemleri
    suspend fun readReadiness() = fetch(ObdCommand.Readiness)
    suspend fun readDiagnostics() = fetch(ObdCommand.ReadDTC)
    suspend fun readPendingDiagnostics() = fetch(ObdCommand.ReadPendingDTC)
    suspend fun clearDiagnostics() = fetch(ObdCommand.ClearDTC)

    private suspend fun fetch(command: ObdCommand): ParseResult = withContext(Dispatchers.IO) {
        if (!connection.isConnected) return@withContext ParseResult.Error("Bağlantı Yok")
        val response = connection.sendCommand(command.pid)
        command.parse(response)
    }
    suspend fun getVin(): com.example.nsobd2.data.ParseResult {
        val raw = connection.sendCommand(com.example.nsobd2.data.ObdCommand.Vin.pid)
        return com.example.nsobd2.data.ObdCommand.Vin.parse(raw)
    }
}