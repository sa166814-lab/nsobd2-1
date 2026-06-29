package com.example.nsobd2.data

import android.content.Context

object FuelTankStore {
    private const val PREF = "fuel_tank_prefs"
    private const val K_TANK_CAPACITY = "tank_capacity_l"
    private const val K_CURRENT_FUEL = "current_fuel_l"
    private const val K_LAST_REFILL = "last_refill_ts"
    private const val K_FUEL_KIND = "fuel_kind" // BENZIN / LPG

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
        val current = getCurrentFuel(context)
        setCurrentFuel(context, (current - liters).coerceAtLeast(0.0))
    }

    /** Tam doldur */
    fun refillFull(context: Context) {
        val cap = getCapacity(context)
        setCurrentFuel(context, cap)
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putLong(K_LAST_REFILL, System.currentTimeMillis()).apply()
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