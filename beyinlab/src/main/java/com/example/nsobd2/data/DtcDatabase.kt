package com.example.nsobd2.data

/**
 * KAPSAMLI ARIZA KODU VERİTABANI
 *
 * Ücretli uygulamalar binlerce kodu İngilizce ve kuru tanımla verir.
 * Buradaki fark: her kod için Türkçe açıklama + Peugeot 206 / PSA'ya özel
 * "önce şuna bak" sıralaması.
 *
 * Yapı:
 *  1) Tam eşleşme sözlüğü (en sık görülen ve 206'ya özel kodlar)
 *  2) Aralık tabanlı akıllı geri dönüş (sözlükte olmayan her kod için)
 */
object DtcDatabase {

    data class Bilgi(
        val baslik: String,       // kısa tanım
        val aciklama: String,     // ne anlama geliyor
        val supheliler: String,   // sırayla ne kontrol edilecek
        val onem: Onem
    )

    enum class Onem(val etiket: String, val ikon: String) {
        KRITIK("Acil — sürmeyi bırak", "🔴"),
        YUKSEK("Yüksek — bir an önce hallet", "🟠"),
        ORTA("Orta — planla", "🟡"),
        DUSUK("Düşük — takip et", "🔵")
    }

    // =====================================================================
    //  1) TAM EŞLEŞME
    // =====================================================================

    private val sozluk: Map<String, Bilgi> = mapOf(

        // ---------- P00xx : YAKIT / HAVA ÖLÇÜMÜ ----------
        "P0010" to Bilgi("Eksantrik ayar aktüatörü (Bank 1)",
            "Değişken supap zamanlaması aktüatörünün devresinde sorun var.",
            "1. Aktüatör soketi\n2. Yağ seviyesi ve kalitesi (kirli yağ valfi tıkar)\n3. Aktüatörün kendisi", Onem.ORTA),

        "P0100" to Bilgi("Hava debimetre (MAF) devresi",
            "Beyin hava akış sensöründen geçersiz sinyal alıyor.",
            "206 1.1/1.4 MAP sensörlüdür, MAF yoktur. Bu kod çıkıyorsa beyin farklı donanıma göre kodlanmış olabilir.", Onem.ORTA),

        "P0105" to Bilgi("MAP sensörü devresi",
            "Emme manifold basınç sensöründen sinyal gelmiyor ya da mantıksız.",
            "1. Sensör soketi ve vakum hortumu\n2. Sensörü sök, içini karbürator temizleyici ile temizle\n3. Sensör değişimi\n206'da çok yaygın.", Onem.YUKSEK),
        "P0106" to Bilgi("MAP sinyali aralık dışı",
            "Basınç değeri motor devrine göre mantıksız.",
            "1. Vakum hortumu çatlağı / kopması\n2. Kirli MAP sensörü\n3. Emme manifold contası kaçağı", Onem.YUKSEK),
        "P0107" to Bilgi("MAP sinyali çok düşük",
            "Sensör neredeyse sıfır gerilim veriyor — kopuk devre gibi.",
            "1. Soket çıkmış / kablo kopuk\n2. Sensör ölmüş", Onem.YUKSEK),
        "P0108" to Bilgi("MAP sinyali çok yüksek",
            "Sensör sürekli maksimum okuyor — kısa devre olabilir.",
            "1. Kablo kısa devresi\n2. Vakum hortumu tıkalı/çıkmış\n3. Sensör arızalı", Onem.YUKSEK),

        "P0110" to Bilgi("Emilen hava sıcaklığı (IAT) devresi",
            "Hava sıcaklık sensöründen geçersiz sinyal.",
            "1. Sensör soketi\n2. Sensör yağlanmışsa temizle\n3. Değişim", Onem.DUSUK),
        "P0112" to Bilgi("IAT sinyali çok düşük", "Kısa devre gibi okunuyor.",
            "1. Kablo kısa devresi\n2. Sensör arızalı", Onem.DUSUK),
        "P0113" to Bilgi("IAT sinyali çok yüksek", "Kopuk devre gibi okunuyor.",
            "1. Soket çıkmış\n2. Kablo kopuk\n3. Sensör arızalı", Onem.DUSUK),

        "P0115" to Bilgi("Su sıcaklık sensörü (müşür) devresi",
            "Beyin motorun sıcaklığını bilmiyor. Sonuçları ağır:\n" +
                    "• FAN DEVREYE GİRMEZ → hararet riski\n" +
                    "• Soğuk çalıştırma karışımı basar → zengin, tekleme, fazla yakıt\n" +
                    "• Kapalı çevrime geçemez, lambda kullanılmaz",
            "1. Termostat gövdesindeki soketi çıkar, oksit/yeşillenme var mı bak, kontak temizleyici sık\n" +
                    "2. Kabloyu soketten geriye doğru yokla (sürtme, ezilme)\n" +
                    "3. Sensörü değiştir (ucuz parça)\n" +
                    "Test: motor çalışırken soketi çıkar — fan devreye giriyorsa fan sistemi sağlam, sorun sensörde.", Onem.YUKSEK),
        "P0116" to Bilgi("Su sıcaklığı mantıksız",
            "Sensör okuyor ama değer beklenenle uyuşmuyor.",
            "1. Termostat açık kalmış (motor ısınmıyor)\n2. Sensör kayma yapmış\n3. Soğutma suyu seviyesi düşük", Onem.ORTA),
        "P0117" to Bilgi("Su sıcaklığı sinyali çok düşük", "Kısa devre.",
            "1. Kablo kısa devresi\n2. Sensör arızalı", Onem.YUKSEK),
        "P0118" to Bilgi("Su sıcaklığı sinyali çok yüksek", "Kopuk devre — soket çıkmış olabilir.",
            "1. Soket takılı mı kontrol et\n2. Kablo kopuk mu\n3. Sensör arızalı", Onem.YUKSEK),

        "P0120" to Bilgi("Gaz kelebeği pozisyon sensörü (TPS)",
            "Kelebek açıklığı sinyali geçersiz.",
            "1. TPS soketi\n2. Kelebek boğazı kurumlanması — sök ve temizle\n3. TPS potansiyometresi aşınmış (206'da yaygın)", Onem.ORTA),
        "P0121" to Bilgi("TPS sinyali mantıksız",
            "Kelebek konumu MAP ve devirle uyuşmuyor.",
            "1. Kelebek boğazını temizle\n2. TPS ayarı/değişimi", Onem.ORTA),
        "P0122" to Bilgi("TPS sinyali çok düşük", "Kopuk ya da kısa devre.",
            "1. Soket ve kablo\n2. TPS değişimi", Onem.ORTA),
        "P0123" to Bilgi("TPS sinyali çok yüksek", "Kısa devre.",
            "1. Kablo kısa devresi\n2. TPS değişimi", Onem.ORTA),

        "P0130" to Bilgi("Ön lambda sensörü devresi (B1S1)",
            "Katalizör öncesi oksijen sensöründen geçersiz sinyal.",
            "1. Sensör soketi (egzoz manifoldu üstü, 4 kablolu) — oksit/yanık\n" +
                    "2. Isıtıcı sigortası\n3. Egzoz kaçağı sensörün önünde\n4. Sensör değişimi\n" +
                    "Not: Beyin açık çevrimdeyse (STFT donuksa) sensör suçlu olmayabilir.", Onem.ORTA),
        "P0131" to Bilgi("Ön lambda gerilimi çok düşük",
            "Sensör sürekli fakir okuyor ya da ölmüş.",
            "1. Egzoz kaçağı (sahte hava sensöre ulaşıyor)\n2. Vakum kaçağı\n3. Sensör yorulmuş", Onem.ORTA),
        "P0132" to Bilgi("Ön lambda gerilimi çok yüksek",
            "Sensör sürekli zengin okuyor.",
            "1. Kaçıran enjektör\n2. Yüksek yakıt basıncı\n3. Sensör arızalı", Onem.ORTA),
        "P0133" to Bilgi("Ön lambda yavaş tepki veriyor",
            "Sensör salınıyor ama tembelleşmiş. Yakıt tüketimini artırır.",
            "1. Sensör yaşlanmış (100.000 km üstü normal)\n2. Isıtıcı zayıflamış\n3. Değişim", Onem.ORTA),
        "P0134" to Bilgi("Ön lambda sinyali yok",
            "Sensörden hiç aktivite gelmiyor.",
            "1. Soket ve kablo kopukluğu\n2. Isıtıcı sigortası\n3. Sensör ölmüş", Onem.ORTA),
        "P0135" to Bilgi("Ön lambda ısıtıcı devresi",
            "Sensörün ısıtıcı direnci kopmuş. Sensör çalışma sıcaklığına ulaşamıyor.",
            "1. Isıtıcı sigortasını kontrol et\n2. Soketteki besleme gerilimini ölç\n3. Sensör değişimi (ısıtıcı ayrı değişmez)", Onem.ORTA),

        "P0136" to Bilgi("Arka lambda sensörü devresi (B1S2)",
            "Katalizör sonrası sensörden geçersiz sinyal.",
            "1. Soket ve kablo\n2. Egzoz kaçağı\n3. Sensör değişimi", Onem.DUSUK),
        "P0137" to Bilgi("Arka lambda gerilimi çok düşük",
            "Katalizör sonrası sürekli fakir. Egzoz kaçağı olabilir.",
            "1. Egzoz kaçağı (katalizör sonrası)\n2. Sensör arızalı", Onem.DUSUK),
        "P0138" to Bilgi("Arka lambda gerilimi çok yüksek",
            "Egzozda hiç oksijen kalmıyor — motor zengin çalışıyor.",
            "1. Karışım zenginliğinin sebebini bul (kaçıran enjektör, arızalı müşür)\n2. Sensör arızalı olabilir", Onem.ORTA),
        "P0139" to Bilgi("Arka lambda yavaş tepki", "Sensör tembelleşmiş.",
            "1. Sensör yaşlanmış\n2. Değişim", Onem.DUSUK),
        "P0140" to Bilgi("Arka lambda sinyali yok", "Sensörden aktivite gelmiyor.",
            "1. Soket/kablo\n2. Sensör ölmüş", Onem.DUSUK),
        "P0141" to Bilgi("Arka lambda ısıtıcı devresi", "Isıtıcı direnci kopmuş.",
            "1. Sigorta\n2. Sensör değişimi", Onem.DUSUK),

        "P0170" to Bilgi("Yakıt karışımı sapması (Bank 1)",
            "Beyin karışımı düzeltmeye çalışıyor ama sınıra dayanmış.",
            "1. Vakum kaçağı\n2. LPG ayarı bozulmuş\n3. Tıkalı enjektör\n4. Yakıt basıncı", Onem.YUKSEK),
        "P0171" to Bilgi("FAKİR karışım (Bank 1)",
            "Motora gereğinden az yakıt / fazla hava gidiyor. Beyin yakıt ekleyerek telafi ediyor.",
            "206'da sıralı şüpheliler:\n" +
                    "1. Emme manifold contası kaçağı (1 numara)\n" +
                    "2. Fren servosu hortumu çatlağı\n" +
                    "3. Kirli MAP sensörü\n" +
                    "4. Tıkalı enjektör / düşük yakıt basıncı\n" +
                    "5. Yorulmuş ön lambda\n" +
                    "Test: rölantide hortumların üzerine karbürator temizleyici sık, devir değişirse kaçak orada.", Onem.YUKSEK),
        "P0172" to Bilgi("ZENGİN karışım (Bank 1)",
            "Motora fazla yakıt gidiyor. Yakıt tüketimi artar, katalizör zarar görür.",
            "1. LPG enjektörü kaçırıyor\n2. Tıkalı hava filtresi\n3. Arızalı su sıcaklık sensörü (beyin soğuk sanıyor)\n" +
                    "4. Kaçıran enjektör\n5. Yüksek yakıt basıncı (regülatör)", Onem.YUKSEK),
        "P0174" to Bilgi("FAKİR karışım (Bank 2)", "4 silindirli 206'da tek bank vardır; bu kod beklenmez.",
            "Beyin kodlaması ya da okuma hatası olabilir.", Onem.DUSUK),

        // ---------- P02xx : YAKIT SİSTEMİ ----------
        "P0201" to Bilgi("1. silindir enjektör devresi", "Enjektör sargısı ya da kablosunda sorun.",
            "1. Enjektör soketi\n2. Enjektör direncini ölç (yaklaşık 12-16 ohm)\n3. Enjektör değişimi", Onem.YUKSEK),
        "P0202" to Bilgi("2. silindir enjektör devresi", "Enjektör sargısı ya da kablosunda sorun.",
            "1. Soket\n2. Direnç ölçümü\n3. Değişim", Onem.YUKSEK),
        "P0203" to Bilgi("3. silindir enjektör devresi", "Enjektör sargısı ya da kablosunda sorun.",
            "1. Soket\n2. Direnç ölçümü\n3. Değişim", Onem.YUKSEK),
        "P0204" to Bilgi("4. silindir enjektör devresi", "Enjektör sargısı ya da kablosunda sorun.",
            "1. Soket\n2. Direnç ölçümü\n3. Değişim", Onem.YUKSEK),

        "P0230" to Bilgi("Yakıt pompası besleme devresi", "Pompa rölesi ya da besleme hattı sorunu.",
            "1. Yakıt pompası rölesi\n2. Sigorta\n3. Pompa kablo tesisatı", Onem.YUKSEK),
        // ---------- P03xx : ATEŞLEME / TEKLEME ----------
        "P0300" to Bilgi("ÇOKLU silindir teklemesi",
            "Birden fazla silindirde ateşleme kaçağı. Katalizöre çiğ yakıt gider, katalizör ölebilir.",
            "206'da sıralı şüpheliler:\n" +
                    "1. Bobin kütüğü (kronik arıza)\n" +
                    "2. Bujiler — tırnak aralığı, kurumlanma\n" +
                    "3. Buji kabloları / kapakları\n" +
                    "4. Vakum kaçağı\n" +
                    "5. Yakıt basıncı düşüklüğü\n" +
                    "Not: su sıcaklık sensörü sökülüyken bu kod sahte olarak çıkar.", Onem.YUKSEK),
        "P0301" to Bilgi("1. silindir teklemesi", "Volan tarafındaki silindir ateşlemiyor.",
            "1. Bujiyi sök, rengine bak (siyah=zengin, beyaz=fakir, yağlı=segman)\n" +
                    "2. Bobin/kabloyu komşu silindirle değiştir, kod taşınıyor mu bak\n" +
                    "3. Enjektör\n4. Kompresyon ölçümü", Onem.YUKSEK),
        "P0302" to Bilgi("2. silindir teklemesi", "İkinci silindir ateşlemiyor.",
            "1. Buji\n2. Bobin/kablo değiştirme testi\n3. Enjektör\n4. Kompresyon", Onem.YUKSEK),
        "P0303" to Bilgi("3. silindir teklemesi", "Üçüncü silindir ateşlemiyor.",
            "1. Buji\n2. Bobin/kablo değiştirme testi\n3. Enjektör\n4. Kompresyon", Onem.YUKSEK),
        "P0304" to Bilgi("4. silindir teklemesi", "Triger tarafındaki silindir ateşlemiyor.",
            "1. Buji\n2. Bobin/kablo değiştirme testi\n3. Enjektör\n4. Kompresyon", Onem.YUKSEK),

        "P0325" to Bilgi("Vuruntu (knock) sensörü devresi",
            "Beyin vuruntu duyamıyor, emniyet için avansı kısıyor. Araç ağırlaşır, yakıt artar.",
            "1. Sensör soketi (blok üzerinde)\n2. Sensör torku (gevşekse yanlış okur)\n3. Değişim", Onem.ORTA),
        "P0335" to Bilgi("Krank mili pozisyon sensörü",
            "Beyin motorun konumunu göremiyor. 206'da klasik: araç sıcakken stop eder, soğuyunca çalışır.",
            "1. Sensör soketi ve kablo demeti (sürtme çok yaygın)\n" +
                    "2. Sensör ile volan arası boşluk / kir\n3. Sensör değişimi", Onem.KRITIK),
        "P0336" to Bilgi("Krank sensörü sinyali mantıksız", "Sinyal var ama düzensiz.",
            "1. Volan dişlisi hasarı\n2. Sensör mesafesi\n3. Sensör değişimi", Onem.YUKSEK),
        "P0340" to Bilgi("Eksantrik (kam) mili sensörü",
            "Beyin supap zamanlamasını göremiyor. Zor çalışma ve güç kaybı yapar.",
            "1. Soket ve kablo\n2. Triger kayışı atlaması (ciddi)\n3. Sensör değişimi", Onem.YUKSEK),
        "P0341" to Bilgi("Kam sensörü sinyali mantıksız", "Krank ile kam sinyali uyuşmuyor.",
            "1. Triger kayışı atlamış olabilir — ACİL kontrol\n2. Sensör", Onem.KRITIK),
        "P0343" to Bilgi("Kam sensörü sinyali yüksek", "Kopuk devre.",
            "1. Soket\n2. Kablo\n3. Sensör", Onem.YUKSEK),

        // ---------- P04xx : EMİSYON ----------
        "P0400" to Bilgi("EGR akışı", "Egzoz gazı geri dönüşümü çalışmıyor.",
            "1. EGR valfi kurumdan tıkalı — sök, temizle\n2. Vakum hattı\n3. Valf değişimi", Onem.ORTA),
        "P0401" to Bilgi("EGR akışı yetersiz", "Yeterli egzoz gazı geri dönmüyor.",
            "1. EGR valfi ve kanalları kurumlanmış\n2. Vakum hattı çatlağı", Onem.ORTA),
        "P0402" to Bilgi("EGR akışı fazla", "Valf açık kalmış olabilir. Rölanti bozulur.",
            "1. EGR valfi kapanmıyor — sök, temizle\n2. Valf değişimi", Onem.ORTA),
        "P0403" to Bilgi("EGR kontrol devresi", "Valfin elektrik devresinde sorun.",
            "1. Soket\n2. Kablo\n3. Valf", Onem.ORTA),

        "P0420" to Bilgi("Katalizör verimi eşiğin altında",
            "Katalizör egzoz gazını yeterince temizlemiyor. TÜVTÜRK'ten kalma sebebi.",
            "Katalizörü değiştirmeden ÖNCE şunları ele (çoğu zaman sebep bunlar):\n" +
                    "1. Arka lambda sensörü yorulmuş\n" +
                    "2. Egzoz kaçağı (manifold, flanş, conta)\n" +
                    "3. Motor zengin çalışıyor — çiğ yakıt katalizörü boğuyor\n" +
                    "4. Tekleme var mı (P030x)\n" +
                    "5. LPG kullanımı katalizör ömrünü kısaltır\n" +
                    "Hepsini eledikten sonra katalizör değişimi.", Onem.ORTA),

        "P0440" to Bilgi("Yakıt buharı (EVAP) sistemi", "Depo buhar sisteminde kaçak.",
            "1. Depo kapağı contası (en sık)\n2. Kanister valfi\n3. Hortumlar", Onem.DUSUK),
        "P0441" to Bilgi("EVAP tahliye akışı yanlış", "Kanister valfi doğru çalışmıyor.",
            "1. Kanister valfi\n2. Vakum hortumu", Onem.DUSUK),
        "P0442" to Bilgi("EVAP küçük kaçak", "Sistemde küçük bir kaçak var.",
            "1. Depo kapağını sıkı kapat / contayı değiştir\n2. Hortum çatlakları", Onem.DUSUK),
        "P0443" to Bilgi("EVAP tahliye valfi devresi", "Valfin elektrik devresi.",
            "1. Soket\n2. Valf değişimi", Onem.DUSUK),
        "P0446" to Bilgi("EVAP havalandırma devresi", "Havalandırma valfi sorunu.",
            "1. Valf\n2. Kanister tıkanıklığı", Onem.DUSUK),
        "P0455" to Bilgi("EVAP büyük kaçak", "Sistemde belirgin kaçak.",
            "1. Depo kapağı takılı mı / contası sağlam mı\n2. Kopuk hortum", Onem.DUSUK),

        // ---------- P05xx : HIZ / RÖLANTİ ----------
        "P0500" to Bilgi("Araç hız sensörü (VSS)",
            "Hız sinyali gelmiyor. Hız göstergesi sıfırlanır, vites/ateşleme haritaları şaşar.",
            "1. Şanzıman üstündeki hız müşürü soketi\n2. Kablo\n3. Sensör değişimi", Onem.ORTA),
        "P0501" to Bilgi("Hız sensörü sinyali mantıksız", "Sinyal var ama düzensiz.",
            "1. Sensör dişlisi aşınmış\n2. Sensör değişimi", Onem.ORTA),
        "P0505" to Bilgi("Rölanti kontrol sistemi",
            "Beyin rölanti devrini tutamıyor. Motor stop edebilir ya da devir dalgalanır.",
            "1. Kelebek boğazını sök, kurumdan temizle (206'da 1 numara)\n" +
                    "2. Rölanti step motoru\n3. Vakum kaçağı", Onem.ORTA),
        "P0506" to Bilgi("Rölanti devri düşük", "Motor rölantide boğuluyor.",
            "1. Kelebek boğazı kurumu\n2. Rölanti motoru\n3. Yakıt beslemesi", Onem.ORTA),
        "P0507" to Bilgi("Rölanti devri yüksek", "Rölanti olması gerekenden yüksek.",
            "1. Vakum kaçağı (en sık)\n2. Kelebek ayarı\n3. Rölanti motoru takılı kalmış", Onem.ORTA),

        "P0560" to Bilgi("Sistem gerilimi", "Besleme gerilimi mantıksız.",
            "1. Akü kutup başları — temizle, sık\n2. Alternatör çıkışı\n3. Şase kabloları", Onem.ORTA),
        "P0562" to Bilgi("Sistem gerilimi düşük", "Akü zayıf ya da şarj yok.",
            "1. Akü sağlığı\n2. Alternatör ve kayışı\n3. Şase bağlantıları", Onem.YUKSEK),
        "P0563" to Bilgi("Sistem gerilimi yüksek", "Aşırı şarj — elektroniklere zarar verir.",
            "1. Alternatör regülatörü\n2. Akü", Onem.YUKSEK),

        // ---------- P06xx : BEYİN ----------
        "P0601" to Bilgi("Beyin hafıza hatası", "ECU iç kontrol toplamı tutmuyor.",
            "1. Akü kutup başları ve şase\n2. Beyin yazılımı / değişim", Onem.YUKSEK),
        "P0605" to Bilgi("Beyin ROM hatası", "ECU iç arızası.",
            "1. Önce besleme ve şaseyi kontrol et\n2. Beyin onarımı", Onem.YUKSEK),

        // ---------- P1xxx : PSA ÖZEL ----------
        "P1351" to Bilgi("PSA — rölanti kontrol devresi", "Üretici özel kodu.",
            "1. Rölanti motoru\n2. Kelebek boğazı temizliği", Onem.ORTA),
        "P1500" to Bilgi("PSA — araç hız bilgisi", "Hız sinyali beyne ulaşmıyor.",
            "1. Hız sensörü\n2. Gösterge paneli bağlantısı\n" +
                    "NOT: Bu kod yanlış çözümlemeden de çıkabilir — ham cevabı kontrol et.", Onem.ORTA),
        "P1608" to Bilgi("PSA — beyin iç arızası", "ECU kendi içinde hata bildiriyor.",
            "1. Akü ve şase temizliği\n2. Beyin kontrolü", Onem.YUKSEK),
        "P1610" to Bilgi("PSA — immobilizer / anahtar tanıma",
            "Beyin ile immobilizer arasında sorun. Araç çalışmayabilir.",
            "1. Akü gerilimi\n2. Anahtar transponderi\n3. Kontak antenli halkası\n" +
                    "Bu bölgeye kendin müdahale etme, kilitlenirse araç çalışmaz.", Onem.YUKSEK),
        "P1611" to Bilgi("PSA — immobilizer kodlama", "Anahtar kodlaması uyuşmuyor.",
            "Yetkili servis / Lexia gerekir.", Onem.YUKSEK),
        "P1612" to Bilgi("PSA — beyin/immobilizer iletişimi", "İki birim anlaşamıyor.",
            "1. Şase ve besleme\n2. Servis kontrolü", Onem.YUKSEK)
    )

    // =====================================================================
    //  2) AKILLI GERİ DÖNÜŞ — sözlükte olmayan her kod için
    // =====================================================================

    private fun geriDonus(kod: String): Bilgi {
        val harf = kod.firstOrNull() ?: 'P'
        val sayi = kod.drop(1)

        if (harf == 'P') {
            val grup = sayi.take(2)
            return when (grup) {
                "00" -> Bilgi("Yakıt ve hava ölçüm sistemi",
                    "Hava/yakıt ölçüm sensörlerinden biri sınır dışı.",
                    "MAP, IAT, su sıcaklığı ve kelebek sensörlerini sırayla kontrol et.", Onem.ORTA)
                "01" -> Bilgi("Yakıt/hava karışımı ve sensörleri",
                    "Karışım oranı ya da onu ölçen sensörlerde sorun.",
                    "1. Vakum kaçağı\n2. Lambda sensörü\n3. MAP sensörü\n4. Yakıt basıncı", Onem.YUKSEK)
                "02" -> Bilgi("Yakıt sistemi (enjektör / pompa)",
                    "Yakıt besleme tarafında elektriksel ya da mekanik sorun.",
                    "1. Enjektör soketleri ve dirençleri\n2. Yakıt pompası ve rölesi\n3. Yakıt filtresi", Onem.YUKSEK)
                "03" -> Bilgi("Ateşleme sistemi / tekleme",
                    "Ateşleme kaçağı ya da devir/kam sensörü sorunu.",
                    "1. Bujiler\n2. Bobin kütüğü\n3. Buji kabloları\n4. Krank/kam sensörü", Onem.YUKSEK)
                "04" -> Bilgi("Emisyon kontrol sistemi",
                    "Katalizör, EGR ya da yakıt buharı sistemi.",
                    "1. Egzoz kaçağı\n2. Lambda sensörleri\n3. EGR valfi\n4. Depo kapağı", Onem.ORTA)
                "05" -> Bilgi("Hız ve rölanti kontrolü",
                    "Rölanti devri ya da hız sinyali sorunu.",
                    "1. Kelebek boğazı temizliği\n2. Rölanti motoru\n3. Hız sensörü", Onem.ORTA)
                "06" -> Bilgi("Beyin ve çıkış devreleri",
                    "ECU iç hatası ya da bir çıkış devresinde sorun.",
                    "1. Akü kutup başları ve şase noktaları\n2. Besleme gerilimi\n3. Beyin kontrolü", Onem.YUKSEK)
                "07" -> Bilgi("Şanzıman", "Vites kutusu ile ilgili kod.",
                    "206 düz vitesse bu kod beklenmez; sensör ya da kodlama sorunu olabilir.", Onem.ORTA)
                else -> Bilgi("Üretici (PSA) özel kodu",
                    "Standart listede olmayan, PSA'ya özel bir kod.",
                    "Lexia / Diagbox ile bakılması gerekebilir. Kodu internette 'Peugeot $kod' diye ara.", Onem.ORTA)
            }
        }

        return when (harf) {
            'C' -> Bilgi("Şasi sistemi",
                "ABS, fren, direksiyon ya da süspansiyon ile ilgili.",
                "1. ABS tekerlek sensörleri ve soketleri\n2. Fren hidroliği seviyesi\n3. ABS beyni", Onem.YUKSEK)
            'B' -> Bilgi("Gövde sistemi",
                "Airbag, klima, kapı, aydınlatma gibi konfor sistemleri.",
                "1. İlgili sigortalar\n2. Soket ve kablo bağlantıları\n" +
                        "Airbag kodlarına kendin müdahale etme, servise git.", Onem.ORTA)
            'U' -> Bilgi("İletişim hattı",
                "Beyinler arası haberleşme kopuk (CAN / K-line).",
                "1. Akü gerilimi düşük olabilir\n2. Şase noktaları\n3. BSI / BSM bağlantıları", Onem.YUKSEK)
            else -> Bilgi("Bilinmeyen kod", "Bu kod veritabanında yok.",
                "Kodu internette 'Peugeot 206 $kod' diye aratabilirsin.", Onem.ORTA)
        }
    }

    // =====================================================================
    //  DIŞARIYA AÇIK FONKSİYONLAR
    // =====================================================================

    fun bilgi(kod: String): Bilgi = sozluk[kod.uppercase()] ?: geriDonus(kod.uppercase())

    /** Tek satırlık kısa tanım. */
    fun kisa(kod: String): String = bilgi(kod).baslik

    /** Ekranda gösterilecek tam metin. */
    fun tam(kod: String): String {
        val b = bilgi(kod)
        return buildString {
            append("${b.onem.ikon} $kod — ${b.baslik}\n")
            append("Önem: ${b.onem.etiket}\n\n")
            append("${b.aciklama}\n\n")
            append("Kontrol sırası:\n${b.supheliler}")
        }
    }

    /** Kodları önem derecesine göre sıralar — en acil en üstte. */
    fun onemeGoreSirala(kodlar: List<String>): List<String> =
        kodlar.sortedBy { bilgi(it).onem.ordinal }

    /** Listedeki en yüksek önem derecesi. */
    fun enYuksekOnem(kodlar: List<String>): Onem? =
        kodlar.minByOrNull { bilgi(it).onem.ordinal }?.let { bilgi(it).onem }

    /** Veritabanındaki tam tanımlı kod sayısı. */
    fun kayitliKodSayisi(): Int = sozluk.size

    /** ESKİ API — eski çağrılar bozulmasın diye duruyor. */
    fun getDescription(code: String): String {
        val b = bilgi(code)
        return "${b.baslik} — ${b.aciklama}"
    }
}