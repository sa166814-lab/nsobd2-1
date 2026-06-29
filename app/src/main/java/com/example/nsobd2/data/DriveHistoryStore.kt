package com.example.nsobd2.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DriveHistoryStore {
    private const val PREF = "drive_history_prefs"
    private const val KEY = "drives_json"

    data class Record(
        val date: String,
        val timestamp: Long,
        val durationSec: Long,
        val totalKm: Double,
        val totalLiters: Double,
        val avgL100: Double,
        val maxRpm: Int,
        val maxSpeed: Int,
        val maxCoolant: Int,
        val avgSpeed: Double,
        val ecoScore: Int,
        val cost: Double
    )

    fun add(context: Context, r: Record) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val arr = readArray(prefs)
        val obj = JSONObject().apply {
            put("date", r.date)
            put("timestamp", r.timestamp)
            put("durationSec", r.durationSec)
            put("totalKm", r.totalKm)
            put("totalLiters", r.totalLiters)
            put("avgL100", r.avgL100)
            put("maxRpm", r.maxRpm)
            put("maxSpeed", r.maxSpeed)
            put("maxCoolant", r.maxCoolant)
            put("avgSpeed", r.avgSpeed)
            put("ecoScore", r.ecoScore)
            put("cost", r.cost)
        }
        arr.put(obj)
        while (arr.length() > 100) arr.remove(0)
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun getAll(context: Context): List<Record> {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val arr = readArray(prefs)
        val list = mutableListOf<Record>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                Record(
                    date = o.optString("date"),
                    timestamp = o.optLong("timestamp"),
                    durationSec = o.optLong("durationSec"),
                    totalKm = o.optDouble("totalKm", 0.0),
                    totalLiters = o.optDouble("totalLiters", 0.0),
                    avgL100 = o.optDouble("avgL100", 0.0),
                    maxRpm = o.optInt("maxRpm"),
                    maxSpeed = o.optInt("maxSpeed"),
                    maxCoolant = o.optInt("maxCoolant"),
                    avgSpeed = o.optDouble("avgSpeed", 0.0),
                    ecoScore = o.optInt("ecoScore"),
                    cost = o.optDouble("cost", 0.0)
                )
            )
        }
        return list.reversed() // En yeni başta
    }

    fun clear(context: Context) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY).apply()
    }

    /** Belirli gün sayısı içindeki kayıtları getir. */
    fun getLastDays(context: Context, days: Int): List<Record> {
        val cutoff = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L
        return getAll(context).filter { it.timestamp >= cutoff }
    }

    private fun readArray(prefs: android.content.SharedPreferences): JSONArray {
        val s = prefs.getString(KEY, null) ?: return JSONArray()
        return try { JSONArray(s) } catch (e: Exception) { JSONArray() }
    }
}