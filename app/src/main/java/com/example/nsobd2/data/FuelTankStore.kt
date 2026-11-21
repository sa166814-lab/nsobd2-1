package com.example.nsobd2.data

import android.content.Context

object FuelTankStore {
    private const val PREF = "fuel_tank_prefs"
    private const val K_TANK_CAPACITY = "tank_capacity_l"
    private const val K_CURRENT_FUEL = "current_fuel_l"
    private const val K_LAST_REFILL = "last_refill_ts"
    private const val K_FUEL_KIND = "fuel_kind" // BENZIN / LPG
    private const val K_EST_SINCE_FULL = "est_since_full_l" // son full'den beri uygulamanın tahmini

    fun getCapacity(context: Context): Double {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(K_TANK_CAPACITY, "50.0")?.toDoubleOrNull() ?: 50.0
    }
    fun setCapacity(context: Context, l: Double) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(K_TANK_CAPACITY, l.toString()).apply()
    }

    fun getCurrentFuel(context: Context): Double {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(K_CURRENT_FUEL, "0.0")?.toDoubleOrNull() ?: 0.0
    }
    fun setCurrentFuel(context: Context, l: Double) {
        val cap = getCapacity(context)
        val clamped = l.coerceIn(0.0, cap)
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(K_CURRENT_FUEL, clamped.toString()).apply()
    }

    /** Sürüş sonunda yakıttan düş */
    fun consume(context: Context, liters: Double) {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val est = (sp.getString(K_EST_SINCE_FULL, "0")?.toDoubleOrNull() ?: 0.0) + liters
        sp.edit().putString(K_EST_SINCE_FULL, est.toString()).apply()
        val current = getCurrentFuel(context)
        setCurrentFuel(context, (current - liters).coerceAtLeast(0.0))
    }

    fun getEstimatedSinceFull(context: Context): Double =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(K_EST_SINCE_FULL, "0")?.toDoubleOrNull() ?: 0.0

    /**
     * Full'den full'e kalibrasyon. Depoyu tam doldurdun ve [actualLiters] aldın;
     * uygulama aynı dönemde [getEstimatedSinceFull] tahmin etmişti. Yeni katsayıyı döndürür
     * (yeterli veri yoksa null). Ani sıçramayı önlemek için eski katsayıyla yarı yarıya harmanlar.
     */
    fun calibrateOnFull(context: Context, actualLiters: Double): Double? {
        val est = getEstimatedSinceFull(context)
        if (est < 5.0 || actualLiters < 5.0) return null
        val old = AppPrefs.getFuelCalib(context)
        val ideal = old * (actualLiters / est)
        val yeni = ((old + ideal) / 2.0).coerceIn(0.6, 1.6)
        AppPrefs.setFuelCalib(context, yeni)
        return yeni
    }

    /** Tam doldur */
    fun refillFull(context: Context) {
        val cap = getCapacity(context)
        setCurrentFuel(context, cap)
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putLong(K_LAST_REFILL, System.currentTimeMillis())
            .putString(K_EST_SINCE_FULL, "0").apply()
    }

    /** Belirli litre ekle */
    fun addLiters(context: Context, l: Double) {
        val current = getCurrentFuel(context)
        setCurrentFuel(context, current + l)
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putLong(K_LAST_REFILL, System.currentTimeMillis()).apply()
    }

    fun getFuelKind(context: Context): String {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(K_FUEL_KIND, "BENZIN") ?: "BENZIN"
    }
    fun setFuelKind(context: Context, kind: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(K_FUEL_KIND, kind).apply()
    }

    /** Doluluk yüzdesi */
    fun getFuelPercentage(context: Context): Int {
        val cap = getCapacity(context)
        if (cap <= 0) return 0
        return ((getCurrentFuel(context) / cap) * 100).toInt().coerceIn(0, 100)
    }
}