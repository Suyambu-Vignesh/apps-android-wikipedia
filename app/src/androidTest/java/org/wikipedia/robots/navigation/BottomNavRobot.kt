package org.wikipedia.robots.navigation

import BaseRobot
import android.util.Log
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import org.hamcrest.Matchers.allOf
import org.wikipedia.R
import org.wikipedia.base.TestConfig

class BottomNavRobot : BaseRobot() {
    // The bottom navigation bar is a Compose component (NavTabBottomBar); each tab is identified by
    // the content description of its icon, so it is driven through the Compose test rule.
    fun navigateToExploreFeed() = apply {
        composeTestRule.onNodeWithContentDescription("Home").performClick()
        delay(TestConfig.DELAY_LARGE)
    }

    fun navigateToSavedPage() = apply {
        composeTestRule.onNodeWithContentDescription("Saved").performClick()
        delay(TestConfig.DELAY_SHORT)
    }

    fun navigateToSearchPage() = apply {
        composeTestRule.onNodeWithContentDescription("Search").performClick()
        delay(TestConfig.DELAY_SHORT)
    }

    fun navigateToActivityTab() = apply {
        composeTestRule.onNodeWithContentDescription("Activity").performClick()
        val isOnboardingActivity = composeTestRule.onNodeWithText("Introducing Activity").isDisplayed()
        if (isOnboardingActivity) {
            pressBack()
        }
        delay(TestConfig.DELAY_LARGE)
    }

    fun navigateToMoreMenu() = apply {
        composeTestRule.onNodeWithContentDescription("More").performClick()
        delay(TestConfig.DELAY_SHORT)
    }

    fun goToSettings() = apply {
        // Click on `Settings` option
        onView(allOf(withId(R.id.main_drawer_settings_container), isDisplayed())).perform(click())
        delay(TestConfig.DELAY_SHORT)
    }

    fun clickLoginMenuItem() = apply {
        try {
            click.onViewWithId(R.id.main_drawer_login_button)
            delay(TestConfig.DELAY_SHORT)
        } catch (e: Exception) {
            Log.e("BottomNavRobotError:", "User logged in.")
        }
    }

    fun clickEditsMenuItem() = apply {
        try {
            click.onViewWithId(R.id.main_drawer_edit_container)
            delay(TestConfig.DELAY_SHORT)
        } catch (e: Exception) {
            Log.e("BottomNavRobotError:", "Cannot find edits container.")
        }
    }

    fun gotoWatchList() = apply {
        click.onViewWithId(R.id.main_drawer_watchlist_container)
        delay(TestConfig.DELAY_SHORT)
    }

    fun pressBack() = apply {
        goBack()
        delay(TestConfig.DELAY_SHORT)
    }
}
