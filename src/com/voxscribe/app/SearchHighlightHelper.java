package com.voxscribe.app;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class SearchHighlightHelper {

    public static class MatchRange {
        public final int start;
        public final int end;

        public MatchRange(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    /**
     * Normalizes text for fast, fuzzy-friendly case-insensitive and Arabic-friendly search
     * while preserving 1:1 string length so indexes remain identical.
     */
    public static String normalize(String input) {
        if (input == null) return "";
        char[] chars = input.toLowerCase(Locale.ROOT).toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            // Normalize Arabic alef forms
            if (c == '\u0622' || c == '\u0623' || c == '\u0625' || c == '\u0671') {
                chars[i] = '\u0627'; // ط§
            }
            // Normalize Taa Marbuta to Haa
            else if (c == '\u0629') {
                chars[i] = '\u0647'; // ظ‡
            }
            // Normalize Alif Maqsura to Yaa
            else if (c == '\u0649') {
                chars[i] = '\u064A'; // ظٹ
            }
        }
        return new String(chars);
    }

    /**
     * Find all occurrences of query in originalText.
     */
    public static List<MatchRange> findAllMatches(String originalText, String query) {
        if (originalText == null || query == null) return Collections.emptyList();
        String q = normalize(query.trim());
        if (q.isEmpty()) return Collections.emptyList();

        String normText = normalize(originalText);
        List<MatchRange> list = new ArrayList<>();
        int idx = 0;
        int qLen = q.length();
        while ((idx = normText.indexOf(q, idx)) != -1) {
            list.add(new MatchRange(idx, idx + qLen));
            idx += qLen;
        }
        return list;
    }

    public static int countMatches(String originalText, String query) {
        return findAllMatches(originalText, query).size();
    }

    /**
     * Creates a smart snippet with the first match in context and highlighted.
     */
    public static CharSequence createHighlightedSnippet(String text, String query, int normalHighlightBg, int normalHighlightText) {
        if (text == null) return "";
        if (query == null || query.trim().isEmpty()) {
            return text;
        }

        List<MatchRange> matches = findAllMatches(text, query);
        if (matches.isEmpty()) {
            return text;
        }

        MatchRange first = matches.get(0);
        int snippetStart = Math.max(0, first.start - 35);
        int snippetEnd = Math.min(text.length(), first.end + 80);

        String prefix = (snippetStart > 0) ? "..." : "";
        String suffix = (snippetEnd < text.length()) ? "..." : "";
        String sub = text.substring(snippetStart, snippetEnd);

        SpannableStringBuilder ssb = new SpannableStringBuilder(prefix + sub + suffix);
        int offset = prefix.length() + (first.start - snippetStart);
        int matchLen = first.end - first.start;

        if (offset >= 0 && offset + matchLen <= ssb.length()) {
            ssb.setSpan(new BackgroundColorSpan(normalHighlightBg), offset, offset + matchLen, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            ssb.setSpan(new ForegroundColorSpan(normalHighlightText), offset, offset + matchLen, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            ssb.setSpan(new StyleSpan(Typeface.BOLD), offset, offset + matchLen, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        return ssb;
    }

    /**
     * Highlights all matches in the given Spannable text.
     */
    public static void applyHighlights(Spannable spannable, List<MatchRange> matches, int currentIndex,
                                       int normalBgColor, int normalTextColor,
                                       int activeBgColor, int activeTextColor) {
        if (spannable == null || matches == null) return;

        // Clear existing highlight spans
        BackgroundColorSpan[] bgSpans = spannable.getSpans(0, spannable.length(), BackgroundColorSpan.class);
        for (BackgroundColorSpan s : bgSpans) {
            spannable.removeSpan(s);
        }
        ForegroundColorSpan[] fgSpans = spannable.getSpans(0, spannable.length(), ForegroundColorSpan.class);
        for (ForegroundColorSpan s : fgSpans) {
            spannable.removeSpan(s);
        }
        StyleSpan[] styleSpans = spannable.getSpans(0, spannable.length(), StyleSpan.class);
        for (StyleSpan s : styleSpans) {
            spannable.removeSpan(s);
        }

        for (int i = 0; i < matches.size(); i++) {
            MatchRange m = matches.get(i);
            if (m.start >= 0 && m.end <= spannable.length() && m.start < m.end) {
                boolean isActive = (i == currentIndex);
                int bg = isActive ? activeBgColor : normalBgColor;
                int fg = isActive ? activeTextColor : normalTextColor;

                spannable.setSpan(new BackgroundColorSpan(bg), m.start, m.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                spannable.setSpan(new ForegroundColorSpan(fg), m.start, m.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (isActive) {
                    spannable.setSpan(new StyleSpan(Typeface.BOLD), m.start, m.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }
    }
}

