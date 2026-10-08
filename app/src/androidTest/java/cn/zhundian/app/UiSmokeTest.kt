package cn.zhundian.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI launch/navigation only: this test never enables fallback or calls a phone number. */
@RunWith(AndroidJUnit4::class)
class UiSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun calendarEditorAndCapabilitiesAreReachable() {
        compose.onNodeWithText("今日安排").assertIsDisplayed()
        compose.onNodeWithText("日历").performClick()
        compose.onNodeWithText("日历与日期详情").assertIsDisplayed()
        compose.onNodeWithText("添加安排").performClick()
        compose.onNodeWithText("安排编辑").assertIsDisplayed()
        compose.onNodeWithText("标题").assertIsDisplayed()
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("设置与权限").assertIsDisplayed()
        compose.onNodeWithText("能力与权限状态").performScrollTo().assertIsDisplayed()
    }
}
