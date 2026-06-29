package com.example.nsobd2.data

/**
 * Sürüş loglarından parça sağlığı çıkarımı yapar.
 *
 * Önemli: OBD verisi parçaların doğrudan sağlığını söylemez.
 * Bu skorlar olasılıksal göstergelerdir — sensör değerlerinin
 * normal aralıkta olup olmadığına bakar. Gerçek arıza için
 * mekanik teşhis şart.
 */
object PartHealthAnalyzer {

    data class PartScore(
        val name: String,
        val score: Int,           // 0-100
        val emoji: String,
        val status: String,
        val advice: String,
        val howToFix: String
    )

    fun analyze(entries: List<DriveLogEntry>): List<PartScore> {
        if (entries.size < 5) return emptyList()
        return listOf(
            scoreMap(entries),
            scoreO2(entries),
            scoreFuelTrim(entries),
            scoreCoolant(entries),
            scoreIgnition(entries),
            scoreThrottle(entries)
        )
    }

    // ---------- MAP ----------
    private fun scoreMap(entries: List<DriveLogEntry>): PartScore {
        val maps = entries.mapNotNull { it.map.toDoubleOrNull() }
        val rpms = entries.mapNotNull { it.rpm.toDoubleOrNull() }
        if (maps.isEmpty() || rpms.isEmpty()) return PartScore(
            "MAP Sensörü", 100, "❓", "Veri yok", "Yeterli sürüş verisi toplanmadı.", ""
        )
        // Normal: rölantide 25-45 kPa, gazda 70-100 kPa civarı
        val rolantiMaps = entries.filter { (it.rpm.toDoubleOrNull() ?: 0.0) in 600.0..1100.0 }
            .mapNotNull { it.map.toDoubleOrNull() }
        var score = 100
        var notes = mutableListOf<String>()
        if (rolantiMaps.isNotEmpty()) {
            val avg = rolantiMaps.average()
            if (avg < 20 || avg > 55) {
                score -= 25
                notes.add("Rölanti MAP değeri (${"%.0f".format(avg)} kPa) aralık dışı")
            }
        }
        // MAP hiç değişmiyorsa sıkışmış olabilir
        val variation = if (maps.isNotEmpty()) (maps.max() - maps.min()) else 0.0
        if (variation < 10 && maps.size > 30) {
            score -= 30
            notes.add("MAP değeri çok az değişiyor — sensör sıkışmış olabilir")
        }
        score = score.coerceIn(0, 100)
        val (emoji, status) = scoreToStatus(score)
        return PartScore(
            "MAP Sensörü", score, emoji, status,
            advice = if (notes.isEmpty()) "Değerler normal aralıkta görünüyor."
            else notes.joinToString(". "),
            howToFix = if (score < 70) """
                MAP sensörü temizleme:
                1. Emme manifoldunda, kelebek boğazına yakın küçük sensördür.
                2. Soketi çıkar, 10mm anahtar veya yıldız tornavida ile söker.
                3. Kontak temizleyici (CRC veya benzeri) ile dikkatli sıkıp bekle.
                4. ASLA brüş veya tel ile sürtme — diyafram hassastır.
                5. Tam kuruduktan sonra tak, 5 dk araç çalıştır.
            """.trimIndent() else ""
        )
    }

    // ---------- O2 (Lambda) ----------
    private fun scoreO2(entries: List<DriveLogEntry>): PartScore {
        val volts = entries.mapNotNull { it.lambda.toDoubleOrNull() }
        if (volts.isEmpty()) return PartScore(
            "Oksijen Sensörü", 100, "❓", "Veri yok", "Yeterli sürüş verisi toplanmadı.", ""
        )
        var score = 100
        val notes = mutableListOf<String>()
        // Sağlıklı sensör 0.1V - 0.9V arası dalgalanır
        val variation = volts.max() - volts.min()
        if (variation < 0.2) {
            score -= 35
            notes.add("Lambda voltajı az dalgalanıyor (${"%.2f".format(variation)}V) — sensör yorulmuş olabilir")
        }
        val avg = volts.average()
        if (avg < 0.1 || avg > 0.9) {
            score -= 20
            notes.add("Ortalama lambda voltajı ekstrem (${"%.2f".format(avg)}V)")
        }
        // Sensör sürekli aynı değerde takılı kalmışsa
        val sameCount = volts.count { kotlin.math.abs(it - avg) < 0.05 }
        if (sameCount > volts.size * 0.7) {
            score -= 25
            notes.add("Lambda değeri çoğu zaman sabit — geri besleme bozuk")
        }
        score = score.coerceIn(0, 100)
        val (emoji, status) = scoreToStatus(score)
        return PartScore(
            "Oksijen Sensörü", score, emoji, status,
            advice = if (notes.isEmpty()) "Lambda dalgalanması normal aralıkta."
            else notes.joinToString(". "),
            howToFix = if (score < 70) """
                Oksijen sensörü kontrolü/değişimi:
                1. 206'da sensör katalizör öncesinde, egzoz manifoldunda.
                2. Soketi çıkar, 22mm özel lambda anahtarıyla söker (motor soğukken).
                3. Yeni sensörün dişine yüksek sıcaklık gresi sür.
                4. El ile takıp anahtarla sıkıştır (aşırı sıkmaya çalışma).
                5. Soketi tak, hata kodlarını sil. 10-15 dk sürerek adapte et.
                Maliyet: 400-1500 TL arası (orijinal/muadil).
            """.trimIndent() else ""
        )
    }

    // ---------- Yakıt Karışımı (Trim'lerden) ----------
    private fun scoreFuelTrim(entries: List<DriveLogEntry>): PartScore {
        val stfts = entries.mapNotNull { it.stft.toDoubleOrNull() }
        val ltfts = entries.mapNotNull { it.ltft.toDoubleOrNull() }
        if (stfts.isEmpty() && ltfts.isEmpty()) return PartScore(
            "Yakıt Karışımı", 100, "❓", "Veri yok", "Yeterli sürüş verisi toplanmadı.", ""
        )
        var score = 100
        val notes = mutableListOf<String>()
        val ltftAvg = if (ltfts.isNotEmpty()) ltfts.average() else 0.0
        val stftAvg = if (stfts.isNotEmpty()) stfts.average() else 0.0
        // İdeal: -5 ile +5 arası
        if (kotlin.math.abs(ltftAvg) > 10) {
            score -= 30
            val tip = if (ltftAvg > 0) "yakıt artırma" else "yakıt kısma"
            notes.add("LTFT ortalama %${"%.1f".format(ltftAvg)} ($tip eğilimi)")
        }
        if (kotlin.math.abs(stftAvg) > 10) {
            score -= 20
            notes.add("STFT ortalama %${"%.1f".format(stftAvg)}")
        }
        score = score.coerceIn(0, 100)
        val (emoji, status) = scoreToStatus(score)
        val tipDir = when {
            ltftAvg > 10 -> "Sistem yakıt ekliyor (fakir karışım) → vakum kaçağı, MAF/MAP arızası, lambda yorgun, hava emiş kaçağı."
            ltftAvg < -10 -> "Sistem yakıt kısıyor (zengin karışım) → enjektör kaçağı, basınç regülatörü bozuk, LPG kaçırıyor olabilir."
            else -> "Yakıt karışımı normal görünüyor."
        }
        return PartScore(
            "Yakıt Karışımı", score, emoji, status,
            advice = if (notes.isEmpty()) "Karışım normal." else notes.joinToString(". ") + "\n\n$tipDir",
            howToFix = if (score < 70) """
                Karışım sorunu teşhisi:
                1. Önce MAP sensörünü temizle.
                2. Emme manifoldu contası ve fren servosu vakum hortumunu kontrol et.
                3. Lambda sensörü 100.000+ km'deyse değiştir.
                4. LPG'liyse: enjektör kaçağı, yakıt fiyat regülatörü kontrol.
                5. Yakıt filtresini değiştir.
            """.trimIndent() else ""
        )
    }

    // ---------- Soğutma ----------
    private fun scoreCoolant(entries: List<DriveLogEntry>): PartScore {
        val temps = entries.mapNotNull { it.coolant.toIntOrNull() }
        if (temps.isEmpty()) return PartScore(
            "Soğutma Sistemi", 100, "❓", "Veri yok", "Yeterli sürüş verisi toplanmadı.", ""
        )
        var score = 100
        val notes = mutableListOf<String>()
        val maxT = temps.max()
        val avgT = temps.average()
        if (maxT >= 110) {
            score -= 50
            notes.add("Hararet $maxT°C'ye çıktı — ciddi sorun!")
        } else if (maxT >= 105) {
            score -= 25
            notes.add("Hararet $maxT°C'ye çıktı — sınırda")
        }
        if (avgT > 100) {
            score -= 20
            notes.add("Ortalama sıcaklık (${"%.0f".format(avgT)}°C) yüksek")
        } else if (avgT < 75 && temps.size > 30) {
            score -= 15
            notes.add("Motor yeterince ısınamıyor (${"%.0f".format(avgT)}°C) — termostat takılı kalmış olabilir")
        }
        score = score.coerceIn(0, 100)
        val (emoji, status) = scoreToStatus(score)
        return PartScore(
            "Soğutma Sistemi", score, emoji, status,
            advice = if (notes.isEmpty()) "Sıcaklık normal aralıkta." else notes.joinToString(". "),
            howToFix = if (score < 70) """
                Soğutma sorunu kontrolü:
                1. Antifriz seviyesini kontrol et (motor soğukken!).
                2. Termostat çalışıyor mu? — Motor 90°C'ye gelir gelmez fan açıyor mu?
                3. Radyatör kapağı sızdırıyor mu?
                4. Su pompası kayışı normal mi?
                5. 206 klasik: termostat gövdesi plastik, çatlak kontrol et.
            """.trimIndent() else ""
        )
    }

    // ---------- Ateşleme (RPM stabilitesi + avans) ----------
    private fun scoreIgnition(entries: List<DriveLogEntry>): PartScore {
        val idle = entries.filter { (it.rpm.toDoubleOrNull() ?: 0.0) in 600.0..1100.0 }
            .mapNotNull { it.rpm.toDoubleOrNull() }
        if (idle.size < 5) return PartScore(
            "Ateşleme Sistemi", 100, "❓", "Veri yok", "Rölanti verisi az.", ""
        )
        var score = 100
        val notes = mutableListOf<String>()
        val mean = idle.average()
        val variance = idle.map { (it - mean) * (it - mean) }.average()
        val stdDev = kotlin.math.sqrt(variance)
        if (stdDev > 100) {
            score -= 30
            notes.add("Rölantide RPM dalgalanıyor (±${stdDev.toInt()}) — buji/bobin sorunu olabilir")
        }
        // Avans değişiklikleri
        val avans = entries.mapNotNull { it.timingAdvance.toDoubleOrNull() }
        if (avans.isNotEmpty()) {
            val avansVariation = avans.max() - avans.min()
            if (avansVariation < 5 && avans.size > 30) {
                score -= 15
                notes.add("Ateşleme avansı çok az değişiyor — ECU adaptasyon sorunu")
            }
        }
        score = score.coerceIn(0, 100)
        val (emoji, status) = scoreToStatus(score)
        return PartScore(
            "Ateşleme Sistemi", score, emoji, status,
            advice = if (notes.isEmpty()) "Ateşleme stabil." else notes.joinToString(". "),
            howToFix = if (score < 70) """
                Ateşleme sistemi kontrolü (206 1.1):
                1. Bujileri kontrol et: tırnak aralığı 0.9mm olmalı, siyah karbon birikmemeli.
                2. Bobin kütüğü 206'da kroniktir — direnç ölç, ısındıkça kesiyorsa değiştir.
                3. Buji kabloları/yüksek voltaj kabloları çatlak/yanık var mı?
                4. Krank pozisyon sensörü için P0335 kodu var mı bak.
            """.trimIndent() else ""
        )
    }

    // ---------- Kelebek (TPS) ----------
    private fun scoreThrottle(entries: List<DriveLogEntry>): PartScore {
        val tps = entries.mapNotNull { it.tps.toDoubleOrNull() }
        if (tps.isEmpty()) return PartScore(
            "Gaz Kelebeği", 100, "❓", "Veri yok", "Yeterli sürüş verisi toplanmadı.", ""
        )
        var score = 100
        val notes = mutableListOf<String>()
        val idleTps = entries.filter { (it.rpm.toDoubleOrNull() ?: 0.0) in 600.0..1100.0 }
            .mapNotNull { it.tps.toDoubleOrNull() }
        if (idleTps.isNotEmpty()) {
            val avgIdleTps = idleTps.average()
            if (avgIdleTps > 18) {
                score -= 25
                notes.add("Rölanti TPS yüksek (%${"%.1f".format(avgIdleTps)}) — kelebek yağlanmış olabilir")
            }
        }
        score = score.coerceIn(0, 100)
        val (emoji, status) = scoreToStatus(score)
        return PartScore(
            "Gaz Kelebeği", score, emoji, status,
            advice = if (notes.isEmpty()) "TPS normal aralıkta." else notes.joinToString(". "),
            howToFix = if (score < 70) """
                Gaz kelebeği temizleme:
                1. Hava filtresi kutusunun çıkışına bağlı boğazı sök.
                2. Kelebek temizleme spreyi sık (özel ürün, yağ çözücü değil!).
                3. Yumuşak bezle dikkatli sil — kelebek motoru elektrikse aşırı zorlama.
                4. Tak, çalıştır, 10 dk rölantide bekle (öğrenme için).
                Maliyet: 30-50 TL sprey, kendin yapabilirsin.
            """.trimIndent() else ""
        )
    }

    private fun scoreToStatus(score: Int): Pair<String, String> {
        return when {
            score >= 85 -> "🟢" to "Sağlıklı"
            score >= 65 -> "🟡" to "İyi"
            score >= 45 -> "🟠" to "Dikkat"
            else -> "🔴" to "Sorunlu"
        }
    }
}