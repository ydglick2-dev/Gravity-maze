package com.gravity.phonefinder

/**
 * Decides whether a piece of text — a speech recognition result or the body of an
 * incoming chat notification — is the "find my phone" command or the "stop" command.
 *
 * Both trigger paths (voice and notification) go through here so the two can never
 * drift apart.
 */
object TriggerMatcher {

    /**
     * The vocabularies are written the way the words are spelled and folded through
     * [normalize] here, so they compare against normalized input.
     */

    /** Words that express the intent to locate the phone. */
    private val FIND_WORDS = folded("מצא", "תמצא", "איפה", "חפש", "תחפש", "צלצל", "תצלצל")

    /** Words naming the device. */
    private val DEVICE_WORDS =
        folded("טלפון", "פלאפון", "פלפון", "סלולרי", "נייד", "מכשיר", "מובייל")

    /** Phrases that silence a running alarm. */
    private val STOP_PHRASES =
        folded("עצור", "תעצור", "תפסיק", "הפסק", "מצאתי", "די", "שקט", "תשתוק")

    /** English fallbacks, in case the recognizer is set to a non-Hebrew locale. */
    private val FIND_WORDS_EN = listOf("find", "where", "ring")
    private val DEVICE_WORDS_EN = listOf("phone", "mobile", "device")
    private val STOP_PHRASES_EN = listOf("stop", "found it", "quiet")

    private fun folded(vararg words: String): List<String> = words.map { normalize(it) }

    /**
     * Folds Hebrew text into a shape that compares reliably: final letter forms are
     * unified with their regular forms, niqqud and punctuation are dropped, and runs
     * of whitespace collapse to a single space.
     */
    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val sb = StringBuilder(raw.length)
        for (ch in raw.lowercase()) {
            val folded = when (ch) {
                'ם' -> 'מ'
                'ן' -> 'נ'
                'ך' -> 'כ'
                'ף' -> 'פ'
                'ץ' -> 'צ'
                else -> ch
            }
            when {
                // Hebrew niqqud, cantillation and the geresh/gershayim marks.
                folded in '֑'..'ׇ' -> {}
                folded == '׳' || folded == '״' -> {}
                folded.isLetterOrDigit() -> sb.append(folded)
                else -> sb.append(' ')
            }
        }
        return sb.toString().trim().replace(Regex("\\s+"), " ")
    }

    /** True when [raw] asks the phone to make itself heard. */
    fun isFindCommand(raw: String?): Boolean {
        val text = normalize(raw)
        if (text.isEmpty()) return false
        // "מצאתי" is a stop word, not a find word — don't let it satisfy "מצא".
        if (containsAny(text, STOP_PHRASES)) return false
        val hebrew = containsAny(text, FIND_WORDS) && containsAny(text, DEVICE_WORDS)
        val english = containsAny(text, FIND_WORDS_EN) && containsAny(text, DEVICE_WORDS_EN)
        return hebrew || english
    }

    /** True when [raw] asks a sounding alarm to stop. */
    fun isStopCommand(raw: String?): Boolean {
        val text = normalize(raw)
        if (text.isEmpty()) return false
        return containsAny(text, STOP_PHRASES) || containsAny(text, STOP_PHRASES_EN)
    }

    /**
     * Word-boundary aware search. Hebrew has no casing and glues prefixes such as
     * ה/ו/ל onto the next word, so a prefix of up to two letters before the needle
     * is accepted ("הטלפון", "ולטלפון") while a longer suffix is not ("מצאתי").
     */
    private fun containsAny(text: String, needles: List<String>): Boolean =
        needles.any { needle -> matches(text, needle) }

    private fun matches(text: String, needle: String): Boolean {
        if (needle.contains(' ')) return " $text ".contains(" $needle ")
        for (word in text.split(' ')) {
            if (word == needle) return true
            if (word.length > needle.length && word.endsWith(needle) &&
                word.length - needle.length <= 2
            ) {
                return true
            }
        }
        return false
    }
}
