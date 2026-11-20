package com.example.nsobd2.data

/**
 * Anlık sensör sağlık kontrolü — DTC (arıza kodu) beklemeden,
 * canlı değerin kendisi fiziksel olarak imkansız/mantıksızsa arıza der.
 * Klasik teşhis cihazlarında olmayan özellik.
 */
object LiveSensorCheck {

    data class Warning(
        val sensor: String,
        val message: String,
        val severity: Severity
    )
    enum class Severity { CRITICAL, WARNING }

    /**
     * @param data Dashboard'daki anlık _uiState map'i (RPM, SPEED, TEMP, IAT, TPS, O2, MAP...)
     * @param o2History son O2 okumalarının listesi (salınım kontrolü için)
     */
    fun check(
        data: Map<String, ParseResult>,
        o2History: List<Double> = emptyList()
    ): List<Warning> {
        val warnings = mutableListOf<Warning>()

        fun num(key: String): Double? =
            (data[key] as? ParseResult.Success)?.numericValue
                ?: (data[key] as? ParseResult.Success)?.value?.toDoubleOrNull()

        val rpm = num("RPM")
        val temp = num("TEMP")
        val iat = num("IAT")
        val tps = num("TPS")
        val map = num("MAP")
        val engineRunning = (rpm ?: 0.0) > 400  // motor çalışıyor mu

        // --- SU SICAKLIK SENSÖRÜ ---
        if (temp != null) {
            if (temp <= -30) {
                warnings.add(Warning(
                    "Su Sıcaklık Sensörü",
                    "Sensör -40°C gibi imkansız değer veriyor → ECU okuyamıyor. Müşür soketi/kablosu arızalı olabilir.",
                    Severity.CRITICAL
                ))
            } else if (engineRunning && temp > 130) {
                warnings.add(Warning(
                    "Su Sıcaklık Sensörü",
                    "Sensör aşırı yüksek (${temp.toInt()}°C) okuyor. Gerçek hararet ya da sensör arızası.",
                    Severity.CRITICAL
                ))
            } else if (engineRunning && temp >= 112) {
                // 206 TU motorlarda fan ~97°C'de devreye girer; 110 üstü tehlike.
                warnings.add(Warning(
                    "🔥 HARARET",
                    "${temp.toInt()}°C! Güvenli bir yerde dur, motoru hemen kapatma; rölantide fanın çalışıp çalışmadığına bak. Fan dönmüyorsa motoru kapat.",
                    Severity.CRITICAL
                ))
            } else if (engineRunning && temp >= 105) {
                warnings.add(Warning(
                    "Hararet yükseliyor",
                    "${temp.toInt()}°C — normalde fan 97°C civarı devreye girer. Fan çalışıyor mu kontrol et.",
                    Severity.WARNING
                ))
            }
        }

        // --- IAT (Hava Emme) ---
        // IAT, Su ile birebir aynı sabit değerse → ortak hat/kısa devre şüphesi
        if (iat != null && temp != null && iat == temp && (iat <= -30 || iat > 120)) {
            warnings.add(Warning(
                "IAT Sensörü",
                "Hava emme ve su sıcaklığı aynı geçersiz değerde — sensör hattında kısa devre olabilir.",
                Severity.WARNING
            ))
        }

        // --- TPS (Gaz Kelebeği) ---
        // Motor çalışıyorsa TPS hiç değilse minimum bir değer vermeli
        if (engineRunning && tps != null && tps <= 0.0) {
            warnings.add(Warning(
                "Gaz Kelebeği (TPS)",
                "Motor çalışıyor ama TPS %0 — potansiyometre sinyali gelmiyor olabilir.",
                Severity.WARNING
            ))
        }

        // --- MAP ---
        if (engineRunning && map != null && map <= 0.0) {
            warnings.add(Warning(
                "MAP Sensörü",
                "Motor çalışıyor ama MAP 0 kPa — sensör veya vakum hattı sorunu.",
                Severity.WARNING
            ))
        }

        // --- O2 (sadece yeterli canlı veri varsa) ---
        // Canlı ekranda toplanmış son ~15 O2 örneği hep aynıysa = ölü/donmuş
        if (engineRunning && o2History.size >= 15) {
            val variation = o2History.max() - o2History.min()
            if (variation < 0.05) {
                warnings.add(Warning(
                    "Oksijen Sensörü",
                    "O2 voltajı hiç salınmıyor (sabit ${"%.2f".format(o2History.last())}V) — sensör donmuş/ölü olabilir.",
                    Severity.WARNING
                ))
            }
        }

        return warnings
    }
}
