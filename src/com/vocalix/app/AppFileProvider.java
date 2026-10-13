package com.vocalix.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Lightweight, zero-dependency ContentProvider for sharing PDF, TXT, SRT, and Backup files.
 */
public class AppFileProvider extends ContentProvider {
    public static final String AUTHORITY = "com.vocalix.app.fileprovider";

    public static Uri getUriForFile(Context context, File file) {
        return Uri.parse("content://" + AUTHORITY + "/" + file.getName());
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (getContext() == null) throw new FileNotFoundException("No context");
        String name = uri.getLastPathSegment();
        if (name == null) throw new FileNotFoundException("Invalid URI");

        File file = new File(getContext().getCacheDir(), name);
        if (!file.exists()) {
            file = new File(getContext().getFilesDir(), name);
        }
        if (!file.exists()) {
            throw new FileNotFoundException("File not found: " + name);
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name != null) {
            if (name.endsWith(".pdf")) return "application/pdf";
            if (name.endsWith(".txt")) return "text/plain";
            if (name.endsWith(".json")) return "application/json";
            if (name.endsWith(".m4a")) return "audio/mp4";
            if (name.endsWith(".wav")) return "audio/wav";
        }
        return "application/octet-stream";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        if (getContext() == null) return null;
        String name = uri.getLastPathSegment();
        if (name == null) return null;

        File file = new File(getContext().getCacheDir(), name);
        if (!file.exists()) {
            file = new File(getContext().getFilesDir(), name);
        }

        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        if (file.exists()) {
            cursor.addRow(new Object[]{file.getName(), file.length()});
        }
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}

