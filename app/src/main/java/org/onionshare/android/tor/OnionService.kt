package org.onionshare.android.tor

import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import org.onionshare.android.ui.NOTIFICATION_ID
import org.onionshare.android.ui.OnionNotificationManager
import org.slf4j.LoggerFactory.getLogger
import org.torproject.jni.TorService
import javax.inject.Inject
import kotlin.system.exitProcess

private val LOG = getLogger(OnionService::class.java)

@AndroidEntryPoint
class OnionService : TorService() {

    @Inject
    internal lateinit var nm: OnionNotificationManager

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LOG.debug("onStartCommand $intent")
        startForeground(NOTIFICATION_ID, nm.getForegroundNotification())
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        LOG.debug("onDestroy")
        stopForeground(true)
        super.onDestroy()
        exitProcess(0)
    }

}
