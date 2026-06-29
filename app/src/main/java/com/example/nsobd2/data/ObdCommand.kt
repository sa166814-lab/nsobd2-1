package com.example.nsobd2.data

import java.util.Locale

sealed class ParseResult {
    data class Success(val value: String, val numericValue: Double? = null) : ParseResult()
    data class Error(val message: String) : ParseResult()
    data class NoData(val reason: String) : ParseResult()
}

sealed class ObdCommand(val pid: String, val name: String) {

    abstract fun parse(rawResponse: String): ParseResult

    protected fun getHexBytes(raw: String): List<Int> {
        val clean = raw.replace(" ", "").replace("\r", "").replace("\n", "").replace(">", "")
        if (clean.length < 4 || !clean.startsWith("41")) return emptyList()
        return clean.substring(4).chunked(2).mapNotNull { it.toIntOrNull(16) }
    }

    object RPM : ObdCommand("010C", "Motor Devri") {
        override fun parse(rawResponse: String): ParseResult {
            val bytes = getHexBytes(rawResponse)
            return if (bytes.size >= 2) {
                val v = (((bytes[0] * 256) + bytes[1]) / 4.0)
                ParseResult.Success(v.toInt().toString(), v)
            } else ParseResult.NoData("-")
        }
    }
    object Speed : ObdCommand("010D", "Araç Hızı") {
        override fun parse(rawResponse: String): ParseResult {
            val bytes = getHexBytes(rawResponse)
            return if (bytes.isNotEmpty()) ParseResult.Success(bytes[0].toString(), bytes[0].toDouble()) else ParseResult.NoData("-")
        }
    }
    object CoolantTemp : ObdCommand("0105", "Su Sıcaklığı") {
        override fun parse(rawResponse: String): ParseResult {
            val bytes = getHexBytes(rawResponse)
            return if (bytes.isNotEmpty()) {
                val v = (bytes[0] - 40).toDouble()
                ParseResult.Success(v.toInt().toString(), v)
            } else ParseResult.NoData("-")
        }
    }
    object FuelLevel : ObdCommand("012F", "Yakıt Seviyesi") {
        override fun parse(rawResponse: String): ParseResult {
            val bytes = getHexBytes(rawResponse)
            return if (bytes.isNotEmpty()) {
                val v = (bytes[0] * 100.0) / 255.0
                ParseResult.Success(String.format(Locale.US, "%.1f", v), v)
            } else ParseResult.NoData("-")
        }
    }

    object EngineLoad : ObdCommand("0104", "Motor Yükü") {
        override fun parse(rawResponse: String): ParseResult {
            val bytes = getHexBytes(rawResponse)
            return if (bytes.isNotEmpty()) {
                val v = (bytes[0] * 100.0) / 255.0
                ParseResult.Success(String.format(Locale.US, "%.1f", v), v)
            } else ParseResult.NoData("-")
        }
    }
    object MAP : ObdCommand("010B", "Manifold Basıncı (MAP)") {
        override fun parse(rawResponse: String): ParseResult {
            val bytes = getHexBytes(rawResponse)
            return if (bytes.isNotEmpty()) ParseResult.Success(bytes[0].toString(), bytes[0].toDouble()) else ParseResult.NoData("-")
        }
    }
    object IAT : ObdCommand("010F", "Hava Emme Sıcaklığı (IAT)") {
        override fun parse(rawResponse: String): ParseResult {
            val bytes = getHexBytes(rawResponse)
            return if (bytes.isNotEmpty()) {
                val v = (bytes[0] - 40).toDouble()
                ParseResult.Success(v.toInt().toString(), v)
            } else ParseResult.NoData("-")
        }
    }
    object TPS : ObdCommand("0111", "Gaz Kelebeği (TPS)") {
        override fun parse(rawResponse: String): ParseResult {
            val bytes = getHexBytes(rawResponse)
            return if (bytes.isNotEmpty()) {
                val v = (bytes[0] * 100.0) / 255.0
                ParseResult.Success(String.format(Locale.US, "%.1f", v), v)
            } else ParseResult.NoData("-")
        }
    }
    object TimingAdvance : ObdCommand("010E", "Ateşleme Avansı") {
        override fun parse(rawResponse: String): ParseResult {
            val bytes = getHexBytes(rawResponse)
            return if (bytes.isNotEmpty()) {
                val v = (bytes[0] / 2.0) - 64.0
                ParseResult.Success(String.format(Locale.US, "%.1f", v), v)
            } else ParseResult.NoData("-")
        }
    }
    object ShortTermFuelTrim : ObdCommand("0106", "STFT Bank 1") {
        override fun parse(rawResponse: String): ParseResult {
            val bytes = getHexBytes(rawResponse)
            return if (bytes.isNotEmpty()) {
                val v = ((bytes[0] - 128) * 100.0) / 128.0
                ParseResult.Success(String.format(Locale.US, "%.1f", v), v)
            } else ParseResult.NoData("-")
        }
    }
    object LongTermFuelTrim : ObdCommand("0107", "LTFT Bank 1") {
        override fun parse(rawResponse: String): ParseResult {
            val bytes = getHexBytes(rawResponse)
            return if (bytes.isNotEmpty()) {
                val v = ((bytes[0] - 128) * 100.0) / 128.0
                ParseResult.Success(String.format(Locale.US, "%.1f", v), v)
            } else ParseResult.NoData("-")
        }
    }
    object O2Voltage : ObdCommand("0114", "Oksijen Sensörü (Bank1 Sen1)") {
        override fun parse(rawResponse: String): ParseResult {
            val bytes = getHexBytes(rawResponse)
            return if (bytes.size >= 2) {
                val v = bytes[0] / 200.0
                ParseResult.Success(String.format(Locale.US, "%.3f", v), v)
            } else ParseResult.NoData("-")
        }
    }

    // ELM327'nin kendi komutu — donanımdan gelir, ECU'dan değil.
    object BatteryVoltage : ObdCommand("ATRV", "Akü Voltajı") {
        override fun parse(rawResponse: String): ParseResult {
            val clean = rawResponse
                .replace("ATRV", "", ignoreCase = true)
                .replace("V", "", ignoreCase = true)
                .replace(">", "")
                .replace("\r", "")
                .replace("\n", "")
                .replace(" ", "")
                .trim()
            val match = Regex("""\d+\.?\d*""").find(clean)
            val v = match?.value?.toDoubleOrNull()
            return if (v != null && v > 5.0 && v < 20.0) {
                ParseResult.Success(String.format(Locale.US, "%.1f", v), v)
            } else ParseResult.NoData("Okunamadı")
        }
    }

    object Vin : ObdCommand("0902", "VIN / Şasi Numarası") {
        override fun parse(rawResponse: String): ParseResult {
            val clean = rawResponse.replace(" ", "").replace("\r", "").replace("\n", "").replace(">", "").uppercase()
            if (clean.isEmpty() || clean.contains("NODATA")) return ParseResult.NoData("VIN okunamadı")
            val hex = clean.replace("4902", "")
            val sb = StringBuilder()
            var i = 0
            while (i < hex.length - 1) {
                val byteStr = hex.substring(i, i + 2)
                val b = byteStr.toIntOrNull(16) ?: 0
                if (b in 32..126) sb.append(b.toChar())
                i += 2
            }
            val vin = sb.toString().trim().take(17)
            return if (vin.length >= 17) ParseResult.Success(vin)
            else if (vin.isNotEmpty()) ParseResult.Success(vin + " (kısmi)")
            else ParseResult.NoData("VIN okunamadı")
        }
    }

    object Readiness : ObdCommand("0101", "Hazırlık Monitörleri") {
        override fun parse(rawResponse: String): ParseResult {
            val bytes = getHexBytes(rawResponse)
            if (bytes.size >= 4) {
                val isMilOn = (bytes[0] and 0x80) != 0
                val dtcCount = bytes[0] and 0x7F
                val muayeneDurumu = if (isMilOn || dtcCount > 0) {
                    "🔴 TÜVTÜRK: AĞIR KUSUR (KALIR!)\nMotor arıza ışığı yanıyor veya beyinde silinmemiş hata kodu var. Muayeneye gitmeden önce arızayı çözüp kodları silin."
                } else {
                    "🟢 TÜVTÜRK: GEÇER!\nSistem temiz. Motor arıza ışığı sönük ve emisyon testine hazır."
                }
                return ParseResult.Success("Aktif Arıza Sayısı: $dtcCount\n\n$muayeneDurumu")
            }
            return ParseResult.NoData("Okunamadı")
        }
    }

    protected fun getDtcMeaning(code: String): String {
        return when (code) {
            "P0171" -> "Fakir Karışım! \n[206 Teşhisi: O2 sensörü çok fazla hava algılıyor. Emme manifoldu contasından veya fren servosundan vakum kaçağı olabilir. LPG ayarını da kontrol et.]"
            "P0172" -> "Zengin Karışım! \n[206 Teşhisi: Yakıt çok fazla. LPG enjektörleri kaçırıyor olabilir veya MAP sensörü kirlenmiş olabilir.]"
            "P0130", "P0131", "P0132", "P0133", "P0134" -> "Oksijen Sensörü (Üst) Arızası. \n[206 Teşhisi: Sensör ömrünü tamamlamış (100k km) veya soketi oksitlenmiş olabilir. Titreme ve fazla yakıta sebep olur.]"
            "P0110", "P0111", "P0112", "P0113" -> "IAT (Hava Emme Sıcaklık) Sensörü. \n[206 Teşhisi: Kelebek boğazındaki sensör yağlanmış olabilir. Balata spreyiyle nazikçe temizle.]"
            "P0115", "P0116", "P0117", "P0118" -> "Su Sıcaklık Sensörü (Müşür). \n[206 Teşhisi: Termostat gövdesindeki yeşil soketli müşürü kontrol et. Fan açmamaya veya rölanti dalgalanmasına yol açar.]"
            "P0120", "P0121", "P0122", "P0123" -> "TPS (Gaz Kelebek Pozisyonu). \n[206 Teşhisi: Rölantide dalgalanma veya gaz yememe yapıyorsa TPS potansiyometresi aşınmış olabilir.]"
            "P0300" -> "Çoklu Silindir Teklemesi. \n[206 Teşhisi: Bobin kütüğü 206'larda kroniktir. Bobini veya bujileri (tırnak aralığını) kontrol et.]"
            "P0301" -> "1. Silindir Teklemesi (Volan tarafı)."
            "P0302" -> "2. Silindir Teklemesi."
            "P0303" -> "3. Silindir Teklemesi."
            "P0304" -> "4. Silindir Teklemesi (Triger tarafı)."
            "P0420" -> "Katalitik Konvertör Verimsiz. \n[206 Teşhisi: LPG'li araçlarda çok sık yanar. O2 sensörü yanıltıcı takmak veya katalizör değişimi gerekebilir.]"
            "P0500" -> "VSS (Araç Hız Sensörü). \n[206 Teşhisi: Şanzımanın üstündeki hız müşürü arızalı. Kadran ara sıra sıfıra düşüyorsa bundandır.]"
            else -> {
                if (code.startsWith("P01")) "Yakıt/Hava Karışımı Sensörleri (O2, MAP, IAT, Hararet)"
                else if (code.startsWith("P02")) "Yakıt Sistemi (Pompa, Enjektör)"
                else if (code.startsWith("P03")) "Ateşleme Sistemi (Bobin, Buji, Krank Sensörü)"
                else if (code.startsWith("P04")) "Emisyon (Katalizör, EGR)"
                else "Bilinmeyen Kod"
            }
        }
    }

    protected fun parseDtc(rawResponse: String, modePrefix: String): ParseResult {
        val clean = rawResponse.replace(" ", "").replace("\r", "").replace("\n", "").replace(">", "").uppercase()
        if (clean.contains("NODATA") || clean.isEmpty() || clean.contains("OK") ||
            clean.contains("UNABLE") || clean.contains("?")) {
            return ParseResult.Success("Arıza Kodu Yok\nAslan gibi maşallah!")
        }

        val dtcPart = clean.indexOf(modePrefix)
        if (dtcPart == -1) return ParseResult.Error("Veri Anlaşılamadı")

        // Mode cevap byte'ından sonrasını al (43 -> 03 cevabı, 47 -> 07 cevabı)
        // KWP FAST'te (senin araç) sayaç byte'ı yok, kodlar direkt 2'şer byte gelir.
        val data = clean.substring(dtcPart + 2)
        val codes = mutableListOf<String>()

        var i = 0
        while (i + 4 <= data.length) {
            val dtcHex = data.substring(i, i + 4)
            i += 4

            if (dtcHex == "0000") continue          // boş slot / padding

            val b1 = dtcHex.substring(0, 2).toIntOrNull(16) ?: continue  // ilk byte
            val b2 = dtcHex.substring(2, 4)                              // ikinci byte (3. ve 4. rakam)

            // İlk byte'ın bitlerini doğru parçala:
            val letter = when ((b1 shr 6) and 0x03) {   // üst 2 bit -> harf
                0 -> "P"
                1 -> "C"
                2 -> "B"
                else -> "U"
            }
            val firstDigit = (b1 shr 4) and 0x03                        // sonraki 2 bit -> 1. rakam
            val secondDigit = (b1 and 0x0F).toString(16).uppercase()    // alt 4 bit -> 2. rakam

            val finalCode = "$letter$firstDigit$secondDigit$b2"
            val meaning = getDtcMeaning(finalCode)
            codes.add("🚨 $finalCode\n$meaning")
        }

        return if (codes.isEmpty()) ParseResult.Success("Arıza Kodu Yok")
        else ParseResult.Success(codes.joinToString("\n\n------------------------\n\n"))
    }

    object ReadDTC : ObdCommand("03", "Aktif Arıza Oku") {
        override fun parse(rawResponse: String) = parseDtc(rawResponse, "43")
    }

    object ReadPendingDTC : ObdCommand("07", "Bekleyen Arıza Oku") {
        override fun parse(rawResponse: String) = parseDtc(rawResponse, "47")
    }

    object ClearDTC : ObdCommand("04", "Arıza Sil") {
        override fun parse(rawResponse: String): ParseResult {
            return if (rawResponse.contains("OK") || rawResponse.contains("44") || rawResponse.contains("?")) {
                ParseResult.Success("SİLME KOMUTU GİTTİ! \nÖNEMLİ: Işık sönmediyse kontağı tamamen kapat, 10 saniye bekle ve tekrar aç.")
            } else ParseResult.Error("Hata: Komut iletilemedi.")
        }
    }
}