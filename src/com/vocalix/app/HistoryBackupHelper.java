package com.vocalix.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class HistoryBackupHelper {

    public static void exportBackup(Context context) {
        try {
            List<HistoryManager.HistoryEntry> entries = HistoryManager.getHistoryEntries(context);
            if (entries.isEmpty()) {
                Toast.makeText(context, R.string.backup_empty_warn, Toast.LENGTH_SHORT).show();
                return;
            }

            JSONObject root = new JSONObject();
            root.put("app", "Vocalix");
            root.put("version", 2);
            root.put("exportTime", System.currentTimeMillis());

            JSONArray arr = new JSONArray();
            for (HistoryManager.HistoryEntry e : entries) {
                JSONObject obj = e.toJson();
                if (obj != null) arr.put(obj);
            }
            root.put("items", arr);

            String dateTag = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(new Date());
            File outFile = new File(context.getCacheDir(), "vocalix_backup_" + dateTag + ".json");

            try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(outFile), StandardCharsets.UTF_8)) {
                writer.write(root.toString(2));
            }

            Uri uri = AppFileProvider.getUriForFile(context, outFile);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.backup_share_title)));
        } catch (Exception e) {
            Toast.makeText(context, "Backup failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    public static int restoreFromUri(Context context, Uri uri) throws Exception {
        if (uri == null) return 0;
        StringBuilder sb = new StringBuilder();
        try (InputStream is = context.getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }

        JSONObject root = new JSONObject(sb.toString());
        JSONArray items = root.optJSONArray("items");
        if (items == null) {
            throw new Exception("Invalid backup file: no items array");
        }

        List<HistoryManager.HistoryEntry> existing = HistoryManager.getHistoryEntries(context);
        Set<String> existingIds = new HashSet<>();
        for (HistoryManager.HistoryEntry e : existing) {
            existingIds.add(e.id);
        }

        int addedCount = 0;
        List<HistoryManager.HistoryEntry> merged = new ArrayList<>(existing);
        for (int i = 0; i < items.length(); i++) {
            HistoryManager.HistoryEntry entry = HistoryManager.HistoryEntry.fromJson(items.getJSONObject(i));
            if (entry != null && !existingIds.contains(entry.id)) {
                merged.add(entry);
                existingIds.add(entry.id);
                addedCount++;
            }
        }

        if (addedCount > 0) {
            // Sort by timestamp desc
            merged.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
            // Save via private method reflection or exposed method
            HistoryManager.saveAllEntriesDirect(context, merged);
        }

        return addedCount;
    }
}

