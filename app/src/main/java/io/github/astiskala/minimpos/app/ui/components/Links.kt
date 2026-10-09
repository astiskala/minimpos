package io.github.astiskala.minimpos.app.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/**
 * Opens [url] in the browser, or whatever app answers it; does nothing when the device has none (an Adyen terminal
 * has no browser).
 */
fun Context.openUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (ignored: ActivityNotFoundException) {
    }
}

/**
 * Opens [url] in a browser app, even when another app claims its address with an in-app browser (as the GitHub app does
 * for github.com), whose downloads may offer no way to install them. Falls back to [openUrl] when no app declares
 * itself a browser.
 */
fun Context.openInBrowser(url: String) {
    val browser = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)
    try {
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).apply { selector = browser })
    } catch (ignored: ActivityNotFoundException) {
        openUrl(url)
    }
}
