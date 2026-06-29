package com.example.nsobd2.data

import android.content.Context

object AppPrefs {
    private const val PREF = "app_prefs"

    fun getBenzinPrice(ctx: Context): Double =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("benzin_price", "64.50")?.toDoubleOrNull() ?: 64.50
    fun setBenzinPrice(ctx: Context, p: Double) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("benzin_price", p.toString()).apply()

    fun getLpgPrice(ctx: Context): Double =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("lpg_price", "31.29")?.toDoubleOrNull() ?: 31.29
    fun setLpgPrice(ctx: Context, p: Double) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("lpg_price", p.toString()).apply()

    fun isLpgMode(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("lpg_mode", false)
    fun setLpgMode(ctx: Context, v: Boolean) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean("lpg_mode", v).apply()

    fun getActiveFuelPrice(ctx: Context): Double =
        if (isLpgMode(ctx)) getLpgPrice(ctx) else getBenzinPrice(ctx)

    // Yakıt parametreleri
    fun getAfr(ctx: Context): Double = if (isLpgMode(ctx)) 15.5 else 14.7
    fun getFuelDensity(ctx: Context): Double = if (isLpgMode(ctx)) 0.55 else 0.74

    fun getSavedIp(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("elm_ip", "192.168.0.10") ?: "192.168.0.10"
    fun setSavedIp(ctx: Context, ip: String) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("elm_ip", ip).apply()

    fun getSavedPort(ctx: Context): Int =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt("elm_port", 35000)
    fun setSavedPort(ctx: Context, port: Int) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt("elm_port", port).apply()

    fun getAutoConnect(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("auto_connect", true)
    fun setAutoConnect(ctx: Context, v: Boolean) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean("auto_connect", v).apply()
}