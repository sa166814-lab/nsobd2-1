package com.example.nsobd2.data

import kotlinx.coroutines.delay

/**
 * ASAMA 5A - SESSIZ KESIF  (surum 3)
 *
 * ARACTA OGRENILENLER (19.07.2026)
 *   • Beynin adresi 0x10. Sadece bu adres cevap veriyor.
 *   • Diger adresler (11, 12, 33, 28, 36, 6D) TAMAMEN SESSIZ.
 *     Yani ABS / airbag / BSI bu soketten erisilebilir degil.
 *   • Beyin 1A, 21, 10 servislerini "desteklenmiyor" diye reddediyor.
 *
 * v2'DEKI IKI HATA
 *   1. Adres taramasi bitince baslik son denenen adreste (BSI) kaliyordu;
 *      oturum testleri motor beynine hic gitmedi, hepsi gecersizdi.
 *   2. Ayiklayici, gelen cevabin GERCEKTEN sordugumuz servise ait olup
 *      olmadigini dogrulamiyordu -> bos veriyle sahte "OK" uretti.
 *
 * *** 19.07.2026 KIRILMA NOKTASI ***
 *   Terminalden elle "81" (StartCommunication) gonderildi ->  C1 E9 8F
 *   Yani OLUMLU cevap + anahtar baytlari.
 *   ELM327'nin ATFI'si sadece FIZIKSEL el sikismayi yapiyor; uygulama
 *   seviyesindeki StartCommunication hic gonderilmiyor. Beyin de
 *   "benimle konusmaya baslamadin" deyip her servisi reddediyordu.
 *   v4: once 81 gonderilir, oturum 3E ile canli tutulur, sonra servisler.
 *
 * v3'TEKI YENILIKLER
 *   • Servis eslesme dogrulamasi (sahte olumlu bitti)
 *   • Baslik 0x10'da KILITLI tutuluyor
 *   • FORMAT BAYTI SUPURMESI: 80 / 81 / 82 / 83
 *     KWP'de format baytinin alt 6 biti veri uzunlugudur. 81 = "1 bayt".
 *     Ama 1A 80 iki bayttir; beyin parametreyi hic gormemis olabilir.
 *   • Henuz denenmemis salt okunur servisler: 12, 13, 17, 18, 22, 3E
 *
 * GUVENLIK: kara liste aynen duruyor. Salt okunur, hicbir sey yazilmaz.
 */
object EcuDiscovery {

    // ==================================================== GUVENLIK SUZGECI

    private val IZINLI_SERVISLER = setOf(
        "1A", "21", "22", "17", "18", "13", "12", "10", "20", "3E", "81", "82", "83"
    )

    val YASAKLI_SERVISLER = mapOf(
        "27" to "Guvenlik erisimi - ust uste basarisiz denemede beyin KILITLENIR",
        "2E" to "Veri yazma - kalici degisiklik",
        "3B" to "Veri yazma - kalici degisiklik",
        "30" to "Aktuator kontrolu - Asama 5B'de, ayri ve kontrollu",
        "31" to "Rutin baslat - adaptasyon degerlerini silebilir",
        "32" to "Rutin durdur",
        "11" to "Beyin resetle",
        "14" to "Ariza silme (zaten Mod 04 kullaniyoruz)",
        "34" to "FLASH YAZMA - yanlis giderse beyin olur",
        "35" to "FLASH YAZMA - yanlis giderse beyin olur",
        "36" to "Veri transferi (flash)",
        "37" to "Transfer bitir (flash)",
        "38" to "Dosya islemi",
        "85" to "Ariza kaydini kapatma",
        "23" to "Bellek adresinden okuma - yanlis adres beyni kilitleyebilir",
        "3D" to "Bellek adresine yazma"
    )

    data class IzinSonucu(val izinli: Boolean, val sebep: String)

    fun izinKontrol(komut: String): IzinSonucu {
        val k = komut.uppercase().replace(" ", "").trim()
        if (k.startsWith("AT")) return IzinSonucu(true, "adaptor komutu")
        if (k.length < 2) return IzinSonucu(false, "cok kisa")
        val servis = k.substring(0, 2)
        YASAKLI_SERVISLER[servis]?.let {
            return IzinSonucu(false, "Servis $servis engelli: $it")
        }
        val n = servis.toIntOrNull(16)
        if (n != null && n in 1..10) return IzinSonucu(true, "standart OBD modu")
        if (servis in IZINLI_SERVISLER) return IzinSonucu(true, "salt okunur KWP servisi")
        return IzinSonucu(false, "Taninmayan servis $servis - guvenligi bilinmiyor")
    }

    // ============================================ KWP CERCEVE AYIKLAYICI

    data class Cevap(
        val ham: String,
        val gecerli: Boolean,
        val olumlu: Boolean,
        val servis: String,
        val nrc: String,
        val kaynakAdres: String,
        val veri: String
    )

    private fun hex(s: String) = s.uppercase().filter { it in "0123456789ABCDEF" }

    private fun bosMu(s: String): Boolean {
        val u = s.uppercase()
        return u.isBlank() || u.contains("NO DATA") || u.contains("NODATA") ||
                u.contains("ERROR") || u.contains("UNABLE") || u.contains("STOPPED") ||
                u.contains("?") || u.contains("BUS INIT") || u.contains("SEARCHING")
    }

    /**
     * @param beklenenServis sordugumuz servis. Cevap bununla eslesmiyorsa
     *        olumlu sayilmaz (v2'deki sahte OK bu yuzden olusmustu).
     */
    fun coz(ham: String, beklenenServis: String = ""): Cevap {
        val bos = Cevap(ham, false, false, "", "", "", "")
        if (bosMu(ham)) return bos
        val h = hex(ham)
        if (h.length < 4) return bos

        val fmt = h.substring(0, 2).toIntOrNull(16) ?: return bos
        var idx = 0
        var kaynak = ""

        if ((fmt and 0xC0) == 0x80 && h.length >= 8) {
            kaynak = h.substring(4, 6)
            idx = 6
            if ((fmt and 0x3F) == 0 && h.length > 8) idx = 8
        }
        if (idx + 2 > h.length) return bos

        val ilk = h.substring(idx, idx + 2)

        // Olumsuz cevap: 7F <servis> <sebep>
        if (ilk == "7F") {
            val srv = if (idx + 4 <= h.length) h.substring(idx + 2, idx + 4) else "??"
            val nrc = if (idx + 6 <= h.length) h.substring(idx + 4, idx + 6) else "??"
            return Cevap(ham, true, false, srv, nrc, kaynak, "")
        }

        val srvNum = ilk.toIntOrNull(16) ?: return bos
        val istenen = String.format("%02X", (srvNum - 0x40) and 0xFF)

        // DOGRULAMA: cevap gercekten sordugumuz servise mi ait?
        if (beklenenServis.isNotEmpty() && istenen != beklenenServis.uppercase()) {
            return Cevap(ham, true, false, istenen, "", kaynak, "")
        }

        val sonu = if (kaynak.isNotEmpty() && h.length >= idx + 4) h.length - 2 else h.length
        val veri = if (idx + 2 < sonu) h.substring(idx + 2, sonu) else ""

        // Bos veri de olumlu sayilmaz - anlamli bir cevap degil
        if (veri.isEmpty()) return Cevap(ham, true, false, istenen, "", kaynak, "")

        return Cevap(ham, true, true, istenen, "", kaynak, veri)
    }

    fun nrcAciklama(nrc: String): String = when (nrc.uppercase()) {
        "10" -> "genel red"
        "11" -> "bu servis desteklenmiyor"
        "12" -> "alt fonksiyon desteklenmiyor"
        "13" -> "mesaj uzunlugu hatali"
        "21" -> "beyin mesgul"
        "22" -> "sartlar uygun degil"
        "31" -> "istenen deger aralik disi"
        "33" -> "guvenlik erisimi gerekli"
        "78" -> "cevap hazirlaniyor"
        "" -> "bilinmiyor"
        else -> "red kodu $nrc"
    }

    // ========================================================== KESIF AKISI

    data class Bulgu(val baslik: String, val komut: String, val cevap: String, val basarili: Boolean)

    /** Beynin adresi araçta dogrulandi. */
    private const val BEYIN_ADRESI = "10"

    /** Format bayti alt 6 biti veri uzunlugudur. Hepsini deneyecegiz. */
    // v5: dort formatin dordu de ayni sonucu verdi, ikisi yeter.
    // Akuyu bosuna yememek icin kisaltildi.
    private val FORMATLAR = listOf(
        "80" to "uzunluk ayri baytta",
        "81" to "1 bayt veri"
    )

    /** Sadece salt okunur, spec'te tanimli servisler. */
    // v5: 10 servisin hepsi denendi ve elendi. Dogrulama icin 4 tanesi yeter.
    private val OKUMA_SERVISLERI = listOf(
        Triple("1A80", "1A", "Beyin kimligi"),
        Triple("2101", "21", "Veri oku LID 01"),
        Triple("2201", "22", "Ortak tanimlayici"),
        Triple("1081", "10", "Teshis oturumu ac")
    )

    // v5: yedisi de ayni redde takildi, ikisi dogrulama icin yeterli.
    private val OTURUMLAR = listOf(
        "1081" to "Standart teshis",
        "10C0" to "PSA gelistirici"
    )

    /**
     * KWP uygulama oturumunu acar.
     * ELM327'nin ATFI'si buna yetmiyor; 81 elle gonderilmeli.
     * Olumlu cevap C1 ile baslar ve iki anahtar bayti tasir.
     */
    private suspend fun iletisimBaslat(
        gonder: suspend (String) -> String,
        yaz: (String) -> Unit
    ): Boolean {
        val ham = gonder("81")
        val h = hex(ham)
        delay(120)
        val idx = h.indexOf("C1")
        if (idx >= 0) {
            val anahtar = if (h.length >= idx + 6) h.substring(idx + 2, idx + 6) else "??"
            yaz("   OK 81 -> C1 (OLUMLU), anahtar baytlari: $anahtar  [$ham]")
            return true
        }
        yaz("   X  81 -> iletisim baslatilamadi  [$ham]")
        return false
    }

    suspend fun kesfet(
        gonder: suspend (String) -> String,
        yaz: (String) -> Unit
    ): List<Bulgu> {
        val bulgular = mutableListOf<Bulgu>()

        suspend fun sor(komut: String, servis: String): Cevap {
            val izin = izinKontrol(komut)
            if (!izin.izinli) {
                yaz("   [engellendi] $komut - ${izin.sebep}")
                return Cevap("ENGELLENDI", false, false, "", "", "", "")
            }
            val c = coz(gonder(komut), servis)
            delay(100)
            return c
        }

        fun goster(etiket: String, c: Cevap): String = when {
            !c.gecerli -> "   .  $etiket -> sessiz  [${c.ham.ifBlank { "bos" }}]"
            c.olumlu -> "   OK $etiket -> OLUMLU! veri: ${c.veri}  [${c.ham}]"
            c.nrc.isNotEmpty() -> "   X  $etiket -> ${nrcAciklama(c.nrc)}  [${c.ham}]"
            else -> "   ?  $etiket -> beklenmedik cevap  [${c.ham}]"
        }

        yaz("")
        yaz("========================================")
        yaz("  ASAMA 5A - SESSIZ KESIF  (v5)")
        yaz("  Sadece OKUMA. Hicbir sey yazilmaz.")
        yaz("========================================")
        yaz("")
        yaz("*** v5 - IKI KAPI ***")
        yaz("Bolum 1: hizli baslatma dogrulamasi (kisa, ~30 sn)")
        yaz("Bolum 2: YAVAS BASLATMA taramasi (yeni kapi, ~2 dk)")
        yaz("")
        yaz("*** v4 KIRILMA NOKTASI ***")
        yaz("Terminalden elle 81 gonderildi -> C1 E9 8F (OLUMLU!)")
        yaz("ELM327'nin ATFI'si sadece fiziksel el sikisma yapiyor;")
        yaz("uygulama seviyesindeki StartCommunication hic gonderilmiyordu.")
        yaz("Beyin de 'benimle konusmaya baslamadin' deyip reddediyordu.")
        yaz("Artik once 81 gonderiyoruz, oturumu 3E ile canli tutuyoruz.")
        yaz("")
        yaz("Engelli servisler: ${YASAKLI_SERVISLER.keys.sorted().joinToString(" ")}")

        yaz("")
        yaz("-- ADAPTOR HAZIRLIGI --")
        gonder("ATAL"); gonder("ATH1"); gonder("ATST96")
        yaz("   ATAL / ATH1 / ATST96 uygulandi")

        // ============ 1) FORMAT BAYTI SUPURMESI
        yaz("")
        yaz("-- 1) FORMAT BAYTI SUPURMESI (adres 0x10) --")
        yaz("KWP'de format baytinin alt 6 biti veri uzunlugudur.")
        yaz("81 = '1 bayt veri' demek; ama 1A 80 iki bayttir.")
        yaz("Beyin parametreyi hic gormemis olabilir. Hepsini deniyoruz.")

        var enIyiBaslik: String? = null
        var enIyiSkor = -1

        for ((fmt, aciklama) in FORMATLAR) {
            val baslik = "ATSH$fmt${BEYIN_ADRESI}F1"
            yaz("")
            yaz("  > format $fmt ($aciklama)")
            gonder(baslik); delay(80)
            gonder("ATFI"); delay(200)

            // KRITIK: uygulama seviyesinde iletisimi baslat
            val acildi = iletisimBaslat(gonder, yaz)
            if (acildi) bulgular += Bulgu("Iletisim acildi [format $fmt]", "81", "C1", true)
            delay(120)

            var cevapVeren = 0
            var olumluSayisi = 0
            for ((komut, servis, etiket) in OKUMA_SERVISLERI) {
                gonder("3E")   // oturumu canli tut - KWP 3-5 sn'de kapanir
                delay(60)
                val c = sor(komut, servis)
                yaz(goster("$komut ($etiket)", c))
                if (c.gecerli) cevapVeren++
                if (c.olumlu) {
                    olumluSayisi++
                    bulgular += Bulgu("$etiket [format $fmt]", komut, c.ham, true)
                }
            }
            yaz("   -> cevap veren: $cevapVeren/${OKUMA_SERVISLERI.size}, OLUMLU: $olumluSayisi")

            val skor = olumluSayisi * 100 + cevapVeren
            if (skor > enIyiSkor) { enIyiSkor = skor; enIyiBaslik = baslik }
            if (olumluSayisi > 0) {
                yaz("   *** BU FORMAT CALISIYOR ***")
                break
            }
        }

        // ============ 2) OTURUM DENEMELERI (baslik kilitli)
        yaz("")
        yaz("-- 2) TESHIS OTURUMU DENEMELERI --")
        yaz("Baslik ${enIyiBaslik ?: "-"} uzerinden, motor beynine.")
        if (enIyiBaslik != null) {
            gonder(enIyiBaslik); delay(80); gonder("ATFI"); delay(200)
            iletisimBaslat(gonder, yaz); delay(120)
        }

        var acikOturum: String? = null
        for ((komut, aciklama) in OTURUMLAR) {
            gonder("3E"); delay(60)
            val c = sor(komut, "10")
            yaz(goster("$komut ($aciklama)", c))
            if (c.olumlu) {
                acikOturum = komut
                yaz("   *** OTURUM ACILDI: $aciklama ***")
                bulgular += Bulgu("Oturum: $aciklama", komut, c.ham, true)
                break
            }
        }

        // ============ 3) LID TARAMASI
        if (acikOturum != null) {
            yaz("")
            yaz("-- 3) VERI TANIMLAYICI (LID) TARAMASI --")
            var bulunan = 0
            for (i in 0x01..0x60) {
                val lid = String.format("%02X", i)
                val c = sor("21$lid", "21")
                if (c.olumlu) {
                    bulunan++
                    yaz("   OK 21 $lid -> ${c.veri}")
                    bulgular += Bulgu("LID $lid", "21$lid", c.ham, true)
                }
                if (i % 8 == 0) { gonder("3E"); delay(50) }
            }
            yaz("   -> $bulunan adet veri tanimlayici bulundu")
        } else {
            yaz("")
            yaz("-- 3) LID TARAMASI ATLANDI --")
            yaz("   Oturum acilamadi, taramanin anlami yok.")
        }

        // ============ TEMIZLIK
        yaz("")
        yaz("-- TEMIZLIK --")
        if (acikOturum != null) { gonder("20"); yaz("   Teshis oturumu kapatildi") }
        gonder("82"); yaz("   Iletisim kapatildi (82)")
        gonder("ATH0"); gonder("ATSP5"); gonder("ATFI")
        yaz("   Adaptor normal OBD moduna alindi")

        // ============ HUKUM
        val olumlular = bulgular.filter { it.basarili }
        yaz("")
        yaz("====== KESIF OZETI ======")
        yaz("Gercek OLUMLU cevap : ${olumlular.size}")
        yaz("")
        // Sadece iletisim acildi ama hicbir SERVIS calismadiysa,
        // ikinci kapiyi (yavas baslatma) dene.
        val servisBulgulari = olumlular.filter { it.komut != "81" }
        if (servisBulgulari.isEmpty()) {
            yaz("Hizli baslatma tarafinda servis yok - dogrulandi.")
            yaz("Simdi IKINCI KAPIYA geciliyor: yavas baslatma.")
            yaz("=========================")
            val yavas = yavasBaslatmaTaramasi(gonder, yaz)
            return bulgular + yavas
        }

        if (olumlular.isEmpty()) {
            yaz("HUKUM: Bu beyin uretici teshis katmanini acmiyor.")
            yaz("")
            yaz("Kanitlar:")
            yaz(" - Beyin 0x10 adresinden duzgun KWP cevabi veriyor")
            yaz("   (yani K hatti, ELM327 ve adresleme SAGLAM)")
            yaz(" - Dort format baytinin dordunde de ayni red geliyor")
            yaz(" - Tum salt okunur servisler reddediliyor")
            yaz(" - Diger beyinler (ABS/airbag/BSI) bu soketten sessiz")
            yaz("")
            yaz("Sebep: Magneti Marelli / Sagem beyinlerin bir kismi uretici")
            yaz("servislerini yalnizca L hattindan gelen ozel uyandirma")
            yaz("sinyaliyle acar. ELM327 klonlari L hattini surmez.")
            yaz("")
            yaz("SONUC: Asama 5B (aktuator testi) bu donanimla mumkun degil.")
            yaz("Gerekli olan: Lexia / PP2000 arayuzu (K+L hatti sürer).")
            yaz("")
            yaz("Bu bir basarisizlik degil, sinirin nerede oldugunu")
            yaz("kesin olarak ogrendik. Jenerik OBD tarafinda devam ederiz.")
        } else {
            yaz("ACIK KAPILAR:")
            olumlular.forEach { yaz("  - ${it.baslik}  (${it.komut})") }
            yaz("")
            yaz("Bu listeyi paylas - birlikte cozecegiz.")
        }
        yaz("=========================")

        return bulgular
    }


    // ==================================================================
    //  ASAMA 5A-2 — YAVAS BASLATMA (5 baud slow init) TARAMASI
    // ==================================================================
    //
    // NEDEN:
    //   Simdiye kadarki her sey HIZLI BASLATMA (fast init, ATSP5/ATFI)
    //   uzerinden gitti. Bu kapi OBD'ye aciliyor ve beyin uretici
    //   servislerini orada hic uygulamamis.
    //
    //   ELM327'nin ikinci bir kapisi var: 5 baud YAVAS BASLATMA.
    //   Baslatma sirasinda bir ADRES gonderilir (ATIIA) ve beyin
    //   o adrese gore farkli bir servis tablosu acabilir.
    //   Lexia'nin muhtemelen kullandigi yol bu.
    //
    // SURE: yavas baslatma tanimi geregi ~2-3 saniyedir (5 baud = saniyede
    //   5 bit). 18 deneme yaklasik 1.5-2 dakika surer.

    /** Yavas baslatmada denenecek adresler. */
    private val INIT_ADRESLERI = listOf(
        "33" to "standart OBD",
        "11" to "PSA yaygin",
        "10" to "motor beyni (bildigimiz adres)",
        "12" to "alternatif",
        "13" to "alternatif",
        "01" to "genel",
        "28" to "ABS",
        "36" to "airbag",
        "6D" to "BSI"
    )

    /** Yavas baslatma sonrasi denenecek servisler. */
    private val YAVAS_SERVISLER = listOf(
        Triple("81", "81", "Iletisim baslat"),
        Triple("1A80", "1A", "Beyin kimligi"),
        Triple("2101", "21", "Veri oku LID 01"),
        Triple("2180", "21", "Veri oku LID 80"),
        Triple("1081", "10", "Teshis oturumu"),
        Triple("10C0", "10", "PSA gelistirici oturumu")
    )

    suspend fun yavasBaslatmaTaramasi(
        gonder: suspend (String) -> String,
        yaz: (String) -> Unit
    ): List<Bulgu> {
        val bulgular = mutableListOf<Bulgu>()

        suspend fun sor(komut: String, servis: String): Cevap {
            val izin = izinKontrol(komut)
            if (!izin.izinli) {
                yaz("      [engellendi] $komut")
                return Cevap("ENGELLENDI", false, false, "", "", "", "")
            }
            val c = coz(gonder(komut), servis)
            delay(120)
            return c
        }

        yaz("")
        yaz("========================================")
        yaz("  ASAMA 5A-2 - YAVAS BASLATMA TARAMASI")
        yaz("  5 baud slow init. Hala sadece OKUMA.")
        yaz("========================================")
        yaz("")
        yaz("Simdiye kadar HIZLI baslatma (fast init) kullandik.")
        yaz("O kapi OBD'ye aciliyor ve uretici servisleri orada yok.")
        yaz("ELM327'nin ikinci kapisi: 5 baud YAVAS baslatma.")
        yaz("Baslatmada bir ADRES gonderilir; beyin o adrese gore")
        yaz("farkli bir servis tablosu acabilir. Lexia'nin yolu bu olabilir.")
        yaz("")
        yaz("Her deneme 2-3 saniye surer (5 baud tanimi geregi).")
        yaz("Toplam 1.5-2 dakika. Sabret, ekran yavas akacak.")

        // Adaptoru hazirla
        gonder("ATZ"); delay(1200)
        gonder("ATE0"); gonder("ATL0"); gonder("ATS0")
        gonder("ATH1"); gonder("ATAL")
        gonder("ATKW0")   // anahtar bayti kontrolunu gevset - uyumsuz beyinler icin
        yaz("")
        yaz("   Adaptor sifirlandi, ATKW0 (esnek anahtar kontrolu) acildi")

        var basariliInit = 0

        for ((protokol, pAd) in listOf("4" to "ISO 14230 yavas baslatma",
            "3" to "ISO 9141-2")) {
            yaz("")
            yaz("========== PROTOKOL $protokol - $pAd ==========")

            for ((adres, aciklama) in INIT_ADRESLERI) {
                yaz("")
                yaz("  > init adresi 0x$adres ($aciklama)")

                gonder("ATSP$protokol"); delay(150)
                gonder("ATIB10"); delay(100)      // 10.4 kbaud
                gonder("ATIIA$adres"); delay(150)

                val initCevap = gonder("ATSI")
                delay(400)
                val ok = !bosMu(initCevap) &&
                        (initCevap.uppercase().contains("OK") || hex(initCevap).length >= 2)

                if (!ok) {
                    yaz("      X  baslatma basarisiz  [$initCevap]")
                    continue
                }

                basariliInit++
                yaz("      OK BASLATMA BASARILI  [$initCevap]")
                bulgular += Bulgu("Yavas baslatma 0x$adres (protokol $protokol)",
                    "ATIIA$adres", initCevap, true)

                // Bu adres icin baslik kur ve servisleri dene
                gonder("ATSH81${adres}F1"); delay(120)

                for ((komut, servis, etiket) in YAVAS_SERVISLER) {
                    val c = sor(komut, servis)
                    val satir = when {
                        !c.gecerli -> "      .  $komut ($etiket) -> sessiz"
                        c.olumlu -> "      OK $komut ($etiket) -> OLUMLU! veri: ${c.veri}  [${c.ham}]"
                        c.nrc.isNotEmpty() -> "      X  $komut ($etiket) -> ${nrcAciklama(c.nrc)}"
                        else -> "      ?  $komut ($etiket) -> [${c.ham}]"
                    }
                    yaz(satir)
                    if (c.olumlu) {
                        bulgular += Bulgu("$etiket @ 0x$adres (protokol $protokol)",
                            komut, c.ham, true)
                    }
                }
            }
        }

        // Temizlik - normal OBD moduna don
        yaz("")
        yaz("-- TEMIZLIK --")
        gonder("ATZ"); delay(1200)
        gonder("ATE0"); gonder("ATL0"); gonder("ATS0"); gonder("ATH0")
        gonder("ATSP5"); delay(200)
        gonder("ATFI"); delay(300)
        yaz("   Adaptor normal OBD moduna dondu")

        // Hukum
        val olumlular = bulgular.filter { it.basarili }
        val servisBulgulari = olumlular.filter { !it.komut.startsWith("ATIIA") }

        yaz("")
        yaz("====== YAVAS BASLATMA OZETI ======")
        yaz("Basarili baslatma : $basariliInit")
        yaz("Servis cevabi     : ${servisBulgulari.size}")
        yaz("")
        if (servisBulgulari.isEmpty()) {
            yaz("HUKUM (KESIN): Bu beyne ELM327 ile uretici teshis")
            yaz("katmanindan girilemiyor.")
            yaz("")
            yaz("Denenen ve elenen her sey:")
            yaz(" - Hizli baslatma, 4 format bayti, 8 adres")
            yaz(" - Uygulama seviyesinde StartCommunication (81 -> C1 E9 8F)")
            yaz(" - Oturum acma: 7 farkli oturum tipi")
            yaz(" - 10 salt okunur servis")
            yaz(" - Yavas baslatma: 2 protokol x 9 adres")
            yaz("")
            yaz("Beynin uyguladigi servisler sadece sunlar:")
            yaz("   81 / 82 (iletisim), 3E (canli tutma), Mod 01-04 (OBD)")
            yaz("Bu tam olarak ISO 14230-4 zorunlu profilidir.")
            yaz("Uretici servisleri opsiyoneldir ve bu beyinde YOK.")
            yaz("")
            yaz("Aktuator testi icin Lexia/PP2000 arayuzu sart.")
            yaz("Biz jenerik OBD tarafinda devam edecegiz.")
        } else {
            yaz("*** ACIK KAPI BULUNDU ***")
            servisBulgulari.forEach { yaz("  - ${it.baslik}  (${it.komut})  -> ${it.cevap}") }
            yaz("")
            yaz("Bu listeyi paylas, hemen devamini kodlayalim.")
        }
        yaz("==================================")

        return bulgular
    }

    fun hexToAscii(raw: String): String {
        val h = hex(raw)
        val sb = StringBuilder()
        var i = 0
        while (i + 2 <= h.length) {
            val b = h.substring(i, i + 2).toIntOrNull(16) ?: 0
            if (b in 32..126) sb.append(b.toChar())
            i += 2
        }
        return sb.toString().trim()
    }
}