package com.studyontv.tv

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParentActivityTest {

    @Test
    fun `passwordImeOptions uses NEXT for setup and DONE for verification`() {
        assertEquals(
            EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_EXTRACT_UI,
            ParentActivity.passwordImeOptions(setup = true)
        )
        assertEquals(
            EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI,
            ParentActivity.passwordImeOptions(setup = false)
        )
    }

    @Test
    fun `shouldSubmitOnEditorAction triggers once on IME_DONE or hardware Enter DOWN`() {
        assertTrue(ParentActivity.shouldSubmitOnEditorAction(EditorInfo.IME_ACTION_DONE, null, null))
        assertTrue(ParentActivity.shouldSubmitOnEditorAction(EditorInfo.IME_ACTION_DONE, KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN))
        assertFalse(ParentActivity.shouldSubmitOnEditorAction(EditorInfo.IME_ACTION_DONE, KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_UP))

        assertTrue(ParentActivity.shouldSubmitOnEditorAction(EditorInfo.IME_NULL, KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN))
        assertTrue(ParentActivity.shouldSubmitOnEditorAction(EditorInfo.IME_NULL, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.ACTION_DOWN))
        assertFalse(ParentActivity.shouldSubmitOnEditorAction(EditorInfo.IME_NULL, KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_UP))
        assertFalse(ParentActivity.shouldSubmitOnEditorAction(EditorInfo.IME_ACTION_NEXT, null, null))
        assertFalse(ParentActivity.shouldSubmitOnEditorAction(EditorInfo.IME_NULL, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_DOWN))
    }
}
