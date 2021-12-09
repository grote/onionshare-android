package org.onionshare.android.tor

import android.app.Application
import android.app.Application.BIND_AUTO_CREATE
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import androidx.core.content.ContextCompat.startForegroundService
import net.freehaven.tor.control.TorControlCommands.EVENT_CIRCUIT_STATUS
import net.freehaven.tor.control.TorControlCommands.EVENT_ERR_MSG
import net.freehaven.tor.control.TorControlCommands.EVENT_HS_DESC
import net.freehaven.tor.control.TorControlCommands.EVENT_NEW_DESC
import net.freehaven.tor.control.TorControlCommands.EVENT_OR_CONN_STATUS
import net.freehaven.tor.control.TorControlCommands.EVENT_WARN_MSG
import net.freehaven.tor.control.TorControlCommands.HS_ADDRESS
import net.freehaven.tor.control.TorControlConnection
import org.slf4j.LoggerFactory.getLogger
import org.torproject.jni.TorService
import org.torproject.jni.TorService.ACTION_STATUS
import org.torproject.jni.TorService.EXTRA_STATUS
import org.torproject.jni.TorService.getControlSocketFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private val LOG = getLogger(TorManager::class.java)
private val EVENTS = listOf(
    EVENT_CIRCUIT_STATUS, // this one is needed for TorService to function
    EVENT_OR_CONN_STATUS,
    EVENT_HS_DESC,
    EVENT_NEW_DESC,
    EVENT_WARN_MSG,
    EVENT_ERR_MSG,
)

@Singleton
class TorManager @Inject constructor(
    private val app: Application,
) {
    private var binder: IBinder? = null
    private var broadcastReceiver: BroadcastReceiver? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            LOG.info("OnionService connected")
            this@TorManager.binder = binder
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            LOG.info("OnionService disconnected")
            binder = null
        }
    }

    /**
     * Starts [TorService] and creates a new onion service.
     * Suspends until the address of the onion service is available.
     */
    suspend fun start(port: Int): String = suspendCoroutine { continuation ->
        LOG.info("Starting...")
        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, i: Intent) {
                when (i.getStringExtra(EXTRA_STATUS)) {
                    TorService.STATUS_STARTING -> LOG.debug("TorService: Starting...")
                    TorService.STATUS_ON -> {
                        LOG.debug("TorService: Started")
                        val controlFileDescriptor = getControlSocketFileDescriptor(context)
                        val inputStream = FileInputStream(controlFileDescriptor)
                        val outputStream = FileOutputStream(controlFileDescriptor)
                        TorControlConnection(inputStream, outputStream).apply {
                            try {
                                launchThread(true)
                                authenticate(ByteArray(0))
                                setEvents(EVENTS)
                                addRawEventListener { keyword, data ->
                                    LOG.debug("$keyword: $data")
                                }
                            } catch (e: Exception) {
                                // gets caught and logged by caller
                                continuation.resumeWithException(e)
                                return
                            }
                            createOnionService(this, continuation, port)
                        }
                    }
                    // FIXME When we stop unplanned, we need to inform the ShareManager
                    //  that we stopped, so it can clear its state up, stopping webserver, etc.
                    TorService.STATUS_STOPPING -> LOG.debug("TorService: Stopping...")
                    TorService.STATUS_OFF -> LOG.debug("TorService: Stopped")
                }
            }
        }
        app.registerReceiver(broadcastReceiver, IntentFilter(ACTION_STATUS))

        Intent(app, OnionService::class.java).also { intent ->
            intent.putExtra(TorService.EXTRA_PID, Process.myPid())
            startForegroundService(app, intent)
            app.bindService(intent, serviceConnection, BIND_AUTO_CREATE)
        }
        // this method suspends here until it the continuation resumes it
    }

    fun stop() {
        LOG.info("Stopping...")
        // FIXME TorService crashes us when getting destroyed a second time
        //  see: https://github.com/guardianproject/tor-android/issues/57
        if (binder != null) app.unbindService(serviceConnection)
        binder = null
        // simply unbinding doesn't seem sufficient for stopping a foreground service
        Intent(app, OnionService::class.java).also { intent ->
            app.stopService(intent)
        }
        broadcastReceiver?.let { app.unregisterReceiver(it) }
        broadcastReceiver = null
        LOG.info("Stopped")
    }

    /**
     * Creates a new onion service each time it is called
     * and resumes the given [continuation] with its address.
     */
    private fun createOnionService(
        controlConnection: TorControlConnection,
        continuation: Continuation<String>,
        port: Int,
    ) {
        LOG.error("Starting hidden service...")
        val portLines = Collections.singletonMap(80, "127.0.0.1:$port")
        val response = try {
            controlConnection.addOnion("NEW:ED25519-V3", portLines, null)
        } catch (e: IOException) {
            LOG.error("Error creation onion service", e)
            continuation.resumeWithException(e)
            return
        }
        if (!response.containsKey(HS_ADDRESS)) {
            LOG.error("Tor did not return a hidden service address")
            continuation.resumeWithException(IOException("No HS_ADDRESS"))
        }
        // TODO do we need to wait for the service to be actually reachable?
        //  The HTTP connection seems to hang a bit, but finds the service soon enough.
        //  Maybe on slow connections, getting the service up takes more time?
        continuation.resume("${response[HS_ADDRESS]}.onion")
    }

}
