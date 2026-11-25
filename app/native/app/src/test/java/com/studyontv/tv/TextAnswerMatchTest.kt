package com.studyontv.tv

import com.studyontv.tv.LearningEngine.Companion.collapseCjkSpaces
import com.studyontv.tv.LearningEngine.Companion.matchesTextAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ST-606：语音输入 / 简答填空题判分排除标点符号回归测试。
 */
class TextAnswerMatchTest {

    @Test
    fun matchesChineseVoiceInputWithPunctuation() {
        assertTrue(matchesTextAnswer("床前明月光。", "床前明月光"))
        assertTrue(matchesTextAnswer("床前，明月光！", "床前明月光"))
        assertTrue(matchesTextAnswer("床前明月光", "床前，明月光。"))
        assertTrue(matchesTextAnswer("“春眠不觉晓”！", "春眠不觉晓"))
        assertFalse(matchesTextAnswer("床前明月光。", "疑是地上霜"))
    }

    @Test
    fun matchesEnglishVoiceInputWithPunctuationAndCase() {
        assertTrue(matchesTextAnswer("Apple.", "apple"))
        assertTrue(matchesTextAnswer("Hello, world!", "hello world"))
        assertTrue(matchesTextAnswer("Don't!", "don't"))
        assertFalse(matchesTextAnswer("Hello world.", "hello earth"))
    }

    @Test
    fun preservesMathSemanticsWhileStrippingTrailingPunctuation() {
        assertTrue(matchesTextAnswer("3.14。", "3.14"))
        assertTrue(matchesTextAnswer("3.14.", "3.14"))
        assertFalse(matchesTextAnswer("3.14", "314"))

        assertTrue(matchesTextAnswer("-5。", "-5"))
        assertFalse(matchesTextAnswer("-5", "5"))

        assertTrue(matchesTextAnswer("1/2。", "1/2"))
        assertFalse(matchesTextAnswer("1/2", "12"))

        assertTrue(matchesTextAnswer("50%。", "50%"))
        assertFalse(matchesTextAnswer("50%", "50"))
    }

    @Test
    fun handlesEmptyAndPurePunctuationSafely() {
        assertFalse(matchesTextAnswer("", "答案"))
        assertFalse(matchesTextAnswer("。。。", "答案"))
        assertFalse(matchesTextAnswer("。。。", ""))
        // 若题目答案本身就是纯标点，精确匹配兜底仍能通过
        assertTrue(matchesTextAnswer("？", "?"))
    }

    @Test
    fun collapseCjkSpacesRemovesSpacesOnlyBetweenCjkCharacters() {
        // ST-608：纯字符区间实现，替代 OpenJDK 专属 \p{IsHan} 正则
        assertEquals("", collapseCjkSpaces(""))
        assertEquals("床前明月光", collapseCjkSpaces("床前 明月光"))
        assertEquals("床前明月光", collapseCjkSpaces("床前   \t 明月光"))
        assertEquals("hello world", collapseCjkSpaces("hello world"))
        assertEquals("中文 English 混合 123", collapseCjkSpaces("中 文 English 混 合 123"))
        // CJK Extension A (\u3400..\u4DBF) 与兼容汉字 (\uF900..\uFAFF)
        assertEquals("\u3400\u4E00", collapseCjkSpaces("\u3400 \u4E00"))
        assertEquals("\uF900\u4E2D", collapseCjkSpaces("\uF900   \u4E2D"))
    }
}
