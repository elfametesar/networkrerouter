package net.ip.rerouter

import android.app.Application
import com.topjohnwu.superuser.Shell

class RerouterApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // libsu main shell oluşturulmadan ÖNCE default builder'ı ayarla.
        // Shell.getShell() bundan sonra çağrılmalı.
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(15)
        )

        // Root shell'i önceden oluştur.
        // Artık doğru builder kullanılarak oluşturulacak.
        Shell.getShell {}
    }
}
