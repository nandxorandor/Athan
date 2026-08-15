package com.ahmedkhalaf.athan

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File

/** A downloaded athan the user imported: an absolute path and a display name. */
data class ImportedAthan(val path: String, val name: String)

/** An audio file sitting in the phone's Downloads folder, not yet imported. */
data class DownloadAudio(val uri: Uri, val name: String)

/**
 * Keeps the user's downloaded athans as real files inside the app's own private
 * storage, copied in when they pick one via "Choose from device". Copying (not
 * just referencing the picked URI) means the athan keeps working even if the
 * original download is deleted, and it never depends on a fragile persisted URI
 * permission. Nothing leaves the device — it is the user's own file, imported
 * for their own use.
 */
class AthanImports(context: Context) {

    private val app = context.applicationContext
    private val dir = File(app.filesDir, DIR).apply { mkdirs() }
    private val sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(): List<ImportedAthan> =
        dir.listFiles()?.sortedBy { it.lastModified() }?.map {
            ImportedAthan(it.absolutePath, nameOf(it.absolutePath) ?: it.nameWithoutExtension)
        }.orEmpty()

    fun nameOf(path: String): String? = sp.getString(keyFor(path), null)

    /** Copies [uri] into private storage and records its name. Returns the entry. */
    fun import(uri: Uri): ImportedAthan? {
        val display = displayName(uri) ?: "Athan"
        val ext = display.substringAfterLast('.', "mp3").take(4).ifBlank { "mp3" }
        val out = File(dir, "athan_${System.currentTimeMillis()}.$ext")
        return runCatching {
            app.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { input.copyTo(it) }
            } ?: return null
            val name = display.substringBeforeLast('.').ifBlank { display }
            sp.edit().putString(keyFor(out.absolutePath), name).apply()
            ImportedAthan(out.absolutePath, name)
        }.getOrNull()
    }

    fun delete(path: String) {
        runCatching { File(path).delete() }
        sp.edit().remove(keyFor(path)).apply()
    }

    /**
     * Audio files currently in the phone's Downloads folder — what the user just
     * downloaded through the browser. Needs READ_MEDIA_AUDIO; returns empty if
     * that has not been granted yet.
     */
    fun downloadsAudio(): List<DownloadAudio> = runCatching {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val cols = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME)
        // RELATIVE_PATH is API 29+; on 26–28 fall back to the file path column.
        val useRel = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
        val pathCol = if (useRel) MediaStore.Audio.Media.RELATIVE_PATH
        else @Suppress("DEPRECATION") MediaStore.Audio.Media.DATA
        val selection = "$pathCol LIKE ?"
        val arg = if (useRel) "%Download%" else "%/Download/%"
        val out = mutableListOf<DownloadAudio>()
        app.contentResolver.query(
            collection, cols + pathCol, selection, arrayOf(arg),
            "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val name = c.getString(nameCol) ?: continue
                out.add(DownloadAudio(android.content.ContentUris.withAppendedId(collection, id), name))
            }
        }
        out
    }.getOrDefault(emptyList())

    private fun displayName(uri: Uri): String? = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    private fun keyFor(path: String) = "name_" + File(path).name

    companion object {
        private const val DIR = "athans"
        private const val PREFS = "athan_imports"

        /** The permission without which [downloadsAudio] can only return empty. */
        fun permission(): String =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                android.Manifest.permission.READ_MEDIA_AUDIO
            } else {
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            }

        fun canSeeDownloads(context: Context): Boolean =
            context.checkSelfPermission(permission()) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
