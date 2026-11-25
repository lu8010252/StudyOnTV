package com.studyontv.tv

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GradeSubjectsFilterTest {

    @Test
    fun parseGradeSubjectsNormalizesAndPreservesEmptySubjectListAsAllSubjects() {
        val raw = JSONObject()
            .put(" 三年级 ", JSONArray(listOf("英语", " 英语 ", "", "数学")))
            .put("四年级", JSONArray())
            .put("   ", JSONArray(listOf("语文")))
        val parsed = LocalStore.parseGradeSubjects(raw)
        assertEquals(2, parsed.size)
        assertEquals(listOf("英语", "数学"), parsed["三年级"])
        assertEquals(emptyList<String>(), parsed["四年级"])
    }

    @Test
    fun buildScopeFilterFallsBackToLegacyCartesianProductWhenGradeSubjectsEmpty() {
        val filter = LocalStore.buildScopeFilter(
            subjects = listOf("数学", "英语"),
            levels = listOf("三年级", "四年级"),
            selectedLevel = "三年级",
            banks = listOf("默认题库"),
            gradeSubjects = emptyMap()
        )
        assertTrue(filter.clause.contains("questions.subject IN (?,?)"))
        assertTrue(filter.clause.contains("questions.level IN (?,?)"))
        assertTrue(filter.clause.contains("questions.level=?"))
        assertTrue(filter.clause.contains("questions.bank IN (?) OR questions.bank=''"))
        assertEquals(listOf("数学", "英语", "三年级", "四年级", "三年级", "默认题库"), filter.args)
    }

    @Test
    fun buildScopeFilterUsesParameterizedGradeSubjectPairsAndPreventsCrossGradeLeak() {
        val gs = linkedMapOf(
            "三年级" to listOf("英语"),
            "四年级" to listOf("语文", "数学")
        )
        val filter = LocalStore.buildScopeFilter(
            subjects = listOf("数学"), // legacy quizSubjects must be ignored when explicitSubjectFilter=false
            levels = listOf("一年级"),
            selectedLevel = "",
            banks = listOf("校内同步"),
            gradeSubjects = gs,
            explicitSubjectFilter = false
        )
        assertEquals(
            " AND ((questions.level=? AND questions.subject IN (?)) OR (questions.level=? AND questions.subject IN (?,?))) AND questions.bank IN (?)",
            filter.clause
        )
        assertEquals(listOf("三年级", "英语", "四年级", "语文", "数学", "校内同步"), filter.args)

        // In-memory semantic matcher agrees with SQL filter
        assertTrue(LocalStore.matchesGradeSubjects("三年级", "英语", gs))
        assertFalse(LocalStore.matchesGradeSubjects("三年级", "数学", gs))
        assertTrue(LocalStore.matchesGradeSubjects("四年级", "语文", gs))
        assertTrue(LocalStore.matchesGradeSubjects("四年级", "数学", gs))
        assertFalse(LocalStore.matchesGradeSubjects("四年级", "英语", gs))
        assertFalse(LocalStore.matchesGradeSubjects("五年级", "语文", gs))
    }

    @Test
    fun buildScopeFilterRespectsSelectedLevelAndAllSubjectsForGrade() {
        val gs = linkedMapOf(
            "三年级" to listOf("英语"),
            "四年级" to emptyList()
        )

        // Selected level with specific subjects
        val f3 = LocalStore.buildScopeFilter(selectedLevel = "三年级", gradeSubjects = gs)
        assertEquals(" AND (questions.level=? AND questions.subject IN (?))", f3.clause)
        assertEquals(listOf("三年级", "英语"), f3.args)
        assertTrue(LocalStore.matchesGradeSubjects("三年级", "英语", gs, selectedLevel = "三年级"))
        assertFalse(LocalStore.matchesGradeSubjects("三年级", "数学", gs, selectedLevel = "三年级"))
        assertFalse(LocalStore.matchesGradeSubjects("四年级", "英语", gs, selectedLevel = "三年级"))

        // Selected level with empty subject list => all subjects in that grade
        val f4 = LocalStore.buildScopeFilter(selectedLevel = "四年级", gradeSubjects = gs)
        assertEquals(" AND questions.level=?", f4.clause)
        assertEquals(listOf("四年级"), f4.args)
        assertTrue(LocalStore.matchesGradeSubjects("四年级", "科学", gs, selectedLevel = "四年级"))

        // Selected level not present in gradeSubjects => empty result guard
        val f5 = LocalStore.buildScopeFilter(selectedLevel = "五年级", gradeSubjects = gs)
        assertEquals(" AND 1=0", f5.clause)
        assertTrue(f5.args.isEmpty())
        assertFalse(LocalStore.matchesGradeSubjects("五年级", "数学", gs, selectedLevel = "五年级"))
    }
}
