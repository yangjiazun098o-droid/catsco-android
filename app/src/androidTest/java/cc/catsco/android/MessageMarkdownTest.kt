package cc.catsco.android

import cc.catsco.android.ui.normalizeMessageMarkdown
import cc.catsco.android.ui.tableColumnWidths
import cc.catsco.android.ui.readableMessageSpans
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.commonmark.parser.Parser
import org.commonmark.node.StrongEmphasis

class MessageMarkdownTest {
    @Test fun realMarkwonEmphasisUsesTheSameReadingWeightRule() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val renderer = io.noties.markwon.Markwon.create(context)
        val source = renderer.toMarkdown("**短重点**，**${"较长的一段文字".repeat(20)}**")
        val result = readableMessageSpans(source, androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext) as android.text.Spanned
        assertEquals(source.toString(), result.toString())
        assertEquals(1, result.getSpans(0, result.length, io.noties.markwon.core.spans.StrongEmphasisSpan::class.java).size)
        assertEquals(2, result.getSpans(0, result.length, android.text.style.TypefaceSpan::class.java).size)
    }
    @Test fun compactTableKeepsDatesReadableAndLongNamesWithinViewport() {
        val widths = tableColumnWidths(listOf(280f, 110f), 300f)
        assertEquals(110f, widths[1], .1f)
        assertEquals(300f, widths.sum(), .1f)
        assertTrue(widths[0] >= 128f)
        assertEquals(listOf(240f, 240f, 240f), tableColumnWidths(listOf(240f, 240f, 240f), 300f))
    }

    @Test fun longEmphasisUsesMediumWeightWithoutLosingTextOrShortHighlights() {
        val source = android.text.SpannableStringBuilder("重点 " + "较长的一段文字".repeat(20))
        source.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, 2, 33)
        source.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 3, source.length, 33)
        val result = readableMessageSpans(source, androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext) as android.text.Spanned
        assertEquals(source.toString(), result.toString())
        assertEquals(1, result.getSpans(0, result.length, android.text.style.StyleSpan::class.java).size)
        assertEquals(2, result.getSpans(0, result.length, android.text.style.TypefaceSpan::class.java).size)
        assertEquals(2, source.getSpans(0, source.length, android.text.style.StyleSpan::class.java).size)
    }
    @Test fun formatsLegacyChineseBoldWithoutChangingStoredText() {
        val raw = "先说结论：** 「示例公司」属于关联主体。**后续内容。"
        val normalized = normalizeMessageMarkdown(raw)
        assertEquals("先说结论：「**示例公司」属于关联主体**。后续内容。", normalized)
        val paragraph = Parser.builder().build().parse(normalized).firstChild
        assertTrue(paragraph.firstChild.next is StrongEmphasis)
        assertEquals(normalized, normalizeMessageMarkdown(raw.replace("** 「", "**「")))
        assertEquals("**中文\n内容**。", normalizeMessageMarkdown("**\u00a0中文\n内容。**"))
    }

    @Test fun codeEscapesAndMathKeepTheirLiteralCharacters() {
        listOf("`** 中文 **`", "``** 中文 **``", "```text\n** 中文 **\n```", "~~~\n** 中文 **\n~~~", "\\** 中文 **", "2 ** 3 ** 2").forEach { raw ->
            assertEquals(raw, raw, normalizeMessageMarkdown(raw))
        }
    }
}
