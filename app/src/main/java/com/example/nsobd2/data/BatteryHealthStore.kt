package com.example.nsobd2.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BatteryHealthStore {
    private const val PREF = "battery_prefs"
    private const val KEY = "cranking_history"

    data class Cranking(val date: String, val minVoltage: Double, val timestamp: Long)

    fun addCrankingEvent(context: Context, minVoltage: Double) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val arr = readArray(prefs)
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val obj = JSONObject().apply {
            put("date", sdf.format(Date()))
            put("minVoltage", minVoltage)
            put("timestamp", System.currentTimeMillis())
        }
        arr.put(obj)
        while (arr.length() > 30) arr.remove(0)
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun getAll(context: Context): List<Cranking> {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val arr = readArray(prefs)
        val list = mutableListOf<Cranking>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                Cranking(
                    date = o.optString("date"),
                    minVoltage = o.optDouble("minVoltage", 0.0),
                    timestamp = o.optLong("timestamp")
                )
            )
        }
        return list.reversed()
    }

    /** Son 10 marş ortalamasından sağlık skoru (0-100) */
    fun calculateHealthScore(context: Context): Pair<Int, String> {
        val list = getAll(context).take(10)
        if (list.isEmpty()) return 100 to "Henüz marş kaydı yok"
        val avgMin = list.sumOf { it.minVoltage } / list.size
        // 12V+ → 100, 11V → 80, 10V → 60, 9V → 40, 8V → 20
        val score = ((avgMin - 8.0) * 20.0).coerceIn(0.0, 100.0).toInt()
        val comment = when {
            score >= 85 -> "🟢 Akü sağlıklı"
            score >= 65 -> "🟡 Akü iyi durumda"
            score >= 45 -> "🟠 Akü zayıflıyor, dikkat"
            else -> "🔴 Akü değişmeli! Marş anında 9V altına düşüyor."
        }
        return score to "$comment\n\nSon ${list.size} marş ortalama min: ${"%.2f".format(avgMin)}V"
    }

    fun clear(context: Context) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY).apply()
    }

    private fun readArray(prefs: android.content.SharedPreferences): JSONArray {
        val s = prefs.getString(KEY, null) ?: return JSONArray()
        return try { JSONArray(s) } catch (e: Exception) { JSONArray() }
    }
}