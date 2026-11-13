package com.example.fm

import android.app.Application
import com.topjohnwu.superuser.Shell

class FmApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (System.getProperty("user.home") == null) System.setProperty("user.home", filesDir.path)
        Fs.localPrefix = cacheDir.path
        DLog.init(this)
        Shell.setDefaultBuilder(Shell.Builder.create().setFlags(Shell.FLAG_MOUNT_MASTER).setTimeout(15))
    }
}
