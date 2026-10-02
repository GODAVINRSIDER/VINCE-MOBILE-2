package com.godavin.vince

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/**
 * v2.3 - "message / text / WhatsApp <name> <text>" by contact NAME.
 *
 * Before this, "open whatsapp and message John hello" was treated as one huge
 * app name and failed with "couldn't find an app called ...". Now:
 *  - the sentence is split into channel, contact name and message body,
 *  - the contact's number is looked up locally (needs the Contacts permission;
 *    nothing is uploaded and VINCE never reads your messages),
 *  - WhatsApp (or the SMS app) opens on that contact's chat with the text
 *    pre-filled. YOU press send - VINCE never sends by itself.
 * Without Contacts permission it falls back to opening WhatsApp's contact
 * picker with the text pre-filled, so you tap the name yourself.
 */
object MessageActions {
    private val OPTS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)

    // Optional "open whatsapp and" lead, then a messaging verb, then the rest.
    private val COMMAND = Regex(
        "^(?:please\\s+|hey\\s+|ok\\s+|okay\\s+)*" +
            "(?:(?:open|launch|start)\\s+(?:whatsapp|messages|sms)\\s+(?:and|then|to)\\s+)?" +
            "(?:send\\s+(?:a\\s+|an\\s+)?(?:whatsapp\\s+|text\\s+|sms\\s+)?(?:message\\s+|msg\\s+)?(?:to\\s+)?|" +
            "message\\s+|msg\\s+|text\\s+|sms\\s+|whatsapp\\s+|wa\\s+|tell\\s+|ping\\s+)" +
            "(.+)$", OPTS
    )

    private val DELIMITER = Regex(
        "\\s+(?:saying|that says|telling (?:him|her|them)|to say|and say|and tell (?:him|her|them)|with the message|that|say)\\s+|\\s*[:]\\s*",
        OPTS
    )

    private val NOT_A_NAME = setOf(
        "me", "my", "myself", "him", "her", "them", "us", "you", "it", "vince", "clara", "davina",
        "everyone", "everybody", "someone", "anyone", "the", "a", "an", "about", "what", "how", "why", "when", "where", "who"
    )

    private val NUMBER_ONLY = Regex("^\\+?\\d[\\d\\s\\-]{5,}\\d$")

    fun handle(context: Context, rawText: String): String? {
        val text = rawText.replace('\u2019', '\'').trim().trimEnd('.', '!')
        val lower = text.lowercase()

        // Typed numbers are handled by DeviceActions (text/whatsapp <number> saying ...).
        val m = COMMAND.find(text) ?: return null
        var rest = m.groupValues[1].trim()
        if (rest.isBlank()) return null

        val mentionsWhatsApp = Regex("\\b(whatsapp|wa)\\b").containsMatchIn(lower)
        val mentionsSms = Regex("^(?:please\\s+)?(?:text|sms)\\b|\\bsms\\b|\\btext message\\b").containsMatchIn(lower)
        val explicitMessage = mentionsWhatsApp || mentionsSms ||
            Regex("^(?:please\\s+)?(?:open\\s+\\w+\\s+(?:and|then|to)\\s+)?(?:message|msg|send)\\b").containsMatchIn(lower)
        val useWhatsApp = mentionsWhatsApp || !mentionsSms

        // drop channel words that trail the name: "John on whatsapp saying hi"
        rest = rest.replace(Regex("\\s+(?:on|via|using|through|in)\\s+(?:whatsapp|sms|text|messages)\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^(?:a\\s+)?(?:whatsapp\\s+|text\\s+|sms\\s+)?(?:message\\s+|msg\\s+)?to\\s+", RegexOption.IGNORE_CASE), "")
            .trim()
        if (rest.isBlank()) return null

        // Split into name + body.
        var name: String
        var body: String
        val d = DELIMITER.find(rest)
        if (d != null && d.range.first > 0) {
            name = rest.substring(0, d.range.first).trim()
            body = rest.substring(d.range.last + 1).trim()
        } else {
            name = ""
            body = ""
        }

        if (NUMBER_ONLY.matches(name.ifBlank { rest.substringBefore(' ') })) return null // typed number: DeviceActions

        val hasPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

        var contact: Contact? = null
        if (name.isNotBlank()) {
            if (hasPerm) contact = findContact(context, name)
        } else if (hasPerm) {
            // no "saying": try 1..3 word prefixes as the contact name
            val words = rest.split(Regex("\\s+"))
            for (n in 1..minOf(3, words.size - 1)) {
                val tryName = words.take(n).joinToString(" ")
                val c = findContact(context, tryName)
                if (c != null) {
                    contact = c
                    name = tryName
                    body = words.drop(n).joinToString(" ")
                    break
                }
            }
        }

        body = body.trim().trim('"', '\'', '\u201C', '\u201D').trim()

        if (name.isBlank()) {
            // could not tell where the name ends - only react if the user clearly meant a message
            val strong = mentionsWhatsApp ||
                Regex("^(?:please\\s+)?(?:open\\s+\\w+\\s+(?:and|then|to)\\s+)?(?:message|msg|send)\\b").containsMatchIn(lower)
            if (!strong) return null
            return if (hasPerm) {
                "I couldn't find that contact. Try: \"message <name> saying <your text>\"."
            } else {
                needPermissionReply(context, body = rest)
            }
        }
        if (!hasPerm) {
            if (!explicitMessage) return null
            return needPermissionReply(context, body)
        }
        if (contact == null) {
            if (!explicitMessage) return null
            return "I couldn't find a contact matching \"$name\" in your phone. Check the spelling, or give me the number: \"whatsapp +2547... saying $body\"."
        }
        if (body.isBlank()) {
            return "What should I say to ${contact.name}? Say it like: \"message ${contact.name} saying <your text>\"."
        }

        return if (useWhatsApp) openWhatsAppChat(context, contact, body) else openSms(context, contact, body)
    }

    // ---------------------------------------------------------------

    private class Contact(val name: String, val number: String)

    private fun findContact(context: Context, query: String): Contact? {
        val q = query.trim()
        if (q.isBlank() || q.lowercase() in NOT_A_NAME) return null
        val found = ArrayList<Contact>()
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$q%"),
                null
            )?.use { c ->
                while (c.moveToNext() && found.size < 60) {
                    val n = c.getString(0) ?: continue
                    val num = c.getString(1) ?: continue
                    found.add(Contact(n, num))
                }
            }
        } catch (e: Exception) {
            return null
        }
        if (found.isEmpty()) return null
        fun rank(c: Contact): Int {
            val n = c.name.lowercase()
            val ql = q.lowercase()
            return when {
                n == ql -> 0
                n.startsWith(ql) -> 1
                n.split(" ").any { it.startsWith(ql) } -> 2
                else -> 3
            }
        }
        val best = found.minByOrNull { rank(it) } ?: return null
        // a bare "contains" hit (e.g. "me" inside "James") is too loose to act on
        return if (rank(best) <= 2) best else null
    }

    private fun toWaDigits(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return when {
            raw.trim().startsWith("+") -> digits
            digits.startsWith("00") -> digits.drop(2)
            digits.startsWith("0") -> "254" + digits.drop(1)   // local Kenyan format
            else -> digits
        }
    }

    private fun openWhatsAppChat(context: Context, c: Contact, body: String): String {
        val url = "https://wa.me/${toWaDigits(c.number)}?text=" + Uri.encode(body)
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b", null)) {
            try {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (pkg != null) i.setPackage(pkg)
                context.startActivity(i)
                ActivityLog.addEvent(context, "WhatsApp draft to ${c.name}")
                return "Opened WhatsApp to ${c.name} with your message. Press send when you're ready."
            } catch (e: ActivityNotFoundException) {
                // try the next package
            } catch (e: Exception) {
                return "I couldn't open WhatsApp."
            }
        }
        return "I couldn't open WhatsApp - is it installed?"
    }

    private fun openSms(context: Context, c: Contact, body: String): String {
        val number = c.number.filter { it.isDigit() || it == '+' }
        return try {
            val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).putExtra("sms_body", body)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            ActivityLog.addEvent(context, "SMS draft to ${c.name}")
            "Opened a text to ${c.name} with your message. Press send when you're ready."
        } catch (e: Exception) {
            "I couldn't open a messaging app."
        }
    }

    /** No Contacts permission: open WhatsApp's contact picker with the text filled in. */
    private fun needPermissionReply(context: Context, body: String): String {
        val text = body.trim()
        if (text.isNotBlank()) {
            for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
                try {
                    val i = Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(pkg)
                        .putExtra(Intent.EXTRA_TEXT, text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(i)
                    return "I don't have Contacts access yet, so I opened WhatsApp with your text - pick the person and press send. " +
                        "Allow Contacts for VINCE in Settings > Apps > VINCE > Permissions and I can open the right chat by name."
                } catch (e: Exception) { }
            }
        }
        return "I need Contacts permission to find people by name. Allow it in Settings > Apps > VINCE > Permissions, " +
            "or give me the number: \"whatsapp +2547... saying hello\"."
    }
}
