package dev.herdr.mobile

import dev.herdr.mobile.net.QuestionItem
import dev.herdr.mobile.net.QuestionKind
import dev.herdr.mobile.net.QuestionOption
import dev.herdr.mobile.ui.QuestionInput
import dev.herdr.mobile.ui.buildAnswers
import dev.herdr.mobile.ui.clampNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuestionAnswersTest {
    private fun choice(q: String, vararg labels: String, multi: Boolean = false) =
        QuestionItem(q, "H", QuestionKind.Choice, labels.map { QuestionOption(it) }, multiSelect = multi)

    @Test fun singleSelectAnswersWithTheLabel() {
        val qs = listOf(choice("Color?", "Red", "Blue"))
        assertEquals(mapOf("Color?" to "Blue"), buildAnswers(qs, listOf(QuestionInput(selected = setOf("Blue")))))
    }

    @Test fun multiSelectJoinsLabelsInOptionOrder() {
        val qs = listOf(choice("Pick", "A", "B", "C", multi = true))
        assertEquals(mapOf("Pick" to "A, C"), buildAnswers(qs, listOf(QuestionInput(selected = setOf("C", "A")))))
    }

    @Test fun otherTextWinsForSingleSelect() {
        val qs = listOf(choice("Color?", "Red", "Blue"))
        assertEquals(
            mapOf("Color?" to "Green"),
            buildAnswers(qs, listOf(QuestionInput(selected = setOf("Red"), other = "  Green "))),
        )
    }

    @Test fun otherTextIsAppendedForMultiSelect() {
        val qs = listOf(choice("Pick", "A", "B", multi = true))
        assertEquals(
            mapOf("Pick" to "B, zed"),
            buildAnswers(qs, listOf(QuestionInput(selected = setOf("B"), other = "zed"))),
        )
        assertEquals(mapOf("Pick" to "zed"), buildAnswers(qs, listOf(QuestionInput(other = "zed"))))
    }

    @Test fun textQuestionAnswersWithTrimmedText() {
        val qs = listOf(QuestionItem("Name?", "H", QuestionKind.Text))
        assertEquals(mapOf("Name?" to "bob"), buildAnswers(qs, listOf(QuestionInput(text = " bob "))))
    }

    @Test fun numberIsClampedAndFormatted() {
        val q = QuestionItem("How many?", "H", QuestionKind.Number, min = 1.0, max = 10.0)
        assertEquals(mapOf("How many?" to "10"), buildAnswers(listOf(q), listOf(QuestionInput(number = "42"))))
        assertEquals(mapOf("How many?" to "1"), buildAnswers(listOf(q), listOf(QuestionInput(number = "-3"))))
        assertEquals(mapOf("How many?" to "2.5"), buildAnswers(listOf(q), listOf(QuestionInput(number = "2.50"))))
        assertNull(buildAnswers(listOf(q), listOf(QuestionInput(number = "abc"))))
    }

    @Test fun clampNumberHandlesOpenBounds() {
        assertEquals(5.0, clampNumber(5.0, null, null), 0.0)
        assertEquals(3.0, clampNumber(5.0, null, 3.0), 0.0)
        assertEquals(7.0, clampNumber(5.0, 7.0, null), 0.0)
    }

    @Test fun multiQuestionNeedsEveryAnswer() {
        val qs = listOf(choice("Color?", "Red", "Blue"), QuestionItem("Name?", "H", QuestionKind.Text))
        assertEquals(
            mapOf("Color?" to "Red", "Name?" to "x"),
            buildAnswers(qs, listOf(QuestionInput(selected = setOf("Red")), QuestionInput(text = "x"))),
        )
        assertNull(buildAnswers(qs, listOf(QuestionInput(selected = setOf("Red")), QuestionInput())))
        assertNull(buildAnswers(qs, listOf(QuestionInput(selected = setOf("Red")))))
    }

    @Test fun emptySelectionIsIncomplete() {
        assertNull(buildAnswers(listOf(choice("Color?", "Red")), listOf(QuestionInput())))
    }
}
