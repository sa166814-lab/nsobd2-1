package com.example.nsobd2.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DtcHistoryStore {
    private const val PREF_NAME = "dtc_history_prefs"
    private const val KEY_HISTORY = "history_json"

    data class Record(val date: String, val codes: List<String>)

    fun add(context: Context, codes: List<String>) {
        if (codes.isEmpty()) return
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val arr = readArray(prefs)
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val obj = JSONObject().apply {
            put("date", sdf.format(Date()))
            put("codes", JSONArray(codes))
        }
        arr.put(obj)
        while (arr.length() > 50) arr.remove(0) // En fazla 50 kayıt
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    fun getAll(context: Context): List<Record> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val arr = readArray(prefs)
        val result = mutableListOf<Record>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val codes = mutableListOf<String>()
            val cArr = obj.getJSONArray("codes")
            for (j in 0 until cArr.length()) codes.add(cArr.getString(j))
            result.add(Record(obj.getString("date"), codes))
        }
        return result.reversed() // En yeni en üstte
    }

    fun clear(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    private fun readArray(prefs: android.content.SharedPreferences): JSONArray {
        val s = prefs.getString(KEY_HISTORY, null) ?: return JSONArray()
        return try { JSONArray(s) } catch (e: Exception) { JSONArray() }
    }
}