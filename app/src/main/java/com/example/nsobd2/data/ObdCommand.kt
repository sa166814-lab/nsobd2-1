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
            // Önce kontrol: cevap yok / desteklenmiyor
            val rawUpper = rawResponse.uppercase()
            if (rawUpper.contains("NODATA") || rawUpper.contains("UNABLE") || rawUpper.isBlank())
                return ParseResult.NoData("VIN okunamadı")

            // Tüm boşlukları/satırları temizle, tek hex dizisi yap
            val clean = rawUpper.replace(" ", "").replace("\r", "").replace("\n", "").replace(">", "")

            // 4902 mode cevabını bul
            val startIdx = clean.indexOf("4902")
            if (startIdx == -1) return ParseResult.NoData("VIN okunamadı")

            // 4902'den sonrasını al
            val hex = clean.substring(startIdx)

            // Multi-frame: cevap "4902XX...4902XX...4902XX..." şeklinde tekrar eder.
            // Her 4902 başlığını ve hemen ardından gelen frame sayaç byte'ını (01,02,03) at,
            // sadece VIN karakterlerini topla.
            val sb = StringBuilder()
            var i = 0
            while (i < hex.length) {
                // 4902 başlığına denk geldiysek atla
                if (i + 4 <= hex.length && hex.substring(i, i + 4) == "4902") {
                    i += 4
                    // ardından 1 frame sayaç byte'ı gelir (01/02/03...), onu da atla
                    if (i + 2 <= hex.length) i += 2
                    continue
                }
                // normal VIN byte'ı
                if (i + 2 <= hex.length) {
                    val b = hex.substring(i, i + 2).toIntOrNull(16) ?: 0
                    // sadece VIN harf/rakam aralığı (0-9 ve A-Z), dolgu 00'ları atla
                    if (b in 48..90) sb.append(b.toChar())
                    i += 2
                } else break
            }

            val vin = sb.toString().trim().take(17)
            return when {
                vin.length >= 17 -> ParseResult.Success(vin)
                vin.length >= 11 -> ParseResult.Success("$vin (kısmi)")
                else -> ParseResult.NoData("VIN okunamadı")
            }
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
            // --- YAKIT / HAVA KARIŞIMI ---
            "P0171" -> "Fakir Karışım (Bank 1)!\n[206: O2 fazla hava görüyor. Emme manifold contası, fren servosu vakum hortumu kaçağı, MAP kirli veya lambda yorgun. LPG ayarını da kontrol et.]"
            "P0172" -> "Zengin Karışım (Bank 1)!\n[206: Yakıt fazla. LPG enjektörü kaçırıyor, basınç regülatörü bozuk veya MAP kirli olabilir.]"
            "P0174" -> "Fakir Karışım (Bank 2). [206 4 silindirde nadir; genelde ortak emiş kaçağı.]"
            "P0175" -> "Zengin Karışım (Bank 2)."
            "P0170", "P0173" -> "Yakıt Trim Arızası. Karışım ECU'nun düzeltme sınırını aştı — vakum kaçağı veya enjektör sorunu."

            // --- O2 / LAMBDA SENSÖRÜ ---
            "P0130", "P0131", "P0132", "P0133", "P0134" -> "Oksijen Sensörü (Üst/Sensör 1) Arızası.\n[206: Sensör ömrünü doldurmuş (100k km) veya soketi oksitli. Titreme ve fazla yakıt yapar. 0.1-0.9V salınmıyorsa değiştir.]"
            "P0135" -> "O2 Sensörü Isıtıcı Devresi (Üst).\n[206: Sensörün içindeki ısıtıcı rezistansı yanmış. Soğukken kapalı çevrime geçemez, fazla yakar.]"
            "P0136", "P0137", "P0138", "P0139", "P0140" -> "O2 Sensörü (Alt/Katalizör sonrası) Arızası.\n[206: Katalizör çıkışındaki sensör. Bozuksa P0420 ile birlikte gelir.]"
            "P0141" -> "O2 Sensörü Isıtıcı Devresi (Alt)."

            // --- IAT / HAVA SICAKLIK ---
            "P0110", "P0111", "P0112", "P0113", "P0114" -> "IAT (Hava Emme Sıcaklık) Sensörü.\n[206: Kelebek boğazındaki sensör yağlanmış/arızalı. Balata spreyiyle nazikçe temizle, düzelmezse değiştir.]"

            // --- SU SICAKLIK (MÜŞÜR) ---
            "P0115", "P0116", "P0117", "P0118", "P0119" -> "Su Sıcaklık Sensörü (Müşür).\n[206: Termostat gövdesindeki YEŞİL soketli müşür. Fan açmama, rölanti dalgalanma, fazla yakıt yapar. Ucuz parça, değiştir.]"

            // --- TPS / GAZ KELEBEĞİ ---
            "P0120", "P0121", "P0122", "P0123", "P0124" -> "TPS (Gaz Kelebek Pozisyonu).\n[206: Rölanti dalgalanma veya gaz yememe yaparsa potansiyometre aşınmış. Kelebeği temizle, düzelmezse TPS değiştir.]"
            "P0220", "P0221", "P0222", "P0223" -> "Gaz Kelebeği Pozisyon Sensörü B (elektronik gaz)."
            "P0638" -> "Gaz Kelebeği Motoru (elektronik kelebek varsa). Kelebek motoru/kontrol arızası."
            "P2135" -> "TPS/Pedal Sensör Uyumsuzluğu. İki pozisyon sensörü çelişiyor — kelebek veya pedal sensörü."

            // --- MAP / MAF ---
            "P0105", "P0106", "P0107", "P0108", "P0109" -> "MAP (Manifold Basınç) Sensörü.\n[206: Emme manifoldundaki sensör. Kirliyse karışım bozulur. Kontak temizleyiciyle dikkatli temizle (diyaframa sürtme!).]"
            "P0100", "P0101", "P0102", "P0103", "P0104" -> "MAF (Hava Kütle) Sensörü. [206 MAP kullanır, MAF varsa hava akış ölçer kirlenmiştir; MAF temizleyici sık.]"

            // --- ATEŞLEME / TEKLEME ---
            "P0300" -> "Çoklu Silindir Teklemesi.\n[206: Bobin kütüğü kroniktir! Bobini, bujileri (tırnak 0.9mm), buji kablolarını kontrol et. Isınınca kesiyorsa bobin.]"
            "P0301" -> "1. Silindir Teklemesi (Volan tarafı). Buji/bobin/enjektör 1."
            "P0302" -> "2. Silindir Teklemesi. Buji/bobin/enjektör 2."
            "P0303" -> "3. Silindir Teklemesi. Buji/bobin/enjektör 3."
            "P0304" -> "4. Silindir Teklemesi (Triger tarafı). Buji/bobin/enjektör 4."
            "P0350", "P0351", "P0352", "P0353", "P0354" -> "Ateşleme Bobini Devresi.\n[206: Bobin kütüğü veya kablosu. En sık arıza buradadır.]"

            // --- KRANK / KAM SENSÖRÜ (Motor çalışmama/teklenme) ---
            "P0335", "P0336" -> "Krank Pozisyon Sensörü (CKP).\n[206: Volan/triger bölgesindeki sensör. Arızalıysa araç stop eder veya hiç çalışmaz. Kadran sıfıra düşüyorsa bundan.]"
            "P0340", "P0341", "P0342", "P0343" -> "Kam (Eksantrik) Pozisyon Sensörü (CMP).\n[206: Silindir kapağındaki sensör. Zor çalışma, teklenme yapar.]"
            "P0016", "P0017" -> "Krank-Kam Uyumsuzluğu (Triger kaymış olabilir!).\n[206: Triger kayışı atlamış/gevşemiş olabilir — ACİL, motora zarar verebilir!]"

            // --- KNOCK (Vuruntu) ---
            "P0325", "P0326", "P0327", "P0328" -> "Vuruntu (Knock) Sensörü.\n[206: Motor bloğundaki sensör. Arızalıysa ECU avansı kısar, güç düşer, yakıt artar.]"

            // --- KATALİZÖR / EMİSYON ---
            "P0420" -> "Katalitik Konvertör Verimsiz (Bank 1).\n[206: LPG'lilerde çok sık. Alt O2 yaşlanmış olabilir veya katalizör ölmüş. Önce alt lambdayı kontrol et.]"
            "P0430" -> "Katalitik Konvertör Verimsiz (Bank 2)."
            "P0401", "P0402", "P0403", "P0404", "P0405", "P0406" -> "EGR (Egzoz Geri Dönüşüm) Valfi.\n[206: EGR valfi kurumdan tıkanmış olabilir. Söküp temizle veya değiştir. Rölanti dalgalanma yapar.]"
            "P0440", "P0441", "P0442", "P0443", "P0446", "P0455", "P0456" -> "EVAP (Yakıt Buharı) Sistemi.\n[206: Yakıt deposu kapağı gevşek/contası bozuk olabilir! Önce depo kapağını sıkıca kapat. Kanister/hortum kaçağı.]"

            // --- SOĞUTMA / TERMOSTAT ---
            "P0128" -> "Motor Çalışma Sıcaklığına Ulaşamıyor (Termostat).\n[206: Termostat açık takılı kalmış, motor ısınmıyor. Termostat değiştir. Fazla yakıt yapar.]"
            "P0217" -> "Motor Aşırı Isındı. Soğutma sistemi acil kontrol — antifriz, su pompası, radyatör."
            "P0480", "P0481", "P0691", "P0692" -> "Fan/Soğutma Fanı Rölesi Devresi.\n[206: Radyatör fanı veya rölesi arızalı. Hararet yapar, fan dönmez.]"

            // --- HIZ / DEVİR ---
            "P0500", "P0501", "P0502", "P0503" -> "VSS (Araç Hız Sensörü).\n[206: Şanzıman üstündeki hız müşürü. Kadran ara sıra sıfıra düşüyorsa bu. Kilometre saati sapıtır.]"
            "P0505", "P0506", "P0507" -> "Rölanti Kontrol (Stepper/IAC).\n[206: Rölanti motoru kirli/arızalı. Rölanti düşük/yüksek veya dalgalı. Kelebek gövdesini temizle.]"

            // --- ENJEKTÖR / YAKIT POMPA ---
            "P0201", "P0202", "P0203", "P0204" -> "Enjektör Devresi (silindir sırasıyla).\n[206: İlgili silindirin enjektörü veya kablosu. Tekleme/güç kaybı.]"
            "P0230", "P0231", "P0232", "P0627", "P0628", "P0629" -> "Yakıt Pompası Devresi/Rölesi. Pompa veya rölesi arızalı, çalışmama/stop."
            "P0190", "P0191", "P0192", "P0193" -> "Yakıt Basınç Sensörü/Regülatörü."

            // --- VVT / VOLTAJ / ECU ---
            "P0011", "P0012", "P0014", "P0021" -> "Eksantrik Ayar (VVT/VANOS varsa). [206 1.4/1.6 16V'de olabilir; yağ basıncı/VVT valfi.]"
            "P0560", "P0562", "P0563" -> "Sistem Voltajı (Akü/Şarj).\n[206: Akü zayıf veya alternatör/dinamo sorunu. Şarj voltajını kontrol et.]"
            "P0606", "P0601", "P0604", "P0605" -> "ECU/Motor Beyni İç Arızası. Beyin hafıza/işlemci hatası — nadir, soket/topraklama da olabilir."

            // --- GENEL KATEGORİ (tabloda yoksa) ---
            else -> {
                when {
                    code.startsWith("P00") -> "Yakıt/Hava Ölçüm Sensörleri (O2, MAP, IAT, Hararet, Krank, Kam)."
                    code.startsWith("P01") -> "Yakıt/Hava Karışımı ve Sensörleri (O2, MAP, IAT, TPS, Hararet)."
                    code.startsWith("P02") -> "Yakıt Sistemi (Enjektör, Pompa, Basınç)."
                    code.startsWith("P03") -> "Ateşleme Sistemi (Bobin, Buji, Krank/Kam Sensörü, Tekleme)."
                    code.startsWith("P04") -> "Emisyon Sistemi (Katalizör, EGR, EVAP, İkincil Hava)."
                    code.startsWith("P05") -> "Hız/Rölanti/Soğutma Kontrol (VSS, Rölanti motoru, Fan)."
                    code.startsWith("P06") -> "ECU/Çıkış Devreleri (Beyin, Röle, Voltaj)."
                    code.startsWith("P07") || code.startsWith("P08") -> "Şanzıman/Vites Sistemi."
                    code.startsWith("C") -> "Şasi Sistemi (ABS, Fren, Direksiyon) — ayrı modül, Lexia gerekebilir."
                    code.startsWith("B") -> "Gövde/Konfor Sistemi (BSI: cam, kapı, klima) — Lexia gerekebilir."
                    code.startsWith("U") -> "İletişim/Ağ (CAN-BUS) Arızası — modüller arası haberleşme."
                    else -> "Bilinmeyen Kod"
                }
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
