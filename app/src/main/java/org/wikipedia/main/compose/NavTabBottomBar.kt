package org.wikipedia.main.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.wikipedia.compose.theme.BaseTheme
import org.wikipedia.compose.theme.WikipediaTheme
import org.wikipedia.navtab.NavTab

/**
 * Custom Compose replacement for the legacy [org.wikipedia.navtab.NavTabLayout]
 * (a Material [com.google.android.material.bottomnavigation.BottomNavigationView]).
 *
 * Renders one item per [NavTab] entry, driven by [selectedTab]. The [overlayDots] set
 * reproduces the red notification dot that the legacy bar showed via `setOverlayDot`
 * (used for recommended reading lists and the activity-tab onboarding hint).
 */
@Composable
fun NavTabBottomBar(
    selectedTab: NavTab,
    onTabSelected: (NavTab) -> Unit,
    modifier: Modifier = Modifier,
    overlayDots: Set<NavTab> = emptySet(),
    onItemPositioned: (NavTab, Rect) -> Unit = { _, _ -> }
) {
    val colors = WikipediaTheme.colors
    Column(modifier = modifier.background(colors.paperColor)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.borderColor)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavTab.entries.forEach { tab ->
                NavTabItem(
                    tab = tab,
                    selected = tab == selectedTab,
                    showOverlayDot = tab in overlayDots,
                    onClick = { onTabSelected(tab) },
                    modifier = Modifier
                        .weight(1f)
                        .onGloballyPositioned { onItemPositioned(tab, it.boundsInWindow()) }
                )
            }
        }
    }
}

@Composable
private fun NavTabItem(
    tab: NavTab,
    selected: Boolean,
    showOverlayDot: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = WikipediaTheme.colors
    val contentColor = if (selected) colors.primaryColor else colors.inactiveColor
    Column(
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(if (selected) tab.selectedIcon else tab.icon),
                contentDescription = stringResource(tab.text),
                tint = contentColor,
                modifier = Modifier.size(24.dp)
            )
            if (showOverlayDot) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 6.dp, y = (-2).dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(colors.destructiveColor)
                )
            }
        }
        Text(
            text = stringResource(tab.text),
            color = contentColor,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun NavTabBottomBarPreview() {
    BaseTheme {
        NavTabBottomBar(
            selectedTab = NavTab.HOME,
            onTabSelected = {},
            overlayDots = setOf(NavTab.READING_LISTS, NavTab.EDITS)
        )
    }
}
