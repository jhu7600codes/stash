package app.stash.di

import android.content.Context
import androidx.room.Room
import app.stash.data.db.ImportDao
import app.stash.data.db.PlaylistDao
import app.stash.data.db.StashDatabase
import app.stash.data.db.TrackDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): StashDatabase =
        Room.databaseBuilder(context, StashDatabase::class.java, "stash.db")
            .addMigrations(StashDatabase.MIGRATION_1_2, StashDatabase.MIGRATION_2_3)
            .build()

    @Provides
    fun trackDao(db: StashDatabase): TrackDao = db.tracks()

    @Provides
    fun playlistDao(db: StashDatabase): PlaylistDao = db.playlists()

    @Provides
    fun importDao(db: StashDatabase): ImportDao = db.imports()

    @Provides
    @Singleton
    fun http(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
}
