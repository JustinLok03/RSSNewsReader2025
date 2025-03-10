package my.mmu.rssnewsreader.service.tts;

import android.app.Notification;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.media.MediaBrowserServiceCompat;

import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.functions.Action;
import io.reactivex.rxjava3.schedulers.Schedulers;
import my.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;

@AndroidEntryPoint
public class TtsService extends MediaBrowserServiceCompat {

    private static final String TAG = "TtsService";

    @Inject
    TtsPlayer ttsPlayer;
    @Inject
    TtsPlaylist ttsPlaylist;
    @Inject
    SharedPreferencesRepository sharedPreferencesRepository;
    private TtsNotification ttsNotification;
    private static MediaSessionCompat mediaSession;
    private MediaMetadataCompat preparedData;
    private boolean serviceInStartedState;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        TtsMediaButtonReceiver.handleIntent(mediaSession, intent);
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "created");
        ttsPlayer.initTts(TtsService.this, new TtsPlayerListener(), callback);
        ttsNotification = new TtsNotification(this);

        mediaSession = new MediaSessionCompat(this, TAG);
        mediaSession.setCallback(callback);
        mediaSession.setMediaButtonReceiver(null);
        setSessionToken(mediaSession.getSessionToken());
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "destroyed");
        ttsPlayer.stop();
        mediaSession.release();
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        stopSelf();
    }

    @Nullable
    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid, @Nullable Bundle rootHints) {
        return new BrowserRoot("success", null);
    }

    @Override
    public void onLoadChildren(@NonNull String parentId, @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        preparedData = ttsPlaylist.getCurrentMetadata();
        result.sendResult(ttsPlaylist.getMediaItems());
    }

    private final MediaSessionCompat.Callback callback = new MediaSessionCompat.Callback() {

        @Override
        public void onPrepare() {
            Log.d(TAG, "onPrepare called");
            Completable.fromAction(() -> {
                if (!ttsPlayer.isPausedManually()) {
                    ttsPlayer.setupMediaPlayer(false);
                }
                if (ttsPlayer.ttsIsNull()) {
                    ttsPlayer.initTts(TtsService.this, new TtsPlayerListener(), callback);
                }
                preparedData = ttsPlaylist.getCurrentMetadata();
                if (!mediaSession.isActive()) {
                    mediaSession.setActive(true);
                }
                mediaSession.setMetadata(preparedData);
                ttsPlayer.setTtsSpeechRate(Float.parseFloat(preparedData.getString("ttsSpeechRate")));
                String content = preparedData.getString("content");

                String feedLanguage = preparedData.getString("language");
                if (feedLanguage == null || feedLanguage.isEmpty()) {
                    feedLanguage = "en"; // Default to English
                }

                // ✅ Get the user's selected translation language
                String targetLanguage = sharedPreferencesRepository.getDefaultTranslationLanguage();
                if (targetLanguage == null || targetLanguage.isEmpty()) {
                    targetLanguage = "zh"; // Default to Chinese if undefined
                }

                // ✅ Detect if the article is translated
                boolean isTranslated = preparedData.getString("html") != null && preparedData.getString("html").contains("translated-title");

                // ✅ Choose the language based on whether it's translated or not
                String languageToUse = isTranslated ? targetLanguage : feedLanguage;

                // ✅ Pass the correct language to TTS
                ttsPlayer.extract(
                        Long.parseLong(preparedData.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID)),
                        preparedData.getLong("feedId"),
                        content,
                        languageToUse // Use the correct language
                );

                ttsPlayer.extract(Long.parseLong(preparedData.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID)),
                        preparedData.getLong("feedId"), content, preparedData.getString("language"));
            }).subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread()).subscribe();
        }

        @Override
        public void onPlay() {
            Log.d(TAG, "onPlay called");
            if (!ttsPlayer.isPreparing()) {
                ttsPlayer.setPausedManually(false);
                ttsPlayer.setupMediaPlayer(false);
                play();
            }
        }

        @Override
        public void onPause() {
            Log.d(TAG, "onPause called");
            ttsPlayer.setPausedManually(true);
            ttsPlayer.pause();
            ttsPlayer.pauseMediaPlayer();
            updatePlaybackState(PlaybackStateCompat.STATE_PAUSED);
        }

        @Override
        public void onStop() {
            Log.d(TAG, "onStop called");
            ttsPlayer.stop();
            ttsPlaylist.updatePlayingId(0);
            mediaSession.setActive(false);
            stopSelf();
            updatePlaybackState(PlaybackStateCompat.STATE_STOPPED);
        }

        @Override
        public void onSkipToNext() {
            Log.d(TAG, "onSkipToNext called");
            ttsPlayer.pause();
            if (ttsPlaylist.skipNext()) {
                preparedData = null;
                play();
            } else {
                ttsPlayer.stopMediaPlayer();
            }
        }

        @Override
        public void onSkipToPrevious() {
            Log.d(TAG, "onSkipToPrevious called");
            ttsPlayer.pause();
            if (ttsPlaylist.skipPrevious()) {
                preparedData = null;
                play();
            } else {
                ttsPlayer.stopMediaPlayer();
            }
        }

        @Override
        public void onFastForward() {
            Log.d(TAG, "onFastForward called");
            ttsPlayer.fastForward();
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
        }

        @Override
        public void onRewind() {
            Log.d(TAG, "onRewind called");
            ttsPlayer.fastRewind();
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
        }

        @Override
        public void onCustomAction(String action, Bundle extras) {
            super.onCustomAction(action, extras);
            Log.d(TAG, "onCustomAction: Action = " + action);
            switch (action) {
                case "autoPlay":
                    play();
                    break;
                case "playFromService":
                    if (preparedData == null) {
                        onPrepare();
                    } else {
                        if (!ttsPlayer.isUiControlPlayback()) {
                            ttsPlayer.play();
                        }
                    }
                    break;
                default:
                    Log.w(TAG, "Unhandled custom action: " + action);
            }
        }

        private void play() {
            if (preparedData == null) {
                onPrepare();
            } else {
                ttsPlayer.play();
                ttsPlayer.setUiControlPlayback(false);
            }
        }

        private void updatePlaybackState(int state) {
            PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder();
            stateBuilder.setActions(
                    PlaybackStateCompat.ACTION_PLAY |
                            PlaybackStateCompat.ACTION_PAUSE |
                            PlaybackStateCompat.ACTION_PLAY_PAUSE |
                            PlaybackStateCompat.ACTION_STOP |
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                            PlaybackStateCompat.ACTION_FAST_FORWARD |
                            PlaybackStateCompat.ACTION_REWIND
            );
            stateBuilder.setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f, SystemClock.elapsedRealtime());
            mediaSession.setPlaybackState(stateBuilder.build());
        }
    };

    public class TtsPlayerListener extends PlaybackStateListener {

        private final ServiceManager serviceManager;

        TtsPlayerListener() {
            serviceManager = new ServiceManager();
        }

        @Override
        public void
        onPlaybackStateChange(PlaybackStateCompat state) {
            mediaSession.setPlaybackState(state);

            switch (state.getState()) {
                case PlaybackStateCompat.STATE_PLAYING:
                    serviceManager.moveServiceToStartedState(state);
                    break;
                case PlaybackStateCompat.STATE_PAUSED:
                    serviceManager.updateNotificationForPause(state);
                    break;
                case PlaybackStateCompat.STATE_STOPPED:
                    serviceManager.moveServiceOutOfStartedState(state);
                    break;
            }
        }

        class ServiceManager {

            private final Intent intent = new Intent(TtsService.this, TtsService.class);

            private void moveServiceToStartedState(PlaybackStateCompat state) {
                Log.d(TAG, "notification to play");
                Notification notification = ttsNotification.getNotification(preparedData, state, getSessionToken());

                if (!serviceInStartedState) {
                    ContextCompat.startForegroundService(TtsService.this, intent);
                    startForeground(TtsNotification.TTS_NOTIFICATION_ID, notification);
                    serviceInStartedState = true;
                } else {
                    ttsNotification.getNotificationManager().notify(TtsNotification.TTS_NOTIFICATION_ID, notification);
                }
            }

            private void updateNotificationForPause(PlaybackStateCompat state) {

                Log.d(TAG, "notification to pause");

                if (Build.VERSION.SDK_INT < 31) {
                    stopForeground(false);
                }

                Notification notification = ttsNotification.getNotification(preparedData, state, getSessionToken());
                ttsNotification.getNotificationManager().notify(TtsNotification.TTS_NOTIFICATION_ID, notification);
            }

            private void moveServiceOutOfStartedState(PlaybackStateCompat state) {
                if (serviceInStartedState) {
                    Log.d(TAG, "notification destroyed");
                    ttsNotification.getNotificationManager().cancelAll();
                    stopForeground(true);
                    serviceInStartedState = false;
                }
            }
        }
    }
    public static MediaSessionCompat getMediaSession() {
        return mediaSession;
    }
}