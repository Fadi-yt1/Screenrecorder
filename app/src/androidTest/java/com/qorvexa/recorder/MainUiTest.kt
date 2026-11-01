package com.qorvexa.recorder

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun homeShowsRecordButtonAndTabs() {
        rule.onNodeWithText("Tap to Start Recording").assertIsDisplayed()
        rule.onNodeWithContentDescription("Start recording").assertIsDisplayed()
        rule.onAllNodesWithText("Recordings")[0].assertIsDisplayed()
    }

    @Test fun navigatesToDeviceAndSettings() {
        rule.onNodeWithText("Device").performClick()
        rule.onNodeWithText("Device Compatibility").assertIsDisplayed()
        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithText("Video Settings").assertIsDisplayed()
    }
}
