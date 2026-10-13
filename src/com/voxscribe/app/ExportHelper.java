package com.voxscribe.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ExportHelper {

    public static void showExportDialog(Context context, String text, String summary) {
        showExportDialog(context, text, summary, null);
    }

    public static void showExportDialog(Context context, String text, String summary, String date) {
        if (text == null || text.trim().isEmpty()) {
            Toast.makeText(context, R.string.no_text_to_share, Toast.LENGTH_SHORT).show();
            return;
        }

        String[] options = new String[]{
                "📄  " + context.getString(R.string.export_format_pdf),
                "📝  " + context.getString(R.string.export_format_txt)
        };

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.export_dialog_title)
                .setItems(options, (d, which) -> {
                    try {
                        if (which == 0) {
                            File pdf = exportToPdf(context, text, summary, date);
                            shareFile(context, pdf, "application/pdf", context.getString(R.string.export_format_pdf));
                        } else if (which == 1) {
                            File txt = exportToTxt(context, text, summary, date);
                            shareFile(context, txt, "text/plain", context.getString(R.string.export_format_txt));
                        }
                    } catch (Exception e) {
                        Toast.makeText(context, "Export error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();

        AmoledThemeHelper.applyToDialog(context, dialog);
    }

    public static File exportToPdf(Context context, String text, String summary, String date) throws Exception {
        PdfDocument document = new PdfDocument();
        int pageWidth = 595;  // A4 standard points width
        int pageHeight = 842; // A4 standard points height
        int margin = 40;
        int contentWidth = pageWidth - (margin * 2);

        TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setColor(0xFF0B57D0);
        titlePaint.setTextSize(20f);
        titlePaint.setFakeBoldText(true);

        TextPaint metaPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        metaPaint.setColor(0xFF555555);
        metaPaint.setTextSize(11f);

        TextPaint headerPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        headerPaint.setColor(0xFF222222);
        headerPaint.setTextSize(14f);
        headerPaint.setFakeBoldText(true);

        TextPaint bodyPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        bodyPaint.setColor(0xFF1F1F1F);
        bodyPaint.setTextSize(12f);

        // Pre-build layouts
        String safeDate = (date != null && !date.isEmpty()) ? date : new SimpleDateFormat("yyyy-MM-dd hh:mm a", Locale.getDefault()).format(new Date());
        StaticLayout bodyLayout = new StaticLayout(text, bodyPaint, contentWidth, Layout.Alignment.ALIGN_NORMAL, 1.25f, 0f, false);

        StaticLayout summaryLayout = null;
        if (summary != null && !summary.trim().isEmpty()) {
            summaryLayout = new StaticLayout(summary, bodyPaint, contentWidth, Layout.Alignment.ALIGN_NORMAL, 1.25f, 0f, false);
        }

        int pageNum = 1;
        PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create();
        PdfDocument.Page page = document.startPage(pageInfo);
        Canvas canvas = page.getCanvas();

        int y = margin + 10;
        canvas.drawText("VoxScribe Audio Transcript", margin, y, titlePaint);
        y += 20;
        canvas.drawText("Date: " + safeDate, margin, y, metaPaint);
        y += 18;

        Paint linePaint = new Paint();
        linePaint.setColor(0xFFE0E2E0);
        linePaint.setStrokeWidth(1.5f);
        canvas.drawLine(margin, y, pageWidth - margin, y, linePaint);
        y += 24;

        canvas.drawText("Transcription:", margin, y, headerPaint);
        y += 14;

        canvas.save();
        canvas.translate(margin, y);
        bodyLayout.draw(canvas);
        canvas.restore();
        y += bodyLayout.getHeight() + 24;

        if (summaryLayout != null) {
            if (y + summaryLayout.getHeight() + 40 > pageHeight - margin) {
                // If summary doesn't fit on page 1, create second page
                document.finishPage(page);
                pageNum++;
                pageInfo = new PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create();
                page = document.startPage(pageInfo);
                canvas = page.getCanvas();
                y = margin + 10;
            }

            canvas.drawText("AI Summary:", margin, y, headerPaint);
            y += 14;
            canvas.save();
            canvas.translate(margin, y);
            summaryLayout.draw(canvas);
            canvas.restore();
        }

        document.finishPage(page);

        File outFile = new File(context.getCacheDir(), "voxscribe_transcript_" + System.currentTimeMillis() + ".pdf");
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            document.writeTo(fos);
        } finally {
            document.close();
        }
        return outFile;
    }

    public static File exportToTxt(Context context, String text, String summary, String date) throws Exception {
        String safeDate = (date != null && !date.isEmpty()) ? date : new SimpleDateFormat("yyyy-MM-dd hh:mm a", Locale.getDefault()).format(new Date());
        StringBuilder sb = new StringBuilder();
        sb.append("=== VoxScribe Transcript ===\n");
        sb.append("Date: ").append(safeDate).append("\n\n");
        sb.append("--- Transcription ---\n");
        sb.append(text).append("\n\n");
        if (summary != null && !summary.trim().isEmpty()) {
            sb.append("--- AI Summary ---\n");
            sb.append(summary.trim()).append("\n\n");
        }
        sb.append("Generated by VoxScribe\n");

        File outFile = new File(context.getCacheDir(), "voxscribe_transcript_" + System.currentTimeMillis() + ".txt");
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(outFile), StandardCharsets.UTF_8)) {
            writer.write(sb.toString());
        }
        return outFile;
    }



    public static void shareFile(Context context, File file, String mimeType, String title) {
        Uri contentUri = AppFileProvider.getUriForFile(context, file);
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType(mimeType);
        shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(Intent.createChooser(shareIntent, title));
    }
}

