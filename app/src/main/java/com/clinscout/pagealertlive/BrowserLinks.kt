package com.clinscout.pagealertlive

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.net.toUri
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object BrowserLinks {
    fun open(context: Context, url: String): Boolean {
        val parsed = url.toHttpUrlOrNull()
        if (parsed == null || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return false
        return try {
            // Resolve a generic web URL so verified links do not divert a
            // watchdog page into a retailer app instead of the chosen browser.
            val browser = context.packageManager.resolveActivity(Intent(Intent.ACTION_VIEW, "https://example.invalid/".toUri())
                .addCategory(Intent.CATEGORY_BROWSABLE), PackageManager.MATCH_DEFAULT_ONLY)
            val intent = Intent(Intent.ACTION_VIEW, parsed.toString().toUri())
                .addCategory(Intent.CATEGORY_BROWSABLE)
                // Reuse the user's browser task rather than PageAlert's back stack.
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            browser?.activityInfo?.takeUnless { it.packageName == "android" || it.name.endsWith("ResolverActivity") || it.name.endsWith("ChooserActivity") }
                ?.let { intent.setPackage(it.packageName) }
            context.startActivity(intent)
            true
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(context, "No browser is available.", Toast.LENGTH_LONG).show()
            false
        } catch (_: SecurityException) {
            Toast.makeText(context, "The browser could not be opened.", Toast.LENGTH_LONG).show()
            false
        }
    }
}
