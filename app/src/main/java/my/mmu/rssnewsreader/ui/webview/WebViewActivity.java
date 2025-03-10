package my.mmu.rssnewsreader.ui.webview;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;

import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import my.mmu.rssnewsreader.R;
import my.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;
import my.mmu.rssnewsreader.model.EntryInfo;
import my.mmu.rssnewsreader.service.tts.TtsExtractor;
import my.mmu.rssnewsreader.service.tts.TtsPlayer;
import my.mmu.rssnewsreader.service.tts.TtsPlaylist;
import my.mmu.rssnewsreader.service.tts.TtsService;
import my.mmu.rssnewsreader.databinding.ActivityWebviewBinding;
import my.mmu.rssnewsreader.service.util.TextUtil;
import my.mmu.rssnewsreader.ui.feed.ReloadDialog;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;



import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class WebViewActivity extends AppCompatActivity implements WebViewListener {
    private final static String TAG = "WebViewActivity";
    // Share
    private ActivityWebviewBinding binding;
    private WebViewViewModel webViewViewModel;
    private WebView webView;
    private LinearProgressIndicator loading;
    private MenuItem browserButton;
    private MenuItem offlineButton;
    private MenuItem reloadButton;
    private MenuItem bookmarkButton;
    private MenuItem translationButton;
    private MenuItem highlightTextButton;
    private MenuItem backgroundMusicButton;
    private String currentLink;
    private long currentId;
    private long feedId;
    private String html;
    private String content;
    private String bookmark;
    private boolean isPlaying;
    private boolean isReadingMode;
    private boolean showOfflineButton;
    private boolean clearHistory;

    // Translation
    private String targetLanguage;
    private String translationMethod;
    private TextUtil textUtil;
    private CompositeDisposable compositeDisposable;

    // Reading Mode
    private MenuItem switchPlayModeButton;
    private LinearLayout functionButtonsReadingMode;

    // Playing Mode
    private MenuItem switchReadModeButton;
    private MaterialButton playPauseButton;
    private MaterialButton skipNextButton;
    private MaterialButton skipPreviousButton;
    private MaterialButton fastForwardButton;
    private MaterialButton rewindButton;
    private LinearLayout functionButtons;
    private MediaBrowserHelper mMediaBrowserHelper;
    private Set<Long> translatedArticleIds = new HashSet<>();

    @Inject
    TtsPlayer ttsPlayer;

    @Inject
    TtsPlaylist ttsPlaylist;

    @Inject
    TtsExtractor ttsExtractor;

    @Inject
    SharedPreferencesRepository sharedPreferencesRepository;

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (webView.canGoBack()) {
                    browserButton.setVisible(true);
                    webView.goBack();
                } else {
                    finish();
                }
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private void showTranslationLanguageDialog(Context context) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Default Translation Language");

        CharSequence[] entries = getResources().getStringArray(R.array.defaultTranslationLanguage);
        CharSequence[] entryValues = getResources().getStringArray(R.array.defaultTranslationLanguage_values);

        builder.setItems(entries, (dialog, which) -> {
            makeSnackbar("Translating to " + entries[which]);
            String selectedValue = entryValues[which].toString();
            sharedPreferencesRepository.setDefaultTranslationLanguage(selectedValue);
            targetLanguage = selectedValue;
            translate();
            dialog.dismiss();
        });

        builder.show();
    }

    private void doWhenTranslationFinish(EntryInfo entryInfo, String translatedHtml) {
        loading.setVisibility(View.INVISIBLE);
        webViewViewModel.resetEntry(currentId);

        // Handle html
        Document doc = Jsoup.parse(translatedHtml);
        doc.head().append(webViewViewModel.getStyle());
        Objects.requireNonNull(
                        doc.selectFirst("body")).
                prepend(webViewViewModel.getHtml(entryInfo.getEntryTitle(), entryInfo.getFeedTitle(), entryInfo.getEntryPublishedDate(), entryInfo.getFeedImageUrl()));
        webView.loadDataWithBaseURL("file///android_res/", doc.html(), "text/html", "UTF-8", null);
        browserButton.setVisible(true);
        webViewViewModel.updateHtml(translatedHtml, currentId);

        // Handle content
        content = textUtil.extractHtmlContent(translatedHtml, "--####--");
        webViewViewModel.updateContent(content, currentId);
        Log.d(TAG, "translateHtml content: " + content);
        ttsPlayer.extract(currentId, feedId, content, String.valueOf(new Locale(targetLanguage)));

        // Handle player
        if (!isReadingMode) {
            ttsPlayer.stop();
            mMediaBrowserHelper.onStop();
            mMediaBrowserHelper.onStart();
        }
    }

    private void translate() {
        Log.d(TAG, "translate: html\n" + webViewViewModel.getHtmlById(currentId));
        makeSnackbar("Translation in progress");
        loading.setVisibility(View.VISIBLE);
        loading.setProgress(0);

        String content = webViewViewModel.getHtmlById(currentId); // Get article content
        EntryInfo entryInfo = webViewViewModel.getEntryInfoById(currentId); // Fetch entry details
        if (entryInfo == null) {
            makeSnackbar("Entry info could not be loaded.");
            return;
        }
        String feedLanguage = entryInfo.getFeedLanguage(); // Retrieve feed's language

        textUtil.handleLanguageMismatch(content, feedLanguage, (identifiedLanguage, feedLanguageUsed) -> {
            Log.d(TAG, "Identified Language: " + identifiedLanguage + ", Feed Language: " + feedLanguageUsed);
            if (!feedLanguage.equals(identifiedLanguage)) {
                // Ensure prompt is displayed for mismatch
                runOnUiThread(() -> promptLanguageMismatch(identifiedLanguage, feedLanguageUsed, targetLanguage, content));
            } else {
                // Proceed with the configured or identified language
                performTranslation(feedLanguageUsed, targetLanguage, content);
            }
        });
    }


    private void promptLanguageMismatch(String identifiedLanguage, String configuredLanguage, String targetLanguage, String content) {
        new AlertDialog.Builder(this)
                .setTitle("Language Mismatch")
                .setMessage("Identified language: " + identifiedLanguage +
                        "\nConfigured language: " + configuredLanguage +
                        "\nUse identified language for translation?")
                .setPositiveButton("Use Identified", (dialog, which) -> performTranslation(identifiedLanguage, targetLanguage, content))
                .setNegativeButton("Use Configured", (dialog, which) -> performTranslation(configuredLanguage, targetLanguage, content))
                .setCancelable(false)
                .show();
    }

    private void performTranslation(String sourceLanguage, String targetLanguage, String content) {
        if (translationMethod.equals("lineByLine")) {
            textUtil.translateHtmlLineByLine(
                    sourceLanguage,
                    targetLanguage,
                    content,
                    "Article Title",
                    currentId,
                    progress -> runOnUiThread(() -> loading.setProgress(progress))
            ).subscribe(
                    translatedHtml -> {
                        Log.d(TAG, "Translation completed");
                        doWhenTranslationFinish(webViewViewModel.getLastVisitedEntry(), translatedHtml);
                    },
                    throwable -> {
                        Log.e(TAG, "Translation failed", throwable);
                        loading.setVisibility(View.GONE);
                    }
            );
        } else if (translationMethod.equals("allAtOnce")) {
            textUtil.translateHtmlAllAtOnce(
                    sourceLanguage,
                    targetLanguage,
                    content,
                    "Article Title",
                    currentId,
                    progress -> runOnUiThread(() -> loading.setProgress(progress))
            ).subscribe(
                    translatedHtml -> {
                        Log.d(TAG, "Translation completed");
                        doWhenTranslationFinish(webViewViewModel.getLastVisitedEntry(), translatedHtml);
                    },
                    throwable -> {
                        Log.e(TAG, "Translation failed", throwable);
                        loading.setVisibility(View.GONE);
                    }
            );
        }
    }

    @Override
    public void showFakeLoading() {
        new Thread(() -> {
            try {
                int progress = 0;
                while (progress < 100) {
                    Thread.sleep(500);
                    progress += 10;
                    int finalProgress = progress;
                    runOnUiThread(() -> loading.setProgress(finalProgress));
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();
    }

    @Override
    public void hideFakeLoading() {
        runOnUiThread(() -> {
            Log.d(TAG, "TTS is starting, hiding fake loading...");
            loading.setVisibility(View.GONE);
        });
    }


    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        isReadingMode = getIntent().getBooleanExtra("read", false);

        if (ttsPlayer.isPlaying() && isReadingMode) {
            ttsPlayer.stop();
        }

        binding = ActivityWebviewBinding.inflate(getLayoutInflater());
        webViewViewModel = new ViewModelProvider(this).get(WebViewViewModel.class);

        MaterialToolbar toolbar = binding.toolbar;
        browserButton = toolbar.getMenu().findItem(R.id.openInBrowser);
        offlineButton = toolbar.getMenu().findItem(R.id.exitBrowser);
        reloadButton = toolbar.getMenu().findItem(R.id.reload);
        bookmarkButton = toolbar.getMenu().findItem(R.id.bookmark);
        translationButton = toolbar.getMenu().findItem(R.id.translate);
        highlightTextButton = toolbar.getMenu().findItem(R.id.highlightText);
        backgroundMusicButton = toolbar.getMenu().findItem(R.id.toggleBackgroundMusic);
        switchReadModeButton = toolbar.getMenu().findItem(R.id.switchReadMode);
        switchPlayModeButton = toolbar.getMenu().findItem(R.id.switchPlayMode);

        loading = binding.loadingWebView;
        functionButtons = binding.functionButtons;
        functionButtonsReadingMode = binding.functionButtonsReading;

        playPauseButton = binding.playPauseButton;
        skipNextButton = binding.skipNextButton;
        skipPreviousButton = binding.skipPreviousButton;
        fastForwardButton = binding.fastForwardButton;
        rewindButton = binding.rewindButton;

        targetLanguage = sharedPreferencesRepository.getDefaultTranslationLanguage();
        translationMethod = sharedPreferencesRepository.getTranslationMethod();
        textUtil = new TextUtil(sharedPreferencesRepository);
        compositeDisposable = new CompositeDisposable();

        if (sharedPreferencesRepository.getHighlightText()) {
            highlightTextButton.setTitle(R.string.highlight_text_turn_off);
        } else {
            highlightTextButton.setTitle(R.string.highlight_text_turn_on);
        }

        if (sharedPreferencesRepository.getBackgroundMusic()) {
            backgroundMusicButton.setTitle(R.string.background_music_turn_off);
        } else {
            backgroundMusicButton.setTitle(R.string.background_music_turn_on);
        }

        toolbar.setNavigationOnClickListener(view -> onBackPressed());

        toolbar.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            View view = toolbar.findViewById(itemId);

            // Check for long press
            if (view != null) {
                view.setOnLongClickListener(v -> {
                    showTranslationLanguageDialog(view.getContext());
                    return true;
                });
            }

            // Check for regular click
            if (itemId == R.id.translate) {
                // get target language
                if (targetLanguage == null || targetLanguage.isEmpty()) {
                    showTranslationLanguageDialog(view.getContext());
                }
                translate();
                return true;
            } else if (itemId == R.id.zoomIn) {
                int newTextZoom = webView.getSettings().getTextZoom() + 10;
                webView.getSettings().setTextZoom(newTextZoom);
                sharedPreferencesRepository.setTextZoom(newTextZoom);
                return true;
            } else if (itemId == R.id.zoomOut) {
                int newTextZoom = webView.getSettings().getTextZoom() - 10;
                webView.getSettings().setTextZoom(newTextZoom);
                sharedPreferencesRepository.setTextZoom(newTextZoom);
                return true;
            } else if (itemId == R.id.bookmark) {
                if (bookmark == null || bookmark.equals("N")) {
                    bookmarkButton.setIcon(R.drawable.ic_bookmark_filled);
                    webViewViewModel.updateBookmark("Y", currentId);
                    bookmark = "Y";
                    Snackbar.make(findViewById(R.id.webView_view), "Bookmark Complete", Snackbar.LENGTH_SHORT).show();
                } else {
                    bookmarkButton.setIcon(R.drawable.ic_bookmark_outline);
                    webViewViewModel.updateBookmark("N", currentId);
                    bookmark = "N";
                    Snackbar.make(findViewById(R.id.webView_view), "Bookmark Removed", Snackbar.LENGTH_SHORT).show();
                }
                return true;
            } else if (itemId == R.id.share) {
                Intent sendIntent = new Intent();
                sendIntent.setAction(Intent.ACTION_SEND);
                sendIntent.putExtra(Intent.EXTRA_TEXT, currentLink);
                sendIntent.setType("text/plain");

                Intent shareIntent = Intent.createChooser(sendIntent, null);
                startActivity(shareIntent);
                return true;
            } else if (itemId == R.id.openInBrowser) {
                clearHistory = true;
                browserButton.setVisible(false);
                webView.loadUrl(currentLink);
                Log.d("Test Url", currentLink);
                offlineButton.setVisible(true);
                return true;
            } else if (itemId == R.id.exitBrowser) {
                clearHistory = true;
                offlineButton.setVisible(false);
                EntryInfo entryInfo = webViewViewModel.getLastVisitedEntry();
                String html = webViewViewModel.getHtmlById(entryInfo.getEntryId());

                Document doc = Jsoup.parse(html);
                doc.head().append(webViewViewModel.getStyle());
                Objects.requireNonNull(doc.selectFirst("body")).prepend(webViewViewModel.getHtml(entryInfo.getEntryTitle(), entryInfo.getFeedTitle(), entryInfo.getEntryPublishedDate(), entryInfo.getFeedImageUrl()));

                webView.loadDataWithBaseURL("file///android_res/", doc.html(), "text/html", "UTF-8", null);
                browserButton.setVisible(true);
                return true;
            } else if (itemId == R.id.reload) {
                ReloadDialog dialog = new ReloadDialog(this, feedId, R.string.reload_confirmation, R.string.reload_message);
                dialog.show(getSupportFragmentManager(), ReloadDialog.TAG);
                return true;
            } else if (itemId == R.id.switchPlayMode) {
                isReadingMode = false;
                functionButtonsReadingMode.setVisibility(View.INVISIBLE);
                switchPlayModeButton.setVisible(false);
                ttsExtractor.setCallback((WebViewListener) null);
                switchPlayMode();
                mMediaBrowserHelper.onStart();
                functionButtons.setVisibility(View.VISIBLE);
                functionButtons.setAlpha(1.0f);
            } else if (itemId == R.id.switchReadMode) {
                isReadingMode = true;
                functionButtons.setVisibility(View.INVISIBLE);
                switchReadModeButton.setVisible(false);
                ttsPlayer.setWebViewCallback(null);
                mMediaBrowserHelper.getTransportControls().stop();
                mMediaBrowserHelper.onStop();
                webView.clearMatches();
                switchReadMode();
            } else if (itemId == R.id.highlightText) {
                boolean isHighlight = sharedPreferencesRepository.getHighlightText();
                sharedPreferencesRepository.setHighlightText(!isHighlight);
                if (isHighlight) {
                    webView.clearMatches();
                    highlightTextButton.setTitle(R.string.highlight_text_turn_on);
                    Snackbar.make(findViewById(R.id.webView_view), "Highlight is turned off", Snackbar.LENGTH_SHORT).show();
                } else {
                    highlightTextButton.setTitle(R.string.highlight_text_turn_off);
                    Snackbar.make(findViewById(R.id.webView_view), "Highlight is turned on", Snackbar.LENGTH_SHORT).show();
                }
            } else if (itemId == R.id.toggleBackgroundMusic) {
                boolean backgroundMusic = sharedPreferencesRepository.getBackgroundMusic();
                sharedPreferencesRepository.setBackgroundMusic(!backgroundMusic);
                if (backgroundMusic) {
                    ttsPlayer.stopMediaPlayer();
                    backgroundMusicButton.setTitle(R.string.background_music_turn_on);
                    Snackbar.make(findViewById(R.id.webView_view), "Background music is turned off", Snackbar.LENGTH_SHORT).show();
                } else {
                    ttsPlayer.setupMediaPlayer(false);
                    backgroundMusicButton.setTitle(R.string.background_music_turn_off);
                    Snackbar.make(findViewById(R.id.webView_view), "Background music is turned on", Snackbar.LENGTH_SHORT).show();
                }
            } else if (itemId == R.id.openTtsSetting) {
                Intent intent = new Intent("com.android.settings.TTS_SETTINGS");
                startActivity(intent);
            }

            return false;
        });

        webView = binding.webview;
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);

        int textZoom = sharedPreferencesRepository.getTextZoom();
        if (textZoom != 0) {
            webView.getSettings().setTextZoom(textZoom);
        }
        if (sharedPreferencesRepository.getNight()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                webView.getSettings().setForceDark(WebSettings.FORCE_DARK_ON);
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                webView.getSettings().setForceDark(WebSettings.FORCE_DARK_OFF);
            }
        }

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                super.onProgressChanged(view, newProgress);
                if (newProgress < 100) {
                    loading.setVisibility(View.VISIBLE);
                    loading.setProgress(newProgress);
                }
            }
        });

        if (isReadingMode) {
            switchReadMode();
        } else {
            switchPlayMode();
        }
        setContentView(binding.getRoot());
    }

    private void switchReadMode() {
        functionButtonsReadingMode.setVisibility(View.VISIBLE);

        webView.setWebViewClient(new ReadingWebClient());

        binding.nextArticleButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (ttsPlaylist.skipNext()) {
                    setupReadingWebView();
                } else {
                    Snackbar.make(findViewById(R.id.webView_view), "This is the last article", Snackbar.LENGTH_SHORT).show();
                }
            }
        });

        binding.previousArticleButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (ttsPlaylist.skipPrevious()) {
                    setupReadingWebView();
                } else {
                    Snackbar.make(findViewById(R.id.webView_view), "This is the first article", Snackbar.LENGTH_SHORT).show();
                }
            }
        });

        setupReadingWebView();

        ttsPlayer.setupMediaPlayer(false);

        switchPlayModeButton.setVisible(true);
    }

    private void switchPlayMode() {
        webView.setWebViewClient(new WebClient());

        playPauseButton.setOnClickListener(view -> {
            if (isPlaying) {
                playPauseButton.setIcon(ContextCompat.getDrawable(WebViewActivity.this, R.drawable.ic_pause));
                mMediaBrowserHelper.getTransportControls().pause();
                Log.d(TAG, "switchPlayMode: pausing " + ttsPlaylist.getPlayingId());
            } else {
                playPauseButton.setIcon(ContextCompat.getDrawable(WebViewActivity.this, R.drawable.ic_play));
                mMediaBrowserHelper.getTransportControls().play();
                Log.d(TAG, "switchPlayMode: playing " + ttsPlaylist.getPlayingId());
            }
        });

        skipNextButton.setOnClickListener(view -> mMediaBrowserHelper.getTransportControls().skipToNext());

        skipPreviousButton.setOnClickListener(view -> mMediaBrowserHelper.getTransportControls().skipToPrevious());

        fastForwardButton.setOnClickListener(view -> mMediaBrowserHelper.getTransportControls().fastForward());

        rewindButton.setOnClickListener(view -> mMediaBrowserHelper.getTransportControls().rewind());

        mMediaBrowserHelper = new MediaBrowserConnection(this);
        mMediaBrowserHelper.registerCallback(new MediaBrowserListener());

        switchReadModeButton.setVisible(true);
    }

    private void setupReadingWebView() {
        loading.setVisibility(View.VISIBLE);
        loading.setProgress(0);
        bookmarkButton.setVisible(false);
        loading.setProgress(0);
        translationButton.setVisible(false);
        showOfflineButton = false;

        MediaMetadataCompat metadata = ttsPlaylist.getCurrentMetadata();

        content = metadata.getString("content");
        bookmark = metadata.getString("bookmark");
        currentLink = metadata.getString("link");
        currentId = Long.parseLong(metadata.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID));
        feedId = metadata.getLong("feedId");

        if (bookmark == null || bookmark.equals("N")) {
            bookmarkButton.setIcon(R.drawable.ic_bookmark_outline);
        } else {
            bookmarkButton.setIcon(R.drawable.ic_bookmark_filled);
        }

        if (content == null) {
            webView.loadUrl(currentLink);
            Log.d(TAG, "Loading url: " + currentLink);
            browserButton.setVisible(false);
            showOfflineButton = true;
        } else {
            String entryTitle = metadata.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE);
            String feedTitle = metadata.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE);
            long date = metadata.getLong("date");
            Date publishDate = new Date(date);
            String feedImageUrl = metadata.getString("feedImageUrl");

            String html = metadata.getString("html");
            Document doc = Jsoup.parse(html);
            doc.head().append(webViewViewModel.getStyle());
            Objects.requireNonNull(doc.selectFirst("body")).prepend(webViewViewModel.getHtml(entryTitle, feedTitle, publishDate, feedImageUrl));

            webView.loadDataWithBaseURL("file///android_res/", doc.html(), "text/html", "UTF-8", null);
            offlineButton.setVisible(false);
            reloadButton.setVisible(true);
            bookmarkButton.setVisible(true);
            translationButton.setVisible(true);
            browserButton.setVisible(true);
            highlightTextButton.setVisible(true);
        }
    }

    @Override
    public void highlightText(String searchText) {
        if (!isReadingMode && sharedPreferencesRepository.getHighlightText()) {
            String text = searchText.trim();
            if (webViewViewModel.endsWithBreak(text)) {
                text = text.substring(0, text.length() - 1);
            }
            Log.d(TAG, "Highlighted text: " + text);
            String finalText = text.trim();
            ContextCompat.getMainExecutor(getApplicationContext()).execute(() -> webView.findAllAsync(finalText));
        }
    }

    @Override
    public void finishedSetup() {
        ContextCompat.getMainExecutor(getApplicationContext()).execute(new Runnable() {
            @Override
            public void run() {
                if (!isReadingMode) {
                    loading.setVisibility(View.INVISIBLE);
                    functionButtons.setVisibility(View.VISIBLE);
                    functionButtons.setAlpha(1.0f);
                }
                reloadButton.setVisible(true);
                bookmarkButton.setVisible(true);
                translationButton.setVisible(true);
                highlightTextButton.setVisible(true);
                if (showOfflineButton) {
                    offlineButton.setVisible(true);
                }
            }
        });
    }

    @Override
    public void makeSnackbar(String message) {
        Snackbar.make(findViewById(R.id.webView_view), message, Snackbar.LENGTH_SHORT).show();
    }

    @Override
    public void reload() {
        webViewViewModel.resetEntry(currentId);
        if (!isReadingMode) {
            mMediaBrowserHelper.getTransportControls().stop();
        }
        finish();
        overridePendingTransition(0, 0);
        startActivity(getIntent());
        overridePendingTransition(0, 0);
    }

    @Override
    public void askForReload(long feedId) {
        ReloadDialog dialog = new ReloadDialog(this, feedId, R.string.reload_confirmation, R.string.reload_suggestion_message);
        dialog.show(getSupportFragmentManager(), ReloadDialog.TAG);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isReadingMode) {
            switchPlayModeButton.setVisible(false);
            functionButtonsReadingMode.setVisibility(View.INVISIBLE);
        } else {
            functionButtons.setVisibility(View.INVISIBLE);
            switchReadModeButton.setVisible(false);
        }
        reloadButton.setVisible(false);
        bookmarkButton.setVisible(false);
        translationButton.setVisible(false);
        highlightTextButton.setVisible(false);
        compositeDisposable.dispose();
        textUtil.onDestroy();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (!isReadingMode) {
            mMediaBrowserHelper.onStart();
        }
    }

    @Override
    public void onStop() {
        if (isReadingMode) {
            ttsExtractor.setCallback((WebViewListener) null);
        } else {
            ttsPlayer.setWebViewCallback(null);
            mMediaBrowserHelper.onStop();
        }
        super.onStop();
    }

    @Override
    protected void onPause() {
        ttsPlayer.setWebViewConnected(false);
        ttsPlayer.setUiControlPlayback(false);
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ttsPlayer.setWebViewConnected(true);
        if (!isReadingMode) {
            if (ttsPlayer.isPlaying()) {
                isPlaying = true;
                playPauseButton.setIcon(ContextCompat.getDrawable(WebViewActivity.this, R.drawable.ic_pause));
            } else {
                isPlaying = false;
                playPauseButton.setIcon(ContextCompat.getDrawable(WebViewActivity.this, R.drawable.ic_play));
            }
        }
    }

    private class WebClient extends WebViewClient {

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            Log.d(TAG, "WebClient: onPageStarted - loadingWebView visible.");
            webViewViewModel.setLoadingState(true);
            if (clearHistory) {
                clearHistory = false;
                webView.clearHistory();
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            view.loadUrl(url);
            return true;
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            super.onPageCommitVisible(view, url);
            Log.d(TAG, "WebClient: onPageCommitVisible - loadingWebView hidden.");
            webViewViewModel.setLoadingState(false);
            if (content != null) {
                if (currentId != ttsPlaylist.getPlayingId()) {
                    ttsPlaylist.updatePlayingId(currentId);
                    mMediaBrowserHelper.getTransportControls().sendCustomAction("autoPlay", null);
                }
                functionButtons.setVisibility(View.VISIBLE);
                functionButtons.setAlpha(1.0f);
                reloadButton.setVisible(true);
                bookmarkButton.setVisible(true);
                highlightTextButton.setVisible(true);
            } else {
                if (currentId != ttsPlaylist.getPlayingId()) {
                    ttsPlaylist.updatePlayingId(currentId);
                }
            }
        }
    }

    private class ReadingWebClient extends WebViewClient {

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            Log.d(TAG, "ReadingWebClient: onPageStarted - loadingWebView visible.");
            webViewViewModel.setLoadingState(true);
            ttsExtractor.setCallback(WebViewActivity.this);
            if (clearHistory) {
                clearHistory = false;
                webView.clearHistory();
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            view.loadUrl(url);
            return true;
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            super.onPageCommitVisible(view, url);
            loading.setVisibility(View.INVISIBLE);
            Log.d(TAG, "ReadingWebClient: onPageCommitVisible - loadingWebView hidden.");
            webViewViewModel.setLoadingState(false);
        }
    }

    private class MediaBrowserConnection extends MediaBrowserHelper {
        private MediaBrowserConnection(Context context) {
            super(context, TtsService.class);
        }

        @Override
        protected void onChildrenLoaded(@NonNull String parentId, @NonNull List<MediaBrowserCompat.MediaItem> children) {
            super.onChildrenLoaded(parentId, children);

            final MediaControllerCompat mediaController = getMediaController();
            if (mediaController != null) {
                ttsPlayer.setWebViewCallback(WebViewActivity.this);
                ttsPlayer.setWebViewConnected(true);
                mediaController.getTransportControls().prepare();
            }
        }
    }

    private class MediaBrowserListener extends MediaControllerCompat.Callback {
        @Override
        public void onPlaybackStateChanged(PlaybackStateCompat state) {
            super.onPlaybackStateChanged(state);
            isPlaying = state != null && state.getState() == PlaybackStateCompat.STATE_PLAYING;
            if (isPlaying) {
                playPauseButton.setIcon(ContextCompat.getDrawable(WebViewActivity.this, R.drawable.ic_pause));
            } else {
                playPauseButton.setIcon(ContextCompat.getDrawable(WebViewActivity.this, R.drawable.ic_play));
            }
        }

        @Override
        public void onMetadataChanged(MediaMetadataCompat metadata) {
            if (metadata == null) {
                return;
            }
            clearHistory = true;
            loading.setVisibility(View.VISIBLE);
            loading.setProgress(0);
            functionButtons.setVisibility(View.VISIBLE);
            functionButtons.setAlpha(0.5f);
            reloadButton.setVisible(false);
            bookmarkButton.setVisible(false);
            highlightTextButton.setVisible(false);
            showOfflineButton = false;

            content = metadata.getString("content");
            bookmark = metadata.getString("bookmark");
            currentLink = metadata.getString("link");
            currentId = Long.parseLong(metadata.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID));
            feedId = metadata.getLong("feedId");

            if (bookmark == null || bookmark.equals("N")) {
                bookmarkButton.setIcon(R.drawable.ic_bookmark_outline);
            } else {
                bookmarkButton.setIcon(R.drawable.ic_bookmark_filled);
            }

            if (content == null) { // let service play
                ttsPlayer.setUiControlPlayback(false);
                webView.loadUrl(currentLink);
                Log.d(TAG, "Loading url: " + currentLink);
                browserButton.setVisible(false);
                showOfflineButton = true;
            } else { // let ui play
                if (ttsPlayer.isWebViewConnected()) {
                    ttsPlayer.setUiControlPlayback(true);
                }
                String entryTitle = metadata.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE);
                String feedTitle = metadata.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE);
                long date = metadata.getLong("date");
                Date publishDate = new Date(date);
                String feedImageUrl = metadata.getString("feedImageUrl");

                String html = metadata.getString("html");
                Document doc = Jsoup.parse(html);
                doc.head().append(webViewViewModel.getStyle());
                Objects.requireNonNull(doc.selectFirst("body")).prepend(webViewViewModel.getHtml(entryTitle, feedTitle, publishDate, feedImageUrl));
                webView.loadDataWithBaseURL("file///android_res/", doc.html(), "text/html", "UTF-8", null);
                offlineButton.setVisible(false);
                browserButton.setVisible(true);
            }
        }

        @Override
        public void onSessionDestroyed() {
            super.onSessionDestroyed();
        }
    }
}
