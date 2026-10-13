package com.vocalix.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class HistoryManager {
    private static final String PREFS_NAME = "vocalix_history_prefs";
    private static final String KEY_HISTORY_ENABLED = "history_enabled";
    private static final String KEY_HISTORY_ITEMS = "history_items_json";

    public static class HistoryEntry {
        public String id;
        public long timestamp;
        public String dateFormatted;
        public String text;
        public String summary;

        public HistoryEntry(String id, long timestamp, String dateFormatted, String text, String summary) {
            this.id = id;
            this.timestamp = timestamp;
            this.dateFormatted = dateFormatted;
            this.text = text;
            this.summary = summary;
        }

        public JSONObject toJson() {
            try {
                JSONObject obj = new JSONObject();
                obj.put("id", id);
                obj.put("timestamp", timestamp);
                obj.put("dateFormatted", dateFormatted);
                obj.put("text", text);
                obj.put("summary", summary != null ? summary : "");
                return obj;
            } catch (Exception e) {
                return null;
            }
        }

        public static HistoryEntry fromJson(JSONObject obj) {
            if (obj == null) return null;
            return new HistoryEntry(
                    obj.optString("id", String.valueOf(System.currentTimeMillis())),
                    obj.optLong("timestamp", System.currentTimeMillis()),
                    obj.optString("dateFormatted", ""),
                    obj.optString("text", ""),
                    obj.optString("summary", "")
            );
        }
        public String getFormattedDate12h() {
            if (timestamp > 0) {
                return new SimpleDateFormat("yyyy-MM-dd hh:mm a", Locale.getDefault()).format(new Date(timestamp));
            }
            return dateFormatted != null ? dateFormatted : "";
        }
    }

    public static boolean isHistoryEnabled(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_HISTORY_ENABLED, true);
    }

    public static void setHistoryEnabled(Context context, boolean enabled) {
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_HISTORY_ENABLED, enabled).apply();
    }

    public static void saveTranscript(Context context, String text, String summary) {
        if (!isHistoryEnabled(context) || text == null || text.trim().isEmpty()) {
            return;
        }

        List<HistoryEntry> entries = getHistoryEntries(context);
        long now = System.currentTimeMillis();
        String dateStr = new SimpleDateFormat("yyyy-MM-dd hh:mm a", Locale.getDefault()).format(new Date(now));
        String id = "entry_" + now;

        HistoryEntry newEntry = new HistoryEntry(id, now, dateStr, text.trim(), summary != null ? summary.trim() : "");
        entries.add(0, newEntry); // Most recent first

        // Limit to 100 entries to save space
        if (entries.size() > 100) {
            entries = entries.subList(0, 100);
        }

        saveAllEntries(context, entries);
    }

    public static void updateSummary(Context context, String text, String summary) {
        if (!isHistoryEnabled(context) || text == null || text.trim().isEmpty()) {
            return;
        }
        List<HistoryEntry> entries = getHistoryEntries(context);
        if (!entries.isEmpty() && entries.get(0).text.equals(text.trim())) {
            entries.get(0).summary = summary != null ? summary.trim() : "";
            saveAllEntries(context, entries);
        }
    }

    public static List<HistoryEntry> getHistoryEntries(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String jsonStr = sp.getString(KEY_HISTORY_ITEMS, "[]");
        List<HistoryEntry> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(jsonStr);
            for (int i = 0; i < arr.length(); i++) {
                HistoryEntry entry = HistoryEntry.fromJson(arr.getJSONObject(i));
                if (entry != null) {
                    list.add(entry);
                }
            }
        } catch (Exception ignored) {}
        return list;
    }

    public static void deleteEntry(Context context, String id) {
        List<HistoryEntry> entries = getHistoryEntries(context);
        List<HistoryEntry> updated = new ArrayList<>();
        for (HistoryEntry e : entries) {
            if (!e.id.equals(id)) {
                updated.add(e);
            }
        }
        saveAllEntries(context, updated);
    }

    public static void clearAll(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_HISTORY_ITEMS, "[]").apply();
    }

    public static void clearAllHistory(Context context) {
        clearAll(context);
    }

    public static void saveAllEntriesDirect(Context context, List<HistoryEntry> list) {
        saveAllEntries(context, list);
    }

    private static void saveAllEntries(Context context, List<HistoryEntry> list) {
        JSONArray arr = new JSONArray();
        for (HistoryEntry e : list) {
            JSONObject obj = e.toJson();
            if (obj != null) {
                arr.put(obj);
            }
        }
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_HISTORY_ITEMS, arr.toString()).apply();
    }
}

