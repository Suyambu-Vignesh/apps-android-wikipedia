package org.wikipedia.main

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.icu.text.ListFormatter
import android.os.Build
import android.speech.RecognizerIntent
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import org.wikipedia.Constants
import org.wikipedia.Constants.InvokeSource
import org.wikipedia.R
import org.wikipedia.commons.FilePageActivity
import org.wikipedia.dataclient.WikiSite
import org.wikipedia.feed.image.FeaturedImage
import org.wikipedia.feed.news.NewsActivity
import org.wikipedia.feed.news.NewsItem
import org.wikipedia.gallery.MediaDownloadReceiver
import org.wikipedia.history.HistoryEntry
import org.wikipedia.page.PageActivity
import org.wikipedia.readinglist.ReadingListBehaviorsUtil
import org.wikipedia.readinglist.RemoveFromReadingListsDialog
import org.wikipedia.readinglist.database.ReadingList
import org.wikipedia.util.ClipboardUtil
import org.wikipedia.util.FeedbackUtil
import org.wikipedia.util.ShareUtil
import org.wikipedia.util.TabUtil
import org.wikipedia.views.imageservice.ImageService
import java.io.File

/**
 * Groups the Explore-feed action handlers that used to live on MainFragment. They are driven by the
 * Home Compose destination and delegate back to [activity] for Activity-bound operations (launching
 * activities, snackbars, the storage-permission launcher).
 */
class FeedActionsHandler(private val activity: MainActivity) {

    private val downloadReceiver = MediaDownloadReceiver()
    private val downloadReceiverCallback = object : MediaDownloadReceiver.Callback {
        override fun onSuccess() {
            FeedbackUtil.showMessage(activity, R.string.gallery_save_success)
        }
    }
    private var pendingDownloadImage: FeaturedImage? = null

    fun registerDownloadReceiver() = downloadReceiver.register(activity, downloadReceiverCallback)

    fun unregisterDownloadReceiver() = downloadReceiver.unregister(activity)

    fun onStoragePermissionResult(granted: Boolean) {
        if (granted) {
            pendingDownloadImage?.let { download(it) }
        } else {
            FeedbackUtil.showMessage(activity, R.string.gallery_save_image_write_permission_rationale)
        }
    }

    fun voiceSearchRequested() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        try {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, Constants.ACTIVITY_REQUEST_VOICE_SEARCH)
        } catch (a: ActivityNotFoundException) {
            FeedbackUtil.showMessage(activity, R.string.error_voice_search_not_available)
        }
    }

    fun selectPage(entry: HistoryEntry, openInNewBackgroundTab: Boolean) {
        if (openInNewBackgroundTab) {
            TabUtil.openInNewBackgroundTab(entry)
            activity.markTabCountAnimationPending()
            activity.invalidateOptionsMenu()
        } else {
            activity.startActivity(PageActivity.newIntentForNewTab(activity, entry, entry.title))
        }
    }

    fun addPageToList(entry: HistoryEntry, addToDefault: Boolean) {
        ReadingListBehaviorsUtil.addToDefaultList(activity, entry.title, addToDefault, InvokeSource.FEED)
    }

    fun movePageToList(sourceReadingListId: Long, entry: HistoryEntry) {
        ReadingListBehaviorsUtil.moveToList(activity, sourceReadingListId, entry.title, InvokeSource.FEED)
    }

    fun removePageFromList(entry: HistoryEntry, lists: List<ReadingList>) {
        RemoveFromReadingListsDialog(lists).deleteOrShowDialog(activity) { readingLists, _ ->
            if (!activity.isDestroyed) {
                val names = readingLists.map { it.title }.run {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ListFormatter.getInstance().format(this)
                    } else {
                        joinToString(separator = ", ")
                    }
                }
                FeedbackUtil.showMessage(activity, activity.getString(R.string.reading_list_item_deleted_from_list, entry.title.displayText, names))
            }
        }
    }

    fun sharePage(entry: HistoryEntry) {
        ShareUtil.shareText(activity, entry.title.displayText, entry.title.uri)
    }

    fun copyLink(entry: HistoryEntry) {
        ClipboardUtil.setPlainText(activity, text = entry.title.uri)
        FeedbackUtil.showMessage(activity, R.string.address_copied)
    }

    fun newsItemSelected(newsItem: NewsItem, wikiSite: WikiSite) {
        activity.startActivity(NewsActivity.newIntent(activity, newsItem, wikiSite))
    }

    fun shareImage(image: FeaturedImage, age: Int) {
        val thumbUrl = image.thumbnailUrl
        val fullSizeUrl = image.original.source
        ImageService.loadImage(activity, thumbUrl, onSuccess = { bitmap ->
            if (activity.isDestroyed) {
                return@loadImage
            }
            ShareUtil.shareImage(activity.lifecycleScope, activity, bitmap, File(thumbUrl).name,
                ShareUtil.getFeaturedImageShareSubject(activity, age), fullSizeUrl)
        })
    }

    fun downloadImage(image: FeaturedImage) {
        pendingDownloadImage = image
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            download(image)
        } else {
            activity.requestStoragePermissionForDownload()
        }
    }

    fun featuredImageSelected(image: FeaturedImage) {
        activity.startActivity(FilePageActivity.newIntent(activity, image.toPageTitle()))
    }

    private fun download(image: FeaturedImage) {
        pendingDownloadImage = null
        downloadReceiver.download(activity, image)
        FeedbackUtil.showMessage(activity, R.string.gallery_save_progress)
    }
}
