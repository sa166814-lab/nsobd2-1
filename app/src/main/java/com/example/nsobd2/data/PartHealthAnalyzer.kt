package com.example.nsobd2.data

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Sürüş loglarından parça sağlığı çıkarımı.
 *
 * 18-19.07.2026 gerçek araç verisiyle YENİDEN YAZILDI.
 * Eski sürüm her parçaya 100/100 veriyordu — halbuki aynı anda:
 *   • ön lambda hiç 0.55 V'u geçmiyordu (ölü/zayıf)
 *   • STFT 312 ölçümün hepsinde +0.8%'de donmuştu (açık çevrim)
 *   • arka lambda 0.866 V'ta sabitti (zengin çalışma)
 *   • beyinde P0115 kayıtlıydı
 * Eşikler bu veriye göre kalibre edildi.
 *
 * NOT: Bunlar çıkarımdır, kesin teşhis değildir.
 */
object PartHealthAnalyzer {

    data class PartScore(
        val name: String,
        val score: Int,
        val emoji: String,
        val status: String,
        val advice: String,
        val howToFix: String
    )

    fun analyze(entries: List<DriveLogEntry>): List<PartScore> {
        if (entries.size < 5) return emptyList()
        return listOf(
            scoreCoolant(entries),
            scoreClosedLoop(entries),
            scoreO2(entries),
            scoreFuelTrim(entries),
            scoreMap(entries),
            scoreIgnition(entries),
            scoreThrottle(entries),
            scoreIdle(entries)
        ).sortedBy { it.score }   // en kötü en üstte
    }

    // ================================================================ ARAÇLAR

    private fun d(s: String) = s.toDoubleOrNull()
    private fun sd(l: List<Double>): Double {
        if (l.size < 2) return 0.0
        val m = l.average()
        return sqrt(l.sumOf { (it - m) * (it - m) } / (l.size - 1))
    }
    private fun f(v: Double, n: Int = 1) = String.format("%.${n}f", v)

    private fun scoreToStatus(score: Int): Pair<String, String> = when {
        score >= 85 -> "🟢" to "Sağlıklı"
        score >= 65 -> "🟡" to "Takip et"
        score >= 40 -> "🟠" to "Sorunlu"
        else -> "🔴" to "Arızalı"
    }

    private fun bos(name: String) =
        PartScore(name, 100, "❓", "Veri yok", "Yeterli sürüş verisi toplanmadı.", "")

    private fun yap(name: String, score: Int, notlar: List<String>, cozum: String): PartScore {
        val s = score.coerceIn(0, 100)
        val (emoji, status) = scoreToStatus(s)
        return PartScore(
            name, s, emoji, status,
            advice = if (notlar.isEmpty()) "Değerler normal aralıkta." else notlar.joinToString("\n• ", prefix = "• "),
            howToFix = if (s < 85) cozum else ""
        )
    }

    // ======================================================= SOĞUTMA SİSTEMİ

    private fun scoreCoolant(e: List<DriveLogEntry>): PartScore {
        val ham = e.mapNotNull { d(it.coolant) }
        if (ham.isEmpty()) return bos("Soğutma Sistemi")

        val gecersiz = ham.count { !AppPrefs.isCoolantValid(it) }
        val gecerli = ham.filter { AppPrefs.isCoolantValid(it) }

        var score = 100
        val notlar = mutableListOf<String>()

        // Sensör hiç okunmuyor — en ağır durum
        if (gecersiz > ham.size / 2) {
            return yap("Soğutma Sistemi", 0,
                listOf("Sensör geçersiz değer veriyor (-40 °C) — sökük ya da kablosu kopuk",
                    "Beyin motor sıcaklığını bilmiyor: FAN DEVREYE GİRMEZ, hararet riski var",
                    "Ayrıca soğuk çalıştırma karışımı basar: zengin, tekleme, fazla yakıt"),
                """
                Su sıcaklık sensörü (müşür):
                1. Termostat gövdesindeki soketi çıkar, uçlarda oksit/yeşillenme var mı bak.
                2. Kontak temizleyici sık, kurut, tekrar tak.
                3. Kabloyu soketten geriye doğru elle yokla — sürtme, ezilme ara.
                4. Düzelmezse sensörü değiştir (ucuz parça).
                TEST: motor çalışırken soketi çıkar. Fan devreye giriyorsa
                fan motoru/rölesi/sigortası sağlam, sorun sensördedir.
                """.trimIndent())
        }
        if (gecersiz > 0) { score -= 20; notlar.add("$gecersiz ölçümde geçersiz değer — sensör ara ara kesiyor") }
        if (gecerli.isEmpty()) return bos("Soğutma Sistemi")

        val max = gecerli.max()
        val ort = gecerli.average()

        when {
            max > 105 -> { score -= 60; notlar.add("Hararet: ${f(max,0)} °C görüldü — kritik") }
            max > 100 -> { score -= 30; notlar.add("Sıcaklık ${f(max,0)} °C'ye çıktı — sınırda") }
        }
        // Motor hiç ısınmıyorsa termostat açık kalmıştır
        if (e.size > 60 && max < 80) {
            score -= 35
            notlar.add("Motor ${f(max,0)} °C'yi geçmemiş — termostat açık kalmış olabilir")
        }
        if (ort in 85.0..97.0 && notlar.isEmpty()) notlar.add("Çalışma sıcaklığı ideal (${f(ort,0)} °C)")

        return yap("Soğutma Sistemi", score, notlar, """
            Sıcaklık sorunu kontrol sırası:
            1. Soğutma suyu seviyesi (motor SOĞUKKEN bak)
            2. Fan çalışıyor mu — klimayı aç, dönmeli
            3. Termostat
            4. Radyatör tıkanıklığı / peteği
            5. Devirdaim pompası
            """.trimIndent())
    }

    // ============================================== KAPALI ÇEVRİM (yeni)

    /**
     * En kritik ama en görünmez arıza: beyin lambda sensörünü kullanmıyorsa
     * yakıt haritadan sabit basılır. Arıza lambası yanmaz ama tüketim artar
     * ve katalizör ölür. STFT'nin hiç oynamaması bunun kanıtıdır.
     */
    private fun scoreClosedLoop(e: List<DriveLogEntry>): PartScore {
        val stft = e.mapNotNull { d(it.stft) }
        if (stft.size < 15) return bos("Yakıt Kontrol Döngüsü")

        val dalga = sd(stft)
        val notlar = mutableListOf<String>()

        if (dalga < 0.01) {
            return yap("Yakıt Kontrol Döngüsü", 15,
                listOf("STFT ${stft.size} ölçümün hepsinde ${f(stft.average())}% — hiç oynamamış",
                    "Beyin AÇIK ÇEVRİMDE: lambda sensörünü kullanmıyor",
                    "Yakıt haritadan sabit basılıyor — tüketim artar, katalizör yıpranır",
                    "Arıza lambası yanmayabilir, o yüzden fark edilmez"),
                """
                Açık çevrimin sebebi genelde şu ikisinden biridir:
                1. Su sıcaklık sensörü arızası — beyin motorun ısındığını
                   doğrulayamadığı için kapalı çevrime geçmez. Önce buna bak.
                2. Ön lambda sensörü ölü ya da ısıtıcısı yanmış.
                Önce su sensörünü çöz, sonra tekrar ölç.
                """.trimIndent())
        }

        var score = 100
        if (stft.any { abs(it) >= 24.0 }) {
            score -= 45
            val yon = if (stft.average() > 0) "yakıt EKLİYOR (fakir)" else "yakıt KISIYOR (zengin)"
            notlar.add("Düzeltme tavana dayanmış (±25%) — beyin son sınırda $yon")
        }
        if (dalga < 0.5) { score -= 20; notlar.add("Düzeltme çok az oynuyor — kontrol zayıf") }
        if (notlar.isEmpty()) notlar.add("Kapalı çevrim düzgün çalışıyor (oynama ${f(dalga,2)}%)")

        return yap("Yakıt Kontrol Döngüsü", score, notlar, """
            Düzeltme tavana dayandıysa:
            FAKİR tarafta (+): vakum kaçağı (emme contası, fren servosu hortumu),
            tıkalı enjektör, düşük yakıt basıncı, yorulmuş lambda.
            ZENGİN tarafta (-): kaçıran enjektör, tıkalı hava filtresi,
            yüksek yakıt basıncı, arızalı su sıcaklık sensörü.
            """.trimIndent())
    }

    // ============================================================ LAMBDA

    private fun scoreO2(e: List<DriveLogEntry>): PartScore {
        val v = e.mapNotNull { d(it.lambda) }
        if (v.size < 15) return bos("Oksijen Sensörü")

        val ort = v.average()
        val dalga = sd(v)
        val enYuksek = v.max()
        val enDusuk = v.min()
        // Sağlıklı sensör 0.45 V eşiğini sürekli geçer
        val gecis = (1 until v.size).count { (v[it - 1] - 0.45) * (v[it] - 0.45) < 0 }
        val gecisOrani = gecis.toDouble() / v.size

        var score = 100
        val notlar = mutableListOf<String>()

        // 1) Referans geriliminde donma — ölü/kopuk sensör imzası
        if (ort in 0.40..0.50 && dalga < 0.03) {
            return yap("Oksijen Sensörü", 10,
                listOf("Değer ${f(ort,3)} V'ta çakılı, oynama sadece ${f(dalga,3)} V",
                    "Beyin lambda kablosuna 0.45 V referans basar; sensör çalışmıyorsa",
                    "okunan değer tam orada donar. Görülen tablo bu.",
                    "NOT: beyin açık çevrimdeyse sensör sağlam da olabilir — önce onu çöz"),
                """
                Ön lambda sensörü kontrol sırası:
                1. Soket (egzoz manifoldu üstü, 4 kablolu) — oksit, yanık, gevşeklik
                2. Isıtıcı sigortası
                3. Egzoz kaçağı (sensörün önünde kaçak varsa sahte hava girer)
                4. Sağlamsa sensörü değiştir
                """.trimIndent())
        }

        // 2) Hiç zenginleşemiyor — yorulmuş sensör
        if (enYuksek < 0.60) {
            score -= 45
            notlar.add("Hiç ${f(enYuksek,2)} V'un üstüne çıkmamış — sağlıklı sensör 0.85 V'a kadar çıkar")
        }
        // 3) Hiç fakirleşemiyor
        if (enDusuk > 0.25) {
            score -= 25
            notlar.add("Hiç ${f(enDusuk,2)} V'un altına inmemiş — sağlıklı sensör 0.10 V'a kadar iner")
        }
        // 4) Salınım sıklığı
        when {
            gecisOrani < 0.02 -> { score -= 40; notlar.add("Neredeyse hiç salınmıyor ($gecis geçiş / ${v.size} ölçüm) — tembelleşmiş") }
            gecisOrani < 0.08 -> { score -= 20; notlar.add("Salınım seyrek ($gecis geçiş) — yaşlanma başlamış") }
        }
        if (dalga < 0.08) { score -= 15; notlar.add("Oynama düşük (${f(dalga,3)} V)") }

        if (notlar.isEmpty()) notlar.add("Sağlıklı salınıyor (${f(enDusuk,2)}–${f(enYuksek,2)} V, $gecis geçiş)")

        return yap("Oksijen Sensörü", score, notlar, """
            Yorulmuş lambda sensörü:
            • 100.000 km üstünde normal bir aşınmadır
            • Yakıt tüketimini %10-15 artırır
            • Uzun vadede katalizörü öldürür (P0420)
            Değişimden önce soket ve ısıtıcı sigortasını kontrol et.
            """.trimIndent())
    }

    // ======================================================= YAKIT KARIŞIMI

    private fun scoreFuelTrim(e: List<DriveLogEntry>): PartScore {
        val stft = e.mapNotNull { d(it.stft) }
        val ltft = e.mapNotNull { d(it.ltft) }
        if (stft.isEmpty() || ltft.isEmpty()) return bos("Yakıt Karışımı")

        // Beyin açık çevrimdeyse düzeltme yapmıyor demektir; karışım yorumu anlamsız
        if (sd(stft) < 0.01) {
            return yap("Yakıt Karışımı", 50,
                listOf("Değerlendirilemedi — beyin açık çevrimde, düzeltme yapmıyor",
                    "Önce 'Yakıt Kontrol Döngüsü' sorununu çöz"),
                "Su sıcaklık sensörü ve ön lambda sensörünü kontrol et.")
        }

        val toplam = stft.average() + ltft.average()
        var score = 100
        val notlar = mutableListOf<String>()

        when {
            toplam > 20 -> { score -= 60; notlar.add("Çok fakir: toplam düzeltme +${f(toplam)}%") }
            toplam > 12 -> { score -= 35; notlar.add("Fakir: +${f(toplam)}%") }
            toplam > 7 -> { score -= 15; notlar.add("Hafif fakir: +${f(toplam)}%") }
            toplam < -20 -> { score -= 60; notlar.add("Çok zengin: ${f(toplam)}%") }
            toplam < -12 -> { score -= 35; notlar.add("Zengin: ${f(toplam)}%") }
            toplam < -7 -> { score -= 15; notlar.add("Hafif zengin: ${f(toplam)}%") }
            else -> notlar.add("Karışım normal (${f(toplam)}%)")
        }

        return yap("Yakıt Karışımı", score, notlar, """
            FAKİR karışım (206'da sıralı şüpheliler):
            1. Emme manifold contası kaçağı — 1 numaralı sebep
            2. Fren servosu hortumu çatlağı
            3. Kirli MAP sensörü
            4. Tıkalı enjektör / düşük yakıt basıncı
            TEST: rölantide hortumlara karbüratör temizleyici sık,
            devir değişirse kaçak orada.

            ZENGİN karışım:
            1. LPG enjektörü kaçırıyor
            2. Tıkalı hava filtresi
            3. Arızalı su sıcaklık sensörü
            """.trimIndent())
    }

    // =============================================================== MAP

    private fun scoreMap(e: List<DriveLogEntry>): PartScore {
        val maps = e.mapNotNull { d(it.map) }
        if (maps.size < 10) return bos("MAP Sensörü")

        var score = 100
        val notlar = mutableListOf<String>()

        val rolanti = e.filter { (d(it.rpm) ?: 0.0) in 550.0..1150.0 && (d(it.speed) ?: 0.0) == 0.0 }
            .mapNotNull { d(it.map) }
        if (rolanti.size >= 5) {
            val ort = rolanti.average()
            when {
                ort > 55 -> { score -= 35; notlar.add("Rölantide MAP çok yüksek (${f(ort,0)} kPa, normal 30-45) — hava kaçağı olabilir") }
                ort > 48 -> { score -= 15; notlar.add("Rölantide MAP hafif yüksek (${f(ort,0)} kPa)") }
                ort < 20 -> { score -= 30; notlar.add("Rölantide MAP çok düşük (${f(ort,0)} kPa) — sensör veya hortum sorunu") }
                else -> notlar.add("Rölanti MAP normal (${f(ort,0)} kPa)")
            }
        }
        if ((maps.max() - maps.min()) < 10 && maps.size > 30) {
            score -= 40
            notlar.add("MAP neredeyse hiç değişmiyor — sensör sıkışmış olabilir")
        }

        return yap("MAP Sensörü", score, notlar, """
            MAP sensörü temizliği:
            1. Emme manifoldunda, kelebek boğazına yakın küçük sensör.
            2. Soketi çıkar, tornavida ile sök.
            3. Karbüratör/kontak temizleyici sık, KENDİLİĞİNDEN kurumasını bekle.
            4. ASLA fırça veya tel değdirme — diyafram çok hassastır.
            5. Tak, 5 dakika çalıştır.
            Ayrıca vakum hortumunu çatlak ve gevşeklik için kontrol et.
            """.trimIndent())
    }

    // ========================================================== ATEŞLEME

    private fun scoreIgnition(e: List<DriveLogEntry>): PartScore {
        val avans = e.mapNotNull { d(it.timingAdvance) }
        if (avans.size < 10) return bos("Ateşleme Sistemi")

        var score = 100
        val notlar = mutableListOf<String>()

        val yukluAvans = e.filter { (d(it.rpm) ?: 0.0) > 2000 }.mapNotNull { d(it.timingAdvance) }
        if (yukluAvans.size >= 5) {
            val ort = yukluAvans.average()
            if (ort < 5) {
                score -= 40
                notlar.add("Yüksek devirde avans çok düşük (${f(ort)}°) — beyin vuruntudan avans kısıyor olabilir")
            }
        }
        // Aşırı sıçrama = düzensiz yanma
        val sicrama = (1 until avans.size).count { abs(avans[it] - avans[it - 1]) > 15 }
        if (sicrama > avans.size / 5) {
            score -= 25
            notlar.add("Avans sık sık sıçrıyor — düzensiz yanma işareti")
        }
        if (notlar.isEmpty()) notlar.add("Ateşleme kararlı (ortalama ${f(avans.average())}°)")

        return yap("Ateşleme Sistemi", score, notlar, """
            206'da ateşleme sorunu sıralaması:
            1. Bobin kütüğü — kronik arıza
            2. Bujiler: tırnak aralığı, kurumlanma, aşınma
            3. Buji kabloları ve kapakları
            4. Vuruntu sensörü (avans sürekli düşükse)
            Buji rengi ipucu: siyah kuru = zengin, beyaz = fakir,
            yağlı = segman/supap lastiği.
            """.trimIndent())
    }

    // ==================================================== GAZ KELEBEĞİ

    private fun scoreThrottle(e: List<DriveLogEntry>): PartScore {
        val tps = e.mapNotNull { d(it.tps) }
        if (tps.size < 10) return bos("Gaz Kelebeği (TPS)")

        var score = 100
        val notlar = mutableListOf<String>()

        if ((tps.max() - tps.min()) < 3) {
            score -= 45
            notlar.add("TPS neredeyse hiç değişmiyor — potansiyometre takılmış olabilir")
        }
        // Kapalı gazda okunması gereken taban değer
        val kapali = e.filter { (d(it.rpm) ?: 0.0) in 550.0..1150.0 && (d(it.speed) ?: 0.0) == 0.0 }
            .mapNotNull { d(it.tps) }
        if (kapali.size >= 5) {
            val taban = kapali.min()
            if (taban > 20) { score -= 25; notlar.add("Kapalı gazda TPS %${f(taban)} — yüksek, ayar kaymış olabilir") }
        }
        // Sıçramalar = aşınmış iz
        val zipla = (1 until tps.size).count { abs(tps[it] - tps[it - 1]) > 25 }
        if (zipla > tps.size / 8) { score -= 20; notlar.add("TPS sinyali sıçrıyor — potansiyometre izi aşınmış olabilir") }

        if (notlar.isEmpty()) notlar.add("TPS normal çalışıyor")

        return yap("Gaz Kelebeği (TPS)", score, notlar, """
            Kelebek boğazı temizliği (206'da çok işe yarar):
            1. Hava filtresi borusunu sök.
            2. Kelebek kapağını elle aç, iç yüzeydeki siyah kurumu
               karbüratör temizleyici + yumuşak bezle sil.
            3. Kelebeğin kenarındaki halka kurumu mutlaka temizle.
            4. Rölanti motorunun ağzını da temizle.
            5. Taktıktan sonra kontağı 10 sn açık bırak, sonra çalıştır.
            """.trimIndent())
    }

    // =========================================================== RÖLANTİ

    private fun scoreIdle(e: List<DriveLogEntry>): PartScore {
        val rolanti = e.filter { (d(it.speed) ?: 0.0) == 0.0 && (d(it.rpm) ?: 0.0) in 300.0..1600.0 }
            .mapNotNull { d(it.rpm) }
        if (rolanti.size < 8) return bos("Rölanti Kararlılığı")

        var score = 100
        val notlar = mutableListOf<String>()
        val ort = rolanti.average()
        val dalga = sd(rolanti)

        when {
            dalga > 90 -> { score -= 45; notlar.add("Rölanti çok dalgalı (±${f(dalga,0)} d/dk)") }
            dalga > 55 -> { score -= 25; notlar.add("Rölanti dalgalı (±${f(dalga,0)} d/dk)") }
        }
        if (rolanti.min() < 550) { score -= 30; notlar.add("Devir ${f(rolanti.min(),0)}'e kadar düşmüş — stop etme riski") }
        when {
            ort > 1150 -> { score -= 25; notlar.add("Rölanti yüksek (${f(ort,0)} d/dk, normal 750-900)") }
            ort < 650 -> { score -= 20; notlar.add("Rölanti düşük (${f(ort,0)} d/dk)") }
        }
        if (notlar.isEmpty()) notlar.add("Rölanti kararlı (${f(ort,0)} d/dk, ±${f(dalga,0)})")

        return yap("Rölanti Kararlılığı", score, notlar, """
            Rölanti sorunu kontrol sırası:
            1. Kelebek boğazı ve rölanti motoru temizliği
            2. Vakum kaçağı — hortumlar, emme manifold contası
            3. Su sıcaklık sensörü (yanlış sıcaklık rölantiyi bozar)
            4. Bujiler
            5. Yakıt filtresi
            """.trimIndent())
    }
}