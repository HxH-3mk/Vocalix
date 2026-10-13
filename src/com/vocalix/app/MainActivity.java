package com.vocalix.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.Manifest;
import android.app.KeyguardManager;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.widget.SeekBar;
import android.widget.Toast;

import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.LocaleList;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.Spinner;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Vocalix - Modern Material 3 multilingual audio-to-text transcriber.
 * Direct cloud-accelerated intelligence with automatic language detection,
 * speaker diarization, and smart conversational summaries.
 */
public class MainActivity extends Activity {
    private static final String TAG = "VocalixActivity";
    private static final int REQ_PICK_AUDIO = 1001;
    private static final int REQ_RECORD_AUDIO = 2001;
    private static final int REQ_BIOMETRIC_AUTH = 2002;
    private static final int REQ_RESTORE_FILE = 2003;
    private static final int REQ_NOTIF_PERMISSION = 2004;

    private static final int AUTH_ACTION_UNLOCK_HISTORY = 1;
    private static final int AUTH_ACTION_DISABLE_LOCK = 2;
    private static final int AUTH_ACTION_BACKUP = 3;
    private int pendingAuthAction = 0;

    private static final String PREFS_NAME = "vocalix_settings";
    private static final String KEY_AI_PROVIDER = "ai_provider";
    private static final String KEY_AI_MODEL = "ai_model";
    private static final String KEY_API_KEY = "gemini_api_key";
    private static final String KEY_USAGE_DATE = "usage_date";
    private static final String KEY_USAGE_COUNT = "usage_count_today";
    private static final String KEY_APP_LANGUAGE = "app_language";
    public static final String KEY_APP_THEME = "app_theme";
    public static final String KEY_TEXT_SIZE = "script_text_size";
    public static final String KEY_BIOMETRIC_LOCK = "biometric_history_lock";

    public static final String THEME_SYSTEM = "system";
    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";
    public static final String THEME_AMOLED = "amoled";

    public static void applyAppTheme(Activity activity) {
        SharedPreferences sp = activity.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String theme = sp.getString(KEY_APP_THEME, THEME_SYSTEM);

        if (THEME_LIGHT.equals(theme)) {
            activity.setTheme(R.style.Theme_Vocalix);
        } else if (THEME_DARK.equals(theme)) {
            activity.setTheme(R.style.Theme_Vocalix_Dark);
        } else if (THEME_AMOLED.equals(theme)) {
            activity.setTheme(R.style.Theme_Vocalix_Amoled);
        } else {
            int nightModeFlags = activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            if (nightModeFlags == Configuration.UI_MODE_NIGHT_YES) {
                activity.setTheme(R.style.Theme_Vocalix_Dark);
            } else {
                activity.setTheme(R.style.Theme_Vocalix);
            }
        }
    }

    private static final int DAILY_QUOTA_LIMIT = 1500;

    public static final String[] SUPPORTED_LANG_CODES = {
            "system", "ar", "en", "es", "fr", "de", "tr", "zh", "ja", "ko", "ru", "pt", "ur", "fa"
    };

    public static final String[] SUPPORTED_LANG_NAMES = {
            "تلقائي (حسب لغة النظام / System Default)",
            "العربية (Arabic)",
            "English",
            "Español (Spanish)",
            "Français (French)",
            "Deutsch (German)",
            "Türkçe (Turkish)",
            "中文 (Chinese)",
            "日本語 (Japanese)",
            "한국어 (Korean)",
            "Русский (Russian)",
            "Português (Portuguese)",
            "اردو (Urdu)",
            "فارسی (Persian)"
    };

    private SharedPreferences prefs;

    public static final int TAB_HOME = 0;
    public static final int TAB_HISTORY = 1;
    public static final int TAB_SETTINGS = 2;
    public static final String EXTRA_INITIAL_TAB = "extra_initial_tab";

    private int currentTab = TAB_HOME;

    // Tab containers
    private View tabHomeContainer;
    private View tabHistoryContainer;
    private View tabSettingsContainer;

    // Bottom Navigation Elements
    private View navItemHome;
    private View navItemHistory;
    private View navItemSettings;
    private ImageView navIconHome;
    private TextView navLabelHome;
    private ImageView navIconHistory;
    private TextView navLabelHistory;
    private ImageView navIconSettings;
    private TextView navLabelSettings;

    // Tab 1 (Home) UI elements
    private TextView tvStatus;
    private TextView tvAudioInfo;
    private TextView tvWordCount;
    private LinearLayout layoutAudioMeta;
    private View progressBar;
    private EditText etResult;
    private Button btnPickFile;
    private Button btnLiveRecord;
    private Button btnCopy;
    private Button btnShare;
    private Button btnExportResult;
    private Button btnClear;
    private Button btnRetry;
    private M3Switch cbDiarizationMain;

    // Live Voice Recorder & Mini Player elements
    private View cardLiveRecorder;
    private View layoutRecordingActive;
    private TextView tvRecordingTimer;
    private Button btnCancelRecord;
    private Button btnStopRecord;
    private View layoutRecordingPlayer;
    private ImageView btnPlayerPlayPause;
    private SeekBar sbPlayerProgress;
    private TextView tvPlayerTime;
    private Button btnDiscardRecording;
    private Button btnTranscribeRecording;

    private MediaRecorder mediaRecorder;
    private MediaPlayer mediaPlayer;
    private File recordedAudioFile;
    private boolean isRecording = false;
    private long recordingStartTime = 0;
    private Handler recordTimerHandler = new Handler(Looper.getMainLooper());
    private Runnable recordTimerRunnable;
    private Handler playerHandler = new Handler(Looper.getMainLooper());
    private Runnable playerRunnable;

    // Summary Card elements
    private LinearLayout layoutSummaryContainer;
    private TextView tvSummaryContent;
    private View pbSummary;
    private Button btnCopySummary;

    // Tab 2 (History) UI elements
    private View layoutHistoryDisabled;
    private View layoutHistoryEmpty;
    private View layoutHistorySearchEmpty;
    private TextView tvHistorySearchEmptyDesc;
    private View layoutHistorySearchBar;
    private EditText etHistorySearch;
    private ImageView btnHistorySearchClear;
    private View layoutHistorySearchStats;
    private TextView tvHistorySearchCount;
    private ListView lvHistory;
    private ImageView btnHistoryBack;
    private ImageView btnHistoryClearAll;
    private Button btnEnableHistoryHere;
    private List<HistoryManager.HistoryEntry> historyEntries = new ArrayList<>();
    private List<HistoryManager.HistoryEntry> filteredEntries = new ArrayList<>();
    private String currentSearchQuery = "";
    private HistoryTabAdapter historyAdapter;
    private boolean isHistoryUnlockedForSession = false;

    // Tab 3 (Settings) UI elements
    private ImageView btnSettingsBack;
    private View settingsCardProviderPicker;
    private TextView settingsTvCurrentProvider;
    private TextView settingsTvProviderDesc;
    private View settingsCardModelPicker;
    private TextView settingsTvCurrentModel;
    private EditText settingsEtKey;
    private View settingsBtnPaste;
    private View settingsBtnTest;
    private View settingsBtnGetFree;
    private View settingsCardLanguage;
    private TextView settingsTvCurrentLang;
    private View settingsCardTheme;
    private TextView settingsTvCurrentTheme;
    private View settingsCardTextSize;
    private TextView settingsTvCurrentTextSize;
    private View settingsRowDiarization;
    private M3Switch settingsCbDiarization;
    private View settingsRowHistory;
    private M3Switch settingsCbHistory;
    private View settingsRowBiometric;
    private M3Switch settingsCbBiometric;
    private View settingsRowBgNotif;
    private M3Switch settingsCbBgNotif;
    private View settingsRowBackup;
    private View settingsRowRestore;



    // Audio file state
    private File currentRawAudioFile;
    private String currentMimeType = "audio/wav";

    @Override
    protected void attachBaseContext(Context newBase) {
        SharedPreferences sp = newBase.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String langCode = sp.getString(KEY_APP_LANGUAGE, "system");
        String theme = sp.getString(KEY_APP_THEME, THEME_SYSTEM);
        Context context = updateContextLocaleAndTheme(newBase, langCode, theme);
        super.attachBaseContext(context);
    }

    public static Context updateContextLocaleAndTheme(Context context, String langCode, String theme) {
        Locale locale = ("system".equals(langCode) || langCode == null || langCode.isEmpty())
                ? Locale.getDefault()
                : new Locale(langCode);
        Locale.setDefault(locale);

        Configuration config = new Configuration(context.getResources().getConfiguration());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(new LocaleList(locale));
        } else {
            config.locale = locale;
        }

        // Configure night mode according to selected theme
        if (THEME_LIGHT.equals(theme)) {
            config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_NO;
        } else if (THEME_DARK.equals(theme) || THEME_AMOLED.equals(theme)) {
            config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_YES;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return context.createConfigurationContext(config);
        } else {
            context.getResources().updateConfiguration(config, context.getResources().getDisplayMetrics());
            return context;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applyAppTheme(this);
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        setContentView(R.layout.activity_main);

        initViews();
        setupHistoryTab();
        setupSettingsTab();

        AmoledThemeHelper.applyToMainActivity(this);

        int initialTab = getIntent().getIntExtra(EXTRA_INITIAL_TAB, TAB_HOME);
        if (savedInstanceState != null) {
            initialTab = savedInstanceState.getInt("current_tab", initialTab);
        }
        switchTab(initialTab);

        // Check if opened via Share Sheet (from WhatsApp, Telegram, Files, etc.)
        handleIncomingIntent(getIntent());
    }

    private void initViews() {
        tabHomeContainer = findViewById(R.id.tabHomeContainer);
        tabHistoryContainer = findViewById(R.id.tabHistoryContainer);
        tabSettingsContainer = findViewById(R.id.tabSettingsContainer);

        navItemHome = findViewById(R.id.navItemHome);
        navItemHistory = findViewById(R.id.navItemHistory);
        navItemSettings = findViewById(R.id.navItemSettings);
        navIconHome = findViewById(R.id.navIconHome);
        navLabelHome = findViewById(R.id.navLabelHome);
        navIconHistory = findViewById(R.id.navIconHistory);
        navLabelHistory = findViewById(R.id.navLabelHistory);
        navIconSettings = findViewById(R.id.navIconSettings);
        navLabelSettings = findViewById(R.id.navLabelSettings);

        if (navItemHome != null) navItemHome.setOnClickListener(v -> switchTab(TAB_HOME));
        if (navItemHistory != null) navItemHistory.setOnClickListener(v -> switchTab(TAB_HISTORY));
        if (navItemSettings != null) navItemSettings.setOnClickListener(v -> switchTab(TAB_SETTINGS));

        tvStatus = findViewById(R.id.tvStatus);
        tvAudioInfo = findViewById(R.id.tvAudioInfo);
        tvWordCount = findViewById(R.id.tvWordCount);
        layoutAudioMeta = findViewById(R.id.layoutAudioMeta);
        progressBar = findViewById(R.id.progressBar);
        etResult = findViewById(R.id.etResult);

        // Apply saved script font size
        int textSizeSp = prefs.getInt(KEY_TEXT_SIZE, 15);
        if (etResult != null) {
            etResult.setTextSize(textSizeSp);
        }
        btnPickFile = findViewById(R.id.btnPickFile);
        btnLiveRecord = findViewById(R.id.btnLiveRecord);
        btnCopy = findViewById(R.id.btnCopy);
        btnShare = findViewById(R.id.btnShare);
        btnExportResult = findViewById(R.id.btnExportResult);
        btnClear = findViewById(R.id.btnClear);
        btnRetry = findViewById(R.id.btnRetry);
        cbDiarizationMain = findViewById(R.id.cbDiarizationMain);

        // Live Voice Recorder & Mini Player controls
        cardLiveRecorder = findViewById(R.id.cardLiveRecorder);
        layoutRecordingActive = findViewById(R.id.layoutRecordingActive);
        tvRecordingTimer = findViewById(R.id.tvRecordingTimer);
        btnCancelRecord = findViewById(R.id.btnCancelRecord);
        btnStopRecord = findViewById(R.id.btnStopRecord);
        layoutRecordingPlayer = findViewById(R.id.layoutRecordingPlayer);
        btnPlayerPlayPause = findViewById(R.id.btnPlayerPlayPause);
        sbPlayerProgress = findViewById(R.id.sbPlayerProgress);
        tvPlayerTime = findViewById(R.id.tvPlayerTime);
        btnDiscardRecording = findViewById(R.id.btnDiscardRecording);
        btnTranscribeRecording = findViewById(R.id.btnTranscribeRecording);

        if (btnLiveRecord != null) {
            btnLiveRecord.setOnClickListener(v -> checkPermissionAndStartRecording());
        }
        if (btnCancelRecord != null) {
            btnCancelRecord.setOnClickListener(v -> cancelLiveRecording());
        }
        if (btnStopRecord != null) {
            btnStopRecord.setOnClickListener(v -> stopLiveRecording());
        }
        if (btnDiscardRecording != null) {
            btnDiscardRecording.setOnClickListener(v -> discardLiveRecording());
        }
        if (btnTranscribeRecording != null) {
            btnTranscribeRecording.setOnClickListener(v -> transcribeLiveRecording());
        }
        if (btnPlayerPlayPause != null) {
            btnPlayerPlayPause.setOnClickListener(v -> togglePlayer());
        }

        layoutSummaryContainer = findViewById(R.id.layoutSummaryContainer);
        tvSummaryContent = findViewById(R.id.tvSummaryContent);
        if (tvSummaryContent != null) {
            tvSummaryContent.setTextSize(textSizeSp);
        }
        pbSummary = findViewById(R.id.pbSummary);
        btnCopySummary = findViewById(R.id.btnCopySummary);

        if (btnCopySummary != null) {
            btnCopySummary.setOnClickListener(v -> {
                String sumText = tvSummaryContent.getText().toString().trim();
                if (sumText.isEmpty()) return;
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("Audio Summary", sumText);
                cm.setPrimaryClip(clip);
                Toast.makeText(this, R.string.summary_copied_toast, Toast.LENGTH_SHORT).show();
            });
        }

        if (cbDiarizationMain != null) {
            cbDiarizationMain.setChecked(prefs.getBoolean("split_speakers", true));
            cbDiarizationMain.setOnCheckedChangeListener((buttonView, isChecked) -> {
                prefs.edit().putBoolean("split_speakers", isChecked).apply();
                if (settingsCbDiarization != null) {
                    settingsCbDiarization.setChecked(isChecked);
                }
            });
        }

        View cardDiarization = findViewById(R.id.cardDiarization);
        if (cardDiarization != null) {
            cardDiarization.setOnClickListener(v -> {
                if (cbDiarizationMain != null) {
                    cbDiarizationMain.toggle();
                }
            });
        }

        btnRetry.setOnClickListener(v -> {
            btnRetry.setVisibility(View.GONE);
            startTranscription();
        });

        btnPickFile.setOnClickListener(v -> openAudioPicker());

        btnCopy.setOnClickListener(v -> {
            String text = etResult.getText().toString().trim();
            if (text.isEmpty()) {
                Toast.makeText(this, R.string.no_text_to_copy, Toast.LENGTH_SHORT).show();
                return;
            }
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Transcribed Text", text);
            cm.setPrimaryClip(clip);
            Toast.makeText(this, R.string.copied_toast, Toast.LENGTH_SHORT).show();
        });

        btnShare.setOnClickListener(v -> {
            String text = etResult.getText().toString().trim();
            if (text.isEmpty()) {
                Toast.makeText(this, R.string.no_text_to_share, Toast.LENGTH_SHORT).show();
                return;
            }
            String sum = tvSummaryContent != null ? tvSummaryContent.getText().toString().trim() : "";
            String fullShare = text;
            if (!sum.isEmpty()) {
                fullShare = text + "\n\n--- " + getString(R.string.summary_header) + " ---\n" + sum;
            }
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_TEXT, fullShare);
            startActivity(Intent.createChooser(shareIntent, getString(R.string.btn_share)));
        });

        if (btnExportResult != null) {
            btnExportResult.setOnClickListener(v -> {
                String text = etResult.getText().toString().trim();
                if (text.isEmpty()) {
                    Toast.makeText(this, R.string.no_text_to_share, Toast.LENGTH_SHORT).show();
                    return;
                }
                String sum = (tvSummaryContent != null) ? tvSummaryContent.getText().toString().trim() : "";
                ExportHelper.showExportDialog(this, text, sum);
            });
        }

        btnClear.setOnClickListener(v -> {
            NotificationHelper.dismissTranscribingNotification(this, false);
            etResult.setText("");
            if (layoutSummaryContainer != null) {
                layoutSummaryContainer.setVisibility(View.GONE);
                tvSummaryContent.setText("");
            }
            tvStatus.setText(R.string.status_idle);
            layoutAudioMeta.setVisibility(View.GONE);
            progressBar.setVisibility(View.GONE);
            btnRetry.setVisibility(View.GONE);
            updateWordCount("");
            Toast.makeText(this, R.string.cleared_toast, Toast.LENGTH_SHORT).show();
        });

        etResult.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateWordCount(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
    }

    private void recordUsageHit() {
        // Handled silently
    }

    private void updateWordCount(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            tvWordCount.setText("");
            return;
        }
        String[] words = trimmed.split("\\s+");
        tvWordCount.setText(getString(R.string.words_count, words.length, trimmed.length()));
    }

    public void switchTab(int tab) {
        if (tab == TAB_HISTORY) {
            boolean isBioLocked = prefs.getBoolean(KEY_BIOMETRIC_LOCK, false);
            if (isBioLocked && !isHistoryUnlockedForSession) {
                requestBiometricAuth(
                        AUTH_ACTION_UNLOCK_HISTORY,
                        getString(R.string.biometric_prompt_title),
                        getString(R.string.biometric_prompt_desc));
                return;
            }
        }

        currentTab = tab;

        if (tab == TAB_HOME && isRecording) {
            // Keep recording visible
        }

        if (tabHomeContainer != null) {
            tabHomeContainer.setVisibility(tab == TAB_HOME ? View.VISIBLE : View.GONE);
        }
        if (tabHistoryContainer != null) {
            tabHistoryContainer.setVisibility(tab == TAB_HISTORY ? View.VISIBLE : View.GONE);
        }
        if (tabSettingsContainer != null) {
            tabSettingsContainer.setVisibility(tab == TAB_SETTINGS ? View.VISIBLE : View.GONE);
        }

        updateBottomNavUI(tab);

        if (tab == TAB_HISTORY) {
            loadHistoryTab();
        } else if (tab == TAB_SETTINGS) {
            refreshSettingsUI();
        } else if (tab == TAB_HOME) {
            int textSizeSp = prefs.getInt(KEY_TEXT_SIZE, 15);
            if (etResult != null) {
                etResult.setTextSize(textSizeSp);
            }
            if (tvSummaryContent != null) {
                tvSummaryContent.setTextSize(textSizeSp);
            }
            if (cbDiarizationMain != null) {
                cbDiarizationMain.setChecked(prefs.getBoolean("split_speakers", true));
            }
        }
    }

    private void requestBiometricAuth(int action, String promptTitle, String promptDesc) {
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && km != null && km.isKeyguardSecure()) {
            Intent intent = km.createConfirmDeviceCredentialIntent(promptTitle, promptDesc);
            if (intent != null) {
                try {
                    pendingAuthAction = action;
                    startActivityForResult(intent, REQ_BIOMETRIC_AUTH);
                    return;
                } catch (Exception ignored) {}
            }
        }
        // If device has no screen lock configured, proceed directly
        Toast.makeText(this, R.string.biometric_not_configured, Toast.LENGTH_SHORT).show();
        onBiometricAuthSuccess(action);
    }

    private void onBiometricAuthSuccess(int action) {
        switch (action) {
            case AUTH_ACTION_UNLOCK_HISTORY:
                isHistoryUnlockedForSession = true;
                switchTab(TAB_HISTORY);
                break;
            case AUTH_ACTION_DISABLE_LOCK:
                prefs.edit().putBoolean(KEY_BIOMETRIC_LOCK, false).apply();
                isHistoryUnlockedForSession = false;
                if (settingsCbBiometric != null) {
                    settingsCbBiometric.setCheckedSilently(false);
                }
                Toast.makeText(this, R.string.biometric_disabled_toast, Toast.LENGTH_SHORT).show();
                break;
            case AUTH_ACTION_BACKUP:
                HistoryBackupHelper.exportBackup(this);
                break;
        }
    }

    private void updateBottomNavUI(int tab) {
        int primaryColor = getResources().getColor(R.color.color_primary);
        int secondaryColor = getResources().getColor(R.color.text_secondary);

        if (navIconHome != null) navIconHome.setColorFilter(tab == TAB_HOME ? primaryColor : secondaryColor);
        if (navLabelHome != null) {
            navLabelHome.setTextColor(tab == TAB_HOME ? primaryColor : secondaryColor);
            navLabelHome.setTypeface(null, tab == TAB_HOME ? Typeface.BOLD : Typeface.NORMAL);
        }

        if (navIconHistory != null) navIconHistory.setColorFilter(tab == TAB_HISTORY ? primaryColor : secondaryColor);
        if (navLabelHistory != null) {
            navLabelHistory.setTextColor(tab == TAB_HISTORY ? primaryColor : secondaryColor);
            navLabelHistory.setTypeface(null, tab == TAB_HISTORY ? Typeface.BOLD : Typeface.NORMAL);
        }

        if (navIconSettings != null) navIconSettings.setColorFilter(tab == TAB_SETTINGS ? primaryColor : secondaryColor);
        if (navLabelSettings != null) {
            navLabelSettings.setTextColor(tab == TAB_SETTINGS ? primaryColor : secondaryColor);
            navLabelSettings.setTypeface(null, tab == TAB_SETTINGS ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    private void setupHistoryTab() {
        layoutHistoryDisabled = findViewById(R.id.layoutHistoryDisabled);
        layoutHistoryEmpty = findViewById(R.id.layoutHistoryEmpty);
        layoutHistorySearchEmpty = findViewById(R.id.layoutHistorySearchEmpty);
        tvHistorySearchEmptyDesc = findViewById(R.id.tvHistorySearchEmptyDesc);
        layoutHistorySearchBar = findViewById(R.id.layoutHistorySearchBar);
        etHistorySearch = findViewById(R.id.etHistorySearch);
        btnHistorySearchClear = findViewById(R.id.btnHistorySearchClear);
        layoutHistorySearchStats = findViewById(R.id.layoutHistorySearchStats);
        tvHistorySearchCount = findViewById(R.id.tvHistorySearchCount);
        lvHistory = findViewById(R.id.lvHistory);
        btnHistoryBack = findViewById(R.id.btnHistoryBack);
        btnHistoryClearAll = findViewById(R.id.btnHistoryClearAll);
        btnEnableHistoryHere = findViewById(R.id.btnEnableHistoryHere);

        if (btnHistoryBack != null) {
            btnHistoryBack.setOnClickListener(v -> switchTab(TAB_HOME));
        }

        if (btnEnableHistoryHere != null) {
            btnEnableHistoryHere.setOnClickListener(v -> {
                HistoryManager.setHistoryEnabled(this, true);
                if (settingsCbHistory != null) {
                    settingsCbHistory.setChecked(true);
                }
                Toast.makeText(this, R.string.settings_saved_toast, Toast.LENGTH_SHORT).show();
                loadHistoryTab();
            });
        }

        if (btnHistoryClearAll != null) {
            btnHistoryClearAll.setOnClickListener(v -> {
                AlertDialog d = new AlertDialog.Builder(this)
                        .setTitle(R.string.history_clear_all)
                        .setMessage(R.string.history_clear_confirm)
                        .setPositiveButton(android.R.string.ok, (dlg, w) -> {
                            HistoryManager.clearAll(this);
                            Toast.makeText(this, R.string.history_cleared_toast, Toast.LENGTH_SHORT).show();
                            loadHistoryTab();
                        })
                        .setNegativeButton(R.string.btn_cancel, null)
                        .show();
                AmoledThemeHelper.applyToDialog(this, d);
            });
        }

        if (etHistorySearch != null) {
            etHistorySearch.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    filterHistory(s != null ? s.toString() : "");
                }

                @Override
                public void afterTextChanged(Editable s) {}
            });
        }

        if (btnHistorySearchClear != null) {
            btnHistorySearchClear.setOnClickListener(v -> {
                if (etHistorySearch != null) {
                    etHistorySearch.setText("");
                }
            });
        }

        historyAdapter = new HistoryTabAdapter();
        if (lvHistory != null) {
            lvHistory.setAdapter(historyAdapter);
            lvHistory.setOnItemClickListener((parent, view, position, id) -> {
                if (position >= 0 && position < filteredEntries.size()) {
                    HistoryManager.HistoryEntry entry = filteredEntries.get(position);
                    Intent intent = new Intent(this, HistoryDetailActivity.class);
                    intent.putExtra("entry_id", entry.id);
                    intent.putExtra("date", entry.getFormattedDate12h());
                    intent.putExtra("text", entry.text);
                    intent.putExtra("summary", entry.summary);
                    intent.putExtra("search_query", currentSearchQuery);
                    startActivity(intent);
                }
            });
        }
    }

    private void filterHistory(String query) {
        currentSearchQuery = query != null ? query.trim() : "";
        if (currentSearchQuery.isEmpty()) {
            if (btnHistorySearchClear != null) btnHistorySearchClear.setVisibility(View.GONE);
            if (layoutHistorySearchStats != null) layoutHistorySearchStats.setVisibility(View.GONE);
            filteredEntries = new ArrayList<>(historyEntries);
        } else {
            if (btnHistorySearchClear != null) btnHistorySearchClear.setVisibility(View.VISIBLE);
            String normQuery = SearchHighlightHelper.normalize(currentSearchQuery);
            filteredEntries = new ArrayList<>();
            for (HistoryManager.HistoryEntry entry : historyEntries) {
                String normText = SearchHighlightHelper.normalize(entry.text);
                String normSum = SearchHighlightHelper.normalize(entry.summary);
                String normDate = SearchHighlightHelper.normalize(entry.getFormattedDate12h());
                if (normText.contains(normQuery) || normSum.contains(normQuery) || normDate.contains(normQuery)) {
                    filteredEntries.add(entry);
                }
            }
            if (layoutHistorySearchStats != null) {
                layoutHistorySearchStats.setVisibility(View.VISIBLE);
                if (tvHistorySearchCount != null) {
                    tvHistorySearchCount.setText(getString(R.string.history_search_found_count, filteredEntries.size()));
                }
            }
        }

        updateHistoryListVisibility();
        if (historyAdapter != null) {
            historyAdapter.notifyDataSetChanged();
        }
    }

    private void updateHistoryListVisibility() {
        if (historyEntries.isEmpty()) {
            if (layoutHistoryEmpty != null) layoutHistoryEmpty.setVisibility(View.VISIBLE);
            if (layoutHistorySearchEmpty != null) layoutHistorySearchEmpty.setVisibility(View.GONE);
            if (lvHistory != null) lvHistory.setVisibility(View.GONE);
        } else if (filteredEntries.isEmpty()) {
            if (layoutHistoryEmpty != null) layoutHistoryEmpty.setVisibility(View.GONE);
            if (layoutHistorySearchEmpty != null) {
                layoutHistorySearchEmpty.setVisibility(View.VISIBLE);
                if (tvHistorySearchEmptyDesc != null) {
                    tvHistorySearchEmptyDesc.setText(getString(R.string.history_search_no_results_desc, currentSearchQuery));
                }
            }
            if (lvHistory != null) lvHistory.setVisibility(View.GONE);
        } else {
            if (layoutHistoryEmpty != null) layoutHistoryEmpty.setVisibility(View.GONE);
            if (layoutHistorySearchEmpty != null) layoutHistorySearchEmpty.setVisibility(View.GONE);
            if (lvHistory != null) lvHistory.setVisibility(View.VISIBLE);
        }
    }

    private void loadHistoryTab() {
        boolean enabled = HistoryManager.isHistoryEnabled(this);
        if (!enabled) {
            if (layoutHistoryDisabled != null) layoutHistoryDisabled.setVisibility(View.VISIBLE);
            if (layoutHistoryEmpty != null) layoutHistoryEmpty.setVisibility(View.GONE);
            if (layoutHistorySearchEmpty != null) layoutHistorySearchEmpty.setVisibility(View.GONE);
            if (layoutHistorySearchBar != null) layoutHistorySearchBar.setVisibility(View.GONE);
            if (layoutHistorySearchStats != null) layoutHistorySearchStats.setVisibility(View.GONE);
            if (lvHistory != null) lvHistory.setVisibility(View.GONE);
            if (btnHistoryClearAll != null) btnHistoryClearAll.setVisibility(View.GONE);
            return;
        }

        if (layoutHistoryDisabled != null) layoutHistoryDisabled.setVisibility(View.GONE);
        historyEntries = HistoryManager.getHistoryEntries(this);

        if (historyEntries.isEmpty()) {
            if (layoutHistoryEmpty != null) layoutHistoryEmpty.setVisibility(View.VISIBLE);
            if (layoutHistorySearchEmpty != null) layoutHistorySearchEmpty.setVisibility(View.GONE);
            if (layoutHistorySearchBar != null) layoutHistorySearchBar.setVisibility(View.GONE);
            if (layoutHistorySearchStats != null) layoutHistorySearchStats.setVisibility(View.GONE);
            if (lvHistory != null) lvHistory.setVisibility(View.GONE);
            if (btnHistoryClearAll != null) btnHistoryClearAll.setVisibility(View.GONE);
        } else {
            if (layoutHistoryEmpty != null) layoutHistoryEmpty.setVisibility(View.GONE);
            if (layoutHistorySearchBar != null) layoutHistorySearchBar.setVisibility(View.VISIBLE);
            if (btnHistoryClearAll != null) btnHistoryClearAll.setVisibility(View.VISIBLE);
            filterHistory(etHistorySearch != null ? etHistorySearch.getText().toString() : "");
        }
    }

    private class HistoryTabAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return filteredEntries.size();
        }

        @Override
        public HistoryManager.HistoryEntry getItem(int position) {
            return filteredEntries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_history, parent, false);
            }

            HistoryManager.HistoryEntry entry = getItem(position);
            TextView tvDate = convertView.findViewById(R.id.itemHistoryDate);
            TextView tvSnippet = convertView.findViewById(R.id.itemHistorySnippet);
            TextView tvMatchBadge = convertView.findViewById(R.id.itemHistoryMatchBadge);
            ImageView btnDelete = convertView.findViewById(R.id.itemHistoryDelete);

            tvDate.setText(entry.getFormattedDate12h());

            if (!currentSearchQuery.isEmpty()) {
                int countInText = SearchHighlightHelper.countMatches(entry.text, currentSearchQuery);
                int countInSum = SearchHighlightHelper.countMatches(entry.summary, currentSearchQuery);
                int totalMatches = countInText + countInSum;

                if (totalMatches > 0 && tvMatchBadge != null) {
                    tvMatchBadge.setVisibility(View.VISIBLE);
                    tvMatchBadge.setText(getString(R.string.history_matches_in_script, totalMatches));
                } else if (tvMatchBadge != null) {
                    tvMatchBadge.setVisibility(View.GONE);
                }

                int highlightBg = AmoledThemeHelper.isAmoled(MainActivity.this) ? 0xFF5D4037 : 0xFFFFF59D;
                int highlightFg = AmoledThemeHelper.isAmoled(MainActivity.this) ? 0xFFFFE082 : 0xFF000000;
                tvSnippet.setText(SearchHighlightHelper.createHighlightedSnippet(entry.text, currentSearchQuery, highlightBg, highlightFg));
            } else {
                if (tvMatchBadge != null) {
                    tvMatchBadge.setVisibility(View.GONE);
                }
                tvSnippet.setText(entry.text);
            }

            btnDelete.setOnClickListener(v -> {
                HistoryManager.deleteEntry(MainActivity.this, entry.id);
                Toast.makeText(MainActivity.this, R.string.history_deleted_toast, Toast.LENGTH_SHORT).show();
                loadHistoryTab();
            });

            AmoledThemeHelper.applyToHistoryItemView(MainActivity.this, convertView);
            return convertView;
        }
    }

    private void setupSettingsTab() {
        btnSettingsBack = findViewById(R.id.btnSettingsBack);
        settingsCardProviderPicker = findViewById(R.id.settings_card_provider_picker);
        settingsTvCurrentProvider = findViewById(R.id.settings_tv_current_provider);
        settingsTvProviderDesc = findViewById(R.id.settings_tv_provider_desc);
        settingsCardModelPicker = findViewById(R.id.settings_card_model_picker);
        settingsTvCurrentModel = findViewById(R.id.settings_tv_current_model);
        settingsEtKey = findViewById(R.id.settings_et_key);
        settingsBtnPaste = findViewById(R.id.settings_btn_paste);
        settingsBtnTest = findViewById(R.id.settings_btn_test);
        settingsBtnGetFree = findViewById(R.id.settings_btn_get_free);
        settingsCardLanguage = findViewById(R.id.settings_card_language);
        settingsTvCurrentLang = findViewById(R.id.settings_tv_current_lang);
        settingsCardTheme = findViewById(R.id.settings_card_theme);
        settingsTvCurrentTheme = findViewById(R.id.settings_tv_current_theme);
        settingsCardTextSize = findViewById(R.id.settings_card_text_size);
        settingsTvCurrentTextSize = findViewById(R.id.settings_tv_current_text_size);
        settingsRowDiarization = findViewById(R.id.settings_row_diarization);
        settingsCbDiarization = findViewById(R.id.settings_cb_diarization);
        settingsRowHistory = findViewById(R.id.settings_row_history);
        settingsCbHistory = findViewById(R.id.settings_cb_history);

        if (btnSettingsBack != null) {
            btnSettingsBack.setOnClickListener(v -> switchTab(TAB_HOME));
        }

        // Provider Selector (Gemini, OpenAI, Claude, Groq)
        if (settingsCardProviderPicker != null) {
            final String[] providers = {
                    VocalixAiClient.PROVIDER_GEMINI,
                    VocalixAiClient.PROVIDER_OPENAI,
                    VocalixAiClient.PROVIDER_CLAUDE,
                    VocalixAiClient.PROVIDER_GROQ
            };
            final String[] providerNames = {
                    "Google Gemini AI (الأسرع والأوفر)",
                    "OpenAI (Whisper & ChatGPT)",
                    "Anthropic Claude (تلخيص فائق الذكاء)",
                    "Groq Cloud (Whisper Large V3)"
            };

            settingsCardProviderPicker.setOnClickListener(v -> {
                String curProv = prefs.getString(KEY_AI_PROVIDER, VocalixAiClient.PROVIDER_GEMINI);
                int curIdx = 0;
                for (int i = 0; i < providers.length; i++) {
                    if (providers[i].equalsIgnoreCase(curProv)) {
                        curIdx = i;
                        break;
                    }
                }
                AlertDialog d = new AlertDialog.Builder(this)
                        .setTitle(R.string.settings_provider_dialog_title)
                        .setSingleChoiceItems(providerNames, curIdx, (dlg, which) -> {
                            String chosen = providers[which];
                            prefs.edit().putString(KEY_AI_PROVIDER, chosen).apply();
                            prefs.edit().putString(KEY_AI_MODEL, VocalixAiClient.MODEL_AUTO).apply();
                            dlg.dismiss();
                            refreshSettingsUI();
                            Toast.makeText(this, R.string.settings_saved_toast, Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton(R.string.btn_cancel, null)
                        .show();
                AmoledThemeHelper.applyToDialog(this, d);
            });
        }

        // Model Selector
        if (settingsCardModelPicker != null) {
            settingsCardModelPicker.setOnClickListener(v -> showModelPickerDialog());
        }

        if (settingsEtKey != null) {
            settingsEtKey.setText(prefs.getString(KEY_API_KEY, ""));
            settingsEtKey.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    prefs.edit().putString(KEY_API_KEY, s.toString().trim()).apply();
                }

                @Override
                public void afterTextChanged(Editable s) {}
            });
        }

        if (settingsBtnPaste != null) {
            settingsBtnPaste.setOnClickListener(v -> {
                try {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null && clipboard.hasPrimaryClip() && clipboard.getPrimaryClip().getItemCount() > 0) {
                        CharSequence clipText = clipboard.getPrimaryClip().getItemAt(0).getText();
                        if (clipText != null && clipText.length() > 0) {
                            String pastedKey = clipText.toString().trim();
                            if (settingsEtKey != null) {
                                settingsEtKey.setText(pastedKey);
                            }
                            prefs.edit().putString(KEY_API_KEY, pastedKey).apply();
                            Toast.makeText(this, R.string.settings_paste_toast, Toast.LENGTH_SHORT).show();
                        }
                    }
                } catch (Exception ignored) {}
            });
        }

        if (settingsBtnGetFree != null) {
            settingsBtnGetFree.setOnClickListener(v -> {
                String provider = prefs.getString(KEY_AI_PROVIDER, VocalixAiClient.PROVIDER_GEMINI);
                String url = "https://aistudio.google.com/app/apikey";
                if (VocalixAiClient.PROVIDER_OPENAI.equalsIgnoreCase(provider)) {
                    url = "https://platform.openai.com/api-keys";
                } else if (VocalixAiClient.PROVIDER_CLAUDE.equalsIgnoreCase(provider)) {
                    url = "https://console.anthropic.com/settings/keys";
                } else if (VocalixAiClient.PROVIDER_GROQ.equalsIgnoreCase(provider)) {
                    url = "https://console.groq.com/keys";
                }
                try {
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(browserIntent);
                } catch (Exception e) {
                    Toast.makeText(this, url, Toast.LENGTH_LONG).show();
                }
            });
        }

        if (settingsBtnTest != null) {
            settingsBtnTest.setOnClickListener(v -> {
                String testKey = settingsEtKey != null ? settingsEtKey.getText().toString().trim() : "";
                if (testKey.isEmpty()) {
                    Toast.makeText(this, "يرجى كتابة أو لصق المفتاح أولاً", Toast.LENGTH_SHORT).show();
                    return;
                }
                String provider = prefs.getString(KEY_AI_PROVIDER, VocalixAiClient.PROVIDER_GEMINI);
                Toast.makeText(this, "جاري فحص المفتاح واستخراج النماذج المتاحة...", Toast.LENGTH_SHORT).show();
                VocalixAiClient.fetchAvailableModels(provider, testKey, (success, message, availableModels) -> {
                    runOnUiThread(() -> {
                        AlertDialog.Builder resDialog = new AlertDialog.Builder(this);
                        if (success) {
                            resDialog.setTitle("✓ نتيجة الفحص: المفتاح سليم ومفعل");
                            StringBuilder sb = new StringBuilder();
                            sb.append(message).append("\n\nالنماذج المتوفرة:\n");
                            if (availableModels != null) {
                                for (String m : availableModels) {
                                    sb.append("• ").append(m).append("\n");
                                }
                            }
                            resDialog.setMessage(sb.toString());
                        } else {
                            resDialog.setTitle("❌ نتيجة الفحص: فشل التحقق من المفتاح");
                            resDialog.setMessage(message);
                        }
                        resDialog.setNegativeButton(R.string.settings_btn_done, null);
                        AlertDialog ad = resDialog.show();
                        AmoledThemeHelper.applyToDialog(this, ad);
                    });
                });
            });
        }

        // Language Picker
        if (settingsCardLanguage != null) {
            settingsCardLanguage.setOnClickListener(v -> {
                String curLang = prefs.getString(KEY_APP_LANGUAGE, "system");
                int curIdx = 0;
                for (int i = 0; i < SUPPORTED_LANG_CODES.length; i++) {
                    if (SUPPORTED_LANG_CODES[i].equals(curLang)) {
                        curIdx = i;
                        break;
                    }
                }
                final int selectedIndex = curIdx;
                AlertDialog d = new AlertDialog.Builder(this)
                        .setTitle(R.string.settings_lang_dialog_title)
                        .setSingleChoiceItems(SUPPORTED_LANG_NAMES, selectedIndex, (dlg, which) -> {
                            String chosen = (which >= 0 && which < SUPPORTED_LANG_CODES.length)
                                    ? SUPPORTED_LANG_CODES[which] : "system";
                            prefs.edit().putString(KEY_APP_LANGUAGE, chosen).apply();
                            dlg.dismiss();
                            Toast.makeText(this, R.string.settings_saved_toast, Toast.LENGTH_SHORT).show();
                            recreateWithTab(TAB_SETTINGS);
                        })
                        .setNegativeButton(R.string.btn_cancel, null)
                        .show();
                AmoledThemeHelper.applyToDialog(this, d);
            });
        }

        // Theme Picker
        if (settingsCardTheme != null) {
            final String[] themeCodes = {THEME_SYSTEM, THEME_LIGHT, THEME_DARK, THEME_AMOLED};
            final String[] themeNames = {
                    getString(R.string.theme_system),
                    getString(R.string.theme_light),
                    getString(R.string.theme_dark),
                    getString(R.string.theme_amoled)
            };

            settingsCardTheme.setOnClickListener(v -> {
                String curTheme = prefs.getString(KEY_APP_THEME, THEME_SYSTEM);
                int curIdx = 0;
                for (int i = 0; i < themeCodes.length; i++) {
                    if (themeCodes[i].equals(curTheme)) {
                        curIdx = i;
                        break;
                    }
                }
                AlertDialog d = new AlertDialog.Builder(this)
                        .setTitle(R.string.settings_theme_dialog_title)
                        .setSingleChoiceItems(themeNames, curIdx, (dlg, which) -> {
                            String chosen = themeCodes[which];
                            prefs.edit().putString(KEY_APP_THEME, chosen).apply();
                            dlg.dismiss();
                            Toast.makeText(this, R.string.settings_saved_toast, Toast.LENGTH_SHORT).show();
                            recreateWithTab(TAB_SETTINGS);
                        })
                        .setNegativeButton(R.string.btn_cancel, null)
                        .show();
                AmoledThemeHelper.applyToDialog(this, d);
            });
        }

        // Font Size Picker
        if (settingsCardTextSize != null) {
            final int[] fontSizes = {13, 15, 18, 21};
            final String[] fontSizeNames = {
                    getString(R.string.text_size_small),
                    getString(R.string.text_size_medium),
                    getString(R.string.text_size_large),
                    getString(R.string.text_size_xlarge)
            };

            settingsCardTextSize.setOnClickListener(v -> {
                int curFont = prefs.getInt(KEY_TEXT_SIZE, 15);
                int curIdx = 1;
                for (int i = 0; i < fontSizes.length; i++) {
                    if (fontSizes[i] == curFont) {
                        curIdx = i;
                        break;
                    }
                }
                AlertDialog d = new AlertDialog.Builder(this)
                        .setTitle(R.string.settings_text_size_dialog_title)
                        .setSingleChoiceItems(fontSizeNames, curIdx, (dlg, which) -> {
                            int chosenSize = fontSizes[which];
                            prefs.edit().putInt(KEY_TEXT_SIZE, chosenSize).apply();
                            if (settingsTvCurrentTextSize != null) {
                                settingsTvCurrentTextSize.setText(fontSizeNames[which]);
                            }
                            if (etResult != null) {
                                etResult.setTextSize(chosenSize);
                            }
                            if (tvSummaryContent != null) {
                                tvSummaryContent.setTextSize(chosenSize);
                            }
                            dlg.dismiss();
                            Toast.makeText(this, R.string.settings_saved_toast, Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton(R.string.btn_cancel, null)
                        .show();
                AmoledThemeHelper.applyToDialog(this, d);
            });
        }

        // Diarization toggle
        if (settingsCbDiarization != null) {
            settingsCbDiarization.setChecked(prefs.getBoolean("split_speakers", true));
            settingsCbDiarization.setOnCheckedChangeListener((btn, isChecked) -> {
                prefs.edit().putBoolean("split_speakers", isChecked).apply();
                if (cbDiarizationMain != null) {
                    cbDiarizationMain.setChecked(isChecked);
                }
            });
        }
        if (settingsRowDiarization != null) {
            settingsRowDiarization.setOnClickListener(v -> {
                if (settingsCbDiarization != null) {
                    settingsCbDiarization.setChecked(!settingsCbDiarization.isChecked());
                }
            });
        }

        // History toggle
        if (settingsCbHistory != null) {
            settingsCbHistory.setCheckedSilently(HistoryManager.isHistoryEnabled(this));
            settingsCbHistory.setOnCheckedChangeListener((btn, isChecked) -> {
                if (!isChecked) {
                    showDisableHistoryConfirmDialog();
                } else {
                    HistoryManager.setHistoryEnabled(this, true);
                    loadHistoryTab();
                }
            });
        }
        if (settingsRowHistory != null) {
            settingsRowHistory.setOnClickListener(v -> {
                if (settingsCbHistory != null) {
                    if (settingsCbHistory.isChecked()) {
                        showDisableHistoryConfirmDialog();
                    } else {
                        settingsCbHistory.setChecked(true);
                    }
                }
            });
        }

        // Biometric toggle
        settingsRowBiometric = findViewById(R.id.settings_row_biometric);
        settingsCbBiometric = findViewById(R.id.settings_cb_biometric);
        if (settingsCbBiometric != null) {
            settingsCbBiometric.setCheckedSilently(prefs.getBoolean(KEY_BIOMETRIC_LOCK, false));
            settingsCbBiometric.setOnCheckedChangeListener((btn, isChecked) -> {
                if (isChecked) {
                    prefs.edit().putBoolean(KEY_BIOMETRIC_LOCK, true).apply();
                    Toast.makeText(this, R.string.biometric_enabled_toast, Toast.LENGTH_SHORT).show();
                } else {
                    settingsCbBiometric.setCheckedSilently(true);
                    requestBiometricAuth(
                            AUTH_ACTION_DISABLE_LOCK,
                            getString(R.string.biometric_disable_prompt_title),
                            getString(R.string.biometric_disable_prompt_desc));
                }
            });
        }
        if (settingsRowBiometric != null) {
            settingsRowBiometric.setOnClickListener(v -> {
                if (settingsCbBiometric != null) {
                    boolean currentlyChecked = settingsCbBiometric.isChecked();
                    if (!currentlyChecked) {
                        settingsCbBiometric.setChecked(true);
                    } else {
                        requestBiometricAuth(
                                AUTH_ACTION_DISABLE_LOCK,
                                getString(R.string.biometric_disable_prompt_title),
                                getString(R.string.biometric_disable_prompt_desc));
                    }
                }
            });
        }

        // Background Processing & Notifications toggle
        settingsRowBgNotif = findViewById(R.id.settings_row_bg_notif);
        settingsCbBgNotif = findViewById(R.id.settings_cb_bg_notif);
        if (settingsCbBgNotif != null) {
            settingsCbBgNotif.setCheckedSilently(NotificationHelper.isBgProcessingEnabled(this));
            settingsCbBgNotif.setOnCheckedChangeListener((btn, isChecked) -> {
                NotificationHelper.setBgProcessingEnabled(this, isChecked);
                if (isChecked) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF_PERMISSION);
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        try {
                            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                                Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                                intent.setData(Uri.parse("package:" + getPackageName()));
                                startActivity(intent);
                            }
                        } catch (Exception ignored) {}
                    }
                }
            });
        }
        if (settingsRowBgNotif != null) {
            settingsRowBgNotif.setOnClickListener(v -> {
                if (settingsCbBgNotif != null) {
                    settingsCbBgNotif.toggle();
                }
            });
        }

        // Backup & Restore
        settingsRowBackup = findViewById(R.id.settings_row_backup);
        settingsRowRestore = findViewById(R.id.settings_row_restore);
        if (settingsRowBackup != null) {
            settingsRowBackup.setOnClickListener(v -> {
                boolean isBioLocked = prefs.getBoolean(KEY_BIOMETRIC_LOCK, false);
                if (isBioLocked) {
                    requestBiometricAuth(
                            AUTH_ACTION_BACKUP,
                            getString(R.string.biometric_backup_prompt_title),
                            getString(R.string.biometric_backup_prompt_desc));
                } else {
                    HistoryBackupHelper.exportBackup(this);
                }
            });
        }
        if (settingsRowRestore != null) {
            settingsRowRestore.setOnClickListener(v -> openRestorePicker());
        }

        View settingsCardCoffee = findViewById(R.id.settings_card_coffee);
        if (settingsCardCoffee != null) {
            settingsCardCoffee.setOnClickListener(v -> {
                try {
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://buymeacoffee.com/hxh3mk"));
                    startActivity(browserIntent);
                } catch (Exception e) {
                    Toast.makeText(this, "Could not open browser", Toast.LENGTH_SHORT).show();
                }
            });
        }

        refreshSettingsUI();
    }

    private void openRestorePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        String[] mimeTypes = {"application/json", "text/plain", "application/octet-stream"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(intent, getString(R.string.backup_restore_pick_title)), REQ_RESTORE_FILE);
    }

    private void showDisableHistoryConfirmDialog() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.history_disable_warn_title)
                .setMessage(R.string.history_disable_warn_msg)
                .setPositiveButton(R.string.history_disable_warn_confirm, (d, which) -> {
                    HistoryManager.clearAllHistory(this);
                    HistoryManager.setHistoryEnabled(this, false);
                    if (settingsCbHistory != null) {
                        settingsCbHistory.setCheckedSilently(false);
                    }
                    loadHistoryTab();
                    Toast.makeText(this, R.string.history_cleared_toast, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.btn_cancel, (d, which) -> {
                    if (settingsCbHistory != null) {
                        settingsCbHistory.setCheckedSilently(true);
                    }
                })
                .setOnCancelListener(d -> {
                    if (settingsCbHistory != null) {
                        settingsCbHistory.setCheckedSilently(true);
                    }
                })
                .show();

        Button posBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (posBtn != null) {
            posBtn.setTextColor(Color.parseColor("#BA1A1A"));
        }
        TextView msgView = dialog.findViewById(android.R.id.message);
        if (msgView != null) {
            msgView.setTextColor(Color.parseColor("#BA1A1A"));
            msgView.setLineSpacing(0, 1.25f);
        }
        AmoledThemeHelper.applyToDialog(this, dialog);
    }

    private void showModelPickerDialog() {
        String provider = prefs.getString(KEY_AI_PROVIDER, VocalixAiClient.PROVIDER_GEMINI);
        String currentModel = prefs.getString(KEY_AI_MODEL, "gemini-2.0-flash");
        String apiKey = prefs.getString(KEY_API_KEY, "");

        List<String> defaultList = VocalixAiClient.GEMINI_MODELS;
        if (VocalixAiClient.PROVIDER_OPENAI.equalsIgnoreCase(provider)) {
            defaultList = VocalixAiClient.OPENAI_MODELS;
        } else if (VocalixAiClient.PROVIDER_CLAUDE.equalsIgnoreCase(provider)) {
            defaultList = VocalixAiClient.CLAUDE_MODELS;
        } else if (VocalixAiClient.PROVIDER_GROQ.equalsIgnoreCase(provider)) {
            defaultList = VocalixAiClient.GROQ_MODELS;
        }

        final List<String> modelsToShow = new ArrayList<>(defaultList);

        if (!apiKey.isEmpty()) {
            Toast.makeText(this, "جاري جلب النماذج المدعومة من حسابك...", Toast.LENGTH_SHORT).show();
            VocalixAiClient.fetchAvailableModels(provider, apiKey, (success, msg, liveModels) -> {
                runOnUiThread(() -> {
                    if (success && liveModels != null && !liveModels.isEmpty()) {
                        modelsToShow.clear();
                        modelsToShow.addAll(liveModels);
                    }
                    displayModelListDialog(modelsToShow, currentModel);
                });
            });
        } else {
            displayModelListDialog(modelsToShow, currentModel);
        }
    }

    private void displayModelListDialog(List<String> models, String currentModel) {
        List<String> options = new ArrayList<>();
        options.add(VocalixAiClient.MODEL_AUTO);
        for (String m : models) {
            if (!options.contains(m)) options.add(m);
        }

        String[] displayNames = new String[options.size()];
        int selectedIndex = 0;
        for (int i = 0; i < options.size(); i++) {
            String opt = options.get(i);
            if (VocalixAiClient.MODEL_AUTO.equals(opt)) {
                displayNames[i] = getString(R.string.settings_model_auto);
            } else {
                displayNames[i] = opt;
            }
            if (opt.equalsIgnoreCase(currentModel)) {
                selectedIndex = i;
            }
        }

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(R.string.settings_model_dialog_title)
                .setSingleChoiceItems(displayNames, selectedIndex, (dlg, which) -> {
                    String chosenModel = options.get(which);
                    prefs.edit().putString(KEY_AI_MODEL, chosenModel).apply();
                    dlg.dismiss();
                    refreshSettingsUI();
                    Toast.makeText(this, R.string.settings_saved_toast, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
        AmoledThemeHelper.applyToDialog(this, d);
    }

    private void refreshSettingsUI() {
        String curProvider = prefs.getString(KEY_AI_PROVIDER, VocalixAiClient.PROVIDER_GEMINI);
        String curModel = prefs.getString(KEY_AI_MODEL, VocalixAiClient.MODEL_AUTO);

        if (settingsTvCurrentProvider != null) {
            if (VocalixAiClient.PROVIDER_OPENAI.equalsIgnoreCase(curProvider)) {
                settingsTvCurrentProvider.setText("OpenAI (Whisper & ChatGPT)");
                if (settingsTvProviderDesc != null) settingsTvProviderDesc.setText("دقة عالية في التفريغ وتلخيص عبر ChatGPT");
                if (settingsEtKey != null) settingsEtKey.setHint("sk-proj-...");
            } else if (VocalixAiClient.PROVIDER_CLAUDE.equalsIgnoreCase(curProvider)) {
                settingsTvCurrentProvider.setText("Anthropic Claude");
                if (settingsTvProviderDesc != null) settingsTvProviderDesc.setText("أقوى نماذج التفكير والتلخيص الذكي للأحاديث");
                if (settingsEtKey != null) settingsEtKey.setHint("sk-ant-api...");
            } else if (VocalixAiClient.PROVIDER_GROQ.equalsIgnoreCase(curProvider)) {
                settingsTvCurrentProvider.setText("Groq Cloud (Whisper)");
                if (settingsTvProviderDesc != null) settingsTvProviderDesc.setText("سرعة تفريغ خارقة وفورية عبر خوادم LPU");
                if (settingsEtKey != null) settingsEtKey.setHint("gsk_...");
            } else {
                settingsTvCurrentProvider.setText("Google Gemini AI");
                if (settingsTvProviderDesc != null) settingsTvProviderDesc.setText("1,500 طلب تفريغ وتلخيص يومياً مجاناً");
                if (settingsEtKey != null) settingsEtKey.setHint("AIzaSy...");
            }
        }

        if (settingsTvCurrentModel != null) {
            if (VocalixAiClient.MODEL_AUTO.equalsIgnoreCase(curModel)) {
                settingsTvCurrentModel.setText(R.string.settings_model_auto_short);
            } else {
                settingsTvCurrentModel.setText(curModel);
            }
        }

        if (settingsTvCurrentLang != null) {
            String curLang = prefs.getString(KEY_APP_LANGUAGE, "system");
            int curIdx = 0;
            for (int i = 0; i < SUPPORTED_LANG_CODES.length; i++) {
                if (SUPPORTED_LANG_CODES[i].equals(curLang)) {
                    curIdx = i;
                    break;
                }
            }
            settingsTvCurrentLang.setText(SUPPORTED_LANG_NAMES[curIdx]);
        }

        if (settingsTvCurrentTheme != null) {
            String curTheme = prefs.getString(KEY_APP_THEME, THEME_SYSTEM);
            if (THEME_LIGHT.equals(curTheme)) {
                settingsTvCurrentTheme.setText(R.string.theme_light);
            } else if (THEME_DARK.equals(curTheme)) {
                settingsTvCurrentTheme.setText(R.string.theme_dark);
            } else if (THEME_AMOLED.equals(curTheme)) {
                settingsTvCurrentTheme.setText(R.string.theme_amoled);
            } else {
                settingsTvCurrentTheme.setText(R.string.theme_system);
            }
        }

        if (settingsTvCurrentTextSize != null) {
            int curFont = prefs.getInt(KEY_TEXT_SIZE, 15);
            if (curFont == 13) settingsTvCurrentTextSize.setText(R.string.text_size_small);
            else if (curFont == 18) settingsTvCurrentTextSize.setText(R.string.text_size_large);
            else if (curFont == 21) settingsTvCurrentTextSize.setText(R.string.text_size_xlarge);
            else settingsTvCurrentTextSize.setText(R.string.text_size_medium);
        }

        if (settingsCbDiarization != null) {
            settingsCbDiarization.setChecked(prefs.getBoolean("split_speakers", true));
        }
        if (settingsCbHistory != null) {
            settingsCbHistory.setChecked(HistoryManager.isHistoryEnabled(this));
        }
        if (settingsCbBiometric != null) {
            settingsCbBiometric.setCheckedSilently(prefs.getBoolean(KEY_BIOMETRIC_LOCK, false));
        }
        if (settingsCbBgNotif != null) {
            settingsCbBgNotif.setCheckedSilently(NotificationHelper.isBgProcessingEnabled(this));
        }
    }

    private void recreateWithTab(int tab) {
        Intent intent = getIntent();
        intent.putExtra(EXTRA_INITIAL_TAB, tab);
        finish();
        overridePendingTransition(0, 0);
        startActivity(intent);
        overridePendingTransition(0, 0);
    }

    @Override
    public void onBackPressed() {
        if (currentTab != TAB_HOME) {
            switchTab(TAB_HOME);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("current_tab", currentTab);
    }

    @Override
    protected void onResume() {
        super.onResume();
        int textSizeSp = prefs.getInt(KEY_TEXT_SIZE, 15);
        if (etResult != null) {
            etResult.setTextSize(textSizeSp);
        }
        if (tvSummaryContent != null) {
            tvSummaryContent.setTextSize(textSizeSp);
        }
        if (currentTab == TAB_HISTORY) {
            loadHistoryTab();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.hasExtra(EXTRA_INITIAL_TAB)) {
            int targetTab = intent.getIntExtra(EXTRA_INITIAL_TAB, TAB_HOME);
            switchTab(targetTab);
        }
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri audioUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (audioUri == null && intent.getData() != null) {
                audioUri = intent.getData();
            }
            if (audioUri != null) {
                currentMimeType = intent.getType() != null ? intent.getType() : "audio/ogg";
                processAudioUri(audioUri);
            }
        } else if (Intent.ACTION_VIEW.equals(action)) {
            Uri audioUri = intent.getData();
            if (audioUri != null) {
                currentMimeType = intent.getType() != null ? intent.getType() : "audio/wav";
                processAudioUri(audioUri);
            }
        }
    }

    private void openAudioPicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("audio/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(intent, getString(R.string.btn_pick_file)), REQ_PICK_AUDIO);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_AUDIO && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                currentMimeType = getContentResolver().getType(uri);
                if (currentMimeType == null) currentMimeType = "audio/*";
                processAudioUri(uri);
            }
        } else if (requestCode == REQ_BIOMETRIC_AUTH) {
            if (resultCode == RESULT_OK) {
                int action = pendingAuthAction;
                pendingAuthAction = 0;
                onBiometricAuthSuccess(action);
            } else {
                pendingAuthAction = 0;
                Toast.makeText(this, R.string.biometric_auth_failed, Toast.LENGTH_SHORT).show();
                updateBottomNavUI(currentTab);
            }
        } else if (requestCode == REQ_RESTORE_FILE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                try {
                    int count = HistoryBackupHelper.restoreFromUri(this, uri);
                    Toast.makeText(this, getString(R.string.restore_success_toast, count), Toast.LENGTH_SHORT).show();
                    loadHistoryTab();
                } catch (Exception e) {
                    Toast.makeText(this, R.string.backup_restore_error, Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLiveRecording();
            } else {
                Toast.makeText(this, R.string.record_perm_denied, Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQ_NOTIF_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, R.string.settings_saved_toast, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, R.string.bg_notif_perm_required, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void processAudioUri(Uri audioUri) {
        tvStatus.setText(R.string.status_decoding);
        progressBar.setVisibility(View.VISIBLE);
        layoutAudioMeta.setVisibility(View.GONE);
        etResult.setText("");
        updateWordCount("");

        File cacheDir = getCacheDir();
        currentRawAudioFile = new File(cacheDir, "transcribe_raw.audio");
        if (currentRawAudioFile.exists()) currentRawAudioFile.delete();

        new Thread(() -> {
            try {
                // Copy original audio file directly (preserves 100% audio quality without transcoding)
                AudioDecoder.copyUriToFile(this, audioUri, currentRawAudioFile);

                runOnUiThread(() -> {
                    String mime = getContentResolver().getType(audioUri);
                    if (mime != null) currentMimeType = mime;
                    long sizeKb = currentRawAudioFile.length() / 1024;
                    tvAudioInfo.setText(getString(R.string.file_size_info, sizeKb));
                    layoutAudioMeta.setVisibility(View.VISIBLE);
                    startTranscription();
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    btnRetry.setVisibility(View.VISIBLE);
                    tvStatus.setText("تعذر قراءة الملف الصوتي: " + e.getMessage());
                });
            }
        }).start();
    }

    private void startTranscription() {
        String apiKey = prefs.getString(KEY_API_KEY, "");
        if (apiKey.isEmpty()) {
            progressBar.setVisibility(View.GONE);
            tvStatus.setText("يرجى إدخال مفتاح API في صفحة الإعدادات للمتابعة.");
            switchTab(TAB_SETTINGS);
            return;
        }

        tvStatus.setText(R.string.status_transcribing);
        progressBar.setVisibility(View.VISIBLE);
        btnRetry.setVisibility(View.GONE);

        if (currentRawAudioFile == null || !currentRawAudioFile.exists() || currentRawAudioFile.length() == 0) {
            progressBar.setVisibility(View.GONE);
            tvStatus.setText("الملف الصوتي غير متوفر.");
            return;
        }

        etResult.setText("");
        if (layoutSummaryContainer != null) {
            layoutSummaryContainer.setVisibility(View.GONE);
            if (tvSummaryContent != null) tvSummaryContent.setText("");
        }

        NotificationHelper.showTranscribingNotification(this);

        String provider = prefs.getString(KEY_AI_PROVIDER, VocalixAiClient.PROVIDER_GEMINI);
        String model = prefs.getString(KEY_AI_MODEL, "gemini-2.0-flash");
        boolean splitSpeakers = prefs.getBoolean("split_speakers", true);

        VocalixAiClient.transcribe(currentRawAudioFile, currentMimeType, provider, model, apiKey, splitSpeakers, new VocalixAiClient.TranscribeCallback() {
            @Override
            public void onPartialChunk(String partialText) {
                runOnUiThread(() -> {
                    if (etResult != null) {
                        etResult.setText(partialText);
                        etResult.setSelection(partialText.length());
                    }
                    tvStatus.setText(R.string.status_transcribing);
                });
            }

            @Override
            public void onSuccess(String text) {
                recordUsageHit();
                NotificationHelper.dismissTranscribingNotification(MainActivity.this, true);
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    btnRetry.setVisibility(View.GONE);
                    etResult.setText(text);
                    tvStatus.setText(R.string.status_success);
                    Toast.makeText(MainActivity.this, R.string.status_success, Toast.LENGTH_SHORT).show();

                    // Generate summary automatically
                    generateSummary(text, apiKey);

                    // Save to history (if enabled) without saving original audio
                    HistoryManager.saveTranscript(MainActivity.this, text, null);
                });
            }

            @Override
            public void onError(String error) {
                NotificationHelper.dismissTranscribingNotification(MainActivity.this, false);
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    btnRetry.setVisibility(View.VISIBLE);
                    tvStatus.setText(getString(R.string.status_error) + error);
                    Toast.makeText(MainActivity.this, error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void generateSummary(String text, String apiKey) {
        if (text == null || text.trim().length() < 30) {
            // Text is too short for a meaningful summary
            if (layoutSummaryContainer != null) {
                layoutSummaryContainer.setVisibility(View.GONE);
            }
            return;
        }

        if (layoutSummaryContainer != null) {
            layoutSummaryContainer.setVisibility(View.VISIBLE);
        }
        if (pbSummary != null) {
            pbSummary.setVisibility(View.VISIBLE);
        }
        if (tvSummaryContent != null) {
            tvSummaryContent.setText(R.string.generating_summary);
        }

        String provider = prefs.getString(KEY_AI_PROVIDER, VocalixAiClient.PROVIDER_GEMINI);
        String model = prefs.getString(KEY_AI_MODEL, "gemini-2.0-flash");

        VocalixAiClient.summarizeText(text, provider, model, apiKey, new VocalixAiClient.SummarizeCallback() {
            @Override
            public void onSuccess(String summary) {
                recordUsageHit();
                runOnUiThread(() -> {
                    if (pbSummary != null) pbSummary.setVisibility(View.GONE);
                    if (tvSummaryContent != null) tvSummaryContent.setText(summary);

                    // Update history entry with the generated summary
                    HistoryManager.updateSummary(MainActivity.this, text, summary);
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    if (pbSummary != null) pbSummary.setVisibility(View.GONE);
                    if (tvSummaryContent != null) {
                        tvSummaryContent.setText("تعذر توليد الملخص: " + error);
                    }
                });
            }
        });
    }

    // ==========================================
    // Live Voice Recorder & Mini Player Controls
    // ==========================================

    private void checkPermissionAndStartRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
                return;
            }
        }
        startLiveRecording();
    }

    private void startLiveRecording() {
        releaseMediaPlayer();
        try {
            File cacheDir = getCacheDir();
            recordedAudioFile = new File(cacheDir, "live_recording_" + System.currentTimeMillis() + ".m4a");
            if (recordedAudioFile.exists()) {
                recordedAudioFile.delete();
            }

            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setAudioEncodingBitRate(128000);
            mediaRecorder.setAudioSamplingRate(44100);
            mediaRecorder.setOutputFile(recordedAudioFile.getAbsolutePath());
            mediaRecorder.prepare();
            mediaRecorder.start();

            isRecording = true;
            recordingStartTime = SystemClock.elapsedRealtime();

            if (cardLiveRecorder != null) cardLiveRecorder.setVisibility(View.VISIBLE);
            if (layoutRecordingActive != null) layoutRecordingActive.setVisibility(View.VISIBLE);
            if (layoutRecordingPlayer != null) layoutRecordingPlayer.setVisibility(View.GONE);
            if (tvRecordingTimer != null) tvRecordingTimer.setText("00:00");

            recordTimerRunnable = new Runnable() {
                @Override
                public void run() {
                    if (isRecording) {
                        long elapsedMillis = SystemClock.elapsedRealtime() - recordingStartTime;
                        long seconds = (elapsedMillis / 1000) % 60;
                        long minutes = (elapsedMillis / (1000 * 60));
                        if (tvRecordingTimer != null) {
                            tvRecordingTimer.setText(String.format(Locale.US, "%02d:%02d", minutes, seconds));
                        }
                        recordTimerHandler.postDelayed(this, 500);
                    }
                }
            };
            recordTimerHandler.post(recordTimerRunnable);

        } catch (Exception e) {
            isRecording = false;
            if (mediaRecorder != null) {
                try { mediaRecorder.release(); } catch (Exception ignored) {}
                mediaRecorder = null;
            }
            Toast.makeText(this, "طھط¹ط°ط± ط¨ط¯ط، ط§ظ„طھط³ط¬ظٹظ„: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            if (cardLiveRecorder != null) cardLiveRecorder.setVisibility(View.GONE);
        }
    }

    private void stopLiveRecording() {
        if (!isRecording) return;
        isRecording = false;
        recordTimerHandler.removeCallbacks(recordTimerRunnable);

        if (mediaRecorder != null) {
            try {
                mediaRecorder.stop();
            } catch (Exception ignored) {}
            try {
                mediaRecorder.release();
            } catch (Exception ignored) {}
            mediaRecorder = null;
        }

        if (recordedAudioFile != null && recordedAudioFile.exists() && recordedAudioFile.length() > 0) {
            if (layoutRecordingActive != null) layoutRecordingActive.setVisibility(View.GONE);
            if (layoutRecordingPlayer != null) layoutRecordingPlayer.setVisibility(View.VISIBLE);
            setupMiniPlayer();
        } else {
            Toast.makeText(this, "ظ„ظ… ظٹطھظ… طھط³ط¬ظٹظ„ ط£ظٹ طµظˆطھ", Toast.LENGTH_SHORT).show();
            if (cardLiveRecorder != null) cardLiveRecorder.setVisibility(View.GONE);
        }
    }

    private void cancelLiveRecording() {
        isRecording = false;
        recordTimerHandler.removeCallbacks(recordTimerRunnable);
        if (mediaRecorder != null) {
            try { mediaRecorder.stop(); } catch (Exception ignored) {}
            try { mediaRecorder.release(); } catch (Exception ignored) {}
            mediaRecorder = null;
        }
        if (recordedAudioFile != null && recordedAudioFile.exists()) {
            recordedAudioFile.delete();
            recordedAudioFile = null;
        }
        if (cardLiveRecorder != null) cardLiveRecorder.setVisibility(View.GONE);
    }

    private void discardLiveRecording() {
        releaseMediaPlayer();
        if (recordedAudioFile != null && recordedAudioFile.exists()) {
            recordedAudioFile.delete();
            recordedAudioFile = null;
        }
        if (cardLiveRecorder != null) cardLiveRecorder.setVisibility(View.GONE);
    }

    private void transcribeLiveRecording() {
        releaseMediaPlayer();
        if (cardLiveRecorder != null) cardLiveRecorder.setVisibility(View.GONE);
        if (recordedAudioFile == null || !recordedAudioFile.exists()) return;

        currentRawAudioFile = recordedAudioFile;
        currentMimeType = "audio/mp4";

        long sizeKb = currentRawAudioFile.length() / 1024;
        tvAudioInfo.setText(getString(R.string.file_size_info, sizeKb) + " (" + getString(R.string.recording_live_tag) + ")");
        layoutAudioMeta.setVisibility(View.VISIBLE);
        startTranscription();
    }

    private void setupMiniPlayer() {
        releaseMediaPlayer();
        if (recordedAudioFile == null || !recordedAudioFile.exists()) return;

        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(recordedAudioFile.getAbsolutePath());
            mediaPlayer.prepare();

            final int duration = mediaPlayer.getDuration();
            if (sbPlayerProgress != null) {
                sbPlayerProgress.setMax(duration);
                sbPlayerProgress.setProgress(0);
                sbPlayerProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        if (fromUser && mediaPlayer != null) {
                            mediaPlayer.seekTo(progress);
                            updatePlayerTimeUI(progress, duration);
                        }
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {}

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {}
                });
            }

            updatePlayerTimeUI(0, duration);
            if (btnPlayerPlayPause != null) {
                btnPlayerPlayPause.setImageResource(R.drawable.ic_play);
            }

            mediaPlayer.setOnCompletionListener(mp -> {
                if (btnPlayerPlayPause != null) {
                    btnPlayerPlayPause.setImageResource(R.drawable.ic_play);
                }
                if (sbPlayerProgress != null) {
                    sbPlayerProgress.setProgress(0);
                }
                updatePlayerTimeUI(0, duration);
                playerHandler.removeCallbacks(playerRunnable);
            });

            playerRunnable = new Runnable() {
                @Override
                public void run() {
                    if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                        int pos = mediaPlayer.getCurrentPosition();
                        if (sbPlayerProgress != null) {
                            sbPlayerProgress.setProgress(pos);
                        }
                        updatePlayerTimeUI(pos, duration);
                        playerHandler.postDelayed(this, 200);
                    }
                }
            };

        } catch (Exception e) {
            Toast.makeText(this, "طھط¹ط°ط± طھط¬ظ‡ظٹط² ظ…ط´ط؛ظ„ ط§ظ„طµظˆطھ: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void togglePlayer() {
        if (mediaPlayer == null) return;
        try {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
                if (btnPlayerPlayPause != null) {
                    btnPlayerPlayPause.setImageResource(R.drawable.ic_play);
                }
                playerHandler.removeCallbacks(playerRunnable);
            } else {
                mediaPlayer.start();
                if (btnPlayerPlayPause != null) {
                    btnPlayerPlayPause.setImageResource(R.drawable.ic_pause);
                }
                playerHandler.post(playerRunnable);
            }
        } catch (Exception ignored) {}
    }

    private void updatePlayerTimeUI(int currentMs, int totalMs) {
        if (tvPlayerTime == null) return;
        long curSec = (currentMs / 1000) % 60;
        long curMin = (currentMs / (1000 * 60));
        long totSec = (totalMs / 1000) % 60;
        long totMin = (totalMs / (1000 * 60));
        tvPlayerTime.setText(String.format(Locale.US, "%02d:%02d / %02d:%02d", curMin, curSec, totMin, totSec));
    }

    private void releaseMediaPlayer() {
        playerHandler.removeCallbacks(playerRunnable);
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                mediaPlayer.release();
            } catch (Exception ignored) {}
            mediaPlayer = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        NotificationHelper.dismissTranscribingNotification(this, false);
        if (isRecording) {
            cancelLiveRecording();
        }
        releaseMediaPlayer();
    }
}

