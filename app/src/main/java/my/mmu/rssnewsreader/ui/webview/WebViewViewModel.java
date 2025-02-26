package my.mmu.rssnewsreader.ui.webview;

import android.annotation.SuppressLint;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;

import my.mmu.rssnewsreader.data.entry.EntryRepository;
import my.mmu.rssnewsreader.model.EntryInfo;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

@HiltViewModel
public class WebViewViewModel extends ViewModel {

    private EntryRepository entryRepository;

    private MutableLiveData<Map<Long, Boolean>> entryLoadingStates = new MutableLiveData<>(new HashMap<>());

    private final MutableLiveData<Boolean> loadingState = new MutableLiveData<>();

    public LiveData<Boolean> getLoadingState() {
        return loadingState;
    }

    public void setLoadingState(boolean isLoading) {
        loadingState.postValue(isLoading);
    }

    @Inject
    public WebViewViewModel(EntryRepository entryRepository) {
        this.entryRepository = entryRepository;
    }

    public void resetEntry(long id) {
        entryRepository.updateHtml(null, id);
        entryRepository.updateContent(null, id);
        entryRepository.updateSentCountByLink(0, id);
        entryRepository.updatePriority(1, id);
    }

    public void updateHtml(String html, long id) {
        entryRepository.updateHtml(html, id);
    }

    public void updateContent(String content, long id) {
        entryRepository.updateContent(content, id);
    }

    public void updateBookmark(String bool, long id) {
        entryRepository.updateBookmark(bool, id);
    }

    public EntryInfo getLastVisitedEntry() {
        return entryRepository.getLastVisitedEntry();
    }

    public String getHtmlById(long id) {
        return entryRepository.getHtmlById(id);
    }

    public String getStyle() {
        return "<style>\n" +
                "    @font-face {\n" +
                "        font-family: open_sans;\n" +
                "        src: url(\"file:///android_res/font/open_sans.ttf\")\n" +
                "    }\n" +
                "    body {\n" +
                "        font-family: open_sans;\n" +
                "        text-align: justify;\n" +
                "        font-size: 0.875em;\n" +
                "    }\n" +
                "</style>";
    }

    @SuppressLint("SimpleDateFormat")
    public String getHtml(String entryTitle, String feedTitle, Date publishDate, String feedImageUrl) {
        return "<div style=\"display: flex; flex-direction:column;\">\n" +
                "    <div style=\"display: flex; align-items: center;\">\n" +
                "       <img style=\"margin-right: 10px; margin-left: 0; width: 20px; height: 20px\" src=" + feedImageUrl + ">" +
                "       <p style=\"font-size: 0.75em\">" + feedTitle + "</p>\n" +
                "    </div>\n" +
                "    <p style=\"margin:0; font-size: 1.25em; font-weight:bold\">" + entryTitle + "</p>" +
                "    <p style=\"font-size: 0.75em; align-self: flex-end;\">" + new SimpleDateFormat("EEE, d MMM yyyy 'at' hh:mm aaa").format(publishDate) + "</p>\n" +
                "  </div>";
    }

    public boolean endsWithBreak(String text) {
        return text.endsWith(".") || text.endsWith("?") || text.endsWith("!") || text.endsWith("！") || text.endsWith("？") || text.endsWith("。");
    }

    public EntryInfo getEntryInfoById(long id) {
        return entryRepository.getEntryById(id);
    }
}
