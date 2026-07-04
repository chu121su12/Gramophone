package org.akanework.gramophone.logic.utils

import android.content.Context
import android.os.Build
import java.lang.reflect.Method
import java.util.Enumeration
import java.util.Locale

class LyricsTransliterator private constructor(
    private val passes: List<Pass>,
    private val transliterateMethod: Method
) {
    private sealed class PassSpec {
        object Japanese : PassSpec()

        data class Icu(
            val script: Character.UnicodeScript,
            val transformIds: List<String>
        ) : PassSpec()
    }

    private data class Pass(
        val spec: PassSpec,
        val transliterator: Any,
        val japaneseTransliterator: JapaneseLyricsTransliterator?
    )

    private data class ScriptCounts(
        val counts: Map<Character.UnicodeScript, Int>,
        val kana: Int
    )

    private data class CountedPassSpec(
        val spec: PassSpec,
        val count: Int
    )

    fun transliterate(input: String): String? {
        if (input.isBlank() || !input.hasTransliterableCodePoint()) return null
        val output = passes.fold(input) { text, pass ->
            if (!text.hasPassCodePoint(pass)) {
                text
            } else {
                when (pass.spec) {
                    PassSpec.Japanese -> pass.japaneseTransliterator?.transliterate(text)
                        ?: pass.transliterateWithIcu(text)
                    else -> pass.transliterateWithIcu(text)
                }
            }
        }.replaceUnknownCodePoints().normalizeTransliteration()
        return output.takeIf { it.isNotBlank() && it != input }
    }

    private fun Pass.transliterateWithIcu(input: String): String {
        val passSpec = spec
        if (passSpec !is PassSpec.Icu) {
            return transliterateIcuRun(input)
        }
        val out = StringBuilder()
        var index = 0
        var hyphenBeforeNextRoman = false
        while (index < input.length) {
            val cp = input.codePointAt(index)
            if (Character.UnicodeScript.of(cp) == passSpec.script) {
                val start = index
                index += Character.charCount(cp)
                while (index < input.length) {
                    val nextCp = input.codePointAt(index)
                    val nextScript = Character.UnicodeScript.of(nextCp)
                    if (nextScript != passSpec.script &&
                        nextScript != Character.UnicodeScript.INHERITED
                    ) {
                        break
                    }
                    index += Character.charCount(nextCp)
                }
                val source = input.substring(start, index)
                val transliterated = transliterateIcuRun(source)
                val isTransliterated = transliterated != source
                if (start > 0 &&
                    shouldHyphenateRomanTransliterationBoundary(
                        input.codePointBefore(start),
                        input.codePointAt(start),
                        previousTransliterated = false,
                        currentTransliterated = isTransliterated
                    )
                ) {
                    out.append('-')
                }
                out.append(transliterated)
                hyphenBeforeNextRoman = isTransliterated
            } else {
                if (index > 0 &&
                    shouldHyphenateRomanTransliterationBoundary(
                        input.codePointBefore(index),
                        cp,
                        previousTransliterated = hyphenBeforeNextRoman,
                        currentTransliterated = false
                    )
                ) {
                    out.append('-')
                }
                hyphenBeforeNextRoman = false
                out.appendCodePoint(cp)
                index += Character.charCount(cp)
            }
        }
        return out.toString()
    }

    private fun Pass.transliterateIcuRun(input: String): String {
        return transliterateMethod.invoke(transliterator, input) as? String ?: input
    }

    companion object {
        private const val TRANSLITERATOR_CLASS = "android.icu.text.Transliterator"
        private val JAPANESE_KANA_TRANSFORM_ID_CHAINS = listOf(
            listOf("Hiragana-Latin", "Katakana-Latin"),
            listOf("Hiragana-Katakana", "Katakana-Latin"),
            listOf("Katakana-Latin")
        )

        fun isAvailable(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && runCatching {
                Class.forName(TRANSLITERATOR_CLASS)
            }.isSuccess

        fun create(
            context: Context,
            lines: List<String>
        ): LyricsTransliterator? {
            if (!isAvailable()) return null
            val transliteratorClass = runCatching {
                Class.forName(TRANSLITERATOR_CLASS)
            }.getOrNull() ?: return null
            val getInstance = transliteratorClass.getMethod("getInstance", String::class.java)
            val transliterate = transliteratorClass.getMethod("transliterate", String::class.java)
            val availableIds = transliteratorClass.availableIds()
            val passes = lines.scriptCounts().orderedPassSpecs(availableIds).mapNotNull { spec ->
                when (spec) {
                    PassSpec.Japanese -> {
                        val transformIds = availableIds.japaneseKanaTransformIds() ?: return@mapNotNull null
                        val transliterator = getInstance.transliteratorForIds(
                            availableIds,
                            transformIds
                        ) ?: return@mapNotNull null
                        Pass(
                            spec,
                            transliterator,
                            JapaneseLyricsTransliterator.create(context.applicationContext) { input ->
                                transliterate.invoke(transliterator, input) as? String ?: input
                            }
                        )
                    }
                    is PassSpec.Icu -> {
                        Pass(
                            spec,
                            getInstance.transliteratorForIds(
                                availableIds,
                                spec.transformIds
                            ) ?: return@mapNotNull null,
                            null
                        )
                    }
                }
            }
            return LyricsTransliterator(passes.takeIf { it.isNotEmpty() } ?: return null, transliterate)
        }

        private fun Class<*>.availableIds(): Set<String> = runCatching {
            val ids = mutableSetOf<String>()
            val enumeration = getMethod("getAvailableIDs").invoke(null) as? Enumeration<*>
                ?: return@runCatching emptySet<String>()
            while (enumeration.hasMoreElements()) {
                (enumeration.nextElement() as? String)?.let { ids.add(it) }
            }
            ids
        }.getOrDefault(emptySet())

        private fun Method.transliteratorForIds(
            availableIds: Set<String>,
            ids: List<String>
        ): Any? {
            if (!availableIds.containsAll(ids)) return null
            return runCatching {
                invoke(null, ids.joinToString("; "))
            }.getOrNull()
        }

        private fun Set<String>.japaneseKanaTransformIds(): List<String>? =
            JAPANESE_KANA_TRANSFORM_ID_CHAINS.firstOrNull { containsAll(it) }

        private fun List<String>.scriptCounts(): ScriptCounts {
            var kana = 0
            val counts = mutableMapOf<Character.UnicodeScript, Int>()
            forEach { text ->
                text.codePoints().forEach { cp ->
                    val script = Character.UnicodeScript.of(cp)
                    if (script.isCountedScript()) {
                        counts[script] = (counts[script] ?: 0) + 1
                    }
                    if (cp.isKana()) {
                        kana++
                    }
                }
            }
            return ScriptCounts(counts, kana)
        }

        private fun ScriptCounts.orderedPassSpecs(availableIds: Set<String>): List<PassSpec> {
            val strongIcuSpecs = availableIds.strongIcuSpecs()
            val icuSpecs = counts.mapNotNull { (script, count) ->
                if (script.isKanaScript()) return@mapNotNull null
                strongIcuSpecs[script]?.let {
                    CountedPassSpec(PassSpec.Icu(script, it), count)
                }
            }.sortedWith(
                compareByDescending<CountedPassSpec> { it.count }
                    .thenBy { it.spec.sortKey() }
            )
            return buildList {
                if (kana > 0) add(PassSpec.Japanese)
                addAll(icuSpecs.map { it.spec })
            }.distinctBy { it.sortKey() }
        }

        private fun Set<String>.strongIcuSpecs(): Map<Character.UnicodeScript, List<String>> {
            return Character.UnicodeScript.values().mapNotNull { script ->
                if (!script.isStrongIcuCandidate()) return@mapNotNull null
                val latinId = script.icuLatinIds().firstOrNull { it in this } ?: return@mapNotNull null
                script to listOf(latinId)
            }.toMap()
        }

        private fun PassSpec.sortKey(): String =
            when (this) {
                PassSpec.Japanese -> "Japanese"
                is PassSpec.Icu -> script.name
            }

        private fun Character.UnicodeScript.icuLatinIds(): List<String> =
            when (this) {
                Character.UnicodeScript.HANGUL -> listOf("Hangul-Latin", "Korean-Latin")
                Character.UnicodeScript.MYANMAR -> listOf("Burmese-Latin", "Myanmar-Latin")
                Character.UnicodeScript.ORIYA -> listOf("Oriya-Latin", "Odia-Latin")
                else -> listOf("${icuSourceName()}-Latin")
            }

        private fun Character.UnicodeScript.icuSourceName(): String =
            name.split('_').joinToString("-") { it.toIcuNamePart() }

        private fun String.toIcuNamePart(): String {
            val lower = lowercase(Locale.US)
            return lower.substring(0, 1).uppercase(Locale.US) + lower.substring(1)
        }

        private fun String.hasTransliterableCodePoint(): Boolean =
            codePoints().anyMatch { it.isTransliterableCodePoint() }

        private fun String.replaceUnknownCodePoints(): String {
            if (!hasTransliterableCodePoint()) return this
            val out = mutableListOf<String>()
            val passthrough = StringBuilder()
            fun flushPassthrough() {
                if (passthrough.isNotEmpty()) {
                    out.add(passthrough.toString())
                    passthrough.setLength(0)
                }
            }
            var index = 0
            while (index < length) {
                val cp = codePointAt(index)
                if (cp.isTransliterableCodePoint()) {
                    flushPassthrough()
                    out.add("_")
                } else {
                    passthrough.appendCodePoint(cp)
                }
                index += Character.charCount(cp)
            }
            flushPassthrough()
            return out.joinToString(" ")
        }

        private fun String.normalizeTransliteration(): String {
            val wideSpacePlaceholder = "\uE000"
            return replace("\u3000", wideSpacePlaceholder)
                .replace(Regex("\\s+"), " ")
                .replace(Regex(" *${Regex.escape(wideSpacePlaceholder)} *"), wideSpacePlaceholder)
                .replace(Regex("\\s+([,.;:!?])"), "$1")
                .replace(Regex("([(\"'])\\s+"), "$1")
                .trim()
                .replace(wideSpacePlaceholder, "\u3000")
        }

        private fun Int.isTransliterableCodePoint(): Boolean =
            Character.UnicodeScript.of(this).isCountedScript()

        private fun String.hasPassCodePoint(pass: Pass): Boolean =
            codePoints().anyMatch { cp ->
                when (val spec = pass.spec) {
                    PassSpec.Japanese -> cp.isHan() || cp.isKana()
                    is PassSpec.Icu -> Character.UnicodeScript.of(cp) == spec.script
                }
            }

        private fun Int.isHan(): Boolean =
            Character.UnicodeScript.of(this) == Character.UnicodeScript.HAN

        private fun Int.isKana(): Boolean {
            val script = Character.UnicodeScript.of(this)
            return script == Character.UnicodeScript.HIRAGANA ||
                    script == Character.UnicodeScript.KATAKANA
        }

        private fun Character.UnicodeScript.isKanaScript(): Boolean =
            this == Character.UnicodeScript.HIRAGANA ||
                    this == Character.UnicodeScript.KATAKANA

        private fun Character.UnicodeScript.isStrongIcuCandidate(): Boolean =
            isCountedScript() && !isKanaScript()

        private fun Character.UnicodeScript.isCountedScript(): Boolean =
            this != Character.UnicodeScript.LATIN &&
                    this != Character.UnicodeScript.COMMON &&
                    this != Character.UnicodeScript.INHERITED &&
                    this != Character.UnicodeScript.UNKNOWN
    }
}

internal fun shouldHyphenateRomanTransliterationBoundary(
    previousCodePoint: Int,
    currentCodePoint: Int,
    previousTransliterated: Boolean,
    currentTransliterated: Boolean
): Boolean =
    previousCodePoint.isRomanLetter() && currentTransliterated ||
            previousTransliterated && currentCodePoint.isRomanLetter()

private fun Int.isRomanLetter(): Boolean =
    Character.isLetter(this) &&
            Character.UnicodeScript.of(this) == Character.UnicodeScript.LATIN
