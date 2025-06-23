package my.mmu.rssnewsreader.ui.feed;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import my.mmu.rssnewsreader.R;
import my.mmu.rssnewsreader.data.feed.Feed;
import my.mmu.rssnewsreader.databinding.FragmentFeedBinding;
import my.mmu.rssnewsreader.service.rss.RssFeed;
import my.mmu.rssnewsreader.service.rss.RssItem;
import my.mmu.rssnewsreader.ui.feedsetting.FeedSettingDialog;
import my.mmu.rssnewsreader.ui.loginwebview.LoginWebViewActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.tabs.TabLayout;

import java.util.List;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class FeedFragment extends Fragment {

    private FragmentFeedBinding binding;
    private FeedPagerAdapter feedPagerAdapter;

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState
    ) {
        binding = FragmentFeedBinding.inflate(inflater, container, false);

        TabLayout tabLayout = binding.feedTab;
        ViewPager2 viewPager = binding.feedViewPager;
        feedPagerAdapter = new FeedPagerAdapter(this);
        viewPager.setAdapter(feedPagerAdapter);

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                viewPager.setCurrentItem(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {

            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {

            }
        });

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                tabLayout.getTabAt(position).select();
            }
        });

        return binding.getRoot();
    }
}