package my.mmu.rssnewsreader.service.tts;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.NonNull;

import my.mmu.rssnewsreader.R;
import my.mmu.rssnewsreader.data.entry.EntryRepository;
import my.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;
import my.mmu.rssnewsreader.ui.webview.WebViewListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.material.snackbar.Snackbar;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions;
import com.google.mlkit.nl.languageid.LanguageIdentifier;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.w3c.dom.Text;

import java.io.File;
import java.io.IOException;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;


@Singleton
public class TtsPlayer extends PlayerAdapter implements TtsPlayerListener {

    public static final String TAG = TtsPlayer.class.getSimpleName();

    private TextToSpeech tts;
    private PlaybackStateListener listener;
    private MediaSessionCompat.Callback callback;
    private WebViewListener webViewCallback;
    private Context context;
    private final TtsExtractor ttsExtractor;
    private final EntryRepository entryRepository;
    private final SharedPreferencesRepository sharedPreferencesRepository;

    private int sentenceCounter;
    private List<String> sentences = new ArrayList<>();

    private CountDownLatch countDownLatch;
    private int currentState;
    private long currentId = 0;
    private long feedId = 0;
    private String language;
    private boolean isInit = false;
    private boolean isPreparing = false;
    private boolean actionNeeded = false;
    private boolean isPausedManually;
    private boolean webViewConnected = false;
    private boolean uiControlPlayback = false;

    private MediaPlayer mediaPlayer;

    @Inject
    public TtsPlayer(@ApplicationContext Context context, TtsExtractor ttsExtractor, EntryRepository entryRepository, SharedPreferencesRepository sharedPreferencesRepository) {
        super(context);
        this.ttsExtractor = ttsExtractor;
        this.entryRepository = entryRepository;
        this.sharedPreferencesRepository = sharedPreferencesRepository;
        this.context = context;
        this.isPausedManually = sharedPreferencesRepository.getIsPausedManually();
    }

    @SuppressLint("SetJavaScriptEnabled")
    public void initTts(TtsService ttsService, PlaybackStateListener listener, MediaSessionCompat.Callback callback) {
        this.listener = listener;
        this.callback = callback;
        tts = new TextToSpeech(ttsService, status -> {
            if (status == TextToSpeech.SUCCESS) {
                Log.d(TAG, "initTts successful");
                isInit = true;
                if (actionNeeded) {
                    Log.d(TAG, "Performing setup after init");
                    setupTts();
                    actionNeeded = false;
                }
            }
        });
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String s) {

            }

            @Override
            public void onDone(String s) {
                if (sentenceCounter < sentences.size()) {
                    speak();
                    entryRepository.updateSentCount(sentenceCounter, currentId);
                }
                else if (sentences.size() == 0){
                    Log.d(TAG, "do nothing");
                }
                else  {
                    entryRepository.updateSentCount(0, currentId);
                    callback.onSkipToNext();
                }
            }

            @Override
            public void onError(String s) {

            }
        });
    }

    public void extract(long currentId, long feedId, String content, String language) {
        if (currentId != this.currentId || content == null) {
            isPreparing = true;
            sentences = new ArrayList<>();
            this.sentenceCounter = entryRepository.getSentCount(currentId);
            this.language = language;
            this.currentId = currentId;
            this.feedId = feedId;
            countDownLatch = new CountDownLatch(1);

            if (content != null) {
                extractToTts(content);
            } else {
                ttsExtractor.setCallback(this);
            }
            ttsExtractor.prioritize();
        }
    }

    @Override
    public void extractToTts(String content) {
        String[] splitString = content.split(ttsExtractor.delimiter);
        List<String> sentenceList = Arrays.asList(splitString);

        for (int i=0; i < sentenceList.size(); i++) {
            String sentence = sentenceList.get(i);
            if (sentence.length() >= TextToSpeech.getMaxSpeechInputLength()) {
                BreakIterator iterator = BreakIterator.getSentenceInstance();
                iterator.setText(sentence);
                int start = iterator.first();
                for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
                    sentences.add(sentence.substring(start, end));
                }
            } else {
                sentences.add(sentence);
            }
        }

        if (sentences.size() < 2) {
            if (webViewCallback != null) webViewCallback.askForReload(feedId);
            countDownLatch.countDown();
            sentences.clear();
        } else {
            countDownLatch.countDown();
            if (!isInit) {
                Log.d(TAG, "Tts not initialized yet");
                actionNeeded = true;
            } else {
                Log.d(TAG, "Tts is initialized");
                setupTts();
            }
        }
    }

    private void setupTts() {
        if (webViewCallback != null) {
            webViewCallback.finishedSetup();
            webViewCallback.showFakeLoading();
        }
        isPreparing = false;

        if (language == null || language.isEmpty()) {
            Log.w(TAG, "Warning: Language is null or empty, defaulting to English.");
            language = "en";
        }

        try {
            Log.d(TAG, "Setting TTS language to: " + language);
            setLanguage(new Locale(language), true);
        } catch (Exception e) {
            Log.d(TAG, "Invalid locale " + e.getMessage());
            setLanguage(Locale.ENGLISH, true);
        }

        if (!isPausedManually && sentences.size() > 0) {
            Log.d(TAG, "Starting speech after setup.");
            new android.os.Handler().postDelayed(() -> {
                if (webViewCallback != null) {
                    webViewCallback.hideFakeLoading();
                }
                speak();
            }, 3000);
        }
    }

    private void identifyLanguage(String sentence, boolean fromService) {
        float confidenceThreshold = (float) sharedPreferencesRepository.getConfidenceThreshold() / 100;

        LanguageIdentifier languageIdentifier = LanguageIdentification.getClient(
                new LanguageIdentificationOptions.Builder()
                        .setConfidenceThreshold(confidenceThreshold)
                        .build());
        languageIdentifier.identifyLanguage(sentence)
                .addOnSuccessListener(languageCode -> {
                    if (languageCode.equals("und")) {
                        Log.i(TAG, "Can't identify language.");
                        setLanguage(Locale.ENGLISH, fromService);
                    } else {
                        Log.i(TAG, "Language: " + languageCode);
                        setLanguage(new Locale(languageCode), fromService);
                    }
                })
                .addOnFailureListener(Throwable::printStackTrace);
    }

    private void setLanguage(Locale locale, boolean fromService) {
        if (tts == null) {
            return;
        }
        int result = tts.setLanguage(locale);

        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.d(TAG, "Language not supported");
            if (webViewCallback != null) {
                webViewCallback.makeSnackbar("Language not installed. Required language: " + locale.getDisplayLanguage());
            }
            tts.setLanguage(Locale.ENGLISH);
        }
        else {
            Log.d(TAG, "Successfully built");
        }

        if (fromService) {
            callback.onCustomAction("playFromService", null);
        } else {
            if (!isPausedManually) {
                String sentence = sentences.get(sentenceCounter);
                tts.speak(sentence, TextToSpeech.QUEUE_FLUSH, null, TextToSpeech.ACTION_TTS_QUEUE_PROCESSING_COMPLETED);
                sentenceCounter++;
                if (webViewCallback != null) {
                    webViewCallback.highlightText(sentence);
                }
            }
        }
    }

    public void speak() {
        if (!isInit) {
            actionNeeded = true;
        }
        if (tts == null) return;
        if (sentences == null || sentences.size() == 0) {
            Log.d(TAG, "Waiting latch");
            try {
                countDownLatch.await();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        if (sentenceCounter < 0) sentenceCounter = 0;
        if (sentences.size() != 0) {
            if (language == null) {
                identifyLanguage(sentences.get(sentenceCounter), false);
            } else {
                String sentence = sentences.get(sentenceCounter);
                Log.d(TAG, "speak: speaking " + sentence);
                tts.speak(sentence, TextToSpeech.QUEUE_FLUSH, null, TextToSpeech.ACTION_TTS_QUEUE_PROCESSING_COMPLETED);
                sentenceCounter++;
                if (webViewCallback != null) {
                    webViewCallback.highlightText(sentence);
                }
            }
        }
    }

    public void fastForward() {
        if (tts != null) {
            if (sentenceCounter >= sentences.size()) {
                entryRepository.updateSentCount(0, currentId);
                callback.onSkipToNext();
            }
            else {
                sentenceCounter += 1;
                entryRepository.updateSentCount(sentenceCounter, currentId);
                onPlay();
            }
        }
    }

    public void fastRewind() {
        if (tts != null) {
            sentenceCounter -= 1;
            entryRepository.updateSentCount(sentenceCounter, currentId);
            onPlay();
        }
    }

    @Override
    public boolean isPlayingMediaPlayer() {
        return mediaPlayer != null && mediaPlayer.isPlaying();
    }

    public void setupMediaPlayer(boolean forced) {
        if (forced) {
            stopMediaPlayer();
        }

        if (mediaPlayer == null && sharedPreferencesRepository.getBackgroundMusic()) {
            if (sharedPreferencesRepository.getBackgroundMusicFile().equals("default")) {
                mediaPlayer = MediaPlayer.create(context, R.raw.pianomoment);
            } else {
                File savedFile = new File(context.getFilesDir(), "user_file.mp3");
                if (savedFile.exists()) {
                    mediaPlayer = MediaPlayer.create(context, Uri.parse(savedFile.getAbsolutePath()));
                } else {
                    mediaPlayer = MediaPlayer.create(context, R.raw.pianomoment);
                }
            }
            mediaPlayer.setLooping(true);
            changeMediaPlayerVolume();
        }
        playMediaPlayer();
    }

    @Override
    public void playMediaPlayer() {
        if (mediaPlayer != null && !mediaPlayer.isPlaying()) mediaPlayer.start();
    }

    @Override
    public void pauseMediaPlayer() {
        if (mediaPlayer != null) mediaPlayer.pause();
    }

    public void changeMediaPlayerVolume() {
        if (mediaPlayer != null) {
            float volume = (float) sharedPreferencesRepository.getBackgroundMusicVolume() / 100;
            mediaPlayer.setVolume(volume, volume);
        }
    }

    public void stopMediaPlayer() {
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    @Override
    public boolean isPlaying() {
        return tts != null && tts.isSpeaking();
    }

    @Override
    protected void onPlay() {
        if (tts != null && !isPausedManually) {
            sentenceCounter -= 1;
            speak();
            setNewState(PlaybackStateCompat.STATE_PLAYING);
        }
    }

    @Override
    protected void onPause() {
        if (tts != null && tts.isSpeaking()) {
            tts.stop();
        }
        setNewState(PlaybackStateCompat.STATE_PAUSED);
    }

    @Override
    protected void onStop() {
        stopMediaPlayer();
        Log.d(TAG, " player stopped");
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
            isInit = false;
        }
        currentId = 0;
        setNewState(PlaybackStateCompat.STATE_STOPPED);
    }

    private void setNewState(@PlaybackStateCompat.State int state) {
        if (listener != null) {
            currentState = state;
            final PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder();
            stateBuilder.setActions(getAvailableActions());
            stateBuilder.setState(currentState, 0, 1.0f, SystemClock.elapsedRealtime());
            listener.onPlaybackStateChange(stateBuilder.build());
        }
    }

    @PlaybackStateCompat.Actions
    private long getAvailableActions() {
        long actions = PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                | PlaybackStateCompat.ACTION_REWIND
                | PlaybackStateCompat.ACTION_FAST_FORWARD;
        switch (currentState) {
            case PlaybackStateCompat.STATE_STOPPED:
                actions |= PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_PAUSE;
                break;
            case PlaybackStateCompat.STATE_PLAYING:
                actions |= PlaybackStateCompat.ACTION_STOP
                        | PlaybackStateCompat.ACTION_PAUSE;
                break;
            case PlaybackStateCompat.STATE_PAUSED:
                actions |= PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_STOP;
                break;
            default:
                actions |= PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_STOP
                        | PlaybackStateCompat.ACTION_PAUSE;
        }
        return actions;
    }

    public void setTtsSpeechRate(float speechRate) {
        if (speechRate == 0) {
            try {
                int systemRate = Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.TTS_DEFAULT_RATE);
                speechRate = systemRate / 100.0f;
            } catch (Settings.SettingNotFoundException e) {
                e.printStackTrace();
                speechRate = 1.0f;
            }
        }
        tts.setSpeechRate(speechRate);
    }

    public void setWebViewCallback(WebViewListener listener) {
        this.webViewCallback = listener;
    }

    public boolean ttsIsNull() {
        return tts == null;
    }

    public boolean isWebViewConnected() {
        return webViewConnected;
    }

    public void setWebViewConnected(boolean isConnected) {
        this.webViewConnected = isConnected;
    }

    public boolean isUiControlPlayback() {
        return uiControlPlayback;
    }

    public void setUiControlPlayback(boolean isUiControlPlayback) {
        this.uiControlPlayback = isUiControlPlayback;
    }

    public long getCurrentId() {
        return currentId;
    }

    public boolean isPausedManually() {
        return isPausedManually;
    }

    public void setPausedManually(boolean isPaused) {
        sharedPreferencesRepository.setIsPausedManually(isPaused);
        isPausedManually = isPaused;
    }

    public boolean isPreparing() {
        return isPreparing;
    }
}