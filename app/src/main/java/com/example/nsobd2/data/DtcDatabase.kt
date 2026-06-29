package com.example.nsobd2.data

object DtcDatabase {
    // Tüm arıza kodları ve açıklamaları bu Map içinde tutulur.
    // İleride dışarıdan bir JSON veya Room veritabanı ile beslenebilir.
    private val dictionary = mapOf(
        // Standart OBD2 Kodları
        "P0170" to "Yakıt Kesme Arızası (Bank 1)",
        "P0171" to "Sistem Çok Fakir (LPG ayarı gerekebilir veya Vakum Kaçağı)",
        "P0172" to "Sistem Çok Zengin",
        "P0300" to "Rastgele/Çoklu Silindir Ateşleme Hatası",
        "P0301" to "Silindir 1 Ateşleme Hatası (Buji/Bobin/Enjektör)",
        "P0302" to "Silindir 2 Ateşleme Hatası",
        "P0303" to "Silindir 3 Ateşleme Hatası",
        "P0304" to "Silindir 4 Ateşleme Hatası",
        "P0420" to "Katalitik Konvertör Verimliliği Düşük (LPG'li araçlarda sık görülür)",
        "P0133" to "O2 Sensörü Yavaş Yanıt Veriyor",
        "P0113" to "Emme Havası Sıcaklık (IAT) Sensörü Yüksek Giriş",
        "P0106" to "MAP Sensörü Aralık/Performans Sorunu",
        "P0115" to "Motor Su Sıcaklık Sensörü (Müşürü) Arızası",
        "P0201" to "Enjektör Devresi Arızası - Silindir 1",
        "P0500" to "Araç Hız Sensörü Arızası",

        // Peugeot / PSA Özel (P1xxx) Kodları (Özellikle 206 için)
        "P1110" to "Emme Manifoldu Basınç Sensörü Sinyal Uyumu (PSA)",
        "P1113" to "Emme Havası Sıcaklık Sensörü Kısa Devre (PSA)",
        "P1160" to "Emme Manifoldu Kaçağı / Akış Sorunu (PSA)",
        "P1336" to "Krank Mili Konum Sensörü (Krank Okuyucu) Adaptasyon Hatası (Kronik 206)",
        "P1631" to "Motor Kontrol Beyni (ECU) İç Hata (PSA)",
        "P1107" to "Oksijen Sensörü (Lambda) Devresi Sinyal Düşük (PSA)",
        "P1220" to "Gaz Kelebeği Pozisyon Sensörü (TPS) Uyumsuzluğu (PSA)"
    )

    fun getDescription(code: String): String {
        return dictionary[code] ?: "Bilinmeyen veya Üreticiye Özel Hata Kodu"
    }
}