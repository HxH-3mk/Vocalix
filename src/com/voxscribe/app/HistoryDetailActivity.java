package com.voxscribe.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class HistoryDetailActivity extends Activity {
    private static final String PREFS_NAME = "voxscribe_settings";
    private static final String KEY_APP_LANGUAGE = "app_language";

    private String entryId;
    private String entryDate;
    private String entryText;
    private String entrySummary;

    private ScrollView detailRootLayout;
    private ImageView btnDetailBack;
    private ImageView btnDetailSearch;
    private ImageView btnDetailDelete;
    private TextView tvDetailDate;
    private View cardDetailResult;
    private EditText etDetailResult;
    private TextView tvDetailWordCount;
    private Button btnDetailClear;
    private View layoutDetailSummary;
    private TextView tvDetailSummaryContent;
    private Button btnDetailCopySummary;
    private Button btnDetailCopy;
    private Button btnDetailShare;

    // Search inside detail
    private View layoutSearchMatchBar;
    private EditText etDetailSearchInput;
    private TextView tvSearchMatchCounter;
    private ImageView btnSearchPrevMatch;
    private ImageView btnSearchNextMatch;
    private ImageView btnSearchDismiss;

    private List<SearchHighlightHelper.MatchRange> textMatches = new ArrayList<>();
    private int currentMatchIndex = -1;

    @Override
    protected void attachBaseContext(Context newBase) {
        SharedPreferences sp = newBase.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String langCode = sp.getString(KEY_APP_LANGUAGE, "system");
        String theme = sp.getString("app_theme", MainActivity.THEME_SYSTEM);
        super.attachBaseContext(MainActivity.updateContextLocaleAndTheme(newBase, langCode, theme));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        MainActivity.applyAppTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history_detail);
        AmoledThemeHelper.applyToHistoryDetail(this);

        Intent intent = getIntent();
        entryId = intent.getStringExtra("entry_id");
        entryDate = intent.getStringExtra("date");
        entryText = intent.getStringExtra("text");
        entrySummary = intent.getStringExtra("summary");
        String initialSearchQuery = intent.getStringExtra("search_query");

        detailRootLayout = findViewById(R.id.detailRootLayout);
        btnDetailBack = findViewById(R.id.btnDetailBack);
        btnDetailSearch = findViewById(R.id.btnDetailSearch);
        btnDetailDelete = findViewById(R.id.btnDetailDelete);
        tvDetailDate = findViewById(R.id.tvDetailDate);
        cardDetailResult = findViewById(R.id.cardDetailResult);
        etDetailResult = findViewById(R.id.etDetailResult);
        tvDetailWordCount = findViewById(R.id.tvDetailWordCount);
        btnDetailClear = findViewById(R.id.btnDetailClear);
        layoutDetailSummary = findViewById(R.id.layoutDetailSummary);
        tvDetailSummaryContent = findViewById(R.id.tvDetailSummaryContent);
        btnDetailCopySummary = findViewById(R.id.btnDetailCopySummary);
        btnDetailCopy = findViewById(R.id.btnDetailCopy);
        btnDetailShare = findViewById(R.id.btnDetailShare);

        // Search controls
        layoutSearchMatchBar = findViewById(R.id.layoutSearchMatchBar);
        etDetailSearchInput = findViewById(R.id.etDetailSearchInput);
        tvSearchMatchCounter = findViewById(R.id.tvSearchMatchCounter);
        btnSearchPrevMatch = findViewById(R.id.btnSearchPrevMatch);
        btnSearchNextMatch = findViewById(R.id.btnSearchNextMatch);
        btnSearchDismiss = findViewById(R.id.btnSearchDismiss);

        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int textSizeSp = sp.getInt(MainActivity.KEY_TEXT_SIZE, 15);
        if (etDetailResult != null) {
            etDetailResult.setTextSize(textSizeSp);
        }
        if (tvDetailSummaryContent != null) {
            tvDetailSummaryContent.setTextSize(textSizeSp);
        }

        if (entryDate != null) {
            tvDetailDate.setText(entryDate);
        }

        if (entryText != null) {
            etDetailResult.setText(entryText);
            updateWordCount(entryText);
        }

        if (entrySummary != null && !entrySummary.trim().isEmpty()) {
            layoutDetailSummary.setVisibility(View.VISIBLE);
            tvDetailSummaryContent.setText(entrySummary);
        } else {
            layoutDetailSummary.setVisibility(View.GONE);
        }

        btnDetailBack.setOnClickListener(v -> finish());

        if (btnDetailSearch != null) {
            btnDetailSearch.setOnClickListener(v -> {
                if (layoutSearchMatchBar.getVisibility() == View.VISIBLE) {
                    layoutSearchMatchBar.setVisibility(View.GONE);
                    performSearch("");
                } else {
                    layoutSearchMatchBar.setVisibility(View.VISIBLE);
                    etDetailSearchInput.requestFocus();
                }
            });
        }

        if (etDetailSearchInput != null) {
            etDetailSearchInput.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    performSearch(s != null ? s.toString() : "");
                }

                @Override
                public void afterTextChanged(Editable s) {}
            });
        }

        if (btnSearchNextMatch != null) {
            btnSearchNextMatch.setOnClickListener(v -> jumpToMatch(currentMatchIndex + 1));
        }

        if (btnSearchPrevMatch != null) {
            btnSearchPrevMatch.setOnClickListener(v -> jumpToMatch(currentMatchIndex - 1));
        }

        if (btnSearchDismiss != null) {
            btnSearchDismiss.setOnClickListener(v -> {
                if (layoutSearchMatchBar != null) layoutSearchMatchBar.setVisibility(View.GONE);
                if (etDetailSearchInput != null) etDetailSearchInput.setText("");
                performSearch("");
            });
        }

        btnDetailDelete.setOnClickListener(v -> {
            if (entryId != null) {
                HistoryManager.deleteEntry(this, entryId);
                Toast.makeText(this, R.string.history_deleted_toast, Toast.LENGTH_SHORT).show();
            }
            finish();
        });

        btnDetailClear.setOnClickListener(v -> {
            entryText = "";
            etDetailResult.setText("");
            layoutDetailSummary.setVisibility(View.GONE);
            updateWordCount("");
            if (layoutSearchMatchBar != null) layoutSearchMatchBar.setVisibility(View.GONE);
            performSearch("");
            Toast.makeText(this, R.string.cleared_toast, Toast.LENGTH_SHORT).show();
        });

        btnDetailCopy.setOnClickListener(v -> {
            String text = etDetailResult.getText().toString().trim();
            if (text.isEmpty()) {
                Toast.makeText(this, R.string.no_text_to_copy, Toast.LENGTH_SHORT).show();
                return;
            }
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Transcribed Text", text);
            cm.setPrimaryClip(clip);
            Toast.makeText(this, R.string.copied_toast, Toast.LENGTH_SHORT).show();
        });

        btnDetailCopySummary.setOnClickListener(v -> {
            String sum = tvDetailSummaryContent.getText().toString().trim();
            if (sum.isEmpty()) return;
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Summary", sum);
            cm.setPrimaryClip(clip);
            Toast.makeText(this, R.string.summary_copied_toast, Toast.LENGTH_SHORT).show();
        });

        btnDetailShare.setOnClickListener(v -> {
            String text = etDetailResult.getText().toString().trim();
            if (text.isEmpty()) {
                Toast.makeText(this, R.string.no_text_to_share, Toast.LENGTH_SHORT).show();
                return;
            }
            String sum = tvDetailSummaryContent.getText().toString().trim();
            String fullShare = text;
            if (!sum.isEmpty()) {
                fullShare = text + "\n\n--- " + getString(R.string.summary_header) + " ---\n" + sum;
            }
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_TEXT, fullShare);
            startActivity(Intent.createChooser(shareIntent, getString(R.string.btn_share)));
        });

        Button btnDetailExport = findViewById(R.id.btnDetailExport);
        if (btnDetailExport != null) {
            btnDetailExport.setOnClickListener(v -> {
                String text = etDetailResult.getText().toString().trim();
                String sum = tvDetailSummaryContent.getText().toString().trim();
                ExportHelper.showExportDialog(this, text, sum, entryDate);
            });
        }

        // If launched with an initial search query from the history list
        if (initialSearchQuery != null && !initialSearchQuery.trim().isEmpty()) {
            if (layoutSearchMatchBar != null) {
                layoutSearchMatchBar.setVisibility(View.VISIBLE);
            }
            if (etDetailSearchInput != null) {
                etDetailSearchInput.setText(initialSearchQuery);
            }
            performSearch(initialSearchQuery);
        }
    }

    private void performSearch(String query) {
        if (query == null || query.trim().isEmpty() || entryText == null) {
            textMatches.clear();
            currentMatchIndex = -1;
            if (tvSearchMatchCounter != null) tvSearchMatchCounter.setText("0/0");
            if (etDetailResult != null) etDetailResult.setText(entryText != null ? entryText : "");
            if (tvDetailSummaryContent != null && entrySummary != null) {
                tvDetailSummaryContent.setText(entrySummary);
            }
            return;
        }

        String q = query.trim();
        textMatches = SearchHighlightHelper.findAllMatches(entryText, q);

        boolean isAmoled = AmoledThemeHelper.isAmoled(this);
        int normalBg = isAmoled ? 0xFF5D4037 : 0xFFFFF59D;
        int normalFg = isAmoled ? 0xFFFFE082 : 0xFF000000;

        // Also highlight in summary if present
        if (entrySummary != null && !entrySummary.trim().isEmpty() && tvDetailSummaryContent != null) {
            List<SearchHighlightHelper.MatchRange> sumMatches = SearchHighlightHelper.findAllMatches(entrySummary, q);
            if (!sumMatches.isEmpty()) {
                SpannableString sumSs = new SpannableString(entrySummary);
                SearchHighlightHelper.applyHighlights(sumSs, sumMatches, -1, normalBg, normalFg, normalBg, normalFg);
                tvDetailSummaryContent.setText(sumSs);
            } else {
                tvDetailSummaryContent.setText(entrySummary);
            }
        }

        if (textMatches.isEmpty()) {
            currentMatchIndex = -1;
            if (tvSearchMatchCounter != null) tvSearchMatchCounter.setText("0/0");
            if (etDetailResult != null) etDetailResult.setText(entryText);
        } else {
            jumpToMatch(0);
        }
    }

    private void jumpToMatch(int index) {
        if (textMatches.isEmpty() || entryText == null) return;
        currentMatchIndex = (index + textMatches.size()) % textMatches.size();

        boolean isAmoled = AmoledThemeHelper.isAmoled(this);
        int normalBg = isAmoled ? 0xFF5D4037 : 0xFFFFF59D;
        int normalFg = isAmoled ? 0xFFFFE082 : 0xFF000000;
        int activeBg = isAmoled ? 0xFFFF8F00 : 0xFFFF9800;
        int activeFg = isAmoled ? 0xFF000000 : 0xFFFFFFFF;

        SpannableString ss = new SpannableString(entryText);
        SearchHighlightHelper.applyHighlights(ss, textMatches, currentMatchIndex, normalBg, normalFg, activeBg, activeFg);
        if (etDetailResult != null) {
            etDetailResult.setText(ss);
        }

        if (tvSearchMatchCounter != null) {
            tvSearchMatchCounter.setText((currentMatchIndex + 1) + "/" + textMatches.size());
        }

        // Smoothly scroll to the matched position in the script
        if (etDetailResult != null && detailRootLayout != null && cardDetailResult != null) {
            etDetailResult.post(() -> {
                if (currentMatchIndex < 0 || currentMatchIndex >= textMatches.size()) return;
                SearchHighlightHelper.MatchRange m = textMatches.get(currentMatchIndex);
                android.text.Layout layout = etDetailResult.getLayout();
                if (layout != null) {
                    int line = layout.getLineForOffset(m.start);
                    int lineTop = layout.getLineTop(line);
                    int targetY = cardDetailResult.getTop() + etDetailResult.getTop() + lineTop - AmoledThemeHelper.dpToPx(this, 100);
                    if (targetY < 0) targetY = 0;
                    detailRootLayout.smoothScrollTo(0, targetY);
                }
            });
        }
    }

    private void updateWordCount(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            tvDetailWordCount.setText("");
            return;
        }
        String[] words = trimmed.split("\\s+");
        tvDetailWordCount.setText(getString(R.string.words_count, words.length, trimmed.length()));
    }
}

