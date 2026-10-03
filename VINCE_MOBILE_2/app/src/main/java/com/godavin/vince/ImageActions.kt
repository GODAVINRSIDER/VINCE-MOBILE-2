package com.godavin.vince

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/** Copy / save / share for generated images (and any image shown in chat). */
object ImageActions {
    private const val AUTHORITY = "com.godavin.vince.fileprovider"

    private fun uriFor(context: Context, path: String) =
        FileProvider.getUriForFile(context, AUTHORITY, File(path))

    /** Puts the image itself on the clipboard so it can be pasted into WhatsApp, notes, etc. */
    fun copy(context: Context, path: String): Boolean {
        return try {
            val uri = uriFor(context, path)
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newUri(context.contentResolver, "VINCE image", uri))
            true
        } catch (e: Exception) { false }
    }

    /** Saves a copy into Pictures/VINCE so it appears in the gallery. Returns true if saved. */
    fun saveToGallery(context: Context, path: String): Boolean {
        val src = File(path)
        if (!src.exists()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Older Android would need a storage permission; hand it to the
            // share sheet instead (the user can pick "Save to Gallery"/Drive).
            share(context, path)
            return true
        }
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "VINCE_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/VINCE")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (e: Exception) { false }
    }

    fun share(context: Context, path: String) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uriFor(context, path))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(intent, "Share image").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) { /* nothing to share with */ }
    }
}
