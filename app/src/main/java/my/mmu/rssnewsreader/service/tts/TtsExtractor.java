package my.mmu.rssnewsreader.service.tts;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.ContextCompat;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.schedulers.Schedulers;
import my.mmu.rssnewsreader.data.entry.Entry;
import my.mmu.rssnewsreader.data.entry.EntryRepository;
import my.mmu.rssnewsreader.data.feed.FeedRepository;
import my.mmu.rssnewsreader.data.playlist.PlaylistRepository;
import my.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;
import my.mmu.rssnewsreader.service.util.TextUtil;
import my.mmu.rssnewsreader.ui.webview.WebViewListener;

import net.dankito.readability4j.Article;
import net.dankito.readability4j.extended.Readability4JExtended;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;

@Singleton
public class TtsExtractor {

    private final String TAG = TtsExtractor.class.getSimpleName();

    private final Context context;
    private final EntryRepository entryRepository;
    private final FeedRepository feedRepository;
    private final PlaylistRepository playlistRepository;
    private final TextUtil textUtil;
    private final SharedPreferencesRepository sharedPreferencesRepository;
    private WebView webView;
    private String currentLink;
    private String currentTitle;
    private long currentIdInProgress;
    private boolean extractionInProgress;
    private int delayTime;
    private TtsPlayerListener ttsCallback;
    private TtsPlaylist ttsPlaylist;
    private WebViewListener webViewCallback;
    private Date playlistDate;

    public final String delimiter = "--####--";

    @SuppressLint("SetJavaScriptEnabled")
    @Inject
    public TtsExtractor(@ApplicationContext Context context, TtsPlaylist ttsPlaylist, EntryRepository entryRepository, FeedRepository feedRepository, PlaylistRepository playlistRepository, TextUtil textUtil, SharedPreferencesRepository sharedPreferencesRepository) {
        this.context = context;
        this.ttsPlaylist = ttsPlaylist;
        this.entryRepository = entryRepository;
        this.feedRepository = feedRepository;
        this.playlistRepository = playlistRepository;
        this.textUtil = textUtil;
        this.sharedPreferencesRepository = sharedPreferencesRepository;

        ContextCompat.getMainExecutor(context).execute(new Runnable() {
            @Override
            public void run() {
                webView = new WebView(context);
                webView.setWebViewClient(new WebClient());
                webView.clearCache(true);
                webView.getSettings().setJavaScriptEnabled(true);
                webView.getSettings().setDomStorageEnabled(true);
            }
        });
    }

    public void extractAllEntries() {
        Entry entry = entryRepository.getEmptyContentEntry();

        if (entry != null) {
            if (!extractionInProgress) {
                Log.d(TAG, "extracting...");
                extractionInProgress = true;
                currentIdInProgress = entry.getId();
                currentLink = entry.getLink();
                currentTitle = entry.getTitle();
                delayTime = feedRepository.getDelayTimeById(entry.getFeedId());
                ContextCompat.getMainExecutor(context).execute(new Runnable() {
                    @Override
                    public void run() {
                        webView.loadUrl(currentLink);
                        Log.d("Test url",currentLink);
                    }
                });
            }
        }
    }

    private void translateHtml(String html, String content, final long currentIdInProgress, String currentTitle) {
        String sourceLanguage = textUtil.identifyLanguageRx(content).blockingGet();
        String targetLanguage = sharedPreferencesRepository.getDefaultTranslationLanguage();

        if (!sourceLanguage.equals(targetLanguage)) {
            Log.d(TAG, "translateHtml: translating from " + sourceLanguage + " to " + targetLanguage);
            textUtil.translateHtmlLineByLine(sourceLanguage, targetLanguage, html, currentTitle, currentIdInProgress)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                            translatedHtml -> {
                                entryRepository.updateHtml(translatedHtml, currentIdInProgress);
                                String translatedContent = textUtil.extractHtmlContent(translatedHtml, delimiter);
                                entryRepository.updateContent(translatedContent, currentIdInProgress);
                                Log.d(TAG, "translateHtml: translation completed");
                            },
                            throwable -> Log.e(TAG, "translateHtml: error translating", throwable)
                    );
        }
    }

    public void setCallback(TtsPlayerListener callback) {
        this.ttsCallback = callback;
    }

    public void setCallback(WebViewListener callback) {
        this.webViewCallback = callback;
    }

    public void prioritize() {
        Date newPlaylistDate = playlistRepository.getLatestPlaylistCreatedDate();

        if (playlistDate == null || !playlistDate.equals(newPlaylistDate)) {
            playlistDate = newPlaylistDate;
            entryRepository.clearPriority();
            List<Long> playlist = stringToLongList(playlistRepository.getLatestPlaylist());
            long lastId = entryRepository.getLastVisitedEntryId();
            int index = playlist.indexOf(lastId);
            int priority = 1;
            entryRepository.updatePriority(priority, lastId);

            boolean loop = true;
            while (loop) {
                index += 1;
                priority += 1;
                if (index < playlist.size()) {
                    long id = playlist.get(index);
                    entryRepository.updatePriority(priority, id);
                } else {
                    loop = false;
                }
            }
        }
        extractAllEntries();
    }

    public class WebClient extends WebViewClient {

        private final Handler handler = new Handler();

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            view.loadUrl(url);
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            if (extractionInProgress && webView.getProgress() == 100) {
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        webView.evaluateJavascript("(function() {return document.getElementsByTagName('html')[0].outerHTML;})();", new ValueCallback<String>() {
                            @Override
                            public void onReceiveValue(final String value) {
                                Log.d(TAG, "Receiving value...");
                                JsonReader reader = new JsonReader(new StringReader(value));
                                reader.setLenient(true);
                                boolean stopExtracting = false;
                                try {
                                    if (reader.peek() == JsonToken.STRING) {
                                        String html = reader.nextString();
                                        if (html != null) {
                                            Readability4JExtended readability4J = new Readability4JExtended(currentLink, html);
                                            Article article = readability4J.parse();
                                            StringBuilder content = new StringBuilder();
                                            if (currentTitle != null && !currentTitle.isEmpty()) {
                                                content.append(currentTitle);
                                            }
                                            if (article.getContentWithUtf8Encoding() != null) {
                                                Document doc = Jsoup.parse(article.getContentWithUtf8Encoding());
                                                doc.select("img").removeAttr("width");
                                                doc.select("img").removeAttr("height");
                                                doc.select("img").removeAttr("sizes");
                                                doc.select("img").removeAttr("srcset");
                                                doc.select("h1").remove();
                                                doc.select("img").attr("style", "border-radius: 5px; width: 100%; margin-left:0"); // find all images and set width to 100%
                                                doc.select("figure").attr("style", "width: 100%; margin-left:0"); // find all images and set width to 100%
                                                doc.select("iframe").attr("style", "width: 100%; margin-left:0"); // find all images and set width to 100%

                                                List<String> tags = Arrays.asList("h2", "h3", "h4", "h5", "h6", "p", "td", "pre", "th", "li", "figcaption", "blockquote", "section");
                                                for (Element element : doc.getAllElements()) {
                                                    if (tags.contains(element.tagName())) {
                                                        boolean sameContent = false;
                                                        for (Element child : element.children()) {
                                                            if (tags.contains(child.tagName())) {
                                                                sameContent = true;
                                                            }
                                                        }
                                                        if (!sameContent) {
                                                            String text = element.text().trim();
                                                            if (!text.isEmpty() && text.length() > 1) {
                                                                if (currentTitle != null && !currentTitle.isEmpty()) {
                                                                    content.append(delimiter).append(text);
                                                                } else {
                                                                    content.append(text);
                                                                }
                                                            } else {
                                                                element.remove();
                                                            }
                                                        }
                                                    }
                                                }

                                                entryRepository.updateHtml(doc.html(), currentIdInProgress);
                                                entryRepository.updateContent(content.toString(), currentIdInProgress);

                                                if (sharedPreferencesRepository.getAutoTranslate()) {
                                                    translateHtml(doc.html(), content.toString(), currentIdInProgress, currentTitle);
                                                }


                                                if (content.toString().isEmpty()) {
                                                    stopExtracting = true;
                                                }

                                                if (currentIdInProgress == ttsPlaylist.getPlayingId()) {
                                                    if (ttsCallback != null) {
                                                        ttsCallback.extractToTts(content.toString());
                                                        ttsCallback = null;
                                                    }

                                                    if (webViewCallback != null) {
                                                        webViewCallback.finishedSetup();
                                                        webViewCallback = null;
                                                    }
                                                } else {
                                                    Log.d(TAG, "not playing this ID");
                                                }
                                            } else {
                                                Log.d(TAG, "Empty content");
                                            }
                                        } else {
                                            if (webViewCallback != null) {
                                                webViewCallback.makeSnackbar("Failed to retrieve the html");
                                            }
                                            Log.d(TAG, "No html found!");
                                        }
                                    } else {
                                        if (webViewCallback != null) {
                                            webViewCallback.makeSnackbar("Extraction failed");
                                        }
                                        Log.d(TAG, "Error peeking reader!");
                                    }
                                } catch (Exception e) {
                                    Log.d(TAG, e.getMessage());
                                    e.printStackTrace();
                                }
                                currentIdInProgress = -1;
                                extractionInProgress = false;
                                if (!stopExtracting) {
                                    extractAllEntries();
                                }
                            }
                        });
                    }
                }, delayTime * 1000L);
            } else {
                Log.d(TAG, "loading WebView");
            }
        }
    }

    public List<Long> stringToLongList(String genreIds) {
        List<Long> list = new ArrayList<>();

        String[] array = genreIds.split(",");

        for (String s : array) {
            if (!s.isEmpty()) {
                list.add(Long.parseLong(s));
            }
        }
        return list;
    }
}