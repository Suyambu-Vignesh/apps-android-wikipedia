package org.wikipedia.navtab

import org.wikipedia.R
import org.wikipedia.model.EnumCode

enum class NavTab(val text: Int, val id: Int, val icon: Int, val route: String) : EnumCode {

    HOME(R.string.home, R.id.nav_tab_home, R.drawable.ic_home_filled_24dp, "home"),
    READING_LISTS(R.string.nav_item_saved, R.id.nav_tab_reading_lists, R.drawable.selector_nav_saved, "saved"),
    SEARCH(R.string.nav_item_search, R.id.nav_tab_search, R.drawable.selector_nav_search, "search"),
    EDITS(R.string.nav_item_activity, R.id.nav_tab_edits, R.drawable.selector_nav_activity, "activity"),
    MORE(R.string.nav_item_more, R.id.nav_tab_more, R.drawable.ic_menu_white_24dp, "more");

    override fun code(): Int {
        // This enumeration is not marshalled so tying declaration order to presentation order is
        // convenient and consistent.
        return ordinal
    }

    companion object {
        fun of(code: Int): NavTab {
            return entries[code]
        }

        fun ofRoute(route: String): NavTab {
            return entries.first { it.route == route }
        }
    }
}
