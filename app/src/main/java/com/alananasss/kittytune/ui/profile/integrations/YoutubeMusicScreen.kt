package com.alananasss.kittytune.ui.profile.integrations

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.alananasss.kittytune.R
import com.alananasss.kittytune.data.ytmusic.YtmImporter
import com.alananasss.kittytune.data.ytmusic.YtmSession
import com.alananasss.kittytune.ui.common.SettingsScaffold
import com.zionhuang.innertube.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOGIN_URL =
    "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&passive=true" +
        "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F"

/** Log in to YouTube Music (Google sign-in in a WebView) and copy the library into the app. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YoutubeMusicScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loggedIn by remember { mutableStateOf(YtmSession.isLoggedIn(context)) }
    var name by remember { mutableStateOf(YtmSession.accountName(context)) }
    var showLogin by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    if (showLogin) {
        BackHandler { showLogin = false }
        SettingsScaffold(title = stringResource(R.string.ytm_title), onBackClick = { showLogin = false }) { padding ->
            AndroidView(
                modifier = Modifier.fillMaxSize().padding(padding),
                factory = { ctx ->
                    var done = false
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // Google refuses sign-in inside a WebView that announces itself as one.
                        settings.userAgentString = settings.userAgentString.replace("; wv", "")
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                if (done || !url.startsWith("https://music.youtube.com")) return
                                val cookie = CookieManager.getInstance().getCookie(url)
                                if (cookie?.contains("SAPISID=") != true) return
                                done = true
                                scope.launch {
                                    YtmSession.save(context, cookie, null)
                                    val account = YouTube.accountInfo().getOrNull()?.name
                                    YtmSession.save(context, cookie, account)
                                    name = account
                                    loggedIn = true
                                    showLogin = false
                                }
                            }
                        }
                        loadUrl(LOGIN_URL)
                    }
                }
            )
        }
        return
    }

    SettingsScaffold(title = stringResource(R.string.ytm_title), onBackClick = onBackClick) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                if (loggedIn) stringResource(R.string.ytm_subtitle_connected, name ?: "")
                else stringResource(R.string.ytm_subtitle_guest)
            )
            if (!loggedIn) {
                Button(onClick = { showLogin = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.ytm_login))
                }
            } else {
                Text(stringResource(R.string.ytm_import_desc))
                Button(
                    enabled = !importing,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        importing = true
                        scope.launch {
                            status = try {
                                val s = withContext(Dispatchers.IO) {
                                    YtmImporter.import(context) { step ->
                                        status = context.getString(
                                            when (step) {
                                                YtmImporter.Step.LIKES -> R.string.ytm_step_likes
                                                YtmImporter.Step.PLAYLISTS -> R.string.ytm_step_playlists
                                                YtmImporter.Step.ARTISTS -> R.string.ytm_step_artists
                                                YtmImporter.Step.HISTORY -> R.string.ytm_step_history
                                            }
                                        )
                                    }
                                }
                                context.getString(R.string.ytm_import_done, s.likes, s.playlists, s.artists, s.history)
                            } catch (e: Exception) {
                                android.util.Log.e("YtmImport", "import failed", e)
                                context.getString(R.string.ytm_import_failed, e.message ?: e.javaClass.simpleName)
                            }
                            importing = false
                        }
                    }
                ) { Text(stringResource(R.string.ytm_import)) }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        YtmSession.logout(context)
                        loggedIn = false
                        name = null
                        status = null
                    }
                ) { Text(stringResource(R.string.ytm_logout)) }
            }
            status?.let { Text(it) }
        }
    }
}
