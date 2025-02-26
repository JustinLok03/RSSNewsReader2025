package my.mmu.rssnewsreader.service.rss;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.hilt.work.HiltWorker;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import my.mmu.rssnewsreader.data.feed.FeedRepository;
import my.mmu.rssnewsreader.service.tts.TtsExtractor;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedInject;

@HiltWorker
public class RssWorker extends Worker {

    public static final String TAG = "RssWorker";

    private FeedRepository feedRepository;
    private TtsExtractor ttsExtractor;
    private Context context;

    @AssistedInject
    public RssWorker(@Assisted @NonNull Context context, @Assisted @NonNull WorkerParameters workerParams, FeedRepository feedRepository, TtsExtractor ttsExtractor) {
        super(context, workerParams);
        this.context = context;
        this.feedRepository = feedRepository;
        this.ttsExtractor = ttsExtractor;
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            Log.d(TAG, "Starting RSS refresh...");
            String text = feedRepository.refreshEntries();
            RssNotification rssNotification = new RssNotification(context);
            rssNotification.sendNotification(text);
            ttsExtractor.extractAllEntries();
            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "Error in RSS refresh: " + e.getMessage());
            return Result.retry();
        }
    }
}
