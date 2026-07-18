package com.example.nsobd2.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rapor deposu.
 * Paylaşım yarıda kalsa/yanlış gitse bile rapor telefonda kalıcı olarak durur.
 * Ayrıca Android 10+ cihazlarda Belgeler/NSOBD2 klasörüne de kopyalanır,
 * yani Dosyalar uygulamasından da bulunabilir.
 */
object ReportStore {

    private const val FOLDER = "raporlar"

    private fun dir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val d = File(base, FOLDER)
        if (!d.exists()) d.mkdirs()
        return d
    }

    /** Raporu kalıcı olarak kaydeder ve dosyayı döner. */
    fun save(context: Context, prefix: String, content: String): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir(context), "${prefix}_$stamp.txt")
        file.writeText(content)
        copyToDocuments(context, file.name, content)
        return file
    }

    /** Kayıtlı raporları en yeniden eskiye sıralar. */
    fun list(context: Context): List<File> =
        dir(context).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun read(file: File): String = try { file.readText() } catch (e: Exception) { "Okunamadı: ${e.message}" }

    fun deleteAll(context: Context) {
        dir(context).listFiles()?.forEach { try { it.delete() } catch (_: Exception) {} }
    }

    /** Ekranda gösterilecek kısa isim. */
    fun label(file: File): String {
        val d = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US).format(Date(file.lastModified()))
        val kind = when {
            file.name.startsWith("beyin") -> "🧠 Beyin taraması"
            file.name.startsWith("ariza") -> "🚨 Arıza okuması"
            else -> "📄 Rapor"
        }
        return "$kind — $d"
    }

    fun share(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "NSOBD2 — ${file.name}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, "Raporu gönder")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (_: Exception) {
        }
    }

    /** Metnin tamamını panoya kopyalar (paylaşım çalışmazsa yedek yol). */
    fun copyToClipboard(context: Context, content: String) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("NSOBD2 Rapor", content))
        } catch (_: Exception) {
        }
    }

    /** Android 10+ : Belgeler/NSOBD2 klasörüne de yaz (Dosyalar uygulamasından görünsün). */
    private fun copyToDocuments(context: Context, name: String, content: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/NSOBD2")
            }
            val uri = context.contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
            }
        } catch (_: Exception) {
        }
    }
}