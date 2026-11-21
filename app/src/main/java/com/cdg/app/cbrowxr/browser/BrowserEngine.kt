package com.cdg.app.cbrowxr.browser

import android.content.Context
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebRequestError

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
    private var runtimeKey = ""

    fun initRuntime(
        context: Context,
        adBlockEnabled: Boolean = true,
        javaScriptEnabled: Boolean = true,
        cookiePolicy: CookiePolicy = CookiePolicy.FIRST_PARTY
    ): GeckoRuntime {
        val key = "$adBlockEnabled|$javaScriptEnabled|${cookiePolicy.name}"
        runtime?.takeIf { runtimeKey == key }?.let { return it }

        runtime?.shutdown()
        runtime = null

        val contentBlocking = ContentBlocking.Settings.Builder()
            .apply {
                if (adBlockEnabled) {
                    antiTracking(
                        ContentBlocking.AntiTracking.DEFAULT or
                            ContentBlocking.AntiTracking.STP or
                            ContentBlocking.AntiTracking.FINGERPRINTING or
                            ContentBlocking.AntiTracking.CRYPTOMINING
                    )
                }
            }
            .safeBrowsing(ContentBlocking.SafeBrowsing.NONE)
            .cookieBehavior(
                when (cookiePolicy) {
                    CookiePolicy.ALLOW ->
                        ContentBlocking.CookieBehavior.ACCEPT_ALL
                    CookiePolicy.FIRST_PARTY ->
                        ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS
                    CookiePolicy.BLOCK_ALL ->
                        ContentBlocking.CookieBehavior.ACCEPT_NONE
                }
            )
            .build()

        val settings = GeckoRuntimeSettings.Builder()
            .javaScriptEnabled(javaScriptEnabled)
            .consoleOutput(false)
            .aboutConfigEnabled(true)
            .contentBlocking(contentBlocking)
            .build()

        return GeckoRuntime.create(context.applicationContext, settings).also {
            runtime = it
            runtimeKey = key
        }
    }

    fun createHardenedSession(
        context: Context,
        ephemeral: Boolean = false,
        profile: UserAgentProfile = UserAgentProfile.ANDROID,
        trackingEnabled: Boolean = true,
        blockPopups: Boolean = true,
        adBlockEnabled: Boolean = true,
        javaScriptEnabled: Boolean = true,
        cookiePolicy: CookiePolicy = CookiePolicy.FIRST_PARTY,
        onUrlChanged: (String) -> Unit,
        onProgressChanged: (Int) -> Unit,
        onNavigationError: (String?, WebRequestError) -> Unit,
        onBlockedPopup: (String) -> Unit
    ): GeckoSession {
        val rt = initRuntime(context, adBlockEnabled, javaScriptEnabled, cookiePolicy)
        val sessionSettings = GeckoSessionSettings.Builder()
            .usePrivateMode(ephemeral)
            .useTrackingProtection(trackingEnabled)
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

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError
            ): GeckoResult<String>? {
                onNavigationError(uri, error)
                return null
            }

            override fun onNewSession(
                session: GeckoSession,
                uri: String
            ): GeckoResult<GeckoSession> {
                if (blockPopups) {
                    onBlockedPopup(uri)
                    return GeckoResult.fromValue(null)
                }
                val allowedSession = GeckoSession(
                    GeckoSessionSettings.Builder()
                        .usePrivateMode(ephemeral)
                        .useTrackingProtection(trackingEnabled)
                        .apply { profile.uaString?.let { userAgentOverride(it) } }
                        .build()
                )
                allowedSession.open(rt)
                allowedSession.loadUri(uri)
                onUrlChanged(uri)
                return GeckoResult.fromValue(allowedSession)
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
