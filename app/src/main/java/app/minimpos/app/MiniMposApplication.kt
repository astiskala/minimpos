package app.minimpos.app

import android.app.Application
import android.os.StrictMode

/** Creates the process-wide [AppContainer] and starts its background work (see [AppContainer.start]). */
class MiniMposApplication : Application() {
    /** The dependencies of this process; set in [onCreate], before any activity starts. */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        // Debug builds only (R8 drops this from release): main-thread disk or network access and leaked resources
        // show in logcat as stack traces, never with request or secret values.
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy
                    .Builder()
                    .detectAll()
                    .penaltyLog()
                    .build(),
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy
                    .Builder()
                    .detectLeakedClosableObjects()
                    .detectLeakedSqlLiteObjects()
                    .detectLeakedRegistrationObjects()
                    .detectActivityLeaks()
                    .penaltyLog()
                    .build(),
            )
        }
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }
}
