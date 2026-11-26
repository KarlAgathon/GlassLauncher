package com.glass.launcher.vpntest

import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.glass.speed.SpeedEngine

/**
 * Runs the speed test inside this app – which the user put on the VPN's list – and reports
 * progress and the result back to LEAD Launcher.
 */
class ProbeService : Service() {
    @Volatile private var engine: SpeedEngine? = null

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what != SpeedEngine.MSG_START) return
            val reply = msg.replyTo ?: return
            engine?.stop = true
            val manager = getSystemService(ConnectivityManager::class.java)
            val network = manager.activeNetwork
            if (network == null || !SpeedEngine.matchesRoute(manager, network, vpn = true)) {
                runCatching { reply.send(Message.obtain(null, SpeedEngine.MSG_NO_VPN_ROUTE)) }
                return
            }
            val e = SpeedEngine(manager, network, vpn = true)
            engine = e
            Thread {
                fun send(what: Int, arg: Int = 0, b: Bundle? = null) {
                    runCatching { reply.send(Message.obtain(null, what, arg, 0).apply { if (b != null) data = b }) }.onFailure { e.stop = true }
                }
                send(SpeedEngine.MSG_PHASE, 0)
                val w = e.where()
                send(SpeedEngine.MSG_PHASE, 1)
                val p = e.ping()
                if (p == null) { send(SpeedEngine.MSG_ERROR); return@Thread }
                send(SpeedEngine.MSG_PHASE, 2, Bundle().apply { putInt("ping", p); putString("ip", w?.ip); putString("cc", w?.country); putBoolean("vpn", true) })
                val d = e.download { v -> send(SpeedEngine.MSG_SAMPLE, b = Bundle().apply { putFloat("v", v) }) }
                send(SpeedEngine.MSG_PHASE, 3, Bundle().apply { putDouble("down", d) })
                val u = e.upload { v -> send(SpeedEngine.MSG_SAMPLE, b = Bundle().apply { putFloat("v", v) }) }
                if (!e.routeValid() || !d.isFinite() || !u.isFinite() || d <= 0 || u <= 0) {
                    send(SpeedEngine.MSG_ERROR)
                    return@Thread
                }
                send(SpeedEngine.MSG_RESULT, b = Bundle().apply {
                    putBoolean(SpeedEngine.VERIFIED_ROUTE, true)
                    putString("ip", w?.ip); putString("cc", w?.country); putInt("ping", p)
                    putDouble("down", d); putDouble("up", u); putBoolean("vpn", true)
                })
            }.start()
        }
    }
    private val messenger = Messenger(handler)

    override fun onBind(intent: Intent?): IBinder = messenger.binder
    override fun onUnbind(intent: Intent?): Boolean { engine?.stop = true; return false }
}
