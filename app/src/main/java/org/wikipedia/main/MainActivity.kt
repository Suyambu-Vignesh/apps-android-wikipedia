package org.wikipedia.main

import android.Manifest
import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.view.ActionMode
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.Toolbar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.graphics.Insets
import androidx.core.net.toUri
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.wikipedia.BackPressedHandler
import org.wikipedia.Constants
import org.wikipedia.Constants.InvokeSource
import org.wikipedia.R
import org.wikipedia.WikipediaApp
import org.wikipedia.activity.BaseActivity
import org.wikipedia.activitytab.ActivityTabFragment
import org.wikipedia.activitytab.ActivityTabOnboardingActivity
import org.wikipedia.analytics.eventplatform.ImageRecommendationsEvent
import org.wikipedia.analytics.eventplatform.PatrollerExperienceEvent
import org.wikipedia.analytics.eventplatform.ReadingListsAnalyticsHelper
import org.wikipedia.analytics.testkitchen.TestKitchenAdapter
import org.wikipedia.auth.AccountUtil
import org.wikipedia.compose.theme.BaseTheme
import org.wikipedia.concurrency.FlowEventBus
import org.wikipedia.dataclient.WikiSite
import org.wikipedia.events.ImportReadingListsEvent
import org.wikipedia.events.LoggedOutEvent
import org.wikipedia.events.LoggedOutInBackgroundEvent
import org.wikipedia.events.NewRecommendedReadingListEvent
import org.wikipedia.feed.HomeTab
import org.wikipedia.feed.personalization.homepreference.HomePreferenceType
import org.wikipedia.gallery.GalleryActivity
import org.wikipedia.history.HistoryEntry
import org.wikipedia.history.HistoryFragment
import org.wikipedia.login.LoginActivity
import org.wikipedia.navtab.MenuNavTabDialog
import org.wikipedia.navtab.NavTab
import org.wikipedia.notifications.NotificationActivity
import org.wikipedia.onboarding.InitialOnboardingActivity
import org.wikipedia.page.ExclusiveBottomSheetPresenter
import org.wikipedia.page.PageActivity
import org.wikipedia.page.PageTitle
import org.wikipedia.page.tabs.TabActivity
import org.wikipedia.places.PlacesActivity
import org.wikipedia.random.RandomActivity
import org.wikipedia.readinglist.ReadingListsFragment
import org.wikipedia.search.SearchActivity
import org.wikipedia.search.SearchFragment
import org.wikipedia.settings.Prefs
import org.wikipedia.settings.SettingsActivity
import org.wikipedia.staticdata.MainPageNameData
import org.wikipedia.staticdata.UserAliasData
import org.wikipedia.staticdata.UserTalkAliasData
import org.wikipedia.talk.TalkTopicsActivity
import org.wikipedia.theme.Theme
import org.wikipedia.usercontrib.UserContribListActivity
import org.wikipedia.util.DeviceUtil
import org.wikipedia.util.DimenUtil
import org.wikipedia.util.FeedbackUtil
import org.wikipedia.util.ResourceUtil
import org.wikipedia.views.NotificationButtonView
import org.wikipedia.views.TabCountsView
import org.wikipedia.watchlist.WatchlistActivity
import org.wikipedia.widgets.SearchWidgetInstallDialog
import org.wikipedia.widgets.readingchallenge.ReadingChallengeInstallWidgetDialog
import org.wikipedia.widgets.readingchallenge.ReadingChallengeWidgetRepository
import org.wikipedia.yearinreview.YearInReviewDialog
import org.wikipedia.yearinreview.YearInReviewOnboardingActivity
import org.wikipedia.yearinreview.YearInReviewViewModel
import java.util.concurrent.TimeUnit

/**
 * Thin launcher host for the app's main screen. It owns only the Activity-bound essentials
 * (lifecycle, the nav controller, the toolbar menu, deep-link/intent handling, and the tab
 * callback interfaces). The Compose shell lives in [MainScreen] and the Explore-feed actions in
 * [FeedActionsHandler].
 */
class MainActivity : BaseActivity(), HistoryFragment.Callback, MenuNavTabDialog.Callback,
    ActivityTabFragment.Callback, MenuProvider {

    val feedActions = FeedActionsHandler(this)

    private var statusBarInsets = Insets.NONE
    private var controlNavTabInFragment = false
    private var menuProviderAdded = false
    private var showTabCountsAnimation = false

    private var notificationButtonView: NotificationButtonView? = null
    private var tabCountsView: TabCountsView? = null
    private var toolbar: MaterialToolbar? = null

    private val fragmentsByTab = mutableMapOf<NavTab, Fragment>()
    private val navTabBackStack = mutableListOf<NavTab>()
    private val navTabBounds = mutableMapOf<NavTab, Rect>()
    private val pendingTooltips = mutableMapOf<NavTab, () -> Unit>()

    var notificationRefreshTick by mutableIntStateOf(0)

    internal var navController: NavHostController? = null
    internal var wordmark: View? = null
    internal var selectedTab by mutableStateOf(NavTab.HOME)
    internal var toolbarVisible by mutableStateOf(false)
    internal var toolbarTitle by mutableStateOf("")
    internal var bottomBarVisible by mutableStateOf(true)
    internal var overlayDots by mutableStateOf(emptySet<NavTab>())
    internal var needsInitialIntent = false

    private var shellTheme by mutableStateOf(WikipediaApp.instance.currentTheme)
    private var lastHomeSubTab = if (Prefs.homePreferenceSelection == HomePreferenceType.PERSONALIZED) HomeTab.FOR_YOU else HomeTab.COMMUNITY

    var homeOnboardingJustCompleted by mutableStateOf(false)

    private val currentFragment: Fragment? get() = fragmentsByTab[selectedTab]

    fun currentNavTab(): NavTab = selectedTab

    private val onboardingLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        homeOnboardingJustCompleted = true
    }

    private val activityTabOnboardingLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            onNavigateTo(NavTab.EDITS)
        }
    }

    private val downloadPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
        feedActions.onStoragePermissionResult(isGranted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!DeviceUtil.assertAppContext(this)) {
            return
        }

        enableEdgeToEdge()
        DeviceUtil.setLightSystemUiVisibility(this)
        setNavigationBarColor(Color.TRANSPARENT)
        setImageZoomHelper()

        onBackPressedDispatcher.addCallback(this) {
            if (!handleBackPressed()) {
                finish()
            }
        }

        overlayDots = computeInitialOverlayDots()

        if (Prefs.isInitialOnboardingEnabled && savedInstanceState == null &&
            !intent.hasExtra(Constants.INTENT_EXTRA_PREVIEW_SAVED_READING_LISTS)) {
            onboardingLauncher.launch(InitialOnboardingActivity.newIntent(this))
        }

        maybeShowFeedNewModulesTooltip()
        Prefs.incrementExploreFeedVisitCount()
        observeEvents()

        needsInitialIntent = savedInstanceState == null

        setContent {
            BaseTheme(currentTheme = shellTheme) {
                MainScreen(this)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        feedActions.registerDownloadReceiver()
        Prefs.pageLastShown = 0
        invalidateOptionsMenu()
        YearInReviewDialog.maybeShowYearInReviewFeedbackDialog(this)
        if (YearInReviewViewModel.getYearInReviewModel()?.isReadingListCreated == true) {
            onNavigateTo(NavTab.READING_LISTS)
            YearInReviewViewModel.updateYearInReviewModel { it.copy(isReadingListCreated = false) }
        }
    }

    override fun onPause() {
        super.onPause()
        feedActions.unregisterDownloadReceiver()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasExtra(ReadingChallengeWidgetRepository.INTENT_EXTRA_READING_CHALLENGE_JOIN)) {
            if (ReadingChallengeWidgetRepository.shouldShowOnboardingDialog()) {
                showReadingChallenge()
            }
        }
        handleIntent(intent)
    }

    // region State + navigation wiring used by MainScreen

    internal fun onToolbarCreated(tb: MaterialToolbar) {
        toolbar = tb
        wordmark = tb.findViewById(R.id.main_toolbar_wordmark)
        setSupportActionBar(tb)
        supportActionBar?.title = ""
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        tb.navigationIcon = null
        if (!menuProviderAdded) {
            addMenuProvider(this)
            menuProviderAdded = true
        }
        invalidateOptionsMenu()
    }

    internal fun onInsetsChanged(statusTop: Int) {
        statusBarInsets = Insets.of(0, statusTop, 0, 0)
    }

    internal fun registerTabFragment(tab: NavTab, fragment: Fragment) {
        fragmentsByTab[tab] = fragment
    }

    internal fun onNavItemPositioned(tab: NavTab, left: Int, top: Int, right: Int, bottom: Int) {
        navTabBounds[tab] = Rect(left, top, right, bottom)
        pendingTooltips.remove(tab)?.invoke()
    }

    private fun requestNavTabTooltip(tab: NavTab, text: CharSequence, showDismissButton: Boolean) {
        val show = { showNavTabTooltip(tab, text, showDismissButton) }
        if (navTabBounds.containsKey(tab)) show() else pendingTooltips[tab] = show
    }

    private fun showNavTabTooltip(tab: NavTab, text: CharSequence, showDismissButton: Boolean) {
        val bounds = navTabBounds[tab] ?: return
        val parent = findViewById<ViewGroup>(android.R.id.content) ?: return
        parent.post {
            if (isDestroyed || isFinishing) {
                return@post
            }
            val anchor = View(this)
            val params = FrameLayout.LayoutParams(bounds.width().coerceAtLeast(1), bounds.height().coerceAtLeast(1))
            params.leftMargin = bounds.left
            params.topMargin = bounds.top
            parent.addView(anchor, params)
            val balloon = FeedbackUtil.showTooltip(this, anchor, text, aboveOrBelow = true, autoDismiss = false, showDismissButton = showDismissButton)
            balloon.setOnBalloonDismissListener { parent.removeView(anchor) }
        }
    }

    internal fun handleLaunchIntent() {
        handleIntent(intent)
    }

    internal fun selectTab(tab: NavTab) {
        navTabBackStack.clear()
        when {
            tab == NavTab.EDITS && !Prefs.isActivityTabOnboardingShown -> {
                activityTabOnboardingLauncher.launch(ActivityTabOnboardingActivity.newIntent(this))
                overlayDots = overlayDots - NavTab.EDITS
            }
            tab == NavTab.MORE -> {
                ExclusiveBottomSheetPresenter.show(supportFragmentManager, MenuNavTabDialog.newInstance())
            }
            currentFragment is HistoryFragment && tab == NavTab.SEARCH -> {
                openSearchActivity(InvokeSource.NAV_MENU, null, null)
            }
            else -> {
                navigateToTab(tab)
                invalidateOptionsMenu()
                if (tab == NavTab.SEARCH) {
                    maybeShowSearchWidgetInstallPrompt()
                }
            }
        }
    }

    // endregion

    fun onTabChanged(tab: NavTab) {
        selectedTab = tab
        if (tab == NavTab.HOME) {
            toolbarVisible = false
            toolbarTitle = ""
            controlNavTabInFragment = false
            shellTheme = if (lastHomeSubTab == HomeTab.FOR_YOU) Theme.BLACK else WikipediaApp.instance.currentTheme
        } else {
            if (tab == NavTab.SEARCH && Prefs.showSearchTabTooltip) {
                requestNavTabTooltip(NavTab.SEARCH, getString(R.string.search_tab_tooltip), showDismissButton = false)
                Prefs.showSearchTabTooltip = false
            }
            if (tab == NavTab.EDITS) {
                ImageRecommendationsEvent.logImpression("suggested_edit_dialog")
                PatrollerExperienceEvent.logImpression("suggested_edits_dialog")
                if (ReadingChallengeWidgetRepository.shouldShowWidgetInstallDialog()) {
                    ExclusiveBottomSheetPresenter.show(supportFragmentManager, ReadingChallengeInstallWidgetDialog())
                }
            }
            toolbarVisible = true
            toolbarTitle = getString(tab.text)
            controlNavTabInFragment = true
            shellTheme = WikipediaApp.instance.currentTheme
        }
        applySystemUiForTheme(shellTheme)
        invalidateOptionsMenu()
    }

    fun onHomeSubTabChanged(tab: HomeTab) {
        lastHomeSubTab = tab
        if (selectedTab == NavTab.HOME) {
            shellTheme = if (tab == HomeTab.FOR_YOU) Theme.BLACK else WikipediaApp.instance.currentTheme
            applySystemUiForTheme(shellTheme)
        }
    }

    fun updateToolbarElevation(elevate: Boolean) {
        toolbar?.elevation = if (elevate) {
            DimenUtil.dpToPx(DimenUtil.getDimension(R.dimen.toolbar_default_elevation))
        } else {
            0f
        }
    }

    private fun navigateToTab(tab: NavTab) {
        navController?.navigate(tab.route) {
            launchSingleTop = true
            restoreState = true
            popUpTo(NavTab.HOME.route) { saveState = true }
        }
        onTabChanged(tab)
    }

    override fun onNavigateTo(navTab: NavTab) {
        val lastTab = selectedTab
        navigateToTab(navTab)
        if (lastTab == NavTab.EDITS && navTab != NavTab.EDITS) {
            navTabBackStack.add(NavTab.EDITS)
        }
    }

    private fun handleBackPressed(): Boolean {
        if ((currentFragment as? BackPressedHandler)?.onBackPressed() == true) {
            return true
        }
        if (navTabBackStack.isNotEmpty()) {
            onNavigateTo(navTabBackStack.removeAt(navTabBackStack.lastIndex))
            return true
        }
        return false
    }

    private fun applySystemUiForTheme(theme: Theme) {
        val wrapper = ContextThemeWrapper(this, theme.resourceId)
        val paperColor = ResourceUtil.getThemedColor(wrapper, R.attr.paper_color)
        setNavigationBarColor(paperColor)
        DeviceUtil.setLightSystemUiVisibility(this, light = !theme.isDark)
    }

    private fun computeInitialOverlayDots(): Set<NavTab> {
        val dots = mutableSetOf<NavTab>()
        val readingListDot = (!Prefs.isRecommendedReadingListOnboardingShown) ||
            (Prefs.isRecommendedReadingListEnabled && Prefs.isNewRecommendedReadingListGenerated)
        if (readingListDot) {
            dots.add(NavTab.READING_LISTS)
        }
        if (!Prefs.isActivityTabOnboardingShown) {
            dots.add(NavTab.EDITS)
        }
        return dots
    }

    // region MenuProvider (main toolbar menu)

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.menu_main, menu)
    }

    override fun onPrepareMenu(menu: Menu) {
        val fragment = currentFragment
        menu.findItem(R.id.menu_search_lists).isVisible = fragment is ReadingListsFragment
        menu.findItem(R.id.menu_overflow_button).isVisible = fragment is ReadingListsFragment

        val tabsItem = menu.findItem(R.id.menu_tabs)
        if (WikipediaApp.instance.tabCount < 1) {
            tabsItem.isVisible = false
            tabCountsView = null
        } else {
            tabsItem.isVisible = true
            tabCountsView = TabCountsView(this, null).also { view ->
                view.setOnClickListener {
                    if (WikipediaApp.instance.tabCount == 1) {
                        startActivity(PageActivity.newIntent(this))
                    } else {
                        startActivityForResult(TabActivity.newIntent(this), Constants.ACTIVITY_REQUEST_BROWSE_TABS)
                    }
                }
                view.updateTabCount(showTabCountsAnimation)
                view.contentDescription = getString(R.string.menu_page_show_tabs)
                tabsItem.actionView = view
                tabsItem.expandActionView()
                FeedbackUtil.setButtonTooltip(view)
            }
            showTabCountsAnimation = false
        }

        val notificationMenuItem = menu.findItem(R.id.menu_notifications)
        if (AccountUtil.isLoggedIn) {
            notificationMenuItem.isVisible = true
            val notifView = notificationButtonView ?: NotificationButtonView(this).also { notificationButtonView = it }
            notifView.setUnreadCount(Prefs.notificationUnreadCount)
            notifView.setOnClickListener {
                if (AccountUtil.isLoggedIn) {
                    startActivity(NotificationActivity.newIntent(this))
                }
            }
            notifView.contentDescription = getString(R.string.notifications_activity_title)
            notificationMenuItem.actionView = notifView
            notificationMenuItem.expandActionView()
            FeedbackUtil.setButtonTooltip(notifView)
        } else {
            notificationMenuItem.isVisible = false
        }
        updateNotificationDot(false)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        val fragment = currentFragment
        return when (menuItem.itemId) {
            R.id.menu_search_lists -> {
                (fragment as? ReadingListsFragment)?.startSearchActionMode()
                true
            }
            R.id.menu_overflow_button -> {
                (fragment as? ReadingListsFragment)?.showReadingListsOverflowMenu()
                true
            }
            else -> false
        }
    }

    // endregion

    // region Shared actions used by the feed handler / tab fragments

    fun openSearchActivity(source: InvokeSource, query: String?, transitionView: View?) {
        val intent = SearchActivity.newIntent(this, source, query)
        val options = transitionView?.let {
            if (intent.component?.className == SearchActivity::class.java.name) {
                ActivityOptions.makeSceneTransitionAnimation(this, it, getString(R.string.transition_search_bar))
            } else null
        }
        startActivityForResult(intent, Constants.ACTIVITY_REQUEST_OPEN_SEARCH_ACTIVITY, options?.toBundle())
    }

    fun onLoginRequested() {
        startActivityForResult(LoginActivity.newIntent(this, LoginActivity.SOURCE_NAV),
            Constants.ACTIVITY_REQUEST_LOGIN)
    }

    fun onFeedVoiceSearchRequested() {
        feedActions.voiceSearchRequested()
    }

    fun setBottomNavVisible(visible: Boolean) {
        bottomBarVisible = visible
    }

    fun markTabCountAnimationPending() {
        showTabCountsAnimation = true
    }

    fun requestStoragePermissionForDownload() {
        downloadPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    // endregion

    // region MenuNavTabDialog.Callback

    override fun usernameClick() {
        val pageTitle = PageTitle(UserAliasData.valueFor(WikipediaApp.instance.languageState.appLanguageCode), AccountUtil.userName, WikipediaApp.instance.wikiSite)
        val entry = HistoryEntry(pageTitle, HistoryEntry.SOURCE_MAIN_PAGE)
        startActivity(PageActivity.newIntentForNewTab(this, entry, pageTitle))
    }

    override fun loginClick() {
        onLoginRequested()
    }

    override fun talkClick() {
        if (AccountUtil.isLoggedIn) {
            startActivity(TalkTopicsActivity.newIntent(this,
                PageTitle(UserTalkAliasData.valueFor(WikipediaApp.instance.languageState.appLanguageCode), AccountUtil.userName,
                    WikiSite.forLanguageCode(WikipediaApp.instance.appOrSystemLanguageCode)), InvokeSource.NAV_MENU))
        }
    }

    override fun settingsClick() {
        startActivityForResult(SettingsActivity.newIntent(this), Constants.ACTIVITY_REQUEST_SETTINGS)
    }

    override fun watchlistClick() {
        if (AccountUtil.isLoggedIn) {
            startActivity(WatchlistActivity.newIntent(this))
        }
    }

    override fun contribsClick() {
        if (AccountUtil.isLoggedIn) {
            startActivity(UserContribListActivity.newIntent(this, AccountUtil.userName))
        }
    }

    override fun donateClick(campaignId: String?) {
        launchDonateDialog(campaignId = campaignId)
    }

    override fun yearInReviewClick() {
        startActivity(YearInReviewOnboardingActivity.newIntent(this))
    }

    // endregion

    // region HistoryFragment.Callback

    override fun onLoadPage(entry: HistoryEntry) {
        startActivity(PageActivity.newIntentForCurrentTab(this, entry, entry.title))
    }

    // endregion

    override fun onSupportActionModeStarted(mode: ActionMode) {
        super.onSupportActionModeStarted(mode)
        if (!controlNavTabInFragment) {
            setBottomNavVisible(false)
        }
    }

    override fun onSupportActionModeFinished(mode: ActionMode) {
        super.onSupportActionModeFinished(mode)
        setBottomNavVisible(true)
    }

    override fun onGoOffline() {
        (currentFragment as? HistoryFragment)?.refresh()
    }

    override fun onGoOnline() {
        (currentFragment as? HistoryFragment)?.refresh()
    }

    override fun onUnreadNotification() {
        updateNotificationDot(true)
        notificationRefreshTick++
    }

    private fun updateNotificationDot(animate: Boolean) {
        val notifView = notificationButtonView ?: return
        if (AccountUtil.isLoggedIn && Prefs.notificationUnreadCount > 0) {
            notifView.setUnreadCount(Prefs.notificationUnreadCount)
            if (animate) {
                notifView.runAnimation()
            }
        } else {
            notifView.setUnreadCount(0)
        }
    }

    fun getToolbar(): Toolbar {
        return toolbar!!
    }

    fun getStatusBarInsets(): Insets {
        return statusBarInsets
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_MAIN && intent.categories?.contains(Intent.CATEGORY_LAUNCHER) == true) {
            TestKitchenAdapter.client.getInstrument("apps-open")
                .submitInteraction(action = "app_open", actionSource = "app_icon")
        }
        if (Intent.ACTION_VIEW == intent.action && intent.data != null) {
            intent.data?.let {
                if (it.authority.orEmpty().endsWith(WikiSite.BASE_DOMAIN)) {
                    val uri = it.toString().replace("wikipedia://", WikiSite.DEFAULT_SCHEME + "://").toUri()
                    startActivity(Intent(this, PageActivity::class.java)
                        .setAction(Intent.ACTION_VIEW)
                        .setData(uri))
                }
            }
            return
        }
        when {
            intent.hasExtra(Constants.INTENT_APP_SHORTCUT_RANDOMIZER) ->
                startActivity(RandomActivity.newIntent(this, WikipediaApp.instance.wikiSite, InvokeSource.APP_SHORTCUTS))
            intent.hasExtra(Constants.INTENT_APP_SHORTCUT_SEARCH) ->
                openSearchActivity(InvokeSource.APP_SHORTCUTS, null, null)
            intent.hasExtra(Constants.INTENT_APP_SHORTCUT_CONTINUE_READING) ->
                startActivity(PageActivity.newIntent(this))
            intent.hasExtra(Constants.INTENT_APP_SHORTCUT_PLACES) ->
                startActivity(PlacesActivity.newIntent(this))
            intent.hasExtra(Constants.INTENT_EXTRA_DELETE_READING_LIST) ->
                onNavigateTo(NavTab.READING_LISTS)
            intent.hasExtra(Constants.INTENT_EXTRA_GO_TO_MAIN_TAB) &&
                !(selectedTab == NavTab.HOME &&
                    intent.getIntExtra(Constants.INTENT_EXTRA_GO_TO_MAIN_TAB, NavTab.HOME.code()) == NavTab.HOME.code()) ->
                onNavigateTo(NavTab.of(intent.getIntExtra(Constants.INTENT_EXTRA_GO_TO_MAIN_TAB, NavTab.HOME.code())))
            intent.hasExtra(Constants.INTENT_EXTRA_GO_TO_SE_TAB) ->
                onNavigateTo(NavTab.of(intent.getIntExtra(Constants.INTENT_EXTRA_GO_TO_SE_TAB, NavTab.EDITS.code())))
            intent.hasExtra(Constants.INTENT_EXTRA_PREVIEW_SAVED_READING_LISTS) ->
                onNavigateTo(NavTab.READING_LISTS)
            lastPageViewedWithin(1) && !intent.hasExtra(Constants.INTENT_RETURN_TO_MAIN) && WikipediaApp.instance.tabCount > 0 ->
                startActivity(PageActivity.newIntent(this))
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == Constants.ACTIVITY_REQUEST_VOICE_SEARCH && resultCode == Activity.RESULT_OK && data != null &&
            data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS) != null) {
            val searchQuery = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)!![0]
            openSearchActivity(InvokeSource.VOICE, searchQuery, null)
        } else if (requestCode == Constants.ACTIVITY_REQUEST_GALLERY &&
            resultCode == GalleryActivity.ACTIVITY_RESULT_PAGE_SELECTED && data != null) {
            startActivity(data)
        } else if (requestCode == Constants.ACTIVITY_REQUEST_LOGIN &&
            resultCode == LoginActivity.RESULT_LOGIN_SUCCESS) {
            refreshContents()
            FeedbackUtil.showMessage(this, R.string.login_success_toast)
        } else if (requestCode == Constants.ACTIVITY_REQUEST_BROWSE_TABS) {
            if (WikipediaApp.instance.tabCount == 0) {
                return
            }
            if (resultCode == TabActivity.RESULT_NEW_TAB) {
                val entry = HistoryEntry(PageTitle(
                    MainPageNameData.valueFor(WikipediaApp.instance.appOrSystemLanguageCode),
                    WikipediaApp.instance.wikiSite), HistoryEntry.SOURCE_MAIN_PAGE)
                startActivity(PageActivity.newIntentForNewTab(this, entry, entry.title))
            } else if (resultCode == TabActivity.RESULT_LOAD_FROM_BACKSTACK) {
                startActivity(PageActivity.newIntent(this))
            }
        } else if (requestCode == Constants.ACTIVITY_REQUEST_OPEN_SEARCH_ACTIVITY && resultCode == SearchFragment.RESULT_LANG_CHANGED ||
            (requestCode == Constants.ACTIVITY_REQUEST_SETTINGS &&
                (resultCode == SettingsActivity.ACTIVITY_RESULT_LANGUAGE_CHANGED ||
                    resultCode == SettingsActivity.ACTIVITY_RESULT_FEED_CONFIGURATION_CHANGED ||
                    resultCode == SettingsActivity.ACTIVITY_RESULT_LOG_OUT))) {
            refreshContents()
            if (resultCode == SettingsActivity.ACTIVITY_RESULT_LOG_OUT) {
                FeedbackUtil.showMessage(this, R.string.toast_logout_complete)
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun refreshContents() {
        when (val fragment = currentFragment) {
            is ReadingListsFragment -> fragment.updateLists()
            is HistoryFragment -> fragment.refresh()
            else -> {}
        }
    }

    private fun observeEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                FlowEventBus.events.collectLatest { event ->
                    when (event) {
                        is LoggedOutEvent,
                        is LoggedOutInBackgroundEvent -> {
                            invalidateOptionsMenu()
                            ExclusiveBottomSheetPresenter.dismiss(supportFragmentManager)
                            refreshContents()
                        }
                        is ImportReadingListsEvent -> maybeShowImportReadingListsNewInstallDialog()
                        is NewRecommendedReadingListEvent -> {
                            val show = Prefs.isRecommendedReadingListEnabled && Prefs.isNewRecommendedReadingListGenerated
                            overlayDots = if (show) overlayDots + NavTab.READING_LISTS else overlayDots - NavTab.READING_LISTS
                        }
                    }
                }
            }
        }
    }

    private fun maybeShowImportReadingListsNewInstallDialog() {
        if (!Prefs.importReadingListsNewInstallDialogShown) {
            ReadingListsAnalyticsHelper.logReceiveStart(this)
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.shareable_reading_lists_new_install_dialog_title)
                .setMessage(R.string.shareable_reading_lists_new_install_dialog_content)
                .setNegativeButton(R.string.shareable_reading_lists_new_install_dialog_got_it, null)
                .show()
            Prefs.importReadingListsNewInstallDialogShown = true
        }
    }

    private fun maybeShowFeedNewModulesTooltip() {
        if (Prefs.exploreFeedVisitCount == 0) {
            // Explicitly consider this tooltip "shown", since we only want to show it to users
            // who have used the Feed already, instead of completely new users.
            Prefs.isHomeFeedUpdateTooltipShown = true
        } else if (!Prefs.isHomeFeedUpdateTooltipShown) {
            Prefs.isHomeFeedUpdateTooltipShown = true
            requestNavTabTooltip(NavTab.HOME, getString(R.string.home_feed_update_tooltip1), showDismissButton = true)
        }
    }

    private fun maybeShowSearchWidgetInstallPrompt() {
        if (!Prefs.searchWidgetInstallPromptShown && !SearchWidgetInstallDialog.isWidgetInstalled()) {
            ExclusiveBottomSheetPresenter.show(supportFragmentManager, SearchWidgetInstallDialog())
        }
    }

    @Suppress("SameParameterValue")
    private fun lastPageViewedWithin(days: Int): Boolean {
        return TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - Prefs.pageLastShown) < days
    }

    companion object {
        fun newIntent(context: Context): Intent {
            return Intent(context, MainActivity::class.java)
        }
    }
}
