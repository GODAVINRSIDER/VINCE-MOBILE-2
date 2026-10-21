package com.godavin.vince

import android.net.Uri

/**
 * v2.9 - turns the raw text inside a QR code into something VINCE understands.
 * Pure parsing, no network, nothing leaves the phone here.
 */
sealed class QrPayload {
    abstract val raw: String

    data class WiFi(
        override val raw: String,
        val ssid: String,
        val password: String,
        val security: String,   // "WPA", "WEP" or "nopass"
        val hidden: Boolean
    ) : QrPayload()

    data class Url(override val raw: String, val url: String) : QrPayload()
    data class Email(override val raw: String, val address: String, val subject: String, val body: String) : QrPayload()
    data class Phone(override val raw: String, val number: String) : QrPayload()
    data class Sms(override val raw: String, val number: String, val body: String) : QrPayload()
    data class Geo(override val raw: String, val lat: String, val lng: String) : QrPayload()
    data class Contact(
        override val raw: String,
        val name: String,
        val phone: String,
        val email: String,
        val org: String
    ) : QrPayload()

    /** Authenticator / 2FA setup codes: contain a secret, never sent to any AI. */
    data class Secret(override val raw: String, val label: String) : QrPayload()

    data class Text(override val raw: String) : QrPayload()
}

object QrParser {

    fun parse(rawInput: String): QrPayload {
        val raw = rawInput.trim()
        val lower = raw.lowercase()

        if (lower.startsWith("wifi:")) parseWifi(raw)?.let { return it }

        if (lower.startsWith("otpauth:") || lower.startsWith("otpauth-migration:")) {
            return QrPayload.Secret(raw, "Authenticator (2FA) setup code")
        }

        if (lower.startsWith("http://") || lower.startsWith("https://")) return QrPayload.Url(raw, raw)
        if (lower.startsWith("www.") && !raw.contains(' ')) return QrPayload.Url(raw, "https://$raw")

        if (lower.startsWith("mailto:")) {
            return try {
                val uri = Uri.parse(raw)
                val addr = raw.substringAfter(':').substringBefore('?')
                QrPayload.Email(raw, Uri.decode(addr), uri.getQueryParameter("subject").orEmpty(), uri.getQueryParameter("body").orEmpty())
            } catch (e: Exception) {
                QrPayload.Text(raw)
            }
        }
        if (lower.startsWith("matmsg:")) {
            val f = fields(raw.substring(7))
            return QrPayload.Email(raw, f["TO"].orEmpty(), f["SUB"].orEmpty(), f["BODY"].orEmpty())
        }

        if (lower.startsWith("tel:")) return QrPayload.Phone(raw, raw.substring(4).trim())

        if (lower.startsWith("smsto:") || lower.startsWith("sms:")) {
            val rest = raw.substringAfter(':')
            return if (rest.contains('?')) {
                QrPayload.Sms(raw, rest.substringBefore('?'), Uri.parse("sms:$rest").getQueryParameter("body").orEmpty())
            } else {
                QrPayload.Sms(raw, rest.substringBefore(':'), if (rest.contains(':')) rest.substringAfter(':') else "")
            }
        }

        if (lower.startsWith("geo:")) {
            val coords = raw.substring(4).substringBefore('?').substringBefore(';').split(',')
            if (coords.size >= 2) return QrPayload.Geo(raw, coords[0].trim(), coords[1].trim())
        }

        if (lower.startsWith("begin:vcard")) return parseVCard(raw)
        if (lower.startsWith("mecard:")) {
            val f = fields(raw.substring(7))
            return QrPayload.Contact(raw, f["N"].orEmpty().replace(",", " ").trim(), f["TEL"].orEmpty(), f["EMAIL"].orEmpty(), f["ORG"].orEmpty())
        }

        return QrPayload.Text(raw)
    }

    // ---------------------------------------------------------------- Wi-Fi

    /** WIFI:T:WPA;S:name;P:password;H:true;;  with \; \: \, \\ \" escapes. */
    private fun parseWifi(raw: String): QrPayload.WiFi? {
        val f = fields(raw.substring(5))
        val ssid = f["S"] ?: return null
        val security = (f["T"] ?: "nopass").uppercase().let {
            when {
                it.startsWith("WPA") || it == "SAE" -> "WPA"
                it.startsWith("WEP") -> "WEP"
                else -> "nopass"
            }
        }
        val hidden = (f["H"] ?: "").equals("true", ignoreCase = true)
        return QrPayload.WiFi(raw, ssid, f["P"].orEmpty(), security, hidden)
    }

    /** Splits "K:v;K:v;;" into a map, honouring backslash escapes and stripping wrapping quotes. */
    private fun fields(body: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val cur = StringBuilder()
        val parts = ArrayList<String>()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '\\' && i + 1 < body.length) {
                cur.append(body[i + 1]); i += 2; continue
            }
            if (c == ';') { parts.add(cur.toString()); cur.setLength(0) } else cur.append(c)
            i++
        }
        if (cur.isNotEmpty()) parts.add(cur.toString())
        for (p in parts) {
            val idx = p.indexOf(':')
            if (idx <= 0) continue
            val key = p.substring(0, idx).trim().uppercase()
            var v = p.substring(idx + 1)
            if (v.length >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length - 1)
            if (key !in out) out[key] = v
        }
        return out
    }

    // ---------------------------------------------------------------- vCard

    private fun parseVCard(raw: String): QrPayload.Contact {
        var name = ""; var phone = ""; var email = ""; var org = ""
        for (line in raw.lines()) {
            val l = line.trim()
            val key = l.substringBefore(':').substringBefore(';').uppercase()
            val value = l.substringAfter(':', "").trim()
            when (key) {
                "FN" -> if (name.isBlank()) name = value
                "N" -> if (name.isBlank()) name = value.split(';').filter { it.isNotBlank() }.reversed().joinToString(" ")
                "TEL" -> if (phone.isBlank()) phone = value
                "EMAIL" -> if (email.isBlank()) email = value
                "ORG" -> if (org.isBlank()) org = value.replace(';', ' ').trim()
            }
        }
        return QrPayload.Contact(raw, name, phone, email, org)
    }

    // ---------------------------------------------------------------- link sanity checks

    private val SHORTENERS = setOf(
        "bit.ly", "tinyurl.com", "t.co", "goo.gl", "ow.ly", "is.gd", "buff.ly", "rebrand.ly",
        "cutt.ly", "shorturl.at", "tiny.cc", "rb.gy", "lnkd.in", "wa.me"
    )

    fun hostOf(url: String): String =
        try { Uri.parse(url).host.orEmpty().lowercase().removePrefix("www.") } catch (e: Exception) { "" }

    /** Plain-language warnings about a link. Empty list = nothing obviously odd. */
    fun linkWarnings(url: String): List<String> {
        val w = ArrayList<String>()
        val host = hostOf(url)
        if (host.isBlank()) return listOf("Couldn't read the website address.")
        if (url.startsWith("http://", ignoreCase = true)) w.add("Not encrypted (http, not https).")
        if (host in SHORTENERS) w.add("Shortened link - the real destination is hidden until you open it.")
        if (Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host)) w.add("Goes to a raw IP address instead of a normal website name.")
        if (host.contains("xn--")) w.add("Uses look-alike (punycode) characters - could be impersonating another site.")
        if (url.substringAfter("://").substringBefore('/').contains('@')) w.add("Contains an @ before the host, a common trick to disguise the real site.")
        if (host.count { it == '.' } >= 4) w.add("Unusually many sub-domains.")
        return w
    }
}
