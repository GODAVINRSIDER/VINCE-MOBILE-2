package com.godavin.vince

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Fix - photos/screenshots sent to VINCE were never actually kept
 * anywhere: the chat bubble only ever showed a "[Photo]"/"[Screen]"
 * placeholder, and the underlying pixels were gone the moment analysis
 * finished. This is the concrete, working half of "image memory" -
 * every image sent now gets copied into the app's own private storage
 * and stays there, so it's visible in the chat and won't disappear on
 * app restart, unlike the original camera-cache/picker Uri, which isn't
 * guaranteed to stay valid or accessible later.
 *
 * What this does NOT do: make a later text-only follow-up automatically
 * re-run vision on the saved image. The AI's original description of
 * the image is what carries forward into conversation memory (same
 * history mechanism every other message uses) - the raw pixels are
 * available again here if a future feature needs to re-examine them,
 * but nothing currently re-triggers that on its own.
 */
object ImageStore {
    private fun dir(context: Context) = File(context.filesDir, "chat_images").apply { mkdirs() }

    /** Saves [bitmap] as a JPEG in private app storage, returns the
     * absolute path to store on the ChatMessage. */
    fun save(context: Context, bitmap: Bitmap): String {
        val file = File(dir(context), "${UUID.randomUUID()}.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }
        return file.absolutePath
    }

    fun loadBitmap(path: String): Bitmap? {
        return try {
            android.graphics.BitmapFactory.decodeFile(path)
        } catch (e: Exception) {
            null
        }
    }
}
