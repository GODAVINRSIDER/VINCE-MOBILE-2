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

    // Finnhub's free tier reliably covers crypto and forex via these
    // documented exchange-prefixed symbol formats. Deliberately does NOT
    // include gold/silver/indices - Finnhub's free-tier coverage there is
    // inconsistent and not something to guess at; those stay Yahoo-only.
    private val FINNHUB_SYMBOL_MAP = mapOf(
        "bitcoin" to "BINANCE:BTCUSDT",
        "btc" to "BINANCE:BTCUSDT",
        "ethereum" to "BINANCE:ETHUSDT",
        "eth" to "BINANCE:ETHUSDT",
        "eurusd" to "OANDA:EUR_USD",
        "gbpusd" to "OANDA:GBP_USD",
        "usdjpy" to "OANDA:USD_JPY",
    )

    /** Tries Yahoo first (works for everything in TICKER_MAP); if that
     * fails AND this symbol has a known Finnhub mapping AND a Finnhub key
     * is saved, falls back to Finnhub before giving up. */
    suspend fun fetchPriceWithFallback(
        context: android.content.Context,
        displayName: String,
        yahooTicker: String
    ): Result<Double> {
        val yahooResult = fetchPrice(yahooTicker)
        if (yahooResult.isSuccess) return yahooResult

        val finnhubSymbol = FINNHUB_SYMBOL_MAP[displayName] ?: return yahooResult
        val finnhubKey = ApiKeyStore.getKey(context, Provider.FINNHUB)
        if (finnhubKey.isBlank()) return yahooResult

        return fetchFinnhubPrice(finnhubSymbol, finnhubKey)
    }

    private suspend fun fetchFinnhubPrice(symbol: String, apiKey: String): Result<Double> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("https://finnhub.io/api/v1/quote?symbol=$symbol&token=$apiKey")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(Exception("Finnhub error (${response.code})"))
                    }
                    val body = response.body?.string().orEmpty()
                    val root = JSONObject(body)
                    val price = root.optDouble("c", Double.NaN)
                    if (price.isNaN() || price == 0.0) {
                        Result.failure(Exception("Finnhub returned no price for $symbol"))
                    } else {
                        Result.success(price)
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}
