package app.stash

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.stash.cloud.CloudBackup
import app.stash.recs.Covers
import app.stash.recs.Recommender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import app.stash.download.Downloads
import app.stash.importer.ImportQueue
import app.stash.providers.YtDlp
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class StashApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var ytDlp: YtDlp
    @Inject lateinit var downloads: Downloads
    @Inject lateinit var imports: ImportQueue
    @Inject lateinit var cloud: CloudBackup
    @Inject lateinit var recommender: Recommender
    @Inject lateinit var frontends: app.stash.providers.Frontends
    @Inject lateinit var covers: Covers

    // set up before anything else (even content providers): a crash gets written down, and
    // MainActivity shows it on the next launch so it can be photographed / copied
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(base)
        CrashReport.install(base)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DOWNLOADS, "Downloads", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_IMPORTS, "Moving playlists", NotificationManager.IMPORTANCE_LOW)
        )
        ytDlp.start()
        downloads.recoverAfterRestart()
        imports.resume()
        cloud.startAutoBackup()
        covers.fixInBackground()
        // daily mixes / discover weekly, only rebuilt when they're out of date
        ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.IO) { recommender.refreshMixes() }
        // the community list of youtube frontends changes often: refresh it on launch
        ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.IO) { frontends.update() }
    }

    companion object {
        const val CHANNEL_DOWNLOADS = "downloads"
        const val CHANNEL_IMPORTS = "imports"
    }
}
