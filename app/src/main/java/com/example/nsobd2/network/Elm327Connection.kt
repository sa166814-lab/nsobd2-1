package com.example.nsobd2.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Uygulamanin TEK ELM baglantisi. WiFi ELM327 ayni anda tek istemci kabul eder;
 * eskiden Beyin Lab ikinci bir soket aciyordu ve ana baglantiyla cakisiyordu.
 */
object ElmShared {
    val connection = Elm327Connection()
    /** Beyin Lab acikken ana ekran sorgulari ve surus kaydi ELM'ye dokunmaz. */
    @Volatile var labActive = false
}

class Elm327Connection {
    @Volatile var isConnected = false
    /** Kullanici "Baglantiyi kes" dediyse otomatik yeniden baglanma yapilmaz. */
    @Volatile var manualDisconnect = false
    /** Son komutun ham cevabi (teshis icin). */
    @Volatile var lastRaw: String = ""
    private var socket: Socket? = null
    private var inStream: InputStream? = null
    private var outStream: OutputStream? = null
    private val cmdMutex = Mutex()
    private val connectMutex = Mutex()   // iki ekran ayni anda baglanmaya calisirsa birbirini bozmasin
    private var lastIp: String = "192.168.0.10"
    private var lastPort: Int = 35000

    suspend fun connect(ip: String = "192.168.0.10", port: Int = 35000, updateStatus: suspend (String) -> Unit): String =
        connectMutex.withLock { connectInternal(ip, port, updateStatus) }

    /**
     * Soketi kapatmadan ELM ayarlarini fabrika durumuna dondurur (ATD) ve normal ayarlari kurar.
     * Beyin Lab testleri header/adres/baud degistirdikten sonra kullanilir.
     */
    suspend fun restoreDefaults() = cmdMutex.withLock {
        if (!isConnected) return@withLock
        sendCommandInternal("ATPC"); sendCommandInternal("ATD"); delay(300)
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATST64", "ATSP5")) sendCommandInternal(c)
        sendCommandInternal("0100")
    }

    private suspend fun connectInternal(ip: String, port: Int, updateStatus: suspend (String) -> Unit): String {
        return withContext(Dispatchers.IO) {
            try {
                lastIp = ip; lastPort = port; manualDisconnect = false
                try { socket?.close() } catch (_: Exception) {}
                socket = null; inStream = null; outStream = null; isConnected = false

                updateStatus("ADIM 1: Wi-Fi Cihazına Bağlanılıyor...")
                var connected = false; var attempts = 0
                while (!connected && attempts < 3) {
                    try {
                        socket = Socket()
                        socket?.tcpNoDelay = true
                        socket?.keepAlive = true
                        socket?.receiveBufferSize = 8192
                        socket?.connect(InetSocketAddress(ip, port), 5000)
                        socket?.soTimeout = 5000
                        connected = true
                    } catch (e: java.net.NoRouteToHostException) {
                        attempts++
                        updateStatus("Ağ Bekleniyor... ($attempts/3)")
                        delay(1500)
                        if (attempts >= 3) throw e
                    }
                }

                inStream = socket?.getInputStream()
                outStream = socket?.getOutputStream()

                updateStatus("ADIM 2: ELM Hazırlanıyor...")
                sendCommandInternal("ATZ"); delay(2000); drainInput()
                sendCommandInternal("ATE0")
                sendCommandInternal("ATL0")
                sendCommandInternal("ATS0")
                sendCommandInternal("ATH0")
                sendCommandInternal("ATAT1")
                sendCommandInternal("ATST64")

                // Direkt KWP FAST (senin araç bu protokolü kullanıyor)
                updateStatus("ADIM 3: ECU Bağlanıyor (KWP FAST)...")
                sendCommandInternal("ATSP5")
                delay(500)
                var ecuTest = sendCommandInternal("0100")

                if (shouldTryNext(ecuTest)) {
                    updateStatus("SP5 olmadı, otomatik deneniyor...")
                    sendCommandInternal("ATPC"); delay(200)
                    sendCommandInternal("ATSP0"); delay(500)
                    ecuTest = sendCommandInternal("0100")
                }

                if (shouldTryNext(ecuTest)) {
                    isConnected = false
                    return@withContext "HATA: ECU Yanıt Vermiyor!"
                }

                isConnected = true
                val currentProtocol = sendCommandInternal("ATDP")
                return@withContext "Bağlantı Başarılı! ($currentProtocol)"
            } catch (e: Exception) {
                isConnected = false
                return@withContext "HATA: Cihaza Ulaşılamadı"
            }
        }
    }

    private fun shouldTryNext(r: String): Boolean {
        val u = r.uppercase()
        return u.contains("UNABLE") || u.contains("NO DATA") || u.contains("ERROR") || u.isEmpty() || u.contains("?")
    }

    private fun drainInput() {
        try { val a = inStream?.available() ?: 0; if (a > 0) inStream?.read(ByteArray(a)) } catch (_: Exception) {}
    }

    suspend fun sendCommand(cmd: String): String = cmdMutex.withLock { sendCommandInternal(cmd) }

    /** Yavas init gibi uzun suren komutlar icin: '>' gelene kadar daha uzun bekler. */
    suspend fun sendCommandLong(cmd: String, timeoutMs: Long = 8000): String =
        cmdMutex.withLock { sendCommandInternal(cmd, timeoutMs) }

    private suspend fun sendCommandInternal(cmd: String, timeoutMs: Long = 3000): String {
        if (socket == null || socket?.isConnected != true || socket?.isClosed == true) return ""
        return withContext(Dispatchers.IO) {
            try {
                outStream?.write(("$cmd\r").toByteArray()); outStream?.flush()
                val buffer = ByteArray(1024)
                val response = StringBuilder()
                val startTime = System.currentTimeMillis()
                try {
                    while (true) {
                        if (System.currentTimeMillis() - startTime > timeoutMs) break
                        val bytesRead = inStream?.read(buffer) ?: break
                        if (bytesRead < 0) { isConnected = false; break } // karsi taraf baglantiyi kapatti
                        val chunk = String(buffer, 0, bytesRead)
                        response.append(chunk)
                        if (chunk.contains(">")) break
                    }
                } catch (_: SocketTimeoutException) {}
                response.toString().replace(">", "").replace("SEARCHING...", "").replace("\r", "").replace("\n", "").trim()
                    .also { lastRaw = it.ifEmpty { "(bos cevap)" } }
            } catch (_: Exception) { isConnected = false; "" } // yazma/okuma hatasi = baglanti koptu
        }
    }

    /** Son kullanilan IP/port ile bastan baglanir (ATZ + protokol init). Surus kaydi kopunca kullanilir. */
    suspend fun reconnect(): Boolean {
        if (manualDisconnect) return false
        connect(lastIp, lastPort) { }
        return isConnected
    }

    /** Kullanici istegiyle kapatma: otomatik yeniden baglanmayi da durdurur. */
    suspend fun disconnectByUser() { manualDisconnect = true; disconnect() }

    suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            try {
                isConnected = false
                try { outStream?.write("ATPC\r".toByteArray()); outStream?.flush() } catch (_: Exception) {}
                delay(100)
                try { inStream?.close() } catch (_: Exception) {}
                try { outStream?.close() } catch (_: Exception) {}
                try { socket?.close() } catch (_: Exception) {}
                inStream = null; outStream = null; socket = null
            } catch (_: Exception) {}
        }
    }
}