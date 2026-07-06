package org.wikipedia.analytics.eventplatform

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import org.wikipedia.R
import org.wikipedia.activity.SingleFragmentActivity
import org.wikipedia.main.MainActivity
import org.wikipedia.onboarding.InitialOnboardingActivity
import org.wikipedia.page.ExclusiveBottomSheetPresenter

object BreadCrumbViewUtil {
    private const val VIEW_UNNAMED = "unnamed"

    fun getReadableNameForView(view: View): String {
        val parent = view.parent
        if (parent is RecyclerView) {
            val position = parent.findContainingViewHolder(view)?.bindingAdapterPosition ?: 0
            // If no parent card is available, return only recyclerview name and click position for
            // non-cardView recyclerViews
            val parentName = getReadableNameForView(parent)
            return "$parentName.$position"
        }
        return if (view.id == View.NO_ID) {
            if (view is TextView && view !is EditText) {
                view.text.toString()
            } else {
                VIEW_UNNAMED
            }
        } else {
            getViewResourceName(view)
        }
    }

    private fun getViewResourceName(view: View): String {
        return try {
            view.resources.getResourceEntryName(view.id)
        } catch (e: Exception) {
            VIEW_UNNAMED
        }
    }

    fun getReadableScreenName(context: Context, fragment: Fragment? = null): String {
        val fragName = if (fragment == null) getCurrentFragmentName(context) else fragment.javaClass.simpleName
        return context.javaClass.simpleName + (if (fragName.isNotEmpty()) ".$fragName" else "")
    }

    private fun getCurrentFragmentName(context: Context): String {
        if (context is MainActivity) {
            return context.currentNavTab().name
        }
        val fragment = getVisibleFragment(context)

        return when {
            fragment != null && context is InitialOnboardingActivity -> {
                getInitialOnboardingScreenName(fragment)
            }
            fragment != null -> {
                return fragment.javaClass.simpleName
            }
            else -> return ""
        }
    }

    private fun getInitialOnboardingScreenName(fragment: Fragment): String {
        // TODO: add breadcrumb support for new onboarding screen?
        return ""
    }

    private fun getVisibleFragment(context: Context): Fragment? {
        if (context is SingleFragmentActivity<*>) {
            return context.supportFragmentManager.findFragmentById(R.id.fragment_container)
        } else if (context is FragmentActivity) {
            val fragments: List<Fragment> = context.supportFragmentManager.fragments
            for (fragment in fragments) {
                if (fragment.isVisible) return fragment
            }
        } else if (context is ContextThemeWrapper && context.baseContext is FragmentActivity) {
            // Very likely a bottom sheet, so find it within the Context's fragment structure.
            var targetFrag = (context.baseContext as FragmentActivity).supportFragmentManager.findFragmentByTag(ExclusiveBottomSheetPresenter.BOTTOM_SHEET_FRAGMENT_TAG)
            if (targetFrag != null) {
                return targetFrag
            }
            val frags = (context.baseContext as FragmentActivity).supportFragmentManager.fragments
            frags.forEach {
                targetFrag = it.childFragmentManager.findFragmentByTag(ExclusiveBottomSheetPresenter.BOTTOM_SHEET_FRAGMENT_TAG)
                if (targetFrag != null) {
                    return targetFrag
                }
            }
            frags.lastOrNull()
        }
        return null
    }
}
