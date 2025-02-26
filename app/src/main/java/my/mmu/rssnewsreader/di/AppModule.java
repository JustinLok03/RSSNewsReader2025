package my.mmu.rssnewsreader.di;

import android.app.Application;

import androidx.room.Room;

import my.mmu.rssnewsreader.data.database.AppDatabase;
import my.mmu.rssnewsreader.data.entry.EntryDao;
import my.mmu.rssnewsreader.data.feed.FeedDao;
import my.mmu.rssnewsreader.data.history.HistoryDao;
import my.mmu.rssnewsreader.data.playlist.PlaylistDao;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;

@Module
@InstallIn(SingletonComponent.class)
public class AppModule {

    @Provides
    @Singleton
    public static AppDatabase provideDatabase(Application app, AppDatabase.Callback callback) {
        return Room.databaseBuilder(app, AppDatabase.class, "app_database")
                .fallbackToDestructiveMigration()
                .addCallback(callback)
                .allowMainThreadQueries()
                .build();
    }

    @Provides // no need include singleton as room automatically set singleton for DAO
    public static FeedDao provideFeedDao(AppDatabase db) {
        return db.feedDao();
    }

    @Provides
    public static EntryDao provideEntryDao(AppDatabase db) {
        return db.entryDao();
    }

    @Provides
    public static PlaylistDao providePlaylistDao(AppDatabase db) {
        return db.playlistDao();
    }

    @Provides
    public static HistoryDao provideHistoryDao(AppDatabase db) {
        return db.historyDao();
    }
}
