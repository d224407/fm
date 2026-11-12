package com.example.fm

import android.app.Application
import com.topjohnwu.superuser.Shell

class FmApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // JGit reads user.home for its global config; Android leaves it null (init then fails half-way)
        if (System.getProperty("user.home") == null) System.setProperty("user.home", filesDir.path)
        Fs.localPrefix = cacheDir.path
        DLog.init(this)
        // Global mount namespace so "/" looks the same as in a root shell
        Shell.setDefaultBuilder(Shell.Builder.create().setFlags(Shell.FLAG_MOUNT_MASTER).setTimeout(15))
    }
}
