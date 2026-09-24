package com.godavin.vince

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await
import java.util.UUID
import android.util.Base64

/**
 * Kotlin counterpart to VINCE 3.0's vince_relay.py - same Firestore schema,
 * same reasoning, kept in lockstep deliberately so the two sides can never
 * silently drift into incompatible field names.
 *
 * DELIBERATELY NO FIREBASE STORAGE - same reason as the Python side: Cloud
 * Storage for Firebase now requires the Blaze (billing) plan. Voice chunks
 * are base64-encoded and stored directly as a Firestore field instead,
 * capped at MAX_CHUNK_BYTES raw bytes (~700KB, matching Python) to stay
 * safely under Firestore's ~1MB document limit once base64-inflated.
 *
 * DATA SHAPE (must match vince_relay.py exactly):
 *   calls/{call_id}
 *     status: "ringing" | "connected" | "ended" | "declined"
 *     initiated_by: "pc" | "phone"
 *     persona: "vince" | "clara" | "davina"   (always lowercase - PC's
 *       config.PERSONAS keys are lowercase, so Persona.name is lowercased
 *       here before writing)
 *     created_at, connected_at, ended_at: ISO-8601 strings or null
 *
 *   calls/{call_id}/chunks/{chunk_id}
 *     from: "pc" | "phone"
 *     audio_b64, content_type, seq, processed, created_at
 *
 * Requires Anonymous Auth enabled in the Firebase console (Authentication
 * -> Sign-in method) and Firestore rules that check request.auth != null -
 * without both, every call here will fail with a permission-denied error,
 * NOT a code bug if that happens.
 */
object CallRepository {
    private const val MAX_CHUNK_BYTES = 700_000

    private val db by lazy { FirebaseFirestore.getInstance() }
    private val auth by lazy { FirebaseAuth.getInstance() }

    /** Must be called (and awaited) before any other function here - every
     * public function below calls this first anyway, so callers don't
     * strictly need to call it themselves, but doing so once at app
     * startup avoids a first-call delay during an actual call attempt. */
    suspend fun ensureSignedIn() {
        if (auth.currentUser == null) {
            auth.signInAnonymously().await()
        }
    }

    private fun nowIso(): String = java.time.Instant.now().toString()

    // -------------------------------------------------------------------
    // Call session lifecycle
    // -------------------------------------------------------------------

    suspend fun createCall(initiatedBy: String, persona: String): String {
        ensureSignedIn()
        val callId = UUID.randomUUID().toString().replace("-", "").take(12)
        val data = hashMapOf(
            "status" to "ringing",
            "initiated_by" to initiatedBy,
            "persona" to persona.lowercase(),
            "created_at" to nowIso(),
            "connected_at" to null,
            "ended_at" to null
        )
        db.collection("calls").document(callId).set(data).await()
        return callId
    }

    suspend fun getCall(callId: String): Map<String, Any?>? {
        ensureSignedIn()
        val doc = db.collection("calls").document(callId).get().await()
        return if (doc.exists()) doc.data else null
    }

    suspend fun acceptCall(callId: String) {
        ensureSignedIn()
        db.collection("calls").document(callId)
            .update(mapOf("status" to "connected", "connected_at" to nowIso())).await()
    }

    suspend fun declineCall(callId: String) {
        ensureSignedIn()
        db.collection("calls").document(callId).update("status", "declined").await()
    }

    suspend fun endCall(callId: String) {
        ensureSignedIn()
        db.collection("calls").document(callId)
            .update(mapOf("status" to "ended", "ended_at" to nowIso())).await()
    }

    /** Mirrors vince_relay.py's find_ringing_call_for(): finds the most
     * recent call still "ringing" that was initiated by the OTHER side, or
     * null if nothing's waiting. Returns Pair(callId, data). */
    suspend fun findRingingCallFor(side: String): Pair<String, Map<String, Any?>>? {
        ensureSignedIn()
        val otherSide = if (side == "phone") "pc" else "phone"
        val results = db.collection("calls")
            .whereEqualTo("status", "ringing")
            .whereEqualTo("initiated_by", otherSide)
            .orderBy("created_at", Query.Direction.DESCENDING)
            .limit(1)
            .get()
            .await()
        val doc = results.documents.firstOrNull() ?: return null
        return doc.id to (doc.data ?: emptyMap())
    }

    // -------------------------------------------------------------------
    // Voice chunk handoff
    // -------------------------------------------------------------------

    /** Base64-encodes and writes a voice chunk inline, same MAX_CHUNK_BYTES
     * cap as the Python side. Throws IllegalArgumentException (not a
     * silent truncation) if the chunk is too big - callers should record
     * shorter chunks or compress harder, not catch-and-ignore this. */
    suspend fun sendChunk(callId: String, fromSide: String, audioBytes: ByteArray,
                           contentType: String = "audio/ogg"): String {
        ensureSignedIn()
        require(audioBytes.size <= MAX_CHUNK_BYTES) {
            "Voice chunk is ${audioBytes.size} bytes, over the $MAX_CHUNK_BYTES limit for " +
                "inline Firestore storage - record a shorter chunk, or compress the audio more."
        }

        val chunkId = UUID.randomUUID().toString().replace("-", "").take(10)
        val audioB64 = Base64.encodeToString(audioBytes, Base64.NO_WRAP)

        // sequence number = current chunk count from this sender, matching
        // vince_relay.py's ordering approach exactly
        val existing = db.collection("calls").document(callId).collection("chunks")
            .whereEqualTo("from", fromSide).get().await()
        val seq = existing.size()

        val data = hashMapOf(
            "from" to fromSide,
            "audio_b64" to audioB64,
            "content_type" to contentType,
            "seq" to seq,
            "processed" to false,
            "created_at" to nowIso()
        )
        db.collection("calls").document(callId).collection("chunks").document(chunkId)
            .set(data).await()
        return chunkId
    }

    /** Returns unprocessed chunks sent BY fromSide, oldest first, as
     * Pair(chunkId, audioBytes). Does NOT mark them processed - call
     * markChunkProcessed() once each is actually consumed. */
    suspend fun getNewChunks(callId: String, fromSide: String): List<Pair<String, ByteArray>> {
        ensureSignedIn()
        val results = db.collection("calls").document(callId).collection("chunks")
            .whereEqualTo("from", fromSide)
            .whereEqualTo("processed", false)
            .orderBy("seq")
            .get()
            .await()
        return results.documents.mapNotNull { doc ->
            val b64 = doc.getString("audio_b64") ?: return@mapNotNull null
            doc.id to Base64.decode(b64, Base64.NO_WRAP)
        }
    }

    suspend fun markChunkProcessed(callId: String, chunkId: String) {
        ensureSignedIn()
        db.collection("calls").document(callId).collection("chunks").document(chunkId)
            .update("processed", true).await()
    }
}
