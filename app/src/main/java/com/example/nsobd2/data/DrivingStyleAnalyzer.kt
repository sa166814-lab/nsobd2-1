package com.example.nsobd2.data

object DrivingStyleAnalyzer {

    data class StyleInsight(
        val category: String,
        val emoji: String,
        val message: String,
        val impact: String
    )

    fun analyze(entries: List<DriveLogEntry>): List<StyleInsight> {
        if (entries.size < 10) return emptyList()
        val insights = mutableListOf<StyleInsight>()

        var hardAccel = 0
        var prevTps: Double? = null
        for (e in entries) {
            val tps = e.tps.toDoubleOrNull()
            if (prevTps != null && tps != null && (tps - prevTps) > 35) hardAccel++
            if (tps != null) prevTps = tps
        }
        if (hardAccel > entries.size * 0.05) {
            insights.add(StyleInsight(
                category = "Ani Gaz", emoji = "⚡",
                message = "Ani gaza basıyorsun ($hardAccel kez)",
                impact = "Bu sürüş sırasında yakıt tüketimini ~%15-20 artırıyor. Yumuşak gazla dolu yaklaşımı dene."
            ))
        }

        val highRpm = entries.count { (it.rpm.toDoubleOrNull() ?: 0.0) > 3500 }
        if (highRpm > entries.size * 0.15) {
            insights.add(StyleInsight(
                category = "Yüksek RPM", emoji = "🔥",
                message = "Sıkça 3500+ RPM'de gezdin (%${(highRpm * 100 / entries.size)} oranında)",
                impact = "Vites yükseltmek tüketimi ~%10-15 düşürür ve motoru korur."
            ))
        }

        val highSpeed = entries.count { (it.speed.toDoubleOrNull() ?: 0.0) > 110 }
        if (highSpeed > entries.size * 0.20) {
            insights.add(StyleInsight(
                category = "Yüksek Hız", emoji = "🚀",
                message = "Sürüş ${(highSpeed * 100 / entries.size)}'i 110+ km/h",
                impact = "100 km/h'ten 120 km/h'e çıkmak tüketimi yaklaşık %25 artırır (hava direnci)."
            ))
        }

        val rolantiCount = entries.count {
            (it.speed.toDoubleOrNull() ?: 0.0) < 1 && (it.rpm.toDoubleOrNull() ?: 0.0) > 600
        }
        if (rolantiCount > entries.size * 0.20) {
            insights.add(StyleInsight(
                category = "Rölanti", emoji = "⏸",
                message = "Sürüşün ${(rolantiCount * 100 / entries.size)}'i durur halde motor çalıştı",
                impact = "1 dakika rölanti ≈ 0.02 L yakıt. 1 dakikadan uzun durduğunda motoru kapat."
            ))
        }

        val coolantStarts = entries.take(20).mapNotNull { it.coolant.toIntOrNull() }
        if (coolantStarts.isNotEmpty() && coolantStarts.first() < 60) {
            val highRpmCold = entries.take(60).count {
                (it.coolant.toIntOrNull() ?: 100) < 70 && (it.rpm.toDoubleOrNull() ?: 0.0) > 2500
            }
            if (highRpmCold > 5) {
                insights.add(StyleInsight(
                    category = "Soğuk Çalıştırma", emoji = "❄️",
                    message = "Motor soğukken ($highRpmCold kez) yüksek devire çıktın",
                    impact = "Motor 70°C'ye gelene kadar 2500 RPM altında kalmak motor ömrünü uzatır."
                ))
            }
        }

        if (insights.isEmpty()) {
            insights.add(StyleInsight(
                category = "Genel", emoji = "🏆",
                message = "Sürüş tarzın oldukça temiz!",
                impact = "Mevcut alışkanlıklarını koru, motor ömrü ve cüzdan teşekkür ediyor."
            ))
        }

        return insights
    }
}