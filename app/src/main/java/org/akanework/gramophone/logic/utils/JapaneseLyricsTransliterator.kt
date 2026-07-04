package org.akanework.gramophone.logic.utils

import android.content.Context
import org.akanework.gramophone.R

internal class JapaneseLyricsTransliterator private constructor(
    private val dictionary: JapaneseIpadicDictionary?,
    private val kanaToRomaji: (String) -> String
) {
    fun transliterate(input: String): String {
        val dictionary = dictionary
        if (dictionary != null) {
            return dictionary.transliterate(input, kanaToRomaji)
        }
        return transliterateWithoutDictionary(input)
    }

    private fun transliterateWithoutDictionary(input: String): String {
        val out = mutableListOf<TransliteratedSegment>()
        var index = 0
        while (index < input.length) {
            val cp = input.codePointAt(index)
            when {
                cp.isHan() -> {
                    val start = index
                    while (index < input.length && input.codePointAt(index).isHan()) {
                        index += Character.charCount(input.codePointAt(index))
                    }
                    out.add(TransliteratedSegment(start, index, input.substring(start, index)))
                }
                cp.isJapaneseCodePoint() -> {
                    val start = index
                    do {
                        index += Character.charCount(input.codePointAt(index))
                    } while (index < input.length && input.codePointAt(index).isJapaneseCodePoint())
                    out.add(
                        input.transliteratedSegment(
                            start,
                            index,
                            kanaToRomajiWithJapaneseRules(
                                input.substring(start, index),
                                kanaToRomaji,
                                useParticleHeuristics = true,
                                allowParticleBeforeKana = true,
                                hasLeadingJapaneseContext = start > 0 &&
                                        input.codePointBefore(start).isJapaneseCodePoint()
                            )
                        )
                    )
                }
                else -> {
                    val start = index
                    do {
                        index += Character.charCount(input.codePointAt(index))
                    } while (index < input.length && !input.codePointAt(index).isJapaneseCodePoint())
                    out.add(TransliteratedSegment(start, index, input.substring(start, index)))
                }
            }
        }
        return out.joinTransliteratedSegments(input)
    }

    private class JapaneseIpadicDictionary(
        entries: List<Entry>
    ) {
        private val entriesByFirstCodePoint = entries.groupBy { it.surface.codePointAt(0) }

        fun transliterate(input: String, kanaToRomaji: (String) -> String): String {
            val out = mutableListOf<TransliteratedSegment>()
            var index = 0
            while (index < input.length) {
                val cp = input.codePointAt(index)
                when {
                    cp.isJapaneseCodePoint() -> {
                        val start = index
                        do {
                            index += Character.charCount(input.codePointAt(index))
                        } while (index < input.length &&
                            input.codePointAt(index).isJapaneseCodePoint())
                        out.add(
                            input.transliteratedSegment(
                                start,
                                index,
                                transliterateRun(input.substring(start, index), kanaToRomaji)
                            )
                        )
                    }
                    else -> {
                        val start = index
                        do {
                            index += Character.charCount(input.codePointAt(index))
                        } while (index < input.length && !input.codePointAt(index).isJapaneseCodePoint())
                        out.add(TransliteratedSegment(start, index, input.substring(start, index)))
                    }
                }
            }
            return out.joinTransliteratedSegments(input)
        }

        private fun transliterateRun(
            input: String,
            kanaToRomaji: (String) -> String
        ): String {
            val bestCosts = IntArray(input.length + 1) { Int.MAX_VALUE / 4 }
            val bestCandidates = arrayOfNulls<Candidate>(input.length + 1)
            bestCosts[input.length] = 0
            var index = input.length
            while (index > 0) {
                index = input.offsetByCodePoints(index, -1)
                val cp = input.codePointAt(index)
                val dictionaryCandidate =
                    bestDictionaryCandidate(input, index, bestCosts, kanaToRomaji)
                val fallback = if (cp.isKanaOrSoundMark()) {
                    val fallbackEnd = input.kanaFallbackEnd(index)
                    val kanaFallback = Candidate(
                        fallbackEnd,
                        kanaFallbackReading(input, index, fallbackEnd, kanaToRomaji),
                        KANA_FALLBACK_COST *
                                input.codePointCount(index, fallbackEnd) +
                                bestCosts[fallbackEnd],
                        isKatakanaFallback = input.isKatakanaOrSoundMarkRange(index, fallbackEnd)
                    )
                    val katakanaFallbackEnd = if (cp.isKatakanaOrSoundMark()) {
                        input.katakanaFallbackRunEnd(index)
                    } else {
                        fallbackEnd
                    }
                    if (katakanaFallbackEnd > fallbackEnd &&
                        bestCosts[katakanaFallbackEnd] < Int.MAX_VALUE / 4
                    ) {
                        val katakanaFallback = Candidate(
                            katakanaFallbackEnd,
                            kanaFallbackReading(input, index, katakanaFallbackEnd, kanaToRomaji),
                            KANA_FALLBACK_COST *
                                    input.codePointCount(index, katakanaFallbackEnd) +
                                    bestCosts[katakanaFallbackEnd],
                            isKatakanaFallback = true
                        )
                        if (katakanaFallback.cost < kanaFallback.cost ||
                            katakanaFallback.cost == kanaFallback.cost &&
                            katakanaFallback.end > kanaFallback.end
                        ) {
                            katakanaFallback
                        } else {
                            kanaFallback
                        }
                    } else {
                        kanaFallback
                    }
                } else {
                    val fallbackEnd = index + Character.charCount(cp)
                    Candidate(
                        fallbackEnd,
                        String(Character.toChars(cp)),
                        UNKNOWN_KANJI_COST + bestCosts[fallbackEnd]
                    )
                }
                val bestCandidate = if (dictionaryCandidate != null &&
                    (dictionaryCandidate.cost < fallback.cost ||
                            dictionaryCandidate.cost == fallback.cost &&
                            dictionaryCandidate.end > fallback.end)
                ) {
                    dictionaryCandidate
                } else {
                    fallback
                }
                bestCandidates[index] = bestCandidate
                bestCosts[index] = bestCandidate.cost
            }
            val out = mutableListOf<String>()
            var geminateNext = false
            var previousWasKatakanaFallback = false
            index = 0
            while (index < input.length) {
                val candidate = bestCandidates[index]
                if (candidate == null) {
                    out.addReading("_", geminateNext, joinWithPrevious = false)
                    geminateNext = false
                    previousWasKatakanaFallback = false
                    index += Character.charCount(input.codePointAt(index))
                } else {
                    out.addReading(
                        candidate.reading,
                        geminateNext,
                        joinWithPrevious = previousWasKatakanaFallback &&
                                candidate.isKatakanaFallback
                    )
                    geminateNext = candidate.geminatesNext
                    previousWasKatakanaFallback = candidate.isKatakanaFallback
                    index = candidate.end
                }
            }
            return out.joinToString(" ")
        }

        private fun bestDictionaryCandidate(
            input: String,
            index: Int,
            bestCosts: IntArray,
            kanaToRomaji: (String) -> String
        ): Candidate? {
            var bestCandidate: Candidate? = null
            entriesByFirstCodePoint[input.codePointAt(index)]?.forEach { entry ->
                if (!input.startsWith(entry.surface, index)) return@forEach
                val end = index + entry.surface.length
                if (end > input.length || bestCosts[end] >= Int.MAX_VALUE / 4) return@forEach
                if (entry.requiresFollowingKana && !input.hasKanaSuffixAfter(end)) return@forEach
                if (entry.isParticle && !input.isLikelyParticleSurface(index, end)) return@forEach
                if (entry.isSuffix && (index <= 0 || !input.codePointBefore(index).isHan())) {
                    return@forEach
                }
                val cost = entry.cost + bestCosts[end]
                val current = bestCandidate
                if (current == null || cost < current.cost ||
                    cost == current.cost && end > current.end
                ) {
                    bestCandidate = Candidate(
                        end,
                        if (entry.reading == "_") {
                            entry.reading
                        } else {
                            kanaToRomajiWithJapaneseRules(entry.reading, kanaToRomaji)
                        },
                        cost,
                        entry.geminatesNext
                    )
                }
            }
            return bestCandidate
        }

        private data class Candidate(
            val end: Int,
            val reading: String,
            val cost: Int,
            val geminatesNext: Boolean = false,
            val isKatakanaFallback: Boolean = false
        )

        private fun kanaFallbackReading(
            input: String,
            start: Int,
            end: Int,
            kanaToRomaji: (String) -> String
        ): String {
            val text = input.substring(start, end)
            return when (text) {
                "は" -> if (isLikelyParticle(input, start)) "wa" else "ha"
                "へ" -> if (isLikelyParticle(input, start)) "e" else "he"
                "を" -> "o"
                else -> kanaToRomajiWithJapaneseRules(
                    text,
                    kanaToRomaji,
                    useParticleHeuristics = true,
                    hasLeadingJapaneseContext = isLikelyParticle(input, start)
                )
            }
        }

        private fun isLikelyParticle(input: String, start: Int): Boolean {
            return start > 0 && input.codePointBefore(start).isJapaneseCodePoint()
        }

        data class Entry(
            val surface: String,
            val reading: String,
            val cost: Int,
            val requiresFollowingKana: Boolean = false,
            val isParticle: Boolean = false,
            val isSuffix: Boolean = false,
            val geminatesNext: Boolean = false
        )

        private fun String.isLikelyParticleSurface(start: Int, end: Int): Boolean {
            if (start <= 0 || !codePointBefore(start).isJapaneseCodePoint()) return false
            if (end >= length) return true
            val next = codePointAt(end)
            if (!next.isKanaOrSoundMark()) return true
            if (next.canLengthenPreviousKana()) return false
            return hasDictionaryWordAt(end)
        }

        private fun String.hasDictionaryWordAt(index: Int): Boolean {
            return entriesByFirstCodePoint[codePointAt(index)]?.any { entry ->
                !entry.isParticle && !entry.isSuffix &&
                        index + entry.surface.length <= length &&
                        startsWith(entry.surface, index) &&
                        entry.surface.codePointCount(0, entry.surface.length) > 1
            } == true
        }

        private fun String.kanaFallbackEnd(index: Int): Int {
            kanaSyllableAt(index)?.let { return it.end }
            val cp = codePointAt(index)
            val end = index + Character.charCount(cp)
            if (cp.isSmallTsu() && end < length) {
                kanaSyllableAt(end)?.let { return it.end }
            }
            return end
        }

        private fun String.isKatakanaOrSoundMarkRange(start: Int, end: Int): Boolean {
            var index = start
            while (index < end) {
                val cp = codePointAt(index)
                if (!cp.isKatakanaOrSoundMark()) return false
                index += Character.charCount(cp)
            }
            return true
        }

        private fun String.katakanaFallbackRunEnd(index: Int): Int {
            var end = index
            while (end < length && codePointAt(end).isKatakanaOrSoundMark()) {
                end += Character.charCount(codePointAt(end))
            }
            return end
        }

        private fun MutableList<String>.addReading(
            reading: String,
            geminatePrevious: Boolean,
            joinWithPrevious: Boolean
        ) {
            if (reading.isEmpty()) return
            if (geminatePrevious) {
                val gemination = reading.firstGeminationConsonant()
                if (gemination != null) {
                    val prefix = gemination.toString()
                    if (isEmpty()) {
                        add(prefix + reading)
                    } else {
                        this[lastIndex] += prefix + reading
                    }
                    return
                }
            }
            if (joinWithPrevious && isNotEmpty()) {
                this[lastIndex] += reading
            } else {
                add(reading)
            }
        }

        private fun String.hasKanaSuffixAfter(index: Int): Boolean {
            if (index >= length || !codePointAt(index).isKanaOrSoundMark()) return false
            var end = index
            var kanaCount = 0
            while (end < length && codePointAt(end).isKanaOrSoundMark()) {
                end += Character.charCount(codePointAt(end))
                kanaCount++
                if (kanaCount >= 2) return true
            }
            return !substring(index, end).isSingleKanaParticle()
        }

        private fun String.isSingleKanaParticle(): Boolean =
            this == "は" || this == "へ" || this == "を" || this == "が" ||
                    this == "に" || this == "の" || this == "で" || this == "と" ||
                    this == "も" || this == "や" || this == "か"

        companion object {
            private const val KANA_FALLBACK_COST = 12_000
            private const val UNKNOWN_KANJI_COST = 20_000

            @Volatile
            private var cachedDictionary: JapaneseIpadicDictionary? = null

            fun load(context: Context): JapaneseIpadicDictionary? {
                cachedDictionary?.let { return it }
                return synchronized(this) {
                    cachedDictionary ?: loadEntries(context)?.also { cachedDictionary = it }
                }
            }

            private fun loadEntries(context: Context): JapaneseIpadicDictionary? = runCatching {
                val entries = mutableListOf<Entry>()
                context.resources.openRawResource(R.raw.japanese_ipadic).bufferedReader()
                    .useLines { lines ->
                        lines.forEach { line ->
                            val fields = line.split('\t')
                            if (fields.size >= 3) {
                                entries.add(
                                    Entry(
                                        fields[0],
                                        fields[1],
                                        fields[2].toIntOrNull() ?: 0,
                                        requiresFollowingKana = fields.getOrNull(3) == "kanaSuffix",
                                        isParticle = fields.getOrNull(3) == "particle",
                                        isSuffix = fields.getOrNull(3) == "suffix",
                                        geminatesNext = fields.getOrNull(3) == "geminateNext"
                                    )
                                )
                            }
                        }
                    }
                JapaneseIpadicDictionary(entries)
            }.getOrNull()
        }
    }

    companion object {
        fun create(
            context: Context,
            kanaToRomaji: (String) -> String
        ): JapaneseLyricsTransliterator =
            JapaneseLyricsTransliterator(JapaneseIpadicDictionary.load(context), kanaToRomaji)
    }
}

private data class TransliteratedSegment(
    val sourceStart: Int,
    val sourceEnd: Int,
    val text: String,
    val isTransliterated: Boolean = false
)

private fun String.transliteratedSegment(
    start: Int,
    end: Int,
    text: String
): TransliteratedSegment =
    TransliteratedSegment(start, end, text, text != substring(start, end))

private fun List<TransliteratedSegment>.joinTransliteratedSegments(source: String): String {
    if (isEmpty()) return ""
    val out = StringBuilder(first().text)
    for (i in 1 until size) {
        val previous = this[i - 1]
        val current = this[i]
        when {
            source.shouldJoinWithoutInsertedSpace(previous.sourceEnd, current.sourceStart) -> Unit
            source.shouldHyphenateRomanTransliterationBoundary(previous, current) -> out.append('-')
            else -> out.append(' ')
        }
        out.append(current.text)
    }
    return out.toString()
}

private fun String.shouldJoinWithoutInsertedSpace(previousEnd: Int, nextStart: Int): Boolean {
    if (previousEnd != nextStart || previousEnd <= 0 || nextStart >= length) return false
    return codePointBefore(previousEnd).isJapaneseCodePoint() &&
            codePointAt(nextStart).isClosingPunctuation()
}

private fun String.shouldHyphenateRomanTransliterationBoundary(
    previous: TransliteratedSegment,
    current: TransliteratedSegment
): Boolean {
    if (previous.sourceEnd != current.sourceStart ||
        previous.sourceEnd <= 0 ||
        current.sourceStart >= length
    ) return false
    val previousCp = codePointBefore(previous.sourceEnd)
    val currentCp = codePointAt(current.sourceStart)
    return shouldHyphenateRomanTransliterationBoundary(
        previousCp,
        currentCp,
        previous.isTransliterated,
        current.isTransliterated
    )
}

private const val PROLONGED_SOUND_MARK = 0x30FC

private data class KanaSyllable(
    val end: Int,
    val romaji: String,
    val hasSmallKanaVowelSound: Boolean,
    val isStandaloneSmallVowel: Boolean
)

private fun kanaToRomajiWithJapaneseRules(
    text: String,
    kanaToRomaji: (String) -> String,
    useParticleHeuristics: Boolean = false,
    allowParticleBeforeKana: Boolean = false,
    hasLeadingJapaneseContext: Boolean = false
): String {
    val out = StringBuilder()
    val pendingSmallTsu = StringBuilder()
    var previousKanaCanLengthenWithVowel = false
    var index = 0
    while (index < text.length) {
        val cp = text.codePointAt(index)
        when {
            cp.isSmallTsu() -> {
                pendingSmallTsu.appendCodePoint(cp)
                previousKanaCanLengthenWithVowel = false
                index += Character.charCount(cp)
            }
            cp == PROLONGED_SOUND_MARK -> {
                if (pendingSmallTsu.isNotEmpty()) {
                    out.append(pendingSmallTsu)
                    pendingSmallTsu.setLength(0)
                }
                if (!out.extendLastRomanVowel()) {
                    out.appendCodePoint(cp)
                }
                previousKanaCanLengthenWithVowel = false
                index += Character.charCount(cp)
            }
            else -> {
                val syllable = text.kanaSyllableAt(index)
                val roman: String
                if (syllable != null) {
                    roman = if (useParticleHeuristics) {
                        text.particleRomajiAt(
                            index,
                            syllable.end,
                            allowParticleBeforeKana,
                            hasLeadingJapaneseContext
                        ) ?: syllable.romaji
                    } else {
                        syllable.romaji
                    }
                    index = syllable.end
                } else {
                    val start = index
                    do {
                        index += Character.charCount(text.codePointAt(index))
                    } while (index < text.length &&
                        !text.codePointAt(index).isSmallTsu() &&
                        text.codePointAt(index) != PROLONGED_SOUND_MARK &&
                        text.kanaSyllableAt(index) == null)
                    roman = kanaToRomaji(text.substring(start, index))
                }
                val lengtheningVowel = roman.singleLengtheningVowelOrNull()
                if (syllable != null &&
                    pendingSmallTsu.isEmpty() &&
                    lengtheningVowel != null &&
                    (previousKanaCanLengthenWithVowel || syllable.isStandaloneSmallVowel) &&
                    out.macronizeLastRomanVowelWith(lengtheningVowel)
                ) {
                    previousKanaCanLengthenWithVowel = false
                    continue
                }
                val gemination = roman.firstGeminationConsonant()
                if (pendingSmallTsu.isNotEmpty()) {
                    if (gemination != null) {
                        repeat(pendingSmallTsu.codePointCount(0, pendingSmallTsu.length)) {
                            out.append(gemination)
                        }
                    } else {
                        out.append(pendingSmallTsu)
                    }
                    pendingSmallTsu.setLength(0)
                }
                out.append(roman)
                previousKanaCanLengthenWithVowel = syllable?.hasSmallKanaVowelSound == true
            }
        }
    }
    if (pendingSmallTsu.isNotEmpty()) {
        out.append(pendingSmallTsu)
    }
    return out.toString()
}

private fun String.particleRomajiAt(
    start: Int,
    end: Int,
    allowBeforeKana: Boolean,
    hasLeadingJapaneseContext: Boolean
): String? {
    val cp = codePointAt(start)
    val hasPreviousJapanese = start > 0 && codePointBefore(start).isJapaneseCodePoint() ||
            start == 0 && hasLeadingJapaneseContext
    val hasFollowingKana = end < length &&
            codePointAt(end).isKanaOrSoundMark() &&
            !codePointAt(end).canLengthenPreviousKana() &&
            !allowBeforeKana
    return when {
        !hasPreviousJapanese || hasFollowingKana -> null
        cp == 0x306F -> "wa"
        cp == 0x3078 -> "e"
        else -> null
    }
}

private fun String.kanaSyllableAt(index: Int): KanaSyllable? {
    if (index >= length) return null
    val cp = codePointAt(index)
    val charLength = Character.charCount(cp)
    if (!cp.isKana()) return null
    if (index + charLength < length) {
        val nextCp = codePointAt(index + charLength)
        val nextLength = Character.charCount(nextCp)
        val key = substring(index, index + charLength + nextLength).toHiraganaKey()
        KANA_DIGRAPHS[key]?.let {
            return KanaSyllable(
                index + charLength + nextLength,
                it,
                hasSmallKanaVowelSound = key.hasSmallKanaVowelSound(),
                isStandaloneSmallVowel = false
            )
        }
    }
    val key = substring(index, index + charLength).toHiraganaKey()
    return KANA_MONOGRAPHS[key]
        ?.let {
            KanaSyllable(
                index + charLength,
                it,
                hasSmallKanaVowelSound = key.hasSmallKanaVowelSound(),
                isStandaloneSmallVowel = key.isStandaloneSmallVowel()
            )
        }
}

private fun String.toHiraganaKey(): String {
    val out = StringBuilder()
    var index = 0
    while (index < length) {
        val cp = codePointAt(index)
        out.appendCodePoint(if (cp in 0x30A1..0x30F6) cp - 0x60 else cp)
        index += Character.charCount(cp)
    }
    return out.toString()
}

private fun StringBuilder.lastRomanVowel(): Char? {
    for (i in length - 1 downTo 0) {
        val c = this[i].lowercaseChar()
        val plain = c.unmacronizedVowel()
        if (plain != null) return plain
        if (c.isLetter()) return null
    }
    return null
}

private fun StringBuilder.replaceLastRomanVowelWithMacron(): Boolean {
    for (i in length - 1 downTo 0) {
        val c = this[i].lowercaseChar()
        c.macronizedVowel()?.let {
            setCharAt(i, it)
            return true
        }
        if (c.unmacronizedVowel() != null) return true
        if (c.isLetter()) return false
    }
    return false
}

private fun Char.macronizedVowel(): Char? =
    when (this) {
        'a' -> 'ā'
        'i' -> 'ī'
        'u' -> 'ū'
        'e' -> 'ē'
        'o' -> 'ō'
        else -> null
    }

private fun Char.unmacronizedVowel(): Char? =
    when (this) {
        'a', 'ā' -> 'a'
        'i', 'ī' -> 'i'
        'u', 'ū' -> 'u'
        'e', 'ē' -> 'e'
        'o', 'ō' -> 'o'
        else -> null
    }

private val KANA_MONOGRAPHS = mapOf(
    "あ" to "a",
    "い" to "i",
    "う" to "u",
    "え" to "e",
    "お" to "o",
    "ぁ" to "a",
    "ぃ" to "i",
    "ぅ" to "u",
    "ぇ" to "e",
    "ぉ" to "o",
    "か" to "ka",
    "き" to "ki",
    "く" to "ku",
    "け" to "ke",
    "こ" to "ko",
    "が" to "ga",
    "ぎ" to "gi",
    "ぐ" to "gu",
    "げ" to "ge",
    "ご" to "go",
    "さ" to "sa",
    "し" to "shi",
    "す" to "su",
    "せ" to "se",
    "そ" to "so",
    "ざ" to "za",
    "じ" to "ji",
    "ず" to "zu",
    "ぜ" to "ze",
    "ぞ" to "zo",
    "た" to "ta",
    "ち" to "chi",
    "つ" to "tsu",
    "て" to "te",
    "と" to "to",
    "だ" to "da",
    "ぢ" to "ji",
    "づ" to "zu",
    "で" to "de",
    "ど" to "do",
    "な" to "na",
    "に" to "ni",
    "ぬ" to "nu",
    "ね" to "ne",
    "の" to "no",
    "は" to "ha",
    "ひ" to "hi",
    "ふ" to "fu",
    "へ" to "he",
    "ほ" to "ho",
    "ば" to "ba",
    "び" to "bi",
    "ぶ" to "bu",
    "べ" to "be",
    "ぼ" to "bo",
    "ぱ" to "pa",
    "ぴ" to "pi",
    "ぷ" to "pu",
    "ぺ" to "pe",
    "ぽ" to "po",
    "ま" to "ma",
    "み" to "mi",
    "む" to "mu",
    "め" to "me",
    "も" to "mo",
    "や" to "ya",
    "ゆ" to "yu",
    "よ" to "yo",
    "ゃ" to "ya",
    "ゅ" to "yu",
    "ょ" to "yo",
    "ら" to "ra",
    "り" to "ri",
    "る" to "ru",
    "れ" to "re",
    "ろ" to "ro",
    "わ" to "wa",
    "ゐ" to "wi",
    "ゑ" to "we",
    "を" to "o",
    "ん" to "n",
    "ゔ" to "vu"
)

private val KANA_DIGRAPHS = mapOf(
    "きゃ" to "kya",
    "きゅ" to "kyu",
    "きょ" to "kyo",
    "ぎゃ" to "gya",
    "ぎゅ" to "gyu",
    "ぎょ" to "gyo",
    "しゃ" to "sha",
    "しゅ" to "shu",
    "しょ" to "sho",
    "しぇ" to "she",
    "じゃ" to "ja",
    "じゅ" to "ju",
    "じょ" to "jo",
    "じぇ" to "je",
    "ちゃ" to "cha",
    "ちゅ" to "chu",
    "ちょ" to "cho",
    "ちぇ" to "che",
    "ぢゃ" to "ja",
    "ぢゅ" to "ju",
    "ぢょ" to "jo",
    "にゃ" to "nya",
    "にゅ" to "nyu",
    "にょ" to "nyo",
    "ひゃ" to "hya",
    "ひゅ" to "hyu",
    "ひょ" to "hyo",
    "びゃ" to "bya",
    "びゅ" to "byu",
    "びょ" to "byo",
    "ぴゃ" to "pya",
    "ぴゅ" to "pyu",
    "ぴょ" to "pyo",
    "みゃ" to "mya",
    "みゅ" to "myu",
    "みょ" to "myo",
    "りゃ" to "rya",
    "りゅ" to "ryu",
    "りょ" to "ryo",
    "ふぁ" to "fa",
    "ふぃ" to "fi",
    "ふぇ" to "fe",
    "ふぉ" to "fo",
    "ふゅ" to "fyu",
    "てぃ" to "ti",
    "でぃ" to "di",
    "とぅ" to "tu",
    "どぅ" to "du",
    "うぃ" to "wi",
    "うぇ" to "we",
    "うぉ" to "wo",
    "つぁ" to "tsa",
    "つぃ" to "tsi",
    "つぇ" to "tse",
    "つぉ" to "tso",
    "ゔぁ" to "va",
    "ゔぃ" to "vi",
    "ゔぇ" to "ve",
    "ゔぉ" to "vo",
    "ゔゅ" to "vyu"
)

private fun StringBuilder.extendLastRomanVowel(): Boolean {
    if (replaceLastRomanVowelWithMacron()) return true
    val vowel = lastRomanVowel() ?: return false
    append(vowel)
    return true
}

private fun String.firstGeminationConsonant(): Char? {
    val first = firstOrNull { it.isLetter() }?.lowercaseChar() ?: return null
    return first.takeIf { it !in "aeioun" && it in 'a'..'z' }
}

private fun String.singleLengtheningVowelOrNull(): Char? =
    singleOrNull()?.lowercaseChar()?.takeIf { it in "aiueo" }

private fun StringBuilder.macronizeLastRomanVowelWith(vowel: Char): Boolean {
    for (i in length - 1 downTo 0) {
        val c = this[i].lowercaseChar()
        val plain = c.unmacronizedVowel()
        if (plain != null) {
            if (!plain.canLengthenWith(vowel)) return false
            plain.macronizedVowel()?.let { setCharAt(i, it) }
            return true
        }
        if (c.isLetter()) return false
    }
    return false
}

private fun Char.canLengthenWith(vowel: Char): Boolean =
    this == vowel || this == 'o' && vowel == 'u'

private fun String.hasSmallKanaVowelSound(): Boolean {
    var index = 0
    while (index < length) {
        val cp = codePointAt(index)
        if (cp.isSmallKanaVowelSound()) return true
        index += Character.charCount(cp)
    }
    return false
}

private fun String.isStandaloneSmallVowel(): Boolean =
    codePointCount(0, length) == 1 && codePointAt(0).isSmallKanaVowel()

private fun Int.isSmallTsu(): Boolean =
    this == 0x3063 || this == 0x30C3

private fun Int.canLengthenPreviousKana(): Boolean =
    this == PROLONGED_SOUND_MARK || toHiraganaCodePoint().isSmallKanaVowel()

private fun Int.isSmallKanaVowelSound(): Boolean =
    when (toHiraganaCodePoint()) {
        0x3041, 0x3043, 0x3045, 0x3047, 0x3049,
        0x3083, 0x3085, 0x3087 -> true
        else -> false
    }

private fun Int.isSmallKanaVowel(): Boolean =
    when (toHiraganaCodePoint()) {
        0x3041, 0x3043, 0x3045, 0x3047, 0x3049 -> true
        else -> false
    }

private fun Int.toHiraganaCodePoint(): Int =
    if (this in 0x30A1..0x30F6) this - 0x60 else this

private fun Int.isJapaneseCodePoint(): Boolean =
    isHan() || isKanaOrSoundMark()

private fun Int.isHan(): Boolean =
    Character.UnicodeScript.of(this) == Character.UnicodeScript.HAN

private fun Int.isKana(): Boolean {
    val script = Character.UnicodeScript.of(this)
    return script == Character.UnicodeScript.HIRAGANA ||
            script == Character.UnicodeScript.KATAKANA
}

private fun Int.isKatakanaOrSoundMark(): Boolean =
    Character.UnicodeScript.of(this) == Character.UnicodeScript.KATAKANA ||
            this == PROLONGED_SOUND_MARK

private fun Int.isKanaOrSoundMark(): Boolean =
    isKana() || this == PROLONGED_SOUND_MARK

private fun Int.isClosingPunctuation(): Boolean =
    Character.getType(this) == Character.END_PUNCTUATION.toInt() ||
            Character.getType(this) == Character.FINAL_QUOTE_PUNCTUATION.toInt()
