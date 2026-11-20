package com.cdg.app.cbrowxr.browser

import android.content.Context
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings

enum class UserAgentProfile(val label: String, val uaString: String?) {
    ANDROID("Android Móvil", null),
    DESKTOP_LINUX(
        "Desktop Linux",
        "Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0"
    ),
    SMART_TV(
        "Smart TV",
        "Mozilla/5.0 (SMART-TV; Linux; Tizen 7.0) AppleWebKit/537.36 (KHTML, like Gecko) Version/7.0 TV Safari/537.36"
    )
}

object BrowserEngine {
    private var runtime: GeckoRuntime? = null

    fun initRuntime(context: Context): GeckoRuntime {
        runtime?.let { return it }

        val contentBlocking = ContentBlocking.Settings.Builder()
            .antiTracking(
                ContentBlocking.AntiTracking.DEFAULT or
                    ContentBlocking.AntiTracking.STP or
                    ContentBlocking.AntiTracking.FINGERPRINTING or
                    ContentBlocking.AntiTracking.CRYPTOMINING
            )
            .safeBrowsing(ContentBlocking.SafeBrowsing.NONE)
            .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
            .build()

        val settings = GeckoRuntimeSettings.Builder()
            .javaScriptEnabled(true)
            .consoleOutput(false)
            .aboutConfigEnabled(true)
            .contentBlocking(contentBlocking)
            .build()

        return GeckoRuntime.create(context.applicationContext, settings).also {
            runtime = it
        }
    }

    fun createHardenedSession(
        context: Context,
        ephemeral: Boolean = false,
        profile: UserAgentProfile = UserAgentProfile.ANDROID,
        onUrlChanged: (String) -> Unit,
        onProgressChanged: (Int) -> Unit,
        onBlockedPopup: (String) -> Unit
    ): GeckoSession {
        val rt = initRuntime(context)
        val sessionSettings = GeckoSessionSettings.Builder()
            .usePrivateMode(ephemeral)
            .useTrackingProtection(true)
            .apply { profile.uaString?.let { userAgentOverride(it) } }
            .build()

        val session = GeckoSession(sessionSettings)

        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                if (!url.isNullOrBlank()) {
                    onUrlChanged(url)
                }
            }

            override fun onNewSession(
                session: GeckoSession,
                uri: String
            ): GeckoResult<GeckoSession> {
                onBlockedPopup(uri)
                return GeckoResult.fromValue(null)
            }
        }

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onProgressChange(session: GeckoSession, progress: Int) {
                onProgressChanged(progress)
            }
        }

        session.open(rt)
        return session
    }
}
