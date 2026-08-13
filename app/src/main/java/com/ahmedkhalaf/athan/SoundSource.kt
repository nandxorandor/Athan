package com.ahmedkhalaf.athan

import android.content.Context
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.provider.OpenableColumns

/**
 * A stored athan choice is one of two kinds of string: a bundled asset path like
 * "athan/madina/4005.mp3", or a "content://" URI pointing at a file the user
 * picked or a system ringtone. This centralises telling them apart, playing
 * them, and naming them, so the service, the preview and the settings row all
 * agree.
 */
object SoundSource {

    /** A ringtone / picked content URI. */
    fun isRingtone(value: String): Boolean = value.startsWith("content://")

    /** A file the app copied into its own storage (a downloaded athan). */
    fun isLocalFile(value: String): Boolean = value.startsWith("/")

    /** Either kind of non-bundled sound, for the "is this a custom pick" check. */
    fun isCustom(value: String): Boolean = isRingtone(value) || isLocalFile(value)

    /** Points [player] at whatever the stored value refers to. Throws on failure. */
    fun setDataSource(context: Context, player: MediaPlayer, value: String) {
        when {
            isRingtone(value) -> player.setDataSource(context, Uri.parse(value))
            isLocalFile(value) -> player.setDataSource(value)
            else -> {
                val afd = context.assets.openFd(value)
                player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
            }
        }
    }

    /** A human name for a stored value, for the settings row and the picker. */
    fun label(context: Context, catalog: AthanCatalog, value: String): String {
        if (value.isEmpty()) return ""
        if (isLocalFile(value)) return AthanImports(context).nameOf(value)
            ?: context.getString(R.string.custom_sound)
        if (isRingtone(value)) return customName(context, Uri.parse(value))
            ?: context.getString(R.string.custom_sound)
        return catalog.labelFor(value)
    }

    /** File display name, else ringtone title — whichever the URI can give. */
    fun customName(context: Context, uri: Uri): String? {
        runCatching {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        runCatching {
            val title = RingtoneManager.getRingtone(context, uri)?.getTitle(context)
            if (!title.isNullOrBlank()) return title
        }
        return null
    }
}
