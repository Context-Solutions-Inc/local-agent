package com.contextsolutions.mobileagent.platform

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Android [UrlOpener] actual — `ACTION_VIEW` into the default browser. Uses the
 * application context, so it adds `FLAG_ACTIVITY_NEW_TASK` (required when
 * starting an Activity from a non-Activity context).
 */
class AndroidUrlOpener(private val context: Context) : UrlOpener {
    override fun openUrl(url: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
