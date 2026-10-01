package io.minimpos.app

import android.app.Application

/** Creates the process-wide [AppContainer] and starts its background work (see [AppContainer.start]). */
class MiniMposApplication : Application() {
    /** The dependencies of this process; set in [onCreate], before any activity starts. */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }
}
