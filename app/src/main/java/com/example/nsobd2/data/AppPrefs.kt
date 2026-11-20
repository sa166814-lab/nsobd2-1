package com.example.nsobd2.data

import android.content.Context

object AppPrefs {
    private const val PREF = "app_prefs"

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ---------------------------------------------------------- YAKIT FİYAT

    fun getBenzinPrice(ctx: Context): Double =
        p(ctx).getString("benzin_price", "64.50")?.toDoubleOrNull() ?: 64.50
    fun setBenzinPrice(ctx: Context, v: Double) =
        p(ctx).edit().putString("benzin_price", v.toString()).apply()

    fun getLpgPrice(ctx: Context): Double =
        p(ctx).getString("lpg_price", "31.29")?.toDoubleOrNull() ?: 31.29
    fun setLpgPrice(ctx: Context, v: Double) =
        p(ctx).edit().putString("lpg_price", v.toString()).apply()

    fun isLpgMode(ctx: Context): Boolean = p(ctx).getBoolean("lpg_mode", false)
    fun setLpgMode(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("lpg_mode", v).apply()

    fun getActiveFuelPrice(ctx: Context): Double =
        if (isLpgMode(ctx)) getLpgPrice(ctx) else getBenzinPrice(ctx)

    fun getAfr(ctx: Context): Double = if (isLpgMode(ctx)) 15.5 else 14.7
    fun getFuelDensity(ctx: Context): Double = if (isLpgMode(ctx)) 0.55 else 0.74

    // ------------------------------------------------------- MOTOR HACMİ ⚠️
    // Yakıt formülünde motor hacmi SABİT olarak 1.1 L yazılıydı.
    // Araç 1.4 çıkınca tüm tüketim ve maliyet hesapları ~%27 düşük
    // gösteriyordu. Artık buradan geliyor.

    /** Motor hacmi (litre). Varsayılan 1.4 */
    fun getEngineLiters(ctx: Context): Double =
        p(ctx).getString("engine_liters", "1.4")?.toDoubleOrNull() ?: 1.4

    fun setEngineLiters(ctx: Context, v: Double) =
        p(ctx).edit().putString("engine_liters", v.toString()).apply()

    /** Raporlarda kullanılan araç adı, ayarlardaki motor hacmine göre. */
    fun aracEtiketi(ctx: Context): String =
        "Peugeot 206 " + String.format(java.util.Locale.US, "%.1f", getEngineLiters(ctx))

    /** Formülde kullanılan hacim, metreküp (1.4 L -> 0.0014) */
    fun getEngineM3(ctx: Context): Double = getEngineLiters(ctx) / 1000.0

    /** Volumetrik verim. Atmosferik eski motorlarda 0.80-0.90. */
    fun getVolumetricEfficiency(ctx: Context): Double =
        p(ctx).getString("volumetric_eff", "0.85")?.toDoubleOrNull() ?: 0.85

    fun setVolumetricEfficiency(ctx: Context, v: Double) =
        p(ctx).edit().putString("volumetric_eff", v.toString()).apply()

    // --------------------------------------------------------- BAĞLANTI

    fun getSavedIp(ctx: Context): String =
        p(ctx).getString("elm_ip", "192.168.0.10") ?: "192.168.0.10"
    fun setSavedIp(ctx: Context, ip: String) =
        p(ctx).edit().putString("elm_ip", ip).apply()

    fun getSavedPort(ctx: Context): Int = p(ctx).getInt("elm_port", 35000)
    fun setSavedPort(ctx: Context, port: Int) =
        p(ctx).edit().putInt("elm_port", port).apply()

    fun getAutoConnect(ctx: Context): Boolean = p(ctx).getBoolean("auto_connect", true)
    fun setAutoConnect(ctx: Context, v: Boolean) =
        p(ctx).edit().putBoolean("auto_connect", v).apply()

    // --------------------------------------------------------- VIN

    fun getVin(ctx: Context): String? = p(ctx).getString("vin", null)
    fun setVin(ctx: Context, v: String) = p(ctx).edit().putString("vin", v).apply()

    // ------------------------------------------------------ VERİ DOĞRULAMA
    // Su sensörü sökülüyken uygulama bir sürüş boyunca -40 °C kaydetti ve
    // hiç uyarı vermedi. Artık geçersiz veri yakalanıyor.

    fun isCoolantValid(c: Double): Boolean = c > -30 && c < 130
    fun isIatValid(t: Double): Boolean = t > -30 && t < 110

    fun sensorFaultText(c: Double): String = when {
        c <= -30 -> "Sensör sökük ya da kablosu kopuk (-40 °C okunuyor)"
        c >= 130 -> "Sensör kısa devre yapmış olabilir"
        else -> ""
    }
}