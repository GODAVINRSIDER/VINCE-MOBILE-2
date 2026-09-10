package com.godavin.vince

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fetches real, current market prices from Yahoo Finance's public chart
 * endpoint - no API key needed, same free data source PC-VINCE already
 * uses (see the PC codebase's commands.py TICKER_MAP). Kept as a small,
 * fixed symbol map matching Vincent's actual trading focus (gold, major
 * indices, majors/crypto) rather than trying to cover every possible
 * symbol.
 */
object MarketTools {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // display name -> Yahoo Finance ticker. Sorted-by-length matching (see
    // matchSymbol below) so e.g. "us30" isn't accidentally matched inside
    // a longer phrase before a more specific name is checked.
    private val TICKER_MAP = linkedMapOf(
        "gold" to "GC=F",
        "silver" to "SI=F",
        "nasdaq" to "^IXIC",
        "us30" to "^DJI",
        "dow" to "^DJI",
        "spx500" to "^GSPC",
        "sp500" to "^GSPC",
        "s&p 500" to "^GSPC",
        "jp225" to "^N225",
        "nikkei" to "^N225",
        "uk100" to "^FTSE",
        "ftse" to "^FTSE",
        "bitcoin" to "BTC-USD",
        "btc" to "BTC-USD",
        "ethereum" to "ETH-USD",
        "eth" to "ETH-USD",
        "eurusd" to "EURUSD=X",
        "gbpusd" to "GBPUSD=X",
        "usdjpy" to "USDJPY=X",
        "xauusd" to "GC=F",
    )

    /** Returns (displayName, tickerSymbol) for whichever known symbol
     * appears in the text, checking longer names first so "s&p 500"
     * isn't shadowed by a shorter partial match. Null if nothing known
     * is mentioned. */
    fun matchSymbol(lowerText: String): Pair<String, String>? {
        return TICKER_MAP.entries
            .sortedByDescending { it.key.length }
            .firstOrNull { lowerText.contains(it.key) }
            ?.let { it.key to it.value }
    }

    suspend fun fetchPrice(ticker: String): Result<Double> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("https://query1.finance.yahoo.com/v8/finance/chart/$ticker")
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            Exception("Yahoo Finance error (${response.code})")
                        )
                    }
                    val body = response.body?.string().orEmpty()
                    val root = JSONObject(body)
                    val result = root.getJSONObject("chart").getJSONArray("result")
                    if (result.length() == 0) {
                        return@withContext Result.failure(Exception("No price data returned."))
                    }
                    val meta = result.getJSONObject(0).getJSONObject("meta")
                    Result.success(meta.getDouble("regularMarketPrice"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}
