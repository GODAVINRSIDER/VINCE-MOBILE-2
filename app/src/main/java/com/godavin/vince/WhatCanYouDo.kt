package com.godavin.vince

/**
 * v2.7 - "what can you do" answered instantly, by lane. Keep it TRUTHFUL: update it whenever
 * a capability is added or removed (same rule as BrainRouter's capabilities text).
 */
object WhatCanYouDo {
    private val ASK = Regex(
        "^(?:please\\s+)?(?:hey\\s+)?(?:what can you do|what do you do|what are you (?:able|capable) (?:of|to do)|" +
            "what are your (?:abilities|capabilities|features)|your (?:abilities|capabilities|features)|" +
            "capabilities|show (?:me )?(?:your )?(?:abilities|capabilities|features|menu)|how can you help(?: me)?|help me out|help)[?.! ]*$"
    )

    fun handle(text: String): String? {
        if (!ASK.matches(text.lowercase().trim())) return null
        return """Here's what I can do. Just say it, or ask me anything else.

VINCE - builder and strategist
Chart reads from your camera or screen with a trade idea card. Economic calendar, daily briefing, session and news alerts. Your trading rules and today's plan. Research any topic ("research ...") with live web search. Help planning and debugging your own tools and projects.

CLARA - life, brand and people
Drafts for texts, WhatsApp and posts. Ideas for your brand and your store. Journal check-ins, talking things through, and honest nudges.

DAVINA - planner and creative
Plans and focus. Brainstorming, naming, and arguing the other side of an idea. Explaining anything simply and quizzing you on it.

On your phone
Reminders and timers, "remember that ...", open apps, flashlight, volume, music, battery, Wi-Fi and Bluetooth pages, call or message people (you press send). "Where am I" gives your exact address and coordinates. "Where have I been" lists places from the last 48 hours. "Navigate to ..." opens Google Maps. With Phone control on I can read and operate the screen after your yes.

On my own
Morning, midday and evening check-ins from VINCE, DAVINA and CLARA (Settings > Proactive check-ins). Calls to PC-VINCE.

Ask me for an opinion any time. I'll give you one."""
    }
}
