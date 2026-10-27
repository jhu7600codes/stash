package app.stash.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Track::class, Playlist::class, PlaylistTrack::class, UnmatchedTrack::class,
        ImportJob::class, ImportJobTrack::class, MatchCache::class, ArtistGenre::class, CoverCheck::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class StashDatabase : RoomDatabase() {
    abstract fun tracks(): TrackDao
    abstract fun playlists(): PlaylistDao
    abstract fun imports(): ImportDao

    companion object {
        /** v2: background import queue + match cache */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_1_2_SQL.forEach(db::execSQL)
            }
        }

        /** v3: artist genre cache for genre mixes, real covers */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `artist_genres` (`artist` TEXT NOT NULL, `genre` TEXT, " +
                        "`checkedAt` INTEGER NOT NULL, PRIMARY KEY(`artist`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `cover_checks` (`trackId` TEXT NOT NULL, `checkedAt` INTEGER NOT NULL, PRIMARY KEY(`trackId`))"
                )
                db.execSQL("ALTER TABLE `import_job_tracks` ADD COLUMN `sourceUri` TEXT DEFAULT NULL")
            }
        }
    }
}

// copied from the room-generated schemas/…/2.json so the tables match exactly
private val MIGRATION_1_2_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS `import_jobs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
        "`origin` TEXT NOT NULL, `originId` TEXT, `coverUrl` TEXT, `intoLiked` INTEGER NOT NULL, `playlistId` INTEGER, " +
        "`state` TEXT NOT NULL, `total` INTEGER NOT NULL, `done` INTEGER NOT NULL, `matched` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)",
    "CREATE TABLE IF NOT EXISTS `import_job_tracks` (`jobId` INTEGER NOT NULL, `position` INTEGER NOT NULL, `title` TEXT NOT NULL, " +
        "`artist` TEXT NOT NULL, `album` TEXT, `durationMs` INTEGER NOT NULL, `artworkUrl` TEXT, `state` TEXT NOT NULL, " +
        "PRIMARY KEY(`jobId`, `position`))",
    "CREATE TABLE IF NOT EXISTS `match_cache` (`key` TEXT NOT NULL, `trackId` TEXT, `checkedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))",
    // liked songs get their own order
    "ALTER TABLE `tracks` ADD COLUMN `likedAt` INTEGER NOT NULL DEFAULT 0",
    "UPDATE `tracks` SET `likedAt` = `addedAt` WHERE `liked` = 1",
    "ALTER TABLE `unmatched` ADD COLUMN `likedAt` INTEGER NOT NULL DEFAULT 0",
    // playlist_tracks is keyed by position now (a song can be in a playlist twice); sqlite can't
    // change a primary key in place, so rebuild the table
    "CREATE TABLE IF NOT EXISTS `playlist_tracks_new` (`playlistId` INTEGER NOT NULL, `trackId` TEXT NOT NULL, " +
        "`position` INTEGER NOT NULL, PRIMARY KEY(`playlistId`, `position`))",
    "INSERT OR IGNORE INTO `playlist_tracks_new` (`playlistId`, `trackId`, `position`) " +
        "SELECT `playlistId`, `trackId`, `position` FROM `playlist_tracks`",
    "DROP TABLE `playlist_tracks`",
    "ALTER TABLE `playlist_tracks_new` RENAME TO `playlist_tracks`",
    "CREATE INDEX IF NOT EXISTS `index_playlist_tracks_trackId` ON `playlist_tracks` (`trackId`)",
)
