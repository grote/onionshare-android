package org.onionshare.android.tor

import IPtProxy.IPtProxy
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.EXTRA_TEXT
import android.content.IntentFilter
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.LocalSocketAddress.Namespace.FILESYSTEM
import androidx.annotation.UiThread
import androidx.core.content.ContextCompat.startForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import net.freehaven.tor.control.RawEventListener
import net.freehaven.tor.control.TorControlCommands.EVENT_CIRCUIT_STATUS
import net.freehaven.tor.control.TorControlCommands.EVENT_ERR_MSG
import net.freehaven.tor.control.TorControlCommands.EVENT_HS_DESC
import net.freehaven.tor.control.TorControlCommands.EVENT_STATUS_CLIENT
import net.freehaven.tor.control.TorControlCommands.EVENT_WARN_MSG
import net.freehaven.tor.control.TorControlCommands.HS_ADDRESS
import net.freehaven.tor.control.TorControlConnection
import org.onionshare.android.server.PORT
import org.slf4j.LoggerFactory.getLogger
import org.torproject.jni.TorService
import org.torproject.jni.TorService.ACTION_ERROR
import org.torproject.jni.TorService.ACTION_STATUS
import org.torproject.jni.TorService.EXTRA_SERVICE_PACKAGE_NAME
import org.torproject.jni.TorService.EXTRA_STATUS
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

private val LOG = getLogger(TorManager::class.java)
private val EVENTS = listOf(
    EVENT_CIRCUIT_STATUS, // this one is needed for TorService to function
    EVENT_HS_DESC,
    EVENT_STATUS_CLIENT,
    EVENT_WARN_MSG,
    EVENT_ERR_MSG,
)
private val BOOTSTRAP_REGEX = Regex("^NOTICE BOOTSTRAP PROGRESS=([0-9]{1,3}) .*$")

@Singleton
class TorManager @Inject constructor(
    private val app: Application,
) {
    private val _state = MutableStateFlow<TorState>(TorState.Stopped)
    internal val state = _state.asStateFlow()
    private val broadcastReceiver = object : BroadcastReceiver() {
        /**
         * Attention: This gets executes on UI Thread
         */
        override fun onReceive(context: Context, i: Intent) {
            val intentPackageName = i.getStringExtra(EXTRA_SERVICE_PACKAGE_NAME)
            if (intentPackageName != context.packageName) return // this intent was not for us
            when (i.getStringExtra(EXTRA_STATUS)) {
                TorService.STATUS_STARTING -> LOG.debug("TorService: Starting...")
                TorService.STATUS_ON -> {
                    LOG.debug("TorService: Started")
                    startLatch?.countDown() ?: LOG.error("startLatch was null when Tor started.")
                }
                TorService.STATUS_STOPPING -> {
                    LOG.debug("TorService: Stopping...")
                    _state.value = TorState.Stopping
                }
                TorService.STATUS_OFF -> {
                    LOG.debug("TorService: Stopped")
                    onStopped()
                }
            }
        }
    }
    private val errorReceiver = object : BroadcastReceiver() {
        @UiThread
        override fun onReceive(context: Context, i: Intent) {
            LOG.error("TorService Error: ${i.getStringExtra(EXTRA_TEXT)}")
        }
    }

    @Volatile
    private var broadcastReceiverRegistered = false

    @Volatile
    private var localSocket: LocalSocket? = null
    private var startLatch: CountDownLatch? = null

    @Volatile
    private var controlConnection: TorControlConnection? = null
    private val onionListener = RawEventListener { keyword, data ->
        // TODO consider removing the logging below before release
        LOG.debug("$keyword: $data")
        // bootstrapping gets 70% of our progress
        if (keyword == EVENT_STATUS_CLIENT) {
            val matchResult = BOOTSTRAP_REGEX.matchEntire(data)
            val percent = matchResult?.groupValues?.get(1)?.toIntOrNull()
            if (percent != null) {
                val progress = (percent * 0.7).roundToInt()
                val newState = (state.value as? TorState.Starting)?.copy(progress = progress)
                    ?: TorState.Starting(progress)
                _state.value = newState
            }
            return@RawEventListener
        }
        val onion = (state.value as? TorState.Starting)?.onion
        // descriptor upload counts as 90%
        if (state.value !is TorState.Started) {
            if (onion != null && keyword == EVENT_HS_DESC && data.startsWith("UPLOAD $onion")) {
                _state.value = TorState.Starting(90, onion)
            }
        }
        // We consider already the first upload of the onion descriptor as started (100%).
        // In practice, more uploads are needed for the onion service to be reachable.
        if (onion != null && keyword == EVENT_HS_DESC && data.startsWith("UPLOADED $onion")) {
            _state.value = TorState.Started(onion)
        }
    }

    init {
        val ptStateDir = File(app.cacheDir, "pt")
        if (!ptStateDir.exists()) ptStateDir.mkdir()
        IPtProxy.setStateLocation(ptStateDir.absolutePath)
    }

    /**
     * Starts [TorService] and creates a new onion service.
     * Suspends until the address of the onion service is available.
     */
    @Suppress("BlockingMethodInNonBlockingContext")
    suspend fun start() = withContext(Dispatchers.IO) {
        if (state.value !is TorState.Stopped) stop()

        LOG.info("Starting...")
        _state.value = TorState.Starting(0)
        startLatch = CountDownLatch(1)
        app.registerReceiver(broadcastReceiver, IntentFilter(ACTION_STATUS))
        app.registerReceiver(errorReceiver, IntentFilter(ACTION_ERROR))
        broadcastReceiverRegistered = true

        Intent(app, ShareService::class.java).also { intent ->
            startForegroundService(app, intent)
        }
        try {
            startLatch?.await() ?: error("startLatch was null")
            startLatch = null

            startSnowflakeClientDomainFronting()

            onTorServiceStarted()
        } catch (e: Exception) {
            LOG.warn("Error while starting Tor: ", e)
            stop()
        }
    }

    fun stop() {
        LOG.info("Stopping...")
        _state.value = TorState.Stopping
        controlConnection = null
        Intent(app, ShareService::class.java).also { intent ->
            app.stopService(intent)
        }
        // wait for service to stop and broadcast when it gets destroyed
    }

    @UiThread
    fun onStopped() {
        if (broadcastReceiverRegistered) {
            app.unregisterReceiver(broadcastReceiver)
            app.unregisterReceiver(errorReceiver)
            broadcastReceiverRegistered = false
        }
        IPtProxy.stopSnowflake()
        localSocket = null
        LOG.info("Stopped")
        _state.value = TorState.Stopped
    }

    private fun startSnowflakeClientDomainFronting() {
        val target = "https://snowflake-broker.torproject.net.global.prod.fastly.net/"
        val front = "cdn.sstatic.net"
        val stunServer = "stun:stun.stunprotocol.org:3478"
        IPtProxy.startSnowflake(stunServer, target, front, null,
            null, true, false, false, 1)
    }

    private fun startSnowflakeAmp() {
        val stunServers =
            "stun:stun.l.google.com:19302,stun:stun.voip.blackberry.com:3478,stun:stun.altar.com.pl:3478,stun:stun.antisip.com:3478,stun:stun.bluesip.net:3478,stun:stun.dus.net:3478,stun:stun.epygi.com:3478,stun:stun.sonetel.com:3478,stun:stun.sonetel.net:3478,stun:stun.stunprotocol.org:3478,stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478,stun:stun.voys.nl:3478";
        val target = "https://snowflake-broker.torproject.net/";
        val front = "www.google.com";
        val ampCache = "https://cdn.ampproject.org/";
        IPtProxy.startSnowflake(stunServers, target, front, ampCache, null, true, false, false, 1);
    }

    @Throws(IOException::class, IllegalArgumentException::class)
    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun onTorServiceStarted() = withContext(Dispatchers.IO) {
        _state.value = TorState.Starting(5)
        controlConnection = startControlConnection().apply {
            addRawEventListener(onionListener)
            // create listeners as the first thing to prevent modification while already receiving events
            launchThread(true)
            authenticate(ByteArray(0))
            takeOwnership()
            setEvents(EVENTS)

            val conf = listOf(
                "ClientTransportPlugin snowflake socks5 127.0.0.1:" + IPtProxy.snowflakePort(),
                "UseBridges 1",
                "Bridge snowflake 192.0.2.3:1 2B280B23E1107BB62ABFC40DDCC8824814F80A72",
            )
            setConf(conf)

            val onion = createOnionService()
            _state.value = TorState.Starting(10, onion)
        }
    }

    /**
     * Creates a new onion service each time it is called
     * and returns its address (without the final .onion part).
     */
    @Throws(IOException::class)
    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun TorControlConnection.createOnionService(): String = withContext(Dispatchers.IO) {
        LOG.error("Starting hidden service...")
        val portLines = Collections.singletonMap(80, "127.0.0.1:$PORT")
        val response = addOnion("NEW:ED25519-V3", portLines, listOf("DiscardPK"))
        response[HS_ADDRESS]
            ?: throw IOException("Tor did not return a hidden service address")
    }

    @Throws(IOException::class)
    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun startControlConnection(): TorControlConnection = withContext(Dispatchers.IO) {
        val localSocketAddress = LocalSocketAddress(getControlPath(), FILESYSTEM)
        val client = LocalSocket()
        localSocket = client
        client.connect(localSocketAddress)

        val controlFileDescriptor = client.fileDescriptor
        val inputStream = FileInputStream(controlFileDescriptor)
        val outputStream = FileOutputStream(controlFileDescriptor)
        TorControlConnection(inputStream, outputStream)
    }

    /**
     * Returns the absolute path to the control socket on the local filesystem.
     *
     * Note: Exposing this through TorService was rejected by upstream.
     *       https://github.com/guardianproject/tor-android/pull/61
     */
    private fun getControlPath(): String {
        val serviceDir = app.applicationContext.getDir(TorService::class.java.simpleName, 0)
        val dataDir = File(serviceDir, "data")
        return File(dataDir, "ControlSocket").absolutePath
    }

}
