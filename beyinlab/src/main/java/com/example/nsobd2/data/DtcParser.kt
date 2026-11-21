package com.example.nsobd2.data

/**
 * Arıza kodu çözücü.
 *
 * Araçta doğrulanan davranışlar:
 *  - ISO 14230 (KWP) protokolünde SAYAÇ BYTE'I YOKTUR. Kodlar 43'ten hemen
 *    sonra 2'şer byte hâlinde gelir. (43 01 15 00 00 00 00 -> P0115)
 *  - Beyin desteklemediği servise 7F ile cevap verir (7F 07 11 gibi).
 *
 * Kod anlamları artık DtcDatabase'den geliyor.
 */
object DtcParser {

    data class Decoded(
        val codes: List<String>,
        val rawResponse: String,
        val cleanHex: String,
        val method: String,
        val alternative: List<String>,
        val note: String
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
                return Decoded(emptyList(), raw, "", "-", emptyList(),
                    "Beyin bu isteğe cevap vermedi ($t). Kontak açık mı?")
            }
        }
        if (upper.contains("?")) {
            return Decoded(emptyList(), raw, "", "-", emptyList(), "ELM327 komutu anlamadı.")
        }

        val hex = upper.filter { it in "0123456789ABCDEF" }
        if (hex.isEmpty()) {
            return Decoded(emptyList(), raw, "", "-", emptyList(), "Boş cevap.")
        }

        // 7F = olumsuz cevap ("bu servisi desteklemiyorum")
        if (hex.startsWith("7F")) {
            val nrc = if (hex.length >= 6) hex.substring(4, 6) else "??"
            return Decoded(emptyList(), raw, hex, "negatif cevap", emptyList(),
                "Beyin bu servisi desteklemiyor (7F / ${nrcText(nrc)}).")
        }

        val start = hex.indexOf(modePrefix)
        if (start < 0) {
            return Decoded(emptyList(), raw, hex, "-", emptyList(),
                "Cevapta $modePrefix başlığı yok — beyin farklı formatta konuşuyor.")
        }

        val payload = collectFrames(hex, start, modePrefix)

        val withoutCount = pairsToCodes(payload)
        val withCount = if (payload.size >= 2) pairsToCodes(payload.drop(1)) else emptyList()

        // KWP'de sayaç byte'ı yok — doğrudan okuma sabit.
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
                var taken = 0
                while (taken < 6 && i + 2 <= hex.length) {
                    out.add(hex.substring(i, i + 2))
                    i += 2
                    taken++
                }
            } else {
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
            if (b1 == 0 && b2 == "00") continue

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

    // ------------------------------------------------------------ ANLAMLAR
    // Artık DtcDatabase'den geliyor.

    /** Kısa açıklama (eski kodlarla uyumluluk için aynı isim). */
    fun meaning(code: String): String {
        val b = DtcDatabase.bilgi(code)
        return "${b.baslik}\n${b.aciklama}\n\nKontrol sırası:\n${b.supheliler}"
    }

    /** Tek satırlık başlık. */
    fun shortMeaning(code: String): String = DtcDatabase.kisa(code)

    /** Önem derecesi ikonu. */
    fun severityIcon(code: String): String = DtcDatabase.bilgi(code).onem.ikon

    /** Ekranda gösterilecek güzel metin — önem sırasına göre dizilmiş. */
    fun format(d: Decoded, title: String): String {
        val sb = StringBuilder()
        if (d.codes.isEmpty()) {
            sb.append(if (d.note.isNotEmpty()) d.note else "Kod yok.")
        } else {
            val sirali = DtcDatabase.onemeGoreSirala(d.codes)
            if (sirali.size > 1) {
                sb.append("${sirali.size} kod bulundu (en acil en üstte)\n")
                sb.append("═══════════════\n\n")
            }
            sirali.forEachIndexed { idx, c ->
                if (idx > 0) sb.append("\n\n─────────────\n\n")
                sb.append(DtcDatabase.tam(c))
            }
        }
        return sb.toString()
    }
}