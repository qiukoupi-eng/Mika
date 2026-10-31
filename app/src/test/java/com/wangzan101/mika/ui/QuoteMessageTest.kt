package com.wangzan101.mika.ui

import com.wangzan101.mika.core.agent.Conversation
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.i18n.ChineseTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 引用前面的消息:写进话里给模型的样子、截断、拆回来,旧的话照原样;回答复制成纯文字。 */
class QuoteMessageTest {
    @get:Rule val texts = ChineseTexts()

    private val mika = QuoteMessage.Quote(QuoteMessage.From.Mika, "明天上午有小雨\n下午转晴")

    @Test fun theModelSeesWhoIsQuotedAndWhat() {
        assertEquals(
            "> [Quoting your earlier reply]\n> 明天上午有小雨\n> 下午转晴\n\n那要带伞吗",
            QuoteMessage.compose(mika, "那要带伞吗"),
        )
        assertEquals(
            "> [Quoting my earlier message]\n> 帮我查明天的天气\n\n换成后天",
            QuoteMessage.compose(QuoteMessage.Quote(QuoteMessage.From.You, "帮我查明天的天气"), "换成后天"),
        )
        // 引的内容里的空行写成单独一个「>」,整块仍连在一起
        assertEquals(
            "> [Quoting your earlier reply]\n> 第一段\n>\n> 第二段\n\n继续",
            QuoteMessage.compose(QuoteMessage.Quote(QuoteMessage.From.Mika, "第一段\n\n第二段"), "继续"),
        )
    }

    @Test fun noQuoteLeavesTheWordsAlone() {
        assertEquals("查天气", QuoteMessage.compose(null, "查天气"))
        assertEquals("查天气", QuoteMessage.compose(QuoteMessage.Quote(QuoteMessage.From.Mika, "  "), "查天气"))
    }

    @Test fun longQuotesAreClipped() {
        val long = "字".repeat(QuoteMessage.MAX_CHARS + 50)
        val clipped = QuoteMessage.clip(long)
        assertEquals(QuoteMessage.MAX_CHARS + 1, clipped.length)
        assertTrue(clipped.endsWith("…"))
        assertEquals("短的", QuoteMessage.clip("  短的 \n"))
        val sent = QuoteMessage.compose(QuoteMessage.Quote(QuoteMessage.From.Mika, long), "这段太长了")
        assertEquals(clipped, QuoteMessage.parse(sent).quote?.text)
    }

    @Test fun parsesBackWhatWasComposed() {
        val p = QuoteMessage.parse(QuoteMessage.compose(mika, "那要带伞吗"))
        assertEquals(mika, p.quote)
        assertEquals("那要带伞吗", p.words)
        val blank = QuoteMessage.parse(QuoteMessage.compose(QuoteMessage.Quote(QuoteMessage.From.You, "a\n\nb"), "c"))
        assertEquals("a\n\nb", blank.quote?.text)
        assertEquals("c", blank.words)
    }

    // 旧对话里的话没有引用那一行:原样显示;以「>」开头的普通话也不当成引用
    @Test fun oldMessagesHaveNoQuote() {
        assertNull(QuoteMessage.parse("查天气").quote)
        assertEquals("查天气", QuoteMessage.parse("查天气").words)
        assertNull(QuoteMessage.parse("> 这句是我自己写的\n后面").quote)
        assertEquals("> 这句是我自己写的\n后面", QuoteMessage.words("> 这句是我自己写的\n后面"))
    }

    // 引用加附件:附件行接在话后面,拆的时候各归各
    @Test fun quotesAndAttachmentsTogether() {
        val goal = ChatViewModel.withAttachments(QuoteMessage.compose(mika, "照这个改"), listOf("/workspace/inbox/a.pdf" to 2048L))
        val q = QuoteMessage.parse(goal)
        assertEquals(mika, q.quote)
        val a = AttachmentMessage.parse(q.words)
        assertEquals("照这个改", a.words)
        assertEquals(listOf("a.pdf"), a.files.map { it.name })
        // 只有引用和附件、没写话
        val only = QuoteMessage.parse(ChatViewModel.withAttachments(QuoteMessage.compose(mika, ""), listOf("/workspace/inbox/a.pdf" to 2048L)))
        assertEquals(mika, only.quote)
        assertEquals("a.pdf", AttachmentMessage.plain(only.words))
    }

    // 标题、通知里称呼这句话时不带引用
    @Test fun titlesSkipTheQuote() {
        val goal = QuoteMessage.compose(mika, "那要带伞吗")
        assertEquals("那要带伞吗", ChatCopy.plainTitle(goal))
        assertEquals("那要带伞吗", ScreenCopy.plain(goal))
    }

    // 存下的轮次原样带着引用;给模型的上文里也看得出引的是什么
    @Test fun storedTurnsKeepTheQuote() {
        val goal = QuoteMessage.compose(mika, "那要带伞吗")
        val context = (Conversation.context(listOf(Turn(goal, "要带", "succeeded")))!!.parts.single() as Part.Text).text
        assertTrue(context.contains("[Quoting your earlier reply]"))
        assertTrue(context.contains("明天上午有小雨"))
    }

    @Test fun answersCopyAsPlainText() {
        assertEquals(
            "上海今天晴,详见 天气\n\n要点\n\n• 早上 12 度\n• 下午 20 度\n\n1. 带伞\n2. 穿外套\n\n> 注意降温\n\nval x = 1\n\n城市\t天气\n上海\t晴",
            MarkdownModel.toPlainText(
                "上海今天**晴**,详见 [天气](https://example.com)\n\n## 要点\n\n- 早上 12 度\n- 下午 *20* 度\n\n1. 带伞\n2. 穿外套\n\n> 注意降温\n\n" +
                    "```kotlin\nval x = 1\n```\n\n---\n\n| 城市 | 天气 |\n| --- | --- |\n| 上海 | 晴 |",
            ),
        )
        assertEquals("", MarkdownModel.toPlainText(""))
    }
}
