package com.pocketpad.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.pocketpad.MainActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Rule
import org.junit.Test

/**
 * Matches the single picker MethodCard whose content description starts with [prefix].
 *
 * MethodCard puts contentDescription "<label>, <status detail>" on a semantics {} node that merges
 * its children, so this pins down one card inside ConnectionPickerSheet. Matching on the bare text
 * "Wi-Fi" is ambiguous: the Connect screen behind the sheet renders its own "Wi-Fi" quick-pick
 * chip, and the AUTO blurb reads "Tries USB, Wi-Fi, then a paired Bluetooth host".
 */
private fun hasMethodCard(prefix: String) =
    SemanticsMatcher("MethodCard contentDescription starts with '$prefix'") { node ->
        node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
            .firstOrNull()
            ?.startsWith(prefix) == true
    }

@HiltAndroidTest
class PocketPadScreenTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun firstLaunchShowsConnectionPickerAndConnectionOptions() {
        // On launch the picker sheet is shown (showPicker starts true) and renders its title
        // followed by one MethodCard per ConnectionMethod entry. AUTO is labelled
        // "Auto (Recommended)" in MethodCard; the others use their enum label.
        composeRule.onNodeWithText("Connect your gamepad").assertIsDisplayed()
        composeRule.onNodeWithText("Auto (Recommended)").assertIsDisplayed()
        // Wi-Fi is the third MethodCard and can start below the sheet's fold, so scroll it in.
        composeRule.onNode(hasMethodCard("Wi-Fi,")).performScrollTo().assertIsDisplayed()
    }
}
