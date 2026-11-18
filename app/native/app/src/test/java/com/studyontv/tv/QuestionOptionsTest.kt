package com.studyontv.tv

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class QuestionOptionsTest {
    @Test
    fun singleChoiceKeepsOptionsOptionImagesAndAnswerAligned() {
        val original = JSONObject()
            .put("type", "single")
            .put("q", "选出小猫")
            .put("a", JSONArray(listOf("小狗", "小猫", "小鸟", "小鱼")))
            .put("optionImages", JSONArray(listOf("/api/media/dog.png", "/api/media/cat.png", "", "/api/media/fish.png")))
            .put("k", 1)

        val permuted = QuestionOptions.permute(original, listOf(3, 1, 0, 2))
        assertEquals("小鱼", permuted.getJSONArray("a").getString(0))
        assertEquals("小猫", permuted.getJSONArray("a").getString(1))
        assertEquals("小狗", permuted.getJSONArray("a").getString(2))
        assertEquals("小鸟", permuted.getJSONArray("a").getString(3))
        assertEquals("/api/media/fish.png", permuted.getJSONArray("optionImages").getString(0))
        assertEquals("/api/media/cat.png", permuted.getJSONArray("optionImages").getString(1))
        assertEquals("/api/media/dog.png", permuted.getJSONArray("optionImages").getString(2))
        assertEquals("", permuted.getJSONArray("optionImages").getString(3))
        assertEquals(1, permuted.getInt("k"))
        // 原题库快照不能被原地污染
        assertEquals("小狗", original.getJSONArray("a").getString(0))

        // 随机洗牌 30 次均验证文本、图片与正确答案一一对应
        val expectedImgByOption = mapOf(
            "小狗" to "/api/media/dog.png",
            "小猫" to "/api/media/cat.png",
            "小鸟" to "",
            "小鱼" to "/api/media/fish.png"
        )
        repeat(30) {
            val shuffled = QuestionOptions.shuffled(original)
            val opts = shuffled.getJSONArray("a")
            val imgs = shuffled.getJSONArray("optionImages")
            val k = shuffled.getInt("k")
            assertEquals("小猫", opts.getString(k))
            assertEquals("/api/media/cat.png", imgs.getString(k))
            for (i in 0 until opts.length()) {
                assertEquals(expectedImgByOption[opts.getString(i)], imgs.getString(i))
            }
        }
    }

    @Test
    fun multipleChoiceKeepsCorrectIndicesSortedAndOptionImagesAligned() {
        val original = JSONObject()
            .put("type", "multiple")
            .put("q", "选出偶数")
            .put("a", JSONArray(listOf("1", "2", "3", "4")))
            .put("optionImages", JSONArray(listOf("img1", "img2", "img3", "img4")))
            .put("correctIndices", JSONArray(listOf(1, 3)))

        val permuted = QuestionOptions.permute(original, listOf(3, 0, 1, 2))
        val opts = permuted.getJSONArray("a")
        val imgs = permuted.getJSONArray("optionImages")
        val correct = permuted.getJSONArray("correctIndices")
        assertEquals(listOf("4", "1", "2", "3"), (0 until opts.length()).map { opts.getString(it) })
        assertEquals(listOf("img4", "img1", "img2", "img3"), (0 until imgs.length()).map { imgs.getString(it) })
        assertEquals(listOf(0, 2), (0 until correct.length()).map { correct.getInt(it) })

        repeat(20) {
            val shuffled = QuestionOptions.shuffled(original)
            val sOpts = shuffled.getJSONArray("a")
            val sImgs = shuffled.getJSONArray("optionImages")
            val sCorrect = shuffled.getJSONArray("correctIndices")
            val indices = (0 until sCorrect.length()).map { sCorrect.getInt(it) }
            assertEquals(indices.sorted(), indices)
            assertEquals(setOf("2", "4"), indices.map { sOpts.getString(it) }.toSet())
            assertEquals(setOf("img2", "img4"), indices.map { sImgs.getString(it) }.toSet())
        }
    }

    @Test
    fun nonChoiceTypesReturnUnchanged() {
        val booleanQ = JSONObject().put("type", "boolean").put("a", JSONArray(listOf("正确", "错误"))).put("k", 0)
        val textQ = JSONObject().put("type", "text").put("answers", JSONArray(listOf("42")))
        assertSame(booleanQ, QuestionOptions.shuffled(booleanQ))
        assertSame(textQ, QuestionOptions.shuffled(textQ))
    }
}
