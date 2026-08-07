package com.glassify.launcher.data

import android.content.pm.ApplicationInfo

/**
 * Decides which App Library shelf an app belongs on.
 *
 * Order matters: the platform's own category is trusted first when the developer
 * bothered to declare one, then a keyword match on the package name, and only
 * then [AppCategory.OTHER]. The keyword table is deliberately matched against
 * the *package* rather than the display label, because labels are localised —
 * on a Hebrew device half the labels would stop matching.
 */
object AppCategorizer {

    fun categorize(packageName: String, applicationInfo: ApplicationInfo?): AppCategory {
        fromPlatform(applicationInfo)?.let { return it }
        val lower = packageName.lowercase()
        for ((category, keywords) in KEYWORDS) {
            if (keywords.any { lower.contains(it) }) return category
        }
        return AppCategory.OTHER
    }

    private fun fromPlatform(info: ApplicationInfo?): AppCategory? = when (info?.category) {
        ApplicationInfo.CATEGORY_GAME -> AppCategory.GAMES
        ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO -> AppCategory.ENTERTAINMENT
        ApplicationInfo.CATEGORY_IMAGE -> AppCategory.CREATIVITY
        ApplicationInfo.CATEGORY_SOCIAL -> AppCategory.SOCIAL
        ApplicationInfo.CATEGORY_NEWS -> AppCategory.INFORMATION
        ApplicationInfo.CATEGORY_MAPS -> AppCategory.TRAVEL
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> AppCategory.PRODUCTIVITY
        ApplicationInfo.CATEGORY_ACCESSIBILITY -> AppCategory.UTILITIES
        else -> null
    }

    /**
     * Checked in declaration order, so put the specific before the general —
     * "wallet" must be tested before "pay" catches Google Pay's package, and
     * banking apps before the generic finance terms.
     */
    private val KEYWORDS: List<Pair<AppCategory, List<String>>> = listOf(
        AppCategory.SOCIAL to listOf(
            "whatsapp", "telegram", "signal", "messenger", "facebook", "instagram",
            "snapchat", "tiktok", "twitter", "x.android", "discord", "reddit",
            "linkedin", "threads", "mastodon", "viber", "wechat", "line.android",
            "dialer", "contacts", "messaging", "mms", "sms",
        ),
        AppCategory.ENTERTAINMENT to listOf(
            "youtube", "netflix", "spotify", "deezer", "soundcloud", "twitch",
            "disney", "primevideo", "hbo", "plex", "vlc", "music", "podcast",
            "audible", "kodi", "video", "player",
        ),
        AppCategory.CREATIVITY to listOf(
            "camera", "gallery", "photos", "lightroom", "snapseed", "picsart",
            "canva", "capcut", "gopro", "adobe", "figma", "procreate", "editor",
        ),
        AppCategory.PRODUCTIVITY to listOf(
            "gmail", "outlook", "mail", "calendar", "docs", "sheets", "slides",
            "office", "word", "excel", "powerpoint", "notion", "evernote",
            "keep", "todoist", "slack", "teams", "zoom", "drive", "dropbox",
            "onedrive", "notes", "tasks", "obsidian",
        ),
        AppCategory.FINANCE to listOf(
            "wallet", "bank", "leumi", "hapoalim", "discount", "mizrahi", "bit.",
            "paybox", "pepper", "isracard", "max.", "cal.", "paypal", "revolut",
            "coinbase", "binance", "stocks", "trading", "invest", "pay",
        ),
        AppCategory.SHOPPING to listOf(
            "amazon", "ebay", "aliexpress", "shein", "temu", "shop", "store",
            "wolt", "10bis", "tenbis", "market", "ikea", "zara",
        ),
        AppCategory.TRAVEL to listOf(
            "maps", "waze", "moovit", "uber", "lyft", "gett", "bolt", "booking",
            "airbnb", "expedia", "skyscanner", "flight", "airline", "train",
            "navigation", "transit",
        ),
        AppCategory.HEALTH to listOf(
            "health", "fit", "strava", "runtastic", "calm", "headspace",
            "clalit", "maccabi", "meuhedet", "leumit", "medical", "sleep",
            "workout", "nike", "garmin", "samsunghealth",
        ),
        AppCategory.INFORMATION to listOf(
            "news", "weather", "ynet", "walla", "mako", "haaretz", "calcalist",
            "bbc", "cnn", "reuters", "feedly", "wikipedia", "browser", "chrome",
            "firefox", "opera", "brave", "edge", "search", "google.android.googlequicksearchbox",
        ),
        AppCategory.GAMES to listOf(
            "game", "puzzle", "minecraft", "roblox", "candy", "clash", "pubg",
            "fortnite", "chess", "sudoku", "solitaire",
        ),
        AppCategory.UTILITIES to listOf(
            "settings", "clock", "calculator", "files", "filemanager", "torch",
            "flashlight", "scanner", "vpn", "authenticator", "backup", "cleaner",
            "recorder", "compass", "print", "bluetooth", "smartthings", "widget",
        ),
    )
}
