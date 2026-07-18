package com.example.nsobd2.data

/**
 * Arıza kodu çözücü.
 * Eski çözücünün sorunları:
 *  - Çok çerçeveli (multi-frame) cevapları tek blok sanıyordu
 *  - Sayaç (count) byte'ı olan cevaplarda 1 byte kayıyordu -> yanlış kod
 *  - Ham cevabı hiç göstermiyordu, bu yüzden yanlış çözüm fark edilmiyordu
 */
object DtcParser {

    data class Decoded(
        val codes: List<String>,        // ["P0300", "P0171"]
        val rawResponse: String,        // ELM327'den gelen ham metin
        val cleanHex: String,           // sadece hex karakterler
        val method: String,             // hangi yorumla çözüldü
        val alternative: List<String>,  // diğer yorumun sonucu (doğrulama için)
        val note: String                // kullanıcıya mesaj
    )

    private val BAD_TOKENS = listOf(
        "NODATA", "NO DATA", "ERROR", "UNABLE", "STOPPED",
        "BUFFERFULL", "BUSINIT", "CANERROR", "SEARCHING"
    )

    /** Olumsuz cevap kodu (Negative Response Code) açıklaması. */
    fun nrcText(nrc: String): String = when (nrc.uppercase()) {
        "10" -> "genel red"
        "11" -> "bu servis desteklenmiyor"
        "12" -> "bu alt fonksiyon desteklenmiyor"
        "21" -> "beyin meşgul, tekrar dene"
        "22" -> "şartlar uygun değil"
        "31" -> "istenen değer aralık dışı"
        "78" -> "cevap hazırlanıyor, bekle"
        else -> "kod $nrc"
    }

    fun decode(raw: String, modePrefix: String): Decoded {
        val upper = raw.uppercase()

        for (t in BAD_TOKENS) {
            if (upper.replace(" ", "").contains(t.replace(" ", ""))) {
                return Decoded(
                    emptyList(), raw, "", "-", emptyList(),
                    "Beyin bu isteğe cevap vermedi ($t). Kontak açık mı?"
                )
            }
        }
        if (upper.contains("?")) {
            return Decoded(emptyList(), raw, "", "-", emptyList(),
                "ELM327 komutu anlamadı.")
        }

        val hex = upper.filter { it in "0123456789ABCDEF" }
        if (hex.isEmpty()) {
            return Decoded(emptyList(), raw, "", "-", emptyList(), "Boş cevap.")
        }

        // 7F = olumsuz cevap ("bu servisi desteklemiyorum")
        if (hex.startsWith("7F")) {
            val nrc = if (hex.length >= 6) hex.substring(4, 6) else "??"
            return Decoded(
                emptyList(), raw, hex, "negatif cevap", emptyList(),
                "Beyin bu servisi desteklemiyor (7F / ${nrcText(nrc)})."
            )
        }

        // Komut yankısı varsa at (örn. "03" ile başlıyorsa)
        val start = hex.indexOf(modePrefix)
        if (start < 0) {
            return Decoded(emptyList(), raw, hex, "-", emptyList(),
                "Cevapta $modePrefix başlığı yok — beyin farklı formatta konuşuyor.")
        }

        // Çerçeveleri topla. ISO/KWP'de her çerçeve: [prefix][en fazla 6 byte veri]
        val payload = collectFrames(hex, start, modePrefix)

        // İki olası yorum: sayaç byte'ı var / yok
        val withoutCount = pairsToCodes(payload)
        val withCount = if (payload.size >= 2) pairsToCodes(payload.drop(1)) else emptyList()

        // ÖNEMLİ: ISO 14230 (KWP) ve ISO 9141 protokollerinde sayaç byte'ı YOKTUR.
        // Kodlar 43'ten hemen sonra 2'şer byte hâlinde gelir.
        // (Sayaç byte'ı sadece CAN protokolünde bulunur; bu araç CAN kullanmıyor.)
        // Araçta doğrulandı: 43 01 15 00 00 00 00  ->  P0115  (doğrudan okuma)
        val primary = withoutCount
        val secondary = withCount
        val method = "doğrudan okuma (KWP)"

        val note = when {
            primary.isEmpty() && payload.all { it == "00" } -> "Beyinde kayıtlı arıza kodu yok. 👍"
            primary.isEmpty() -> "Kod çözülemedi. Aşağıdaki HAM CEVAP'ı paylaş."
            else -> ""
        }

        return Decoded(primary, raw, hex, method, secondary, note)
    }

    /** Çok çerçeveli cevabı tek veri dizisine indirger. */
    private fun collectFrames(hex: String, startIdx: Int, prefix: String): List<String> {
        val out = mutableListOf<String>()
        var i = startIdx
        var guard = 0
        while (i + prefix.length <= hex.length && guard < 40) {
            guard++
            if (hex.regionMatches(i, prefix, 0, prefix.length)) {
                i += prefix.length
                // Bir çerçevede en fazla 6 veri byte'ı (3 arıza kodu) taşınır
                var taken = 0
                while (taken < 6 && i + 2 <= hex.length) {
                    // Sıradaki çift yeni bir çerçeve başlığıysa dur
                    if (taken > 0 && taken % 6 == 0) break
                    out.add(hex.substring(i, i + 2))
                    i += 2
                    taken++
                    if (taken == 6) break
                }
            } else {
                // Başlık yoksa kalan her şeyi veri say
                while (i + 2 <= hex.length) {
                    out.add(hex.substring(i, i + 2))
                    i += 2
                }
                break
            }
        }
        return out
    }

    /** Byte çiftlerini P/C/B/U koduna çevirir. */
    private fun pairsToCodes(bytes: List<String>): List<String> {
        val codes = mutableListOf<String>()
        var i = 0
        while (i + 1 < bytes.size) {
            val b1 = bytes[i].toIntOrNull(16)
            val b2 = bytes[i + 1]
            i += 2
            if (b1 == null) continue
            if (b1 == 0 && b2 == "00") continue          // boş slot

            val letter = when ((b1 shr 6) and 0x03) {
                0 -> "P"; 1 -> "C"; 2 -> "B"; else -> "U"
            }
            val d1 = (b1 shr 4) and 0x03
            val d2 = String.format("%X", b1 and 0x0F)
            val code = "$letter$d1$d2$b2"
            if (code.length == 5 && !codes.contains(code)) codes.add(code)
        }
        return codes
    }

    // ------------------------------------------------------- ARIZA SÖZLÜĞÜ

    fun meaning(code: String): String = when (code) {
        // --- Karışım ---
        "P0170" -> "Yakıt karışımı sapması (Bank 1).\nLPG'de sık görülür — ayar bozulmuş olabilir."
        "P0171" -> "FAKİR karışım.\n206'da 1 numaralı şüpheli: emme manifold contası ya da fren servosu hortumundan vakum kaçağı. Sonra kirli MAP sensörü."
        "P0172" -> "ZENGİN karışım.\nLPG enjektörü kaçırıyor olabilir. Benzinde: kirli MAP, arızalı su müşürü, tıkalı hava filtresi."
        // --- MAP / hava ---
        "P0105", "P0106", "P0107", "P0108", "P0109" ->
            "MAP (emme manifold basıncı) sensörü devresi.\nSensör kirlenmiş ya da vakum hortumu çatlamış olabilir. 206'da çok yaygın."
        "P0110", "P0111", "P0112", "P0113" ->
            "IAT (emilen hava sıcaklığı) sensörü.\nKelebek boğazındaki sensör yağlanmış olabilir; nazikçe temizle."
        "P0115", "P0116", "P0117", "P0118", "P0119" ->
            "Su sıcaklık sensörü (müşür).\nTermostat gövdesindeki yeşil soketli müşür. Fan çalışmaması ve rölanti dalgalanması yapar."
        "P0120", "P0121", "P0122", "P0123", "P0124" ->
            "TPS — gaz kelebeği pozisyon sensörü.\nAşınmışsa rölanti dalgalanır, gaz yemez."
        // --- Lambda ---
        "P0130", "P0131", "P0132", "P0133", "P0134" ->
            "Ön (katalizör öncesi) lambda sensörü arızası.\n100.000 km üstü sensör yorulur. Soket oksitlenmesini de kontrol et."
        "P0135" -> "Ön lambda sensörü ısıtıcı devresi.\nSensörün ısıtıcı direnci kopmuş — sensör değişimi gerekir."
        "P0136", "P0137", "P0138", "P0139", "P0140" ->
            "Arka (katalizör sonrası) lambda sensörü.\nKatalizör verim hesabını bozar, P0420 ile birlikte gelir."
        "P0141" -> "Arka lambda ısıtıcı devresi."
        // --- Enjektör / yakıt ---
        "P0201" -> "1. silindir enjektör devresi."
        "P0202" -> "2. silindir enjektör devresi."
        "P0203" -> "3. silindir enjektör devresi."
        "P0204" -> "4. silindir enjektör devresi."
        "P0230", "P0231", "P0232" -> "Yakıt pompası rölesi / devresi."
        "P0261", "P0262", "P0264", "P0265", "P0267", "P0268", "P0270", "P0271" ->
            "Enjektör devresi kısa devre / açık devre."
        // --- Ateşleme ---
        "P0300" -> "ÇOKLU silindir teklemesi.\n206'da kronik: bobin kütüğü. Sonra bujiler (tırnak aralığı) ve buji kabloları."
        "P0301" -> "1. silindir teklemesi (volan tarafı).\nBuji, bobin ya da enjektör."
        "P0302" -> "2. silindir teklemesi."
        "P0303" -> "3. silindir teklemesi."
        "P0304" -> "4. silindir teklemesi (triger tarafı)."
        "P0320", "P0321", "P0322" -> "Ateşleme/devir sensörü devresi."
        "P0325", "P0326", "P0327", "P0328" ->
            "Vuruntu (knock) sensörü.\nBeyin avansı kısar, araç ağırlaşır ve yakıt artar."
        "P0335", "P0336" ->
            "Krank mili pozisyon sensörü.\n206'da klasik: araç sıcakken stop eder, sonra çalışmaz. Kablo demeti sürtmesini de kontrol et."
        "P0340", "P0341", "P0343" -> "Eksantrik (kam) mili sensörü.\nZor çalışma ve güç kaybı yapar."
        // --- Emisyon ---
        "P0400", "P0401", "P0402", "P0403", "P0404" ->
            "EGR devresi.\nValf kurumdan tıkanmış olabilir."
        "P0420" -> "Katalizör verimi düşük.\nLPG'li araçlarda çok sık. Önce arka lambda sensörünü ve egzoz kaçağını ele — katalizör en son."
        "P0440", "P0441", "P0442", "P0443", "P0446", "P0455" ->
            "Yakıt buharı (kanister) sistemi.\nDepo kapağı contası ya da kanister valfi."
        // --- Rölanti / hız ---
        "P0500", "P0501" ->
            "VSS — araç hız sensörü.\nŞanzıman üstündeki müşür. Hız kadranı ara ara sıfırlanıyorsa bu."
        "P0505", "P0506", "P0507" ->
            "Rölanti kontrol motoru (stepper).\nKelebek boğazı kurumlanmış olabilir; sökülüp temizlenir."
        "P0560", "P0562", "P0563" ->
            "Besleme gerilimi sorunu.\nAkü, alternatör ya da şase kablosu."
        // --- Beyin / iletişim ---
        "P0600", "P0601", "P0602", "P0603", "P0604", "P0605" ->
            "Beyin (ECU) iç hatası.\nÖnce akü kutup başlarını ve şase noktalarını temizle."
        "P1351", "P1608", "P1610", "P1611", "P1612" ->
            "PSA'ya özel beyin/immobilizer kodu.\nAnahtar tanıma veya kodlama sorunu olabilir."
        else -> when {
            code.startsWith("P00") -> "Yakıt ve hava ölçüm sistemi."
            code.startsWith("P01") -> "Yakıt/hava karışımı ve sensörleri (lambda, MAP, IAT, hararet)."
            code.startsWith("P02") -> "Yakıt sistemi (enjektör, pompa)."
            code.startsWith("P03") -> "Ateşleme sistemi (bobin, buji, krank/kam sensörü)."
            code.startsWith("P04") -> "Emisyon kontrol (katalizör, EGR, kanister)."
            code.startsWith("P05") -> "Hız / rölanti kontrol sistemi."
            code.startsWith("P06") -> "Beyin (ECU) ve çıkış devreleri."
            code.startsWith("P07") -> "Şanzıman."
            code.startsWith("P1")  -> "Üretici (PSA) özel kodu — Lexia/Diagbox gerekebilir."
            code.startsWith("C")   -> "Şasi sistemi (ABS, fren, direksiyon)."
            code.startsWith("B")   -> "Gövde sistemi (airbag, klima, kapı)."
            code.startsWith("U")   -> "İletişim/CAN hattı sorunu."
            else -> "Bilinmeyen kod."
        }
    }

    /** Ekranda gösterilecek güzel metin. */
    fun format(d: Decoded, title: String): String {
        val sb = StringBuilder()
        if (d.codes.isEmpty()) {
            sb.append(if (d.note.isNotEmpty()) d.note else "Kod yok.")
        } else {
            d.codes.forEachIndexed { idx, c ->
                if (idx > 0) sb.append("\n\n─────────────\n\n")
                sb.append("🚨 $c\n").append(meaning(c))
            }
        }
        return sb.toString()
    }
}