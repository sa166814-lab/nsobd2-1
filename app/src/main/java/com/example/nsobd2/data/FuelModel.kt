package com.example.nsobd2.data

/**
 * YAKIT HESABI — tek yerde. Hem anlık tüketim hem sürüş kaydı bunu kullanır.
 *
 * 206'da yakıt akış sensörü yok; hava miktarı MAP + devir + emme havası sıcaklığından
 * hesaplanır (speed-density). Eski formüle göre üç düzeltme:
 *  1) STFT + LTFT: Beynin enjektöre verdiği fazla/eksik yakıt (ör. +%25 → gerçekten %25 fazla yakıt).
 *  2) Motor freni (gaz kesik, vites takılı): beyin enjeksiyonu keser → 0 yakıt.
 *  3) Kalibrasyon katsayısı: depoyu tam doldurunca aldığın litre ile uygulamanın tahmini
 *     karşılaştırılır, aradaki oran sonraki hesaplara uygulanır.
 */
object FuelModel {

    /** Litre/saat. Hesaplanamıyorsa null. */
    fun litersPerHour(
        rpm: Double, mapKpa: Double, iatC: Double?, speedKmh: Double,
        stft: Double?, ltft: Double?,
        isLpg: Boolean, engineM3: Double, volEff: Double, calib: Double
    ): Double? {
        if (rpm <= 0 || mapKpa <= 0) return null

        // Motor freni: araç gidiyor, devir yüksek, manifold derin vakumda → enjeksiyon kesik
        if (speedKmh > 20 && rpm > 1300 && mapKpa < 30) return 0.0

        val afr = if (isLpg) 15.5 else 14.7
        val density = if (isLpg) 0.55 else 0.74
        val iatK = (iatC ?: 25.0) + 273.15
        val airKgS = (mapKpa * 1000.0 * engineM3 * volEff * (rpm / 120.0) * 0.02897) / (8.314 * iatK)

        val trim = (1.0 + ((stft ?: 0.0) + (ltft ?: 0.0)) / 100.0).coerceIn(0.7, 1.35)
        return (airKgS / afr / density) * 3600.0 * trim * calib.coerceIn(0.5, 1.8)
    }
}
