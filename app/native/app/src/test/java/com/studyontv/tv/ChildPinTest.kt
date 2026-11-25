package com.studyontv.tv

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * ST-610：多孩子独立切换密码（哈希存储、脱敏序列化、切换权限评估）单元测试。
 */
class ChildPinTest {

    @Test
    fun normalizeChildPinDistinguishesNullClearAndValidPin() {
        assertNull(LocalStore.normalizeChildPin(null))
        assertEquals("", LocalStore.normalizeChildPin(""))
        assertEquals("", LocalStore.normalizeChildPin("   "))
        assertEquals("1234", LocalStore.normalizeChildPin(" 1234 "))
        assertEquals("a".repeat(16), LocalStore.normalizeChildPin("a".repeat(16)))

        try {
            LocalStore.normalizeChildPin("12345678901234567")
            fail("Expected IllegalArgumentException for >16 char PIN")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("1–16"))
        }
    }

    @Test
    fun saltedPinHashSupportsSetChangeClearAndNeverStoresPlaintext() {
        val salt1 = LocalStore.generatePinSalt()
        val salt2 = LocalStore.generatePinSalt()
        assertEquals(32, salt1.length)
        assertNotEquals(salt1, salt2)

        val hash1 = LocalStore.hashChildPin("2580", salt1)
        val hash2 = LocalStore.hashChildPin("2580", salt2)
        assertEquals(64, hash1.length)
        assertNotEquals(hash1, hash2)
        assertFalse(hash1.contains("2580"))

        // 校验正确/错误/边界
        assertTrue(LocalStore.verifyPinHash("2580", salt1, hash1))
        assertTrue(LocalStore.verifyPinHash(" 2580 ", salt1, hash1))
        assertFalse(LocalStore.verifyPinHash("0000", salt1, hash1))
        assertFalse(LocalStore.verifyPinHash("", salt1, hash1))
        assertFalse(LocalStore.verifyPinHash("2580", "", hash1))
        assertFalse(LocalStore.verifyPinHash("2580", salt1, ""))

        // 改密码后旧密码失效，新密码生效
        val newSalt = LocalStore.generatePinSalt()
        val newHash = LocalStore.hashChildPin("9876", newSalt)
        assertFalse(LocalStore.verifyPinHash("2580", newSalt, newHash))
        assertTrue(LocalStore.verifyPinHash("9876", newSalt, newHash))

        // 清空密码后任何输入均不通过
        assertFalse(LocalStore.verifyPinHash("9876", "", ""))
    }

    @Test
    fun childSummaryJsonExposesOnlyHasPinWithoutLeakingSecrets() {
        val summary = LocalStore.childSummaryJson(
            id = "c12345678",
            name = "小明",
            createdAt = 1700000000000L,
            active = false,
            hasPin = true
        )
        assertEquals("c12345678", summary.getString("id"))
        assertEquals("小明", summary.getString("name"))
        assertTrue(summary.getBoolean("hasPin"))
        assertFalse(summary.has("pin"))
        assertFalse(summary.has("pinHash"))
        assertFalse(summary.has("pinSalt"))
        assertFalse(summary.has("pin_hash"))
        assertFalse(summary.has("pin_salt"))
    }

    @Test
    fun extractOptionalPinPreservesMissingKeyForNoOpUpdate() {
        assertNull(LanServer.extractOptionalPin(JSONObject().put("id", "c1").put("name", "小明")))
        assertNull(LanServer.extractOptionalPin(JSONObject().put("id", "c1").put("name", "小明").put("pin", JSONObject.NULL)))
        assertEquals("", LanServer.extractOptionalPin(JSONObject().put("id", "c1").put("name", "小明").put("pin", "")))
        assertEquals("1234", LanServer.extractOptionalPin(JSONObject().put("id", "c1").put("name", "小明").put("pin", "1234")))
    }

    @Test
    fun localSwitchToPinnedChildRequiresValidPinOrParentFallback() {
        val salt = LocalStore.generatePinSalt()
        val hash = LocalStore.hashChildPin("1357", salt)
        var parentFallbackCalled = false

        // 1. 未传密码 -> child_pin_required
        val missing = LanServer.evaluateChildSwitch(
            targetId = "c2",
            activeId = "",
            isParent = false,
            targetHasPin = true,
            pin = null,
            verifyChildPin = { LocalStore.verifyPinHash(it, salt, hash) },
            verifyParentPassword = { parentFallbackCalled = true; false }
        )
        assertFalse(missing.allowed)
        assertEquals("child_pin_required", missing.errorCode)
        assertFalse(parentFallbackCalled)

        // 2. 传空串 -> child_pin_required
        val blank = LanServer.evaluateChildSwitch(
            targetId = "c2",
            activeId = "",
            isParent = false,
            targetHasPin = true,
            pin = "   ",
            verifyChildPin = { LocalStore.verifyPinHash(it, salt, hash) },
            verifyParentPassword = { false }
        )
        assertFalse(blank.allowed)
        assertEquals("child_pin_required", blank.errorCode)

        // 3. 密码错误且非家长主密码 -> child_pin_invalid
        val invalid = LanServer.evaluateChildSwitch(
            targetId = "c2",
            activeId = "",
            isParent = false,
            targetHasPin = true,
            pin = "9999",
            verifyChildPin = { LocalStore.verifyPinHash(it, salt, hash) },
            verifyParentPassword = { it == "parent123" }
        )
        assertFalse(invalid.allowed)
        assertEquals("child_pin_invalid", invalid.errorCode)

        // 4. 孩子密码匹配 -> 放行且无需触发家长主密码校验
        parentFallbackCalled = false
        val okChild = LanServer.evaluateChildSwitch(
            targetId = "c2",
            activeId = "",
            isParent = false,
            targetHasPin = true,
            pin = "1357",
            verifyChildPin = { LocalStore.verifyPinHash(it, salt, hash) },
            verifyParentPassword = { parentFallbackCalled = true; false }
        )
        assertTrue(okChild.allowed)
        assertFalse(parentFallbackCalled)

        // 5. 家长主密码兜底 -> 放行
        val okParentFallback = LanServer.evaluateChildSwitch(
            targetId = "c2",
            activeId = "",
            isParent = false,
            targetHasPin = true,
            pin = "parent123",
            verifyChildPin = { LocalStore.verifyPinHash(it, salt, hash) },
            verifyParentPassword = { it == "parent123" }
        )
        assertTrue(okParentFallback.allowed)
    }

    @Test
    fun parentSessionUnpinnedChildAndSameChildBypassPinPrompt() {
        // 家长会话免密放行
        val parentSwitch = LanServer.evaluateChildSwitch(
            targetId = "c2",
            activeId = "",
            isParent = true,
            targetHasPin = true,
            pin = null,
            verifyChildPin = { fail("Parent session should not check child PIN"); false },
            verifyParentPassword = { fail("Parent session should not check parent password"); false }
        )
        assertTrue(parentSwitch.allowed)

        // 目标孩子未设密码 -> 放行
        val unpinned = LanServer.evaluateChildSwitch(
            targetId = "c3",
            activeId = "",
            isParent = false,
            targetHasPin = false,
            pin = null,
            verifyChildPin = { false },
            verifyParentPassword = { false }
        )
        assertTrue(unpinned.allowed)

        // 目标孩子就是当前激活孩子 -> 放行
        val sameChild = LanServer.evaluateChildSwitch(
            targetId = "c2",
            activeId = "c2",
            isParent = false,
            targetHasPin = true,
            pin = null,
            verifyChildPin = { false },
            verifyParentPassword = { false }
        )
        assertTrue(sameChild.allowed)
    }
}
