package org.wikipedia.feed

import android.app.Activity.RESULT_OK
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import org.wikipedia.Constants.InvokeSource
import org.wikipedia.R
import org.wikipedia.WikipediaApp
import org.wikipedia.analytics.testkitchen.TestKitchenAdapter
import org.wikipedia.compose.components.WikipediaAlertDialog
import org.wikipedia.compose.components.menu.PageOverflowMenuViewModel
import org.wikipedia.feed.didyouknow.DidYouKnowActivity
import org.wikipedia.feed.didyouknow.DidYouKnowCard
import org.wikipedia.feed.model.Card
import org.wikipedia.feed.model.DiscoverCard
import org.wikipedia.feed.model.EmptyCommunityCard
import org.wikipedia.feed.model.EmptyForYouCard
import org.wikipedia.feed.model.PlacesOfInterestLocationPromptCard
import org.wikipedia.feed.onboarding.ExploreFeedUpdatePromptActivity
import org.wikipedia.feed.onthisday.OnThisDayActivity
import org.wikipedia.feed.onthisday.OnThisDayCard
import org.wikipedia.feed.personalization.PersonalizationActivity
import org.wikipedia.feed.personalization.PersonalizationActivity.Companion.RESULT_INTERESTS_UPDATED
import org.wikipedia.feed.personalization.homepreference.HomePreferenceType
import org.wikipedia.feed.topread.TopReadArticlesActivity
import org.wikipedia.feed.topread.TopReadCard
import org.wikipedia.main.MainActivity
import org.wikipedia.navtab.NavTab
import org.wikipedia.notifications.NotificationActivity
import org.wikipedia.page.tabs.TabActivity
import org.wikipedia.places.PlacesActivity
import org.wikipedia.random.RandomActivity
import org.wikipedia.readinglist.ReadingListActivity
import org.wikipedia.readinglist.ReadingListMode
import org.wikipedia.readinglist.recommended.RecommendedReadingListOnboardingActivity
import org.wikipedia.readinglist.recommended.RecommendedReadingListSettingsActivity
import org.wikipedia.settings.Prefs
import org.wikipedia.settings.homefeed.HomeFeedSettingsActivity
import org.wikipedia.settings.homefeed.HomeFeedSettingsStartDestination
import org.wikipedia.settings.languages.WikipediaLanguagesActivity
import org.wikipedia.util.FeedbackUtil
import org.wikipedia.util.ShareUtil

/**
 * Native Compose destination for the Home (Explore feed) tab, replacing the legacy HomeFragment
 * wrapper. Renders [HomeScreen] and routes its callbacks to the hosting [MainActivity], which owns
 * the relocated feed action handlers.
 */
@Composable
fun HomeRoute() {
    val context = LocalContext.current
    val activity = context as? MainActivity
    val viewModel: HomeViewModel = viewModel()
    val pageOverflowMenuViewModel: PageOverflowMenuViewModel = viewModel()
    val instrument = remember { TestKitchenAdapter.client.getInstrument("apps-home-feed").startFunnel("home_feed") }
    val cardImpressions = remember { mutableSetOf<String>() }

    val selectedTab by viewModel.selectedTab.collectAsState()
    val wikiSite by viewModel.wikiSite.collectAsState()
    val tabsState by viewModel.tabsState.collectAsState()
    val notificationState by viewModel.unreadCount.collectAsState()
    val forYouContentState by viewModel.forYouState.collectAsState()
    val communityContentState by viewModel.communityState.collectAsState()
    var swipeToExplorePromptShown by remember { mutableStateOf(Prefs.isHomeSwipeToExplorePromptShown) }

    val onCardImpression: (Card, Int) -> Unit = { card, index ->
        if (cardImpressions.add(card.hideKey)) {
            instrument.submitInteraction(
                "impression",
                actionSource = card.javaClass.simpleName,
                actionContext = mapOf("card_index" to index)
            )
        }
    }

    val personalizationResultLauncher = rememberLauncherForActivityResult(StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            val tab = if (Prefs.homePreferenceSelection == HomePreferenceType.PERSONALIZED) HomeTab.FOR_YOU else HomeTab.COMMUNITY
            viewModel.selectTab(tab)
        }
    }
    val customizeInterestsLauncher = rememberLauncherForActivityResult(StartActivityForResult()) {
        if (it.resultCode == RESULT_INTERESTS_UPDATED) {
            Prefs.homeForYouModulesToday = ""
            viewModel.refreshForYouContent()
        }
    }

    LaunchedEffect(selectedTab) {
        activity?.onHomeSubTabChanged(selectedTab)
    }

    val notificationRefreshTick = activity?.notificationRefreshTick ?: 0
    LaunchedEffect(notificationRefreshTick) {
        if (notificationRefreshTick > 0) {
            viewModel.refreshUnreadNotificationCount()
        }
    }

    LaunchedEffect(Unit) {
        if (!Prefs.isInitialOnboardingEnabled && !Prefs.isExploreFeedUpdatePromptShown) {
            personalizationResultLauncher.launch(ExploreFeedUpdatePromptActivity.newIntent(context))
        }
    }

    val onboardingJustCompleted = activity?.homeOnboardingJustCompleted == true
    LaunchedEffect(onboardingJustCompleted) {
        if (onboardingJustCompleted) {
            viewModel.updateLanguage(Prefs.homeLanguageCode)
            val tab = if (Prefs.homePreferenceSelection == HomePreferenceType.PERSONALIZED) HomeTab.FOR_YOU else HomeTab.COMMUNITY
            viewModel.selectTab(tab)
            activity.homeOnboardingJustCompleted = false
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    activity?.onTabChanged(NavTab.HOME)
                    viewModel.updateTabCount()
                    viewModel.updateSelectedLanguageIfNeeded()
                    instrument.startFunnel("home_feed")
                    viewModel.refreshUnreadNotificationCount()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    cardImpressions.clear()
                    instrument.stopFunnel()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            instrument.stopFunnel()
        }
    }

    HomeScreen(
        wikiSite = wikiSite,
        languageState = WikipediaApp.instance.languageState,
        selectedTab = selectedTab,
        communityContentState = communityContentState,
        forYouContentState = forYouContentState,
        overflowMenuState = pageOverflowMenuViewModel.pageOverflowMenuState,
        tabsState = tabsState,
        notificationBellState = notificationState,
        onSelectTab = { tab, card ->
            if (card is EmptyCommunityCard || card is EmptyForYouCard) {
                instrument.submitInteraction("click", actionSource = card.javaClass.simpleName, elementId = "community_feed")
            }
            viewModel.selectTab(tab)
        },
        onRefreshTab = {
            Prefs.homeForYouModulesToday = ""
            if (it == HomeTab.COMMUNITY) {
                viewModel.refreshCommunityContent()
            } else {
                viewModel.refreshForYouContent()
            }
        },
        onLoadMoreCommunityContent = viewModel::loadCommunityContent,
        onLoadMoreForYouContent = viewModel::loadForYouContent,
        onHideCommunityCardClick = { card ->
            instrument.submitInteraction("click", actionSource = card.javaClass.simpleName, actionSubtype = "feed_overflow", elementId = "card_hide")
            viewModel.hideCommunityCard(card)
            activity?.let {
                FeedbackUtil.makeSnackbar(it, it.getString(R.string.menu_feed_card_dismissed))
                    .setAction(it.getString(R.string.explore_feed_header_overflow_hide_module_message_action)) {
                        instrument.submitInteraction("click", actionSource = card.javaClass.simpleName, actionSubtype = "feed_overflow", elementId = "undo_card_hide")
                        viewModel.restoreCommunityCard(card)
                    }.show()
            }
        },
        onHideForYouCardClick = { _, card ->
            instrument.submitInteraction("click", actionSource = card.javaClass.simpleName, actionSubtype = "feed_overflow", elementId = "card_hide")
            viewModel.hideForYouCard(card)
            activity?.let {
                FeedbackUtil.makeSnackbar(it, it.getString(R.string.menu_feed_card_dismissed))
                    .setAction(it.getString(R.string.explore_feed_header_overflow_hide_module_message_action)) {
                        instrument.submitInteraction("click", actionSource = card.javaClass.simpleName, actionSubtype = "feed_overflow", elementId = "undo_card_hide")
                        viewModel.restoreForYouCard(card)
                    }.show()
            }
        },
        onHideModuleClick = { moduleKey ->
            instrument.submitInteraction("click", actionSource = moduleKey, actionSubtype = "feed_overflow", elementId = "module_hide")
            viewModel.hideModule(moduleKey)
            activity?.let {
                FeedbackUtil.makeSnackbar(it, it.getString(R.string.explore_feed_header_overflow_hide_module_message))
                    .setAction(it.getString(R.string.explore_feed_header_overflow_hide_module_message_action)) {
                        instrument.submitInteraction("click", actionSource = moduleKey, actionSubtype = "feed_overflow", elementId = "undo_module_hide")
                        viewModel.restoreModule(moduleKey)
                    }.show()
            }
        },
        onPageClick = { card, historyEntry ->
            instrument.submitInteraction("click", actionSource = card.javaClass.simpleName, elementId = "article_open", pageData = TestKitchenAdapter.getPageData(pageTitle = historyEntry.title))
            activity?.feedActions?.selectPage(historyEntry, false)
        },
        onPageBookmarkClick = { card, historyEntry ->
            instrument.submitInteraction("click", actionSource = card.javaClass.simpleName, elementId = "article_save", pageData = TestKitchenAdapter.getPageData(pageTitle = historyEntry.title))
            activity?.feedActions?.addPageToList(historyEntry, true)
        },
        onPageShareClick = { card, historyEntry ->
            instrument.submitInteraction("click", actionSource = card.javaClass.simpleName, elementId = "article_share", pageData = TestKitchenAdapter.getPageData(pageTitle = historyEntry.title))
            ShareUtil.shareText(context, historyEntry.title)
        },
        onPageOverflowClick = { card, pageSummary, source, menuKey ->
            pageOverflowMenuViewModel.onPageOverflowClick(
                context = context,
                wikiSite = wikiSite,
                pageSummary = pageSummary,
                source = source,
                menuKey = menuKey,
                onOpenPage = { entry ->
                    instrument.submitInteraction("click", actionSource = card.javaClass.simpleName, actionSubtype = "feed_item_overflow", elementId = "article_open", pageData = TestKitchenAdapter.getPageData(pageTitle = entry.title))
                    activity?.feedActions?.selectPage(entry, false)
                },
                onOpenInNewTab = { entry ->
                    instrument.submitInteraction("click", actionSource = card.javaClass.simpleName, actionSubtype = "feed_item_overflow", elementId = "article_open_new_tab", pageData = TestKitchenAdapter.getPageData(pageTitle = entry.title))
                    activity?.feedActions?.selectPage(entry, true)
                    viewModel.updateTabCount(true)
                },
                onAddRequest = { entry, addToDefault ->
                    instrument.submitInteraction("click", actionSource = card.javaClass.simpleName, actionSubtype = "feed_item_overflow", elementId = "article_save", pageData = TestKitchenAdapter.getPageData(pageTitle = entry.title))
                    activity?.feedActions?.addPageToList(entry, addToDefault)
                },
                onMoveRequest = { id, entry ->
                    instrument.submitInteraction("click", actionSource = card.javaClass.simpleName, actionSubtype = "feed_item_overflow", elementId = "article_move", pageData = TestKitchenAdapter.getPageData(pageTitle = entry.title))
                    activity?.feedActions?.movePageToList(id, entry)
                },
                onRemoveRequest = { entry, lists ->
                    instrument.submitInteraction("click", actionSource = card.javaClass.simpleName, actionSubtype = "feed_item_overflow", elementId = "article_remove", pageData = TestKitchenAdapter.getPageData(pageTitle = entry.title))
                    activity?.feedActions?.removePageFromList(entry, lists)
                },
                onShareRequest = { entry ->
                    instrument.submitInteraction("click", actionSource = card.javaClass.simpleName, actionSubtype = "feed_item_overflow", elementId = "article_share", pageData = TestKitchenAdapter.getPageData(pageTitle = entry.title))
                    activity?.feedActions?.sharePage(entry)
                },
                onLinkCopyRequest = { entry ->
                    instrument.submitInteraction("click", actionSource = card.javaClass.simpleName, actionSubtype = "feed_item_overflow", elementId = "article_copy_link", pageData = TestKitchenAdapter.getPageData(pageTitle = entry.title))
                    activity?.feedActions?.copyLink(entry)
                }
            )
        },
        onPageOverflowDismiss = {
            pageOverflowMenuViewModel.dismissPageOverflowMenu()
        },
        onNewsClick = { card, newsItem ->
            instrument.submitInteraction("click", actionSource = card.javaClass.simpleName, elementId = card.javaClass.simpleName, actionContext = mapOf("index" to card.news.indexOf(newsItem)))
            activity?.feedActions?.newsItemSelected(newsItem, wikiSite)
        },
        onImageClick = {
            instrument.submitInteraction("click", actionSource = it.javaClass.simpleName, elementId = "article_open", pageData = TestKitchenAdapter.getPageData(pageTitle = it.featuredImage.toPageTitle()))
            activity?.feedActions?.featuredImageSelected(it.featuredImage)
        },
        onImageShareClick = {
            instrument.submitInteraction("click", actionSource = it.javaClass.simpleName, elementId = "share", pageData = TestKitchenAdapter.getPageData(pageTitle = it.featuredImage.toPageTitle()))
            activity?.feedActions?.shareImage(it.featuredImage, it.age)
        },
        onImageDownloadClick = {
            instrument.submitInteraction("click", actionSource = it.javaClass.simpleName, elementId = "download", pageData = TestKitchenAdapter.getPageData(pageTitle = it.featuredImage.toPageTitle()))
            activity?.feedActions?.downloadImage(it.featuredImage)
        },
        onLanguageSelected = { languageCode ->
            instrument.submitInteraction("click", "language_menu", elementId = "language_change", actionContext = mapOf("selected_tab" to selectedTab.name, "language_code" to languageCode))
            viewModel.updateLanguage(languageCode)
        },
        onManageLanguagesClick = {
            instrument.submitInteraction("click", "language_menu", elementId = "manage_languages", actionContext = mapOf("selected_tab" to selectedTab.name))
            context.startActivity(WikipediaLanguagesActivity.newIntent(context, invokeSource = InvokeSource.FEED))
        },
        onCustomizeClick = { card ->
            if (card != null) {
                instrument.submitInteraction("click", actionSource = card.javaClass.simpleName,
                    actionSubtype = if (card !is EmptyForYouCard) "feed_overflow" else null,
                    elementId = "feed_customize")
            }
            if (card is DiscoverCard) {
                context.startActivity(RecommendedReadingListSettingsActivity.newIntent(context))
            } else {
                customizeInterestsLauncher.launch(PersonalizationActivity.newIntent(context, showInterestsOnly = true))
            }
        },
        onTabClick = {
            context.startActivity(TabActivity.newIntent(context))
        },
        onUpdateTabCount = {
            viewModel.updateTabCount(false)
        },
        onCardImpression = { card, index ->
            onCardImpression(card, index)
        },
        onCardFooterClick = { card ->
            when (card) {
                is TopReadCard -> {
                    instrument.submitInteraction("click", actionSource = card.javaClass.simpleName, elementId = "more_top_read")
                    context.startActivity(TopReadArticlesActivity.newIntent(context, TopReadCard(card.articles, card.age, wikiSite)))
                }
                is OnThisDayCard -> {
                    instrument.submitInteraction("click", actionSource = card.javaClass.simpleName, elementId = "more_on_this_day")
                    context.startActivity(OnThisDayActivity.newIntent(context, card.age, -1, wikiSite, InvokeSource.ON_THIS_DAY_CARD_FOOTER))
                }
                is DidYouKnowCard -> {
                    instrument.submitInteraction("click", actionSource = card.javaClass.simpleName, elementId = "more_did_you_know")
                    context.startActivity(DidYouKnowActivity.newIntent(context, card.site, card.items))
                }
            }
        },
        onNotificationClick = {
            context.startActivity(NotificationActivity.newIntent(context))
        },
        onManageModulesClick = {
            instrument.submitInteraction("click", actionSource = "feed_empty", elementId = "customize_feed")
            val intent = HomeFeedSettingsActivity.newIntent(
                context = context,
                startDestination = when (selectedTab) {
                    HomeTab.COMMUNITY -> HomeFeedSettingsStartDestination.COMMUNITY_MODULES
                    HomeTab.FOR_YOU -> HomeFeedSettingsStartDestination.FOR_YOU_MODULES
                }
            )
            context.startActivity(intent)
        },
        onShuffleClick = {
            instrument.submitInteraction("click", elementId = "random_card_shuffle_button")
            context.startActivity(RandomActivity.newIntent(context, wikiSite, InvokeSource.FEED))
        },
        onPlacesTeaserClick = {
            instrument.submitInteraction("click", actionSource = PlacesOfInterestLocationPromptCard::class.java.simpleName, elementId = "go_to_places")
            context.startActivity(PlacesActivity.newIntent(context))
        },
        onDiscoverTeaserClick = {
            instrument.submitInteraction("click", elementId = "enable_discover_reading_list_button")
            context.startActivity(RecommendedReadingListOnboardingActivity.newIntent(context))
        },
        onSeeAllRecommendationsClick = {
            instrument.submitInteraction("click", elementId = "explore_all_recommendations_button")
            context.startActivity(ReadingListActivity.newIntent(context, readingListMode = ReadingListMode.RECOMMENDED))
        }
    )

    if (selectedTab == HomeTab.FOR_YOU && !swipeToExplorePromptShown && forYouContentState.modules.isNotEmpty()) {
        val dismissSwipePrompt = {
            swipeToExplorePromptShown = true
            Prefs.isHomeSwipeToExplorePromptShown = true
        }
        WikipediaAlertDialog(
            title = stringResource(R.string.explore_feed_swipe_to_explore_prompt_title),
            titleModifier = Modifier.fillMaxWidth(),
            message = stringResource(R.string.explore_feed_swipe_to_explore_prompt_message),
            image = {
                Image(
                    painter = painterResource(R.drawable.swipe_gesture_illustration),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButtonText = stringResource(R.string.onboarding_got_it),
            onDismissRequest = dismissSwipePrompt,
            onConfirmButtonClick = dismissSwipePrompt
        )
    }
}
