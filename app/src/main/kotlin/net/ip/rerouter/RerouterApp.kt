package net.ip.rerouter

import android.app.Application
import com.topjohnwu.superuser.Shell

class RerouterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Pre-warm the root shell in the background so the first user action
        // isn't the one eating the su prompt latency.
        Shell.getShell {}
    }
}
