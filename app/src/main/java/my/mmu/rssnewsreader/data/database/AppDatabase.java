package my.mmu.rssnewsreader.data.database;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;
import android.content.Context;
import android.util.Log;

import my.mmu.rssnewsreader.data.entry.Entry;
import my.mmu.rssnewsreader.data.entry.EntryDao;
import my.mmu.rssnewsreader.data.feed.Feed;
import my.mmu.rssnewsreader.data.feed.FeedDao;
import my.mmu.rssnewsreader.data.history.History;
import my.mmu.rssnewsreader.data.history.HistoryDao;
import my.mmu.rssnewsreader.data.playlist.Playlist;
import my.mmu.rssnewsreader.data.playlist.PlaylistDao;

import javax.inject.Inject;
import javax.inject.Provider;

@Database(entities = {Feed.class, Entry.class, Playlist.class, History.class}, version = 3)
@androidx.room.TypeConverters({TypeConverters.class})
// make this abstract to let room do the implementation
public abstract class AppDatabase extends RoomDatabase {

    public abstract FeedDao feedDao();
    public abstract EntryDao entryDao();
    public abstract PlaylistDao playlistDao();
    public abstract HistoryDao historyDao();

    // Migration from version 2 to 3
    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            try {
                database.execSQL("ALTER TABLE entry_table ADD COLUMN isCached INTEGER NOT NULL DEFAULT 0");
                database.execSQL("ALTER TABLE feed_table ADD COLUMN isPreloaded INTEGER NOT NULL DEFAULT 0");
                Log.d("DatabaseMigration", "Migration from v2 to v3 completed successfully.");
            } catch (Exception e) {
                Log.e("DatabaseMigration", "Migration failed: " + e.getMessage());
            }
        }
    };

    public static synchronized AppDatabase getInstance(Context context) {
        return Room.databaseBuilder(context.getApplicationContext(),
                        AppDatabase.class, "rss_database")
                .addMigrations(MIGRATION_2_3)  // Ensure migration is applied
                .fallbackToDestructiveMigration()
                .build();
    }

    public static class Callback extends RoomDatabase.Callback {

        private Provider<AppDatabase> database;

        @Inject
        public Callback(Provider<AppDatabase> db) {
            super();
            this.database = db;
        }

        @Override
        public void onCreate(@NonNull SupportSQLiteDatabase db) {
            super.onCreate(db);

            // since db is not instantiated at this stage (db will only be created after build()), dagger will create an instance to run this
//            FeedDao feedDao = database.get().feedDao();
        }
    }
}
