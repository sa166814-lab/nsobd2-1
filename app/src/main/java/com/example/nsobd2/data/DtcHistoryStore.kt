package com.example.nsobd2.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Arıza geçmişi.
 * Artık sadece kodları değil; olay tipini (okuma / silme), açıklamayı
 * ve ham ECU cevabını da saklar. Böylece geriye dönük doğrulama yapılabilir.
 */
object DtcHistoryStore {

    private const val PREF_NAME = "dtc_history_prefs"
    private const val KEY_HISTORY = "history_json"
    private const val MAX_RECORDS = 200

    const val TYPE_READ = "OKUMA"
    const val TYPE_PENDING = "BEKLEYEN"
    const val TYPE_CLEAR = "SİLME"

    data class Record(
        val date: String,
        val type: String,
        val codes: List<String>,
        val raw: String
    )

    fun add(context: Context, type: String, codes: List<String>, raw: String = "") {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val arr = readArray(prefs)
        val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US)
        val obj = JSONObject().apply {
            put("date", sdf.format(Date()))
            put("type", type)
            put("codes", JSONArray(codes))
            put("raw", raw)
        }
        arr.put(obj)
        while (arr.length() > MAX_RECORDS) arr.remove(0)
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    /** Eski imza — mevcut kodlar bozulmasın diye duruyor. */
    fun add(context: Context, codes: List<String>) = add(context, TYPE_READ, codes, "")

    fun getAll(context: Context): List<Record> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val arr = readArray(prefs)
        val result = mutableListOf<Record>()
        for (i in 0 until arr.length()) {
            try {
                val obj = arr.getJSONObject(i)
                val codes = mutableListOf<String>()
                val cArr = obj.optJSONArray("codes") ?: JSONArray()
                for (j in 0 until cArr.length()) codes.add(cArr.getString(j))
                result.add(
                    Record(
                        date = obj.optString("date", "-"),
                        type = obj.optString("type", TYPE_READ),
                        codes = codes,
                        raw = obj.optString("raw", "")
                    )
                )
            } catch (_: Exception) {
            }
        }
        return result.reversed()   // en yeni en üstte
    }

    /** Aynı kodun kaç kez görüldüğü — tekrar eden arızayı yakalamak için. */
    fun frequency(context: Context): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        getAll(context).filter { it.type != TYPE_CLEAR }.forEach { rec ->
            rec.codes.forEach { c -> map[c] = (map[c] ?: 0) + 1 }
        }
        return map.toList().sortedByDescending { it.second }.toMap()
    }

    /** Ekranda gösterilecek okunabilir metin. */
    fun formatted(context: Context): String {
        val all = getAll(context)
        if (all.isEmpty()) return "Henüz kayıt yok.\nKODLARI OKU'ya bastığında buraya otomatik işlenir."

        val sb = StringBuilder()
        val freq = frequency(context)
        val tekrar = freq.filter { it.value >= 2 }
        if (tekrar.isNotEmpty()) {
            sb.append("🔁 TEKRAR EDEN ARIZALAR\n")
            tekrar.forEach { (c, n) -> sb.append("   $c → $n kez görüldü\n") }
            sb.append("\n──────────────\n\n")
        }

        all.take(40).forEach { r ->
            val icon = when (r.type) {
                TYPE_CLEAR -> "🧹"
                TYPE_PENDING -> "🟠"
                else -> "🚨"
            }
            sb.append("$icon ${r.date}  [${r.type}]\n")
            sb.append(
                if (r.codes.isEmpty()) "   (kod yok)\n"
                else "   " + r.codes.joinToString(", ") + "\n"
            )
            if (r.raw.isNotBlank()) sb.append("   ham: ${r.raw.take(60)}\n")
            sb.append("\n")
        }
        if (all.size > 40) sb.append("… ve ${all.size - 40} kayıt daha")
        return sb.toString()
    }

    /** Paylaşılabilir tam metin. */
    fun exportText(context: Context): String {
        val sb = StringBuilder()
        sb.append("NSOBD2 — ARIZA GEÇMİŞİ\n")
        sb.append("Araç: Peugeot 206 1.1\n")
        sb.append("Çıkarma tarihi: ")
            .append(SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US).format(Date()))
            .append("\n\n")
        getAll(context).forEach { r ->
            sb.append("${r.date} | ${r.type} | ${r.codes.joinToString(", ")}\n")
            if (r.raw.isNotBlank()) sb.append("   ham: ${r.raw}\n")
        }
        return sb.toString()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_HISTORY).apply()
    }

    private fun readArray(prefs: android.content.SharedPreferences): JSONArray {
        val s = prefs.getString(KEY_HISTORY, null) ?: return JSONArray()
        return try { JSONArray(s) } catch (e: Exception) { JSONArray() }
    }
}