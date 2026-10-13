package com.godavin.vince

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * v2.5 - runs a blocking OkHttp call so that cancelling the coroutine also
 * cancels the socket. Needed for the provider race: the slower provider must be
 * cut off the moment the faster one wins, instead of finishing in the background.
 */
object HttpStream {
    suspend fun <T> run(client: OkHttpClient, request: Request, block: (Response) -> T): T =
        withContext(Dispatchers.IO) {
            val call = client.newCall(request)
            val watcher = launch {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            try {
                call.execute().use(block)
            } finally {
                watcher.cancel()
            }
        }
}
