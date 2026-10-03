package com.pocketpad.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.pocketpad.MainActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class PocketPadScreenTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun firstLaunchShowsConnectionPickerAndConnectionOptions() {
        composeRule.onNodeWithText("Link to your gaming PC").assertIsDisplayed()
        composeRule.onNodeWithText("Auto (Recommended)").assertIsDisplayed()
        composeRule.onNodeWithText("Wi-Fi").assertIsDisplayed()
    }
}
