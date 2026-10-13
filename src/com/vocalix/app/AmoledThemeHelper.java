package com.vocalix.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

/**
 * Pure Black AMOLED Engine for Vocalix.
 * Ensures true 100% #000000 pixel-off black across all surfaces, cards,
 * navigation bars, input areas, headers, and dialogs.
 */
public class AmoledThemeHelper {
    public static final int PURE_BLACK = 0xFF000000;
    public static final int BORDER_COLOR = 0xFF222427;
    public static final int DIVIDER_COLOR = 0xFF1C1E22;
    public static final int TEXT_WHITE = 0xFFFFFFFF;

    public static boolean isAmoled(Context context) {
        if (context == null) return false;
        SharedPreferences sp = context.getSharedPreferences("vocalix_settings", Context.MODE_PRIVATE);
        return MainActivity.THEME_AMOLED.equals(sp.getString(MainActivity.KEY_APP_THEME, MainActivity.THEME_SYSTEM));
    }

    public static int dpToPx(Context context, float dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    public static GradientDrawable createCardDrawable(Context context, float radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setColor(PURE_BLACK);
        gd.setCornerRadius(dpToPx(context, radiusDp));
        gd.setStroke(dpToPx(context, 1), BORDER_COLOR);
        return gd;
    }

    public static RippleDrawable createInteractiveCardDrawable(Context context, float radiusDp) {
        GradientDrawable content = createCardDrawable(context, radiusDp);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.RECTANGLE);
        mask.setCornerRadius(dpToPx(context, radiusDp));
        mask.setColor(Color.WHITE);
        return new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), content, mask);
    }

    public static GradientDrawable createInputDrawable(Context context, float radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setColor(PURE_BLACK);
        gd.setCornerRadius(dpToPx(context, radiusDp));
        gd.setStroke(dpToPx(context, 1), BORDER_COLOR);
        return gd;
    }

    public static Drawable createBottomNavDrawable(Context context) {
        GradientDrawable topDivider = new GradientDrawable();
        topDivider.setShape(GradientDrawable.RECTANGLE);
        topDivider.setColor(DIVIDER_COLOR);

        GradientDrawable blackBg = new GradientDrawable();
        blackBg.setShape(GradientDrawable.RECTANGLE);
        blackBg.setColor(PURE_BLACK);

        android.graphics.drawable.LayerDrawable layer = new android.graphics.drawable.LayerDrawable(
                new Drawable[]{topDivider, blackBg}
        );
        int topStrokePx = dpToPx(context, 1);
        layer.setLayerInset(1, 0, topStrokePx, 0, 0);
        return layer;
    }

    public static void applyToMainActivity(MainActivity activity) {
        if (!isAmoled(activity)) return;

        // 1. Window & System Bars (Status bar & Navigation bar)
        if (activity.getWindow() != null) {
            activity.getWindow().setBackgroundDrawable(new ColorDrawable(PURE_BLACK));
            activity.getWindow().setStatusBarColor(PURE_BLACK);
            activity.getWindow().setNavigationBarColor(PURE_BLACK);
        }

        // 2. Main screen root layout & Tab containers
        View root = activity.findViewById(R.id.mainRootLayout);
        if (root != null) root.setBackgroundColor(PURE_BLACK);

        View tabHome = activity.findViewById(R.id.tabHomeContainer);
        if (tabHome != null) tabHome.setBackgroundColor(PURE_BLACK);

        View tabHistory = activity.findViewById(R.id.tabHistoryContainer);
        if (tabHistory != null) tabHistory.setBackgroundColor(PURE_BLACK);

        View tabSettings = activity.findViewById(R.id.tabSettingsContainer);
        if (tabSettings != null) tabSettings.setBackgroundColor(PURE_BLACK);

        // 3. Material 3 Bottom Navigation Bar
        View bottomNav = activity.findViewById(R.id.bottomNavBar);
        if (bottomNav != null) {
            bottomNav.setBackground(createBottomNavDrawable(activity));
            bottomNav.setElevation(0f);
        }

        // 4. Headers (History & Settings)
        View headerHistory = activity.findViewById(R.id.headerHistory);
        if (headerHistory != null) headerHistory.setBackgroundColor(PURE_BLACK);

        View layoutSearchBar = activity.findViewById(R.id.layoutHistorySearchBar);
        if (layoutSearchBar != null) layoutSearchBar.setBackground(createInputDrawable(activity, 24));
        View etHistorySearch = activity.findViewById(R.id.etHistorySearch);
        if (etHistorySearch instanceof EditText) ((EditText) etHistorySearch).setTextColor(TEXT_WHITE);

        View headerSettings = activity.findViewById(R.id.headerSettings);
        if (headerSettings != null) headerSettings.setBackgroundColor(PURE_BLACK);

        // 5. Home Tab Cards & Inputs
        View cardDiarization = activity.findViewById(R.id.cardDiarization);
        if (cardDiarization != null) cardDiarization.setBackground(createCardDrawable(activity, 20));

        View cardStatus = activity.findViewById(R.id.cardStatus);
        if (cardStatus != null) cardStatus.setBackground(createCardDrawable(activity, 24));

        View etResult = activity.findViewById(R.id.etResult);
        if (etResult != null) {
            etResult.setBackground(createInputDrawable(activity, 14));
            if (etResult instanceof EditText) {
                ((EditText) etResult).setTextColor(TEXT_WHITE);
            }
        }

        View layoutSummary = activity.findViewById(R.id.layoutSummaryContainer);
        if (layoutSummary != null) layoutSummary.setBackground(createCardDrawable(activity, 16));

        View cardQuota = activity.findViewById(R.id.cardQuotaNotice);
        if (cardQuota != null) cardQuota.setBackground(createCardDrawable(activity, 16));

        // 6. Settings Tab Cards
        View cardKey = activity.findViewById(R.id.settings_card_key);
        if (cardKey != null) cardKey.setBackground(createCardDrawable(activity, 24));

        View cardProviderPicker = activity.findViewById(R.id.settings_card_provider_picker);
        if (cardProviderPicker != null) cardProviderPicker.setBackground(createInputDrawable(activity, 12));

        View cardModelPicker = activity.findViewById(R.id.settings_card_model_picker);
        if (cardModelPicker != null) cardModelPicker.setBackground(createInputDrawable(activity, 12));

        View boxKeyInput = activity.findViewById(R.id.settings_box_key_input);
        if (boxKeyInput != null) boxKeyInput.setBackground(createInputDrawable(activity, 12));

        View cardLang = activity.findViewById(R.id.settings_card_language);
        if (cardLang != null) cardLang.setBackground(createInteractiveCardDrawable(activity, 16));

        View cardTheme = activity.findViewById(R.id.settings_card_theme);
        if (cardTheme != null) cardTheme.setBackground(createInteractiveCardDrawable(activity, 16));

        View cardTextSize = activity.findViewById(R.id.settings_card_text_size);
        if (cardTextSize != null) cardTextSize.setBackground(createInteractiveCardDrawable(activity, 16));

        View rowDiarization = activity.findViewById(R.id.settings_row_diarization);
        if (rowDiarization != null) rowDiarization.setBackground(createInteractiveCardDrawable(activity, 16));

        View rowHistory = activity.findViewById(R.id.settings_row_history);
        if (rowHistory != null) rowHistory.setBackground(createInteractiveCardDrawable(activity, 16));

        View cardRecorder = activity.findViewById(R.id.cardLiveRecorder);
        if (cardRecorder != null) cardRecorder.setBackground(createCardDrawable(activity, 16));

        View rowBiometric = activity.findViewById(R.id.settings_row_biometric);
        if (rowBiometric != null) rowBiometric.setBackground(createInteractiveCardDrawable(activity, 16));

        View rowBgNotif = activity.findViewById(R.id.settings_row_bg_notif);
        if (rowBgNotif != null) rowBgNotif.setBackground(createInteractiveCardDrawable(activity, 16));

        View cardBackup = activity.findViewById(R.id.settings_card_backup_restore);
        if (cardBackup != null) cardBackup.setBackground(createCardDrawable(activity, 16));

        View btnExport = activity.findViewById(R.id.btnExportResult);
        if (btnExport != null) btnExport.setBackground(createCardDrawable(activity, 12));
    }

    public static void applyToHistoryDetail(Activity activity) {
        if (!isAmoled(activity)) return;

        if (activity.getWindow() != null) {
            activity.getWindow().setBackgroundDrawable(new ColorDrawable(PURE_BLACK));
            activity.getWindow().setStatusBarColor(PURE_BLACK);
            activity.getWindow().setNavigationBarColor(PURE_BLACK);
        }

        View rootContainer = activity.findViewById(R.id.detailMainContainer);
        if (rootContainer != null) rootContainer.setBackgroundColor(PURE_BLACK);

        View header = activity.findViewById(R.id.headerHistoryDetail);
        if (header != null) {
            header.setBackgroundColor(PURE_BLACK);
            header.setElevation(0f);
        }

        View root = activity.findViewById(R.id.detailRootLayout);
        if (root != null) root.setBackgroundColor(PURE_BLACK);

        View layoutSearchMatchBar = activity.findViewById(R.id.layoutSearchMatchBar);
        if (layoutSearchMatchBar != null) layoutSearchMatchBar.setBackground(createCardDrawable(activity, 16));
        View etDetailSearchInput = activity.findViewById(R.id.etDetailSearchInput);
        if (etDetailSearchInput instanceof EditText) ((EditText) etDetailSearchInput).setTextColor(TEXT_WHITE);
        View tvSearchMatchCounter = activity.findViewById(R.id.tvSearchMatchCounter);
        if (tvSearchMatchCounter instanceof TextView) ((TextView) tvSearchMatchCounter).setTextColor(Color.parseColor("#A8C7FA"));

        View cardResult = activity.findViewById(R.id.cardDetailResult);
        if (cardResult != null) cardResult.setBackground(createCardDrawable(activity, 20));

        View etDetail = activity.findViewById(R.id.etDetailResult);
        if (etDetail != null && etDetail instanceof EditText) {
            ((EditText) etDetail).setTextColor(TEXT_WHITE);
        }

        View btnClear = activity.findViewById(R.id.btnDetailClear);
        if (btnClear != null) btnClear.setBackground(createCardDrawable(activity, 12));

        View layoutSummary = activity.findViewById(R.id.layoutDetailSummary);
        if (layoutSummary != null) layoutSummary.setBackground(createCardDrawable(activity, 16));

        View tvSummaryHeader = activity.findViewById(R.id.tvDetailSummaryHeader);
        if (tvSummaryHeader != null && tvSummaryHeader instanceof TextView) {
            ((TextView) tvSummaryHeader).setTextColor(Color.parseColor("#A8C7FA"));
        }

        View tvSummaryContent = activity.findViewById(R.id.tvDetailSummaryContent);
        if (tvSummaryContent != null && tvSummaryContent instanceof TextView) {
            ((TextView) tvSummaryContent).setTextColor(TEXT_WHITE);
        }

        View btnDetailExport = activity.findViewById(R.id.btnDetailExport);
        if (btnDetailExport != null) {
            btnDetailExport.setBackground(createCardDrawable(activity, 12));
        }
    }

    public static void applyToHistoryItemView(Context context, View convertView) {
        if (!isAmoled(context) || convertView == null) return;
        convertView.setBackground(createInteractiveCardDrawable(context, 16));
    }

    public static void applyToDialog(Context context, AlertDialog dialog) {
        if (!isAmoled(context) || dialog == null || dialog.getWindow() == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(PURE_BLACK);
        bg.setCornerRadius(dpToPx(context, 24));
        bg.setStroke(dpToPx(context, 1), BORDER_COLOR);
        dialog.getWindow().setBackgroundDrawable(bg);
    }
}

