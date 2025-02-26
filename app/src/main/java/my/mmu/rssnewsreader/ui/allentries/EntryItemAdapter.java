package my.mmu.rssnewsreader.ui.allentries;

import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import my.mmu.rssnewsreader.R;
import my.mmu.rssnewsreader.model.EntryInfo;

import com.google.android.material.button.MaterialButton;
import com.squareup.picasso.Picasso;

import java.util.Date;
import java.util.concurrent.TimeUnit;

public class EntryItemAdapter extends ListAdapter<EntryInfo, EntryItemAdapter.EntryItemHolder> {

    EntryItemClickInterface entryItemClickInterface;
    private Context context;
    private boolean isSelectionMode = false;
    private static final String TAG = "EntryItemAdapter";


    public EntryItemAdapter(EntryItemClickInterface entryItemClickInterface) {
        super(DIFF_CALLBACK);
        this.entryItemClickInterface = entryItemClickInterface;
    }

    private static final DiffUtil.ItemCallback<EntryInfo> DIFF_CALLBACK = new DiffUtil.ItemCallback<EntryInfo>() {
        @Override
        public boolean areItemsTheSame(@NonNull EntryInfo oldItem, @NonNull EntryInfo newItem) {
            return oldItem.getEntryId() == newItem.getEntryId();
        }

        @Override
        public boolean areContentsTheSame(@NonNull EntryInfo oldItem, @NonNull EntryInfo newItem) {
            return oldItem.equals(newItem);
        }
    };

    @NonNull
    @Override
    public EntryItemHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View itemView = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.entry_item, parent, false);
        context = parent.getContext();
        return new EntryItemHolder(itemView);
    }

    @Override
    public void onBindViewHolder(@NonNull EntryItemAdapter.EntryItemHolder holder, int position) {
        EntryInfo currentEntry = getItem(position);

        boolean isLoading = currentEntry.isLoading();
        Log.d(TAG, "onBindViewHolder: Position " + position + " - isLoading: " + isLoading);

        holder.entryLoadingIndicator.setVisibility(
                currentEntry.isLoading() ? View.VISIBLE : View.GONE
        );

        holder.bind(currentEntry);
    }

    public void exitSelectionMode() {
        isSelectionMode = false;
        entryItemClickInterface.onSelectionModeChanged(isSelectionMode);
        notifyDataSetChanged();
    }

    class EntryItemHolder extends RecyclerView.ViewHolder {

        private TextView textViewEntryTitle, textViewFeedTitle, textViewEntryPubDate;
        private ImageView imageViewEntryImage, imageViewFeedImage;
        private MaterialButton bookmarkButton;
        private MaterialButton moreButton;
        private CheckBox selectedCheckbox;
        private View view;
        private ProgressBar entryLoadingIndicator;

        public EntryItemHolder(@NonNull View itemView) {
            super(itemView);
            textViewEntryTitle = itemView.findViewById(R.id.entryTitle);
            textViewFeedTitle = itemView.findViewById(R.id.feedTitle);
            textViewEntryPubDate = itemView.findViewById(R.id.entryPubDate);
            imageViewFeedImage = itemView.findViewById(R.id.feedImage);
            imageViewEntryImage = itemView.findViewById(R.id.entryImage);
            bookmarkButton = itemView.findViewById(R.id.bookmark_button);
            moreButton = itemView.findViewById(R.id.more_button);
            selectedCheckbox = itemView.findViewById(R.id.selectedCheckbox);
            entryLoadingIndicator = itemView.findViewById(R.id.entryLoadingIndicator);
            view = itemView;
        }

        public void bind(EntryInfo entryInfo) {
            textViewEntryTitle.setTextColor(textViewEntryPubDate.getTextColors());
            textViewEntryTitle.setText(entryInfo.getEntryTitle());
            textViewFeedTitle.setText(entryInfo.getFeedTitle());

            String pubDate = covertTimeToText(entryInfo.getEntryPublishedDate());
            textViewEntryPubDate.setText(pubDate);

            if (entryInfo.getVisitedDate() != null) {
                textViewEntryTitle.setTextColor(Color.parseColor("#CD5C5C"));
            }

            if (entryInfo.getBookmark() == null || entryInfo.getBookmark().equals("N")) {
                bookmarkButton.setIcon(ContextCompat.getDrawable(context, R.drawable.ic_bookmark_outline));
                bookmarkButton.setIconTint(ContextCompat.getColorStateList(context, R.color.text));
            } else {
                bookmarkButton.setIcon(ContextCompat.getDrawable(context, R.drawable.ic_bookmark_filled));
                bookmarkButton.setIconTint(ContextCompat.getColorStateList(context, R.color.primary));
            }
            bookmarkButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (entryInfo.getBookmark() == null || entryInfo.getBookmark().equals("N")) {
                        entryItemClickInterface.onBookmarkButtonClick("Y", entryInfo.getEntryId());
                    } else {
                        entryItemClickInterface.onBookmarkButtonClick("N", entryInfo.getEntryId());
                    }
                }
            });

            moreButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    entryItemClickInterface.onMoreButtonClick(entryInfo.getEntryId(), entryInfo.getEntryLink(), entryInfo.getVisitedDate() == null);
                }
            });

            if (TextUtils.isEmpty(entryInfo.getEntryImageUrl())) {
                imageViewEntryImage.setVisibility(View.GONE);
            } else {
                imageViewEntryImage.setVisibility(View.VISIBLE);
                Picasso.get().load(entryInfo.getEntryImageUrl()).into(imageViewEntryImage);
            }
            if (TextUtils.isEmpty(entryInfo.getFeedImageUrl())) {
                imageViewFeedImage.setVisibility(View.GONE);
            } else {
                imageViewFeedImage.setVisibility(View.VISIBLE);
                Picasso.get().load(entryInfo.getFeedImageUrl()).into(imageViewFeedImage);
            }

            selectedCheckbox.setVisibility(isSelectionMode ? View.VISIBLE : View.GONE);
            selectedCheckbox.setChecked(entryInfo.isSelected());
            selectedCheckbox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    if (isSelectionMode) {
                        entryInfo.setSelected(isChecked);
                        entryItemClickInterface.onItemSelected(entryInfo);
                    }
                }
            });

            view.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    entryItemClickInterface.onEntryClick(entryInfo);
                }
            });

            view.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View view) {
                    isSelectionMode = true;
                    entryItemClickInterface.onSelectionModeChanged(true);
                    notifyDataSetChanged();
                    return true;
                }
            });
        }
    }

    public interface EntryItemClickInterface {
        void onEntryClick(EntryInfo entryInfo);

        void onMoreButtonClick(long entryId, String link, boolean unread);

        void onBookmarkButtonClick(String bool, long id);

        void onSelectionModeChanged(boolean isSelectionMode);

        void onItemSelected(EntryInfo entryInfo);
    }

    public String covertTimeToText(Date date) {

        String convTime = null;
        String suffix = "ago";
        Date nowTime = new Date();

        long dateDiff = nowTime.getTime() - date.getTime();

        long second = TimeUnit.MILLISECONDS.toSeconds(dateDiff);
        long minute = TimeUnit.MILLISECONDS.toMinutes(dateDiff);
        long hour = TimeUnit.MILLISECONDS.toHours(dateDiff);
        long day = TimeUnit.MILLISECONDS.toDays(dateDiff);

        if (second < 60) {
            convTime = second + " seconds " + suffix;
        } else if (minute < 60) {
            convTime = minute + " minutes " + suffix;
        } else if (hour < 24) {
            convTime = hour + " hours " + suffix;
        } else if (day >= 7) {
            if (day > 360) {
                convTime = (day / 360) + " years " + suffix;
            } else if (day > 30) {
                convTime = (day / 30) + " months " + suffix;
            } else {
                convTime = (day / 7) + " week " + suffix;
            }
        } else {
            convTime = day + " days " + suffix;
        }

        return convTime;
    }
}
