package org.onionshare.android.server

import android.net.TrafficStats
import android.util.Base64
import android.util.Base64.NO_PADDING
import android.util.Base64.URL_SAFE
import com.mitchellbosecke.pebble.loader.ClasspathLoader
import io.ktor.http.ContentDisposition.Companion.Attachment
import io.ktor.http.ContentDisposition.Parameters.FileName
import io.ktor.http.HttpHeaders.ContentDisposition
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.resources
import io.ktor.server.http.content.static
import io.ktor.server.netty.Netty
import io.ktor.server.pebble.Pebble
import io.ktor.server.pebble.PebbleContent
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.util.concurrent.RejectedExecutionException
import javax.inject.Inject
import javax.inject.Singleton

private val LOG = LoggerFactory.getLogger(WebserverManager::class.java)
internal const val PORT: Int = 17638

sealed class WebServerState {
    object Starting : WebServerState()
    object Started : WebServerState()
    data class Stopping(val downloadComplete: Boolean = false) : WebServerState()
    data class Stopped(val downloadComplete: Boolean) : WebServerState()
}

@Singleton
@OptIn(DelicateCoroutinesApi::class)
class WebserverManager @Inject constructor() {

    private val secureRandom = SecureRandom()
    private var server: ApplicationEngine? = null
    private val _state = MutableStateFlow<WebServerState>(WebServerState.Stopped(false))
    val state = _state.asStateFlow()

    fun start(sendPage: SendPage) {
        _state.value = WebServerState.Starting
        val staticPath = getStaticPath()
        val staticPathMap = mapOf("static_url_path" to staticPath)
        TrafficStats.setThreadStatsTag(0x42)
        server = embeddedServer(Netty, PORT, watchPaths = emptyList(), configure = {
            // disable response timeout
            responseWriteTimeoutSeconds = 0
        }) {
            install(CallLogging)
            install(Pebble) {
                loader(ClasspathLoader().apply { prefix = "assets/templates" })
            }
            installStatusPages(staticPathMap)
            addListener()
            routing {
                defaultRoutes(staticPath)
                sendRoutes(sendPage, staticPathMap)
            }
        }.also { it.start() }
    }

    fun stop(isFinishingDownloading: Boolean = false) {
        LOG.info("Stopping... (isFinishingDownloading: $isFinishingDownloading)")
        try {
            // Netty doesn't start to really shut down until gracePeriodMillis is over.
            // So we can't use Long.MAX_VALUE for this or the server will never stop.
            // But downloading a file seems to submit new tasks, so the gracePeriodMillis needs to cover the entire
            // download. If the grace-period is over too soon, the download tasks get rejected and the server stops
            // before the download could finish.
            val timeout = if (isFinishingDownloading) {
                _state.value = WebServerState.Stopping(true)
                120_000L
            } else 500L
            server?.stop(timeout, timeout * 2)
        } catch (e: RejectedExecutionException) {
            LOG.warn("Error while stopping webserver", e)
        }
    }

    private fun getStaticPath(): String {
        val staticSuffixBytes = ByteArray(16).apply { secureRandom.nextBytes(this) }
        val staticSuffix =
            Base64.encodeToString(staticSuffixBytes, NO_PADDING or URL_SAFE).trimEnd()
        return "/static_$staticSuffix"
    }

    private fun Application.addListener() {
        environment.monitor.subscribe(ApplicationStarted) {
            _state.value = WebServerState.Started
        }
        environment.monitor.subscribe(ApplicationStopping) {
            // only update if we are not already stopping
            if (state.value !is WebServerState.Stopping) _state.value = WebServerState.Stopping()
        }
        environment.monitor.subscribe(ApplicationStopped) {
            LOG.info("Stopped")
            val downloadComplete = (state.value as? WebServerState.Stopping)?.downloadComplete ?: false
            _state.value = WebServerState.Stopped(downloadComplete)
            server = null
        }
    }

    private fun Application.installStatusPages(staticPathMap: Map<String, String>) {
        install(StatusPages) {
            status(HttpStatusCode.NotFound) { call, _ ->
                call.respond(PebbleContent("404.html", staticPathMap))
            }
            status(HttpStatusCode.MethodNotAllowed) { call, _ ->
                call.respond(PebbleContent("405.html", staticPathMap))
            }
            status(HttpStatusCode.InternalServerError) { call, _ ->
                call.respond(PebbleContent("500.html", staticPathMap))
            }
        }
    }

    private fun Route.defaultRoutes(staticPath: String) {
        static("$staticPath/css") {
            resources("assets/static/css")
        }
        static("$staticPath/img") {
            resources("assets/static/img")
        }
        static("$staticPath/js") {
            resources("assets/static/js")
        }
    }

    private fun Route.sendRoutes(sendPage: SendPage, staticPathMap: Map<String, String>) {
        get("/") {
            val model = sendPage.model + staticPathMap
            call.respond(PebbleContent("send.html", model))
        }
        get("/download") {
            call.response.header(
                ContentDisposition,
                Attachment.withParameter(FileName, sendPage.fileName).toString()
            )
            call.respondFile(sendPage.zipFile)
            LOG.info("Download complete.")
            // stopping in the same coroutine context causes a hang and the server never stops
            GlobalScope.launch(Dispatchers.IO) {
                stop(true)
            }
        }
    }
}
