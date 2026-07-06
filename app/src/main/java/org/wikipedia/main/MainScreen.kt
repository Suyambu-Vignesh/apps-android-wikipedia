package org.wikipedia.main

import android.view.LayoutInflater
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.isVisible
import androidx.fragment.compose.AndroidFragment
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.android.material.appbar.MaterialToolbar
import org.wikipedia.R
import org.wikipedia.activitytab.ActivityTabFragment
import org.wikipedia.feed.HomeRoute
import org.wikipedia.history.HistoryFragment
import org.wikipedia.main.compose.NavTabBottomBar
import org.wikipedia.navtab.NavTab
import org.wikipedia.readinglist.ReadingListsFragment

/**
 * The Compose shell for [MainActivity]: a Scaffold hosting the custom bottom nav bar and a
 * Navigation Compose nav graph. Home is a native Compose destination ([HomeRoute]); the other tabs
 * are Fragments hosted via [AndroidFragment] interop. Activity-bound state and behavior live on
 * [activity]; this composable only wires them into Compose.
 */
@Composable
fun MainScreen(activity: MainActivity) {
    val nav = rememberNavController()
    SideEffect { activity.navController = nav }

    LaunchedEffect(Unit) {
        if (activity.needsInitialIntent) {
            activity.needsInitialIntent = false
            activity.handleLaunchIntent()
        }
    }

    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.getTop(density)
    SideEffect { activity.onInsetsChanged(statusTop) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { MainToolbar(activity, activity.toolbarVisible, activity.toolbarTitle) },
        bottomBar = {
            if (activity.bottomBarVisible) {
                NavTabBottomBar(
                    selectedTab = activity.selectedTab,
                    onTabSelected = { activity.selectTab(it) },
                    overlayDots = activity.overlayDots,
                    onItemPositioned = { tab, rect ->
                        activity.onNavItemPositioned(tab, rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
                    }
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = nav,
            startDestination = NavTab.HOME.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            composable(NavTab.HOME.route) {
                HomeRoute()
            }
            composable(NavTab.READING_LISTS.route) {
                AndroidFragment<ReadingListsFragment>(modifier = Modifier.fillMaxSize()) {
                    activity.registerTabFragment(NavTab.READING_LISTS, it)
                }
            }
            composable(NavTab.SEARCH.route) {
                AndroidFragment<HistoryFragment>(modifier = Modifier.fillMaxSize()) {
                    activity.registerTabFragment(NavTab.SEARCH, it)
                }
            }
            composable(NavTab.EDITS.route) {
                AndroidFragment<ActivityTabFragment>(modifier = Modifier.fillMaxSize()) {
                    activity.registerTabFragment(NavTab.EDITS, it)
                }
            }
        }
    }
}

@Composable
private fun MainToolbar(activity: MainActivity, visible: Boolean, title: String) {
    AndroidView(
        factory = { ctx ->
            val tb = LayoutInflater.from(ctx).inflate(R.layout.view_main_toolbar, null) as MaterialToolbar
            activity.onToolbarCreated(tb)
            tb
        },
        update = { tb ->
            tb.isVisible = visible
            tb.title = title
            activity.wordmark?.isVisible = false
        },
        modifier = if (visible) Modifier.statusBarsPadding() else Modifier.height(0.dp)
    )
}
