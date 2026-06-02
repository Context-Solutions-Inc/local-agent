package com.contextsolutions.mobileagent.platform

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Android [Toaster] actual — a `LENGTH_LONG` `Toast`. Posts to the main looper
 * so callers can invoke it from any thread.
 */
class AndroidToaster(private val context: Context) : Toaster {
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun show(message: String) {
        mainHandler.post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}
