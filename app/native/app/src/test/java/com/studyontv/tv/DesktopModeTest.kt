package com.studyontv.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopModeTest {

    @Test
    fun opensChooserWhenEnablingOrWhenDisablingFormerDefaultHome() {
        // 开启电视桌面始终拉起选择器
        assertTrue(DesktopMode.shouldOpenChooserAfterToggle(on = true, wasDefault = false))
        assertTrue(DesktopMode.shouldOpenChooserAfterToggle(on = true, wasDefault = true))
        // 关闭电视桌面且此前为默认桌面：必须拉起选择器交还系统桌面
        assertTrue(DesktopMode.shouldOpenChooserAfterToggle(on = false, wasDefault = true))
        // 关闭电视桌面且此前本就不是默认桌面：不打扰当前桌面
        assertFalse(DesktopMode.shouldOpenChooserAfterToggle(on = false, wasDefault = false))
    }
}
