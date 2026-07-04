package org.akanework.gramophone

import org.akanework.gramophone.logic.utils.JapaneseLyricsTransliterator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class JapaneseLyricsTransliteratorTest {
    private val transliterator by lazy {
        JapaneseLyricsTransliterator.create(RuntimeEnvironment.getApplication()) { it }
    }

    @Test
    fun casesButShouldNotBeHardCodedDirectPair() {
        val pairs = arrayOf(
            DirectCase(true, "アー", "ā"),
            DirectCase(true, "イー", "ī"),
            DirectCase(true, "ウー", "ū"),
            DirectCase(true, "エー", "ē"),
            DirectCase(true, "オー", "ō"),
            DirectCase(false, "ア一", "ā"),
            DirectCase(false, "イ一", "ī"),
            DirectCase(false, "ウ一", "ū"),
            DirectCase(false, "エ一", "ē"),
            DirectCase(false, "オ一", "ō"),
            DirectCase(true, "純情", "junjō"),
            DirectCase(true, "感情", "kanjō"),
            DirectCase(true, "黙って", "damatte"),
            DirectCase(true, "カタチ", "katachi"),
        )

        pairs.forEach { case ->
            val actual = transliterator.transliterate(case.input).forComparison(
                case.removeOutputSpaces
            )
            val expected = case.expected.forComparison(case.removeOutputSpaces)
            if (case.shouldMatch) {
                assertEquals("input=${case.input}", expected, actual)
            } else {
                assertNotEquals("input=${case.input}", expected, actual)
            }
        }
    }

    @Test
    fun casesButShouldNotBeHardCodedFindSubstring() {
        // [2] is for reference which part needs to be transliterated, it not a must for tl([2]) == [3]

        val pairs = arrayOf(
            SubstringCase(true, "今できることはあなたの幸せを", "は", "wa"),
            SubstringCase(false, "幾つの愛と罰とで君へと辿り着けるの", "罰とで", "ba_tode", true),
            SubstringCase(true, "感情なんて簡単に操って", "操って", "ayatsutte", true),
            SubstringCase(true, "泣き虫な過去に少し笑って見せた", "笑って", "waratte", true),
            SubstringCase(true, "ありふれた一日が", "一日", "ichinichi", true),
            SubstringCase(true, "そうやって何度でも", "何度", "nando", true),
            SubstringCase(true, "心に空いた穴に泣いた", "心に空いた", "kokoroniaita", true),
            SubstringCase(true, "幸せがどこにあるか探した", "探した", "sagashita", true),
            SubstringCase(true, "僕らはみな異邦人", "人", "jin"),
            SubstringCase(true, "越えてゆけると言っていたのに", "越えて", "koete", true),
            SubstringCase(true, "この恋が教えてくれた", "教えて", "oshiete", true),
            SubstringCase(true, "あなた呼ぶ私は一人", "私は一人", "watashiwahitori", true),
            // SubstringCase(true, "春風に最後のうたを", "春風", "harukaze", true),
            SubstringCase(true, "まっすぐな一本道を", "一本道を", "ipponmichi", true),
            SubstringCase(true, "奏で合うのは", "奏で合うのは", "kanadeaunowa", true),
            SubstringCase(true, "優しい顔で言うのは", "で言うのは", "deiunowa", true),
            // SubstringCase(true, "ここはみんな通った通過点だ", "通った", "totta", true),
            SubstringCase(true, "ここはみんな通った通過点だ", "点だ", "tenda", true),
        )

        pairs.forEach { case ->
            assertTrue(
                "missing source substring: ${case.sourceSubstring}",
                case.line.contains(case.sourceSubstring)
            )
            val actual = transliterator.transliterate(case.line).forComparison(
                case.removeOutputSpaces
            )
            val expected = case.expected.forComparison(case.removeOutputSpaces)
            val regex = Regex(".*${Regex.escape(expected)}.*")
            if (case.shouldMatch) {
                assertTrue(
                    "input=${case.line} target=${case.sourceSubstring} actual=$actual",
                    regex.matches(actual)
                )
            } else {
                assertFalse(
                    "input=${case.line} target=${case.sourceSubstring} actual=$actual",
                    regex.matches(actual)
                )
            }
        }
    }

    private fun String.forComparison(removeOutputSpaces: Boolean): String =
        if (removeOutputSpaces) replace(" ", "") else this

    private data class DirectCase(
        val shouldMatch: Boolean,
        val input: String,
        val expected: String,
        val removeOutputSpaces: Boolean = false
    )

    private data class SubstringCase(
        val shouldMatch: Boolean,
        val line: String,
        val sourceSubstring: String,
        val expected: String,
        val removeOutputSpaces: Boolean = false
    )
}
