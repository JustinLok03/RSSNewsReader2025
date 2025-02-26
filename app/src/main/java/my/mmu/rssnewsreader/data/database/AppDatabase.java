package my.mmu.rssnewsreader.data.database;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.RoomDatabase;
import androidx.sqlite.db.SupportSQLiteDatabase;

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

@Database(entities = {Feed.class, Entry.class, Playlist.class, History.class}, version = 2)
@androidx.room.TypeConverters({TypeConverters.class})
// make this abstract to let room do the implementation
public abstract class AppDatabase extends RoomDatabase {

    public abstract FeedDao feedDao();
    public abstract EntryDao entryDao();
    public abstract PlaylistDao playlistDao();
    public abstract HistoryDao historyDao();

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
