package com.example.nsobd2.data

import kotlinx.coroutines.delay

/**
 * PIN TESTI — tek butonla tam baglanti teshisi.
 *
 * ELM327'nin 7. pini hangi arac pinine baglanmissa (dogrudan ya da kopruyle)
 * o hatta su testler sirayla yapilir:
 *
 *   1) HIZLI INIT taramasi   : 01..FF her adrese "3E" (canli mi?)
 *   2) YAVAS INIT taramasi   : 01..FF, 5 baud adres + baud hizi taramasi
 *                              (10400 / 9600 / 4800)
 *   3) ANAHTAR BYTE cozumu   : cevap veren beynin hangi protokolu ve
 *                              hangi mesaj formatini bekledigi
 *   4) Bulunan her beyne birkac SALT OKUNUR soru (3E, 1A80, 0100)
 *
 * GUVENLIK: hicbir yazma / silme / aktuator komutu gonderilmez.
 */
object PinTesti {

    data class Bulunan(
        val adres: String,
        val init: String,          // "hizli" / "yavas"
        val baud: String,          // "10400" / "9600" / "4800"
        val anahtar: String,       // ornek "8F E9"
        val anahtarYorum: String,
        val notlar: MutableList<String> = mutableListOf()
    )

    private fun hex(s: String) = s.uppercase().filter { it in "0123456789ABCDEF" }

    private fun initHatasi(cevap: String): Boolean {
        val u = cevap.uppercase()
        return u.isBlank() || u.contains("ERROR") || u.contains("UNABLE") ||
                u.contains("?") || u.contains("STOPPED")
    }

    // ============================================== ANAHTAR BYTE COZUMU

    /**
     * ISO 9141 / ISO 14230 anahtar byte'larini cozer.
     * kb1 = 1. anahtar byte, kb2 = 2. anahtar byte (hex, 2 karakter).
     */
    fun anahtarCoz(kb1: String, kb2: String): String {
        val a = kb1.toIntOrNull(16) ?: return "anahtar okunamadi"
        val b = kb2.toIntOrNull(16) ?: return "anahtar okunamadi"
        val sb = StringBuilder()

        // KWP2000: ikinci anahtar 0x8F. (Bazi ELM'ler sirayi ters gosterir.)
        val (k1, k2) = when {
            b == 0x8F -> a to b
            a == 0x8F -> b to a
            else -> a to b
        }

        if (k2 == 0x8F) {
            sb.append("KWP2000 (ISO 14230). ")
            val al0 = k1 and 0x01 != 0
            val al1 = k1 and 0x02 != 0
            val hb0 = k1 and 0x04 != 0
            val hb1 = k1 and 0x08 != 0
            val tp0 = k1 and 0x10 != 0
            val tp1 = k1 and 0x20 != 0
            val baslik = mutableListOf<String>()
            if (hb1) baslik += "hedef/kaynak adresli (81 XX F1)"
            if (hb0) baslik += "tek byte (adressiz)"
            sb.append("Baslik: ${if (baslik.isEmpty()) "belirsiz" else baslik.joinToString(" veya ")}. ")
            val uz = mutableListOf<String>()
            if (al0) uz += "format byte icinde"
            if (al1) uz += "ayri uzunluk byte'i"
            sb.append("Uzunluk: ${if (uz.isEmpty()) "belirsiz" else uz.joinToString(" / ")}. ")
            sb.append("Zamanlama: ${when {
                tp0 && tp1 -> "normal + genisletilmis"
                tp1 -> "genisletilmis (yavas cevap verebilir, ATST yuksek tutulmali)"
                tp0 -> "normal"
                else -> "belirsiz"
            }}.")
            if (k1 in listOf(0xE9, 0x6B, 0x6D, 0xEF))
                sb.append(" Bu deger standart OBD profili (ISO 14230-4).")
        } else if ((k1 == 0x08 && k2 == 0x08) || (k1 == 0x94 && k2 == 0x94)) {
            sb.append("ISO 9141-2 (standart OBD). Baslik 68 6A F1 kullanilir.")
        } else {
            sb.append("Standart disi anahtar (${"%02X".format(k1)} ${"%02X".format(k2)}). ")
            sb.append("Ureticiye ozel protokol olabilir - bu degeri not et, arastiralim.")
        }
        return sb.toString()
    }

    /** "ATKW" cevabindan anahtar byte'lari cikarir. Ornek: "1:8F 2:E9" */
    private fun anahtarOku(ham: String): Pair<String, String>? {
        val r = Regex("1:\\s*([0-9A-Fa-f]{2}).*?2:\\s*([0-9A-Fa-f]{2})")
        r.find(ham)?.let { return it.groupValues[1].uppercase() to it.groupValues[2].uppercase() }
        val h = hex(ham)
        return if (h.length >= 4) h.substring(h.length - 4, h.length - 2) to h.substring(h.length - 2) else null
    }

    // ===================================================== ANA AKIS

    /**
     * @param pin   kullanicinin bildirdigi arac pini (sadece raporlama icin)
     * @param derin true ise yavas init 3 baud hizinda, false ise sadece 10400'de
     */
    suspend fun calistir(
        pin: String,
        derin: Boolean,
        gonder: suspend (String) -> String,
        yaz: (String) -> Unit
    ): List<Bulunan> {
        val bulunanlar = mutableListOf<Bulunan>()
        val baslangic = System.currentTimeMillis()

        yaz("")
        yaz("════════════════════════════════════════")
        yaz("  🧪 PIN TESTI — ELM 7. pin -> arac pini $pin")
        yaz("  Mod: ${if (derin) "DERIN (~40 dk)" else "NORMAL (~15 dk)"}")
        yaz("  Sadece OKUMA. Hicbir sey yazilmaz.")
        yaz("════════════════════════════════════════")

        // ---- Hazirlik
        gonder("ATZ"); delay(1200)
        gonder("ATE0"); gonder("ATL0"); gonder("ATS0")
        gonder("ATH1"); gonder("ATAL")
        gonder("ATKW0")
        val volt = gonder("ATRV")
        yaz("Aku: ${volt.trim()}")

        // ==================== 1) HIZLI INIT
        yaz("")
        yaz("── 1/3  HIZLI INIT TARAMASI (01-FF) ──")
        gonder("ATSP5"); delay(150)
        gonder("ATST32")
        for (n in 0x01..0xFF) {
            val adres = "%02X".format(n)
            if (adres == "F1" || adres == "33") continue
            if (n % 32 == 0) yaz("   ... 0x$adres")
            gonder("ATSH81${adres}F1")
            gonder("ATPC")
            val ham = gonder("3E")
            if (initHatasi(ham)) continue
            val c = EcuDiscovery.coz(ham, "3E")
            if (!c.gecerli) continue
            if (c.kaynakAdres.isNotEmpty() && c.kaynakAdres != adres) continue

            // Hizli init'te anahtar byte'lari 81 (StartCommunication) cevabinda gelir
            val sc = gonder("81")
            val schex = hex(sc)
            val idx = schex.indexOf("C1")
            val (kb1, kb2) = if (idx >= 0 && schex.length >= idx + 6)
                schex.substring(idx + 2, idx + 4) to schex.substring(idx + 4, idx + 6)
            else "??" to "??"
            val b = Bulunan(adres, "hizli", "10400", "$kb1 $kb2", anahtarCoz(kb1, kb2))
            yaz("   ✅ 0x$adres cevap verdi  [${ham.trim()}]")
            bulunanlar += b
        }

        // ==================== 2) YAVAS INIT
        val baudlar = if (derin) listOf("10" to "10400", "96" to "9600", "48" to "4800")
                      else listOf("10" to "10400")

        yaz("")
        yaz("── 2/3  YAVAS INIT TARAMASI (01-FF) ──")
        yaz("   Her adres ~2.5 sn surer (5 baud). Sabret.")
        gonder("ATSP4"); delay(150)
        gonder("ATST64")

        for ((ib, baudAd) in baudlar) {
            val ibCevap = gonder("ATIB$ib")
            if (ibCevap.contains("?")) {
                yaz("   ⚠️ ELM $baudAd baud'u desteklemiyor, atlandi")
                continue
            }
            yaz("")
            yaz("   >> $baudAd baud")
            for (n in 0x01..0xFF) {
                val adres = "%02X".format(n)
                if (adres == "F1") continue
                if (n % 32 == 0) yaz("   ... 0x$adres")
                gonder("ATIIA$adres")
                gonder("ATSH81${adres}F1")
                gonder("ATPC")
                val ham = gonder("3E")
                if (initHatasi(ham)) continue

                // Init tuttu! (cevap olmasa bile "NO DATA" gelir, ERROR gelmez)
                val kw = gonder("ATKW")
                val anahtar = anahtarOku(kw)
                val kb1 = anahtar?.first ?: "??"
                val kb2 = anahtar?.second ?: "??"
                val b = Bulunan(adres, "yavas", baudAd, "$kb1 $kb2", anahtarCoz(kb1, kb2))
                if (!ham.uppercase().contains("NO DATA")) b.notlar += "3E -> ${ham.trim()}"
                yaz("   ✅ 0x$adres INIT TUTTU ($baudAd baud)  anahtar: $kb1 $kb2")
                bulunanlar += b
            }
        }

        // ==================== 3) BULUNANLARA SORULAR
        yaz("")
        yaz("── 3/3  BULUNAN BEYINLERE SALT OKUNUR SORULAR ──")
        if (bulunanlar.isEmpty()) {
            yaz("   Bulunan beyin yok, atlandi.")
        }
        for (b in bulunanlar) {
            yaz("")
            yaz("   ▶ 0x${b.adres} (${b.init} init, ${b.baud})")
            if (b.init == "hizli") {
                gonder("ATSP5"); gonder("ATST32")
            } else {
                gonder("ATSP4"); gonder("ATST64")
                gonder("ATIB" + when (b.baud) { "9600" -> "96"; "4800" -> "48"; else -> "10" })
                gonder("ATIIA${b.adres}")
            }
            gonder("ATSH81${b.adres}F1")
            gonder("ATPC")
            for ((komut, servis, ad) in listOf(
                Triple("81", "81", "Iletisim baslat"),
                Triple("3E", "3E", "Canli mi"),
                Triple("1A80", "1A", "Beyin kimligi"),
                Triple("2180", "21", "Veri oku LID 80"),
                Triple("0100", "01", "OBD destek")
            )) {
                if (!EcuDiscovery.izinKontrol(komut).izinli) continue
                val ham = gonder(komut)
                val c = EcuDiscovery.coz(ham, servis)
                val satir = when {
                    !c.gecerli -> "sessiz"
                    c.olumlu -> "OLUMLU: ${c.veri}"
                    c.nrc.isNotEmpty() -> "red (${EcuDiscovery.nrcAciklama(c.nrc)})"
                    else -> "cevap: ${ham.trim()}"
                }
                yaz("      $komut ($ad) -> $satir")
                if (c.gecerli) b.notlar += "$komut: $satir"
                if (servis == "1A" && c.olumlu) {
                    val ascii = EcuDiscovery.hexToAscii(c.veri)
                    if (ascii.isNotBlank()) yaz("         kimlik metni: $ascii")
                }
            }
        }

        // ---- Temizlik
        gonder("ATPC")
        gonder("ATZ"); delay(1200)
        gonder("ATE0"); gonder("ATL0"); gonder("ATS0"); gonder("ATH0")
        gonder("ATSP5"); delay(200)

        // ---- Ozet
        val dk = (System.currentTimeMillis() - baslangic) / 60000
        yaz("")
        yaz("════════ PIN $pin OZETI ($dk dk) ════════")
        if (bulunanlar.isEmpty()) {
            yaz("Bu pinde hic beyin cevap vermedi.")
            yaz("Ya bu pinde beyin yok, ya da kopru/baglanti hatali.")
        } else {
            for (b in bulunanlar) {
                yaz("• 0x${b.adres}  ${b.init} init, ${b.baud} baud, anahtar ${b.anahtar}")
                yaz("    ${b.anahtarYorum}")
                b.notlar.forEach { yaz("    - $it") }
            }
        }
        yaz("Bu raporu Paylas ile gonder, birlikte yorumlayalim.")
        yaz("══════════════════════════════════════")
        return bulunanlar
    }
}
