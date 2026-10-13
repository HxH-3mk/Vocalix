package com.vocalix.app;

import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Vocalix AI Client
 * Universal, production-ready audio transcription and summarization engine supporting:
 * 1. Google Gemini (multimodal native audio)
 * 2. OpenAI (Audio Transcriptions via Whisper / Audio models)
 * 3. Anthropic Claude (via Claude reasoning engine for text / transcript summarization)
 * 4. Groq (Ultra-fast Whisper-Large models)
 *
 * Smart model selection: models that return 404/unsupported are blacklisted permanently
 * (they simply do not exist for this key), while models that hit quota/rate limits (429)
 * or maintenance (503) enter a temporary cooldown window derived from RetryInfo so heavy
 * uploads never get wasted on an exhausted model again the same day.
 */
public class VocalixAiClient {
    private static final String TAG = "VocalixAiClient";

    public static final String PROVIDER_GEMINI = "gemini";
    public static final String PROVIDER_OPENAI = "openai";
    public static final String PROVIDER_CLAUDE = "claude";
    public static final String PROVIDER_GROQ = "groq";

    public static final String MODEL_AUTO = "auto";

    // Standard prioritized model list per provider with ultra-fast and high quota models first.
    // NOTE: entries whose daily free quota is already saturated (e.g. gemini-3.8-flash at 21/20 RPD)
    // were removed from the default priority queue; they remain available if explicitly chosen by
    // the user and will be auto-cooled-down via the smart cooldown engine when they return 429.
    public static final List<String> GEMINI_MODELS = Arrays.asList(
            "gemini-3.5-flash-lite", // 500 RPD ultra-fast lightweight
            "gemini-3.1-flash-lite", // 500 RPD ultra-fast lightweight
            "gemini-2.5-flash-lite", // Next-gen lightweight multimodal, generous RPD
            "gemini-2.5-flash",      // Next-gen ultra-fast multimodal
            "gemini-1.5-flash"       // Highly reliable standard multimodal
    );

    public static final List<String> OPENAI_MODELS = Arrays.asList(
            "whisper-1",
            "gpt-4o-audio-preview",
            "gpt-4o-mini-audio-preview"
    );

    public static final List<String> CLAUDE_MODELS = Arrays.asList(
            "claude-3-5-haiku-latest",
            "claude-3-5-sonnet-latest",
            "claude-3-haiku-20240307"
    );

    public static final List<String> GROQ_MODELS = Arrays.asList(
            "whisper-large-v3",
            "whisper-large-v3-turbo"
    );

    public interface TranscribeCallback {
        void onPartialChunk(String partialText);
        void onSuccess(String fullText);
        void onError(String error);
    }

    public interface SummarizeCallback {
        void onSuccess(String summary);
        void onError(String error);
    }

    public interface ModelListCallback {
        void onResult(boolean success, String message, List<String> models);
    }

    // ==========================================
    // Smart model health / cooldown engine
    // ==========================================

    /** Models confirmed unavailable for this account (404) - skipped permanently this session. */
    private static final java.util.Set<String> BLACKLISTED_MODELS =
            Collections.synchronizedSet(new java.util.HashSet<String>());
    /** modelName -> epoch millis until which the model should not be retried (429 / 503). */
    private static final Map<String, Long> MODEL_COOLDOWN_UNTIL = Collections.synchronizedMap(new HashMap<String, Long>());

    private static long parseRetryDelayMs(String errBody) {
        try {
            JSONObject errObj = new JSONObject(errBody).optJSONObject("error");
            if (errObj == null) return 0;
            JSONArray details = errObj.optJSONArray("details");
            if (details != null) {
                for (int i = 0; i < details.length(); i++) {
                    JSONObject d = details.optJSONObject(i);
                    if (d == null || !d.optString("@type", "").contains("RetryInfo")) continue;
                    int secs = d.optInt("retryDelay", 0);
                    String rd = d.optString("retryDelay", "");
                    if (rd.endsWith("s") && rd.length() > 1) {
                        try { secs = (int) Double.parseDouble(rd.replace("s", "").trim()); } catch (Exception ignored) {}
                    }
                    if (secs > 0) return Math.min(secs, 3600) * 1000L;
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private static void markModelUnavailable(String modelName, int httpCode, String errBody) {
        if (httpCode == 404 || (errBody != null && (errBody.contains("not found") || errBody.contains("is not supported")))) {
            BLACKLISTED_MODELS.add(modelName);
            Log.w(TAG, "Model blacklisted (unavailable for this key): " + modelName);
            return;
        }
        long ms;
        if (httpCode == 429) {
            long hinted = parseRetryDelayMs(errBody);
            ms = hinted > 0 ? hinted : 30_000L;
        } else { // 503 and other transient server states
            ms = 60_000L;
        }
        MODEL_COOLDOWN_UNTIL.put(modelName, System.currentTimeMillis() + ms);
        Log.w(TAG, "Model cooled down for " + ms / 1000 + "s: " + modelName);
    }

    private static boolean isModelAvailable(String modelName) {
        if (BLACKLISTED_MODELS.contains(modelName)) return false;
        Long until = MODEL_COOLDOWN_UNTIL.get(modelName);
        return until == null || until <= System.currentTimeMillis();
    }

    /**
     * Builds the ordered attempt queue: user-chosen model first (if healthy), then every
     * known/fetched model sorted by priority, silently skipping blacklisted and cooling models.
     */
    private static List<String> buildModelQueue(List<String> priorities, List<String> extraKnown, String userChosenModel, boolean isAutoMode) {
        List<String> ordered = new ArrayList<>();
        if (!isAutoMode && userChosenModel != null && !userChosenModel.isEmpty()) {
            ordered.add(userChosenModel.replace("models/", ""));
        }
        ArrayDeque<String> rest = new ArrayDeque<>();
        for (String m : priorities) if (!ordered.contains(m)) rest.add(m);
        if (extraKnown != null) {
            for (String m : extraKnown) {
                String name = m.replace("models/", "");
                if (!ordered.contains(name) && !rest.contains(name)) rest.add(name);
            }
        }
        // Move unhealthy models to the back instead of dropping them entirely:
        // if all preferred models fail we still give them one last chance.
        List<String> degraded = new ArrayList<>();
        for (String m : rest) {
            if (isModelAvailable(m)) ordered.add(m); else degraded.add(m);
        }
        ordered.addAll(degraded);
        return ordered;
    }

    /** Human-readable status of the smart cooldown engine (shown in Settings after key test). */
    public static String getModelHealthReport() {
        StringBuilder sb = new StringBuilder();
        if (!BLACKLISTED_MODELS.isEmpty()) {
            sb.append("غير متوفر نهائياً لهذا المفتاح: ").append(String.join(", ", BLACKLISTED_MODELS)).append("\n");
        }
        if (!MODEL_COOLDOWN_UNTIL.isEmpty()) {
            SimpleDateFormat df = new SimpleDateFormat("HH:mm", Locale.getDefault());
            List<String> parts = new ArrayList<>();
            synchronized (MODEL_COOLDOWN_UNTIL) {
                for (Map.Entry<String, Long> e : MODEL_COOLDOWN_UNTIL.entrySet()) {
                    long left = e.getValue() - System.currentTimeMillis();
                    if (left > 0) parts.add(e.getKey() + " (حتى " + df.format(new Date(e.getValue())) + ")");
                }
            }
            if (!parts.isEmpty()) sb.append("رصيد مستنفد مؤقتاً: ").append(String.join("، ", parts));
        }
        return sb.toString().trim();
    }

    /** Clears learned blacklist/cooldowns (e.g. when the user switches API keys). */
    public static void resetModelHealth() {
        BLACKLISTED_MODELS.clear();
        MODEL_COOLDOWN_UNTIL.clear();
    }

    /**
     * Inspect API key and dynamically retrieve supported models.
     */
    public static void fetchAvailableModels(String provider, String apiKey, ModelListCallback callback) {
        new Thread(() -> {
            if (apiKey == null || apiKey.trim().isEmpty()) {
                callback.onResult(false, "يرجى إدخال مفتاح API أولاً", Collections.emptyList());
                return;
            }

            String cleanKey = apiKey.trim();
            List<String> modelsFound = new ArrayList<>();

            try {
                if (PROVIDER_GEMINI.equalsIgnoreCase(provider)) {
                    queryGeminiModels("v1beta", cleanKey, modelsFound);
                    if (modelsFound.isEmpty()) {
                        queryGeminiModels("v1", cleanKey, modelsFound);
                    }
                    if (modelsFound.isEmpty()) {
                        modelsFound.addAll(GEMINI_MODELS);
                    }
                    sKnownGeminiModels = new ArrayList<>(modelsFound);
                    callback.onResult(true, "تم فحص مفتاح Gemini بنجاح! (" + modelsFound.size() + " نموذج متوفر)", modelsFound);

                } else if (PROVIDER_OPENAI.equalsIgnoreCase(provider)) {
                    queryOpenAiModels(cleanKey, modelsFound);
                    if (modelsFound.isEmpty()) {
                        modelsFound.addAll(OPENAI_MODELS);
                    }
                    sKnownOpenAiModels = new ArrayList<>(modelsFound);
                    callback.onResult(true, "تم فحص مفتاح OpenAI بنجاح! (" + modelsFound.size() + " نموذج)", modelsFound);

                } else if (PROVIDER_CLAUDE.equalsIgnoreCase(provider)) {
                    queryClaudeModels(cleanKey, modelsFound);
                    if (modelsFound.isEmpty()) {
                        modelsFound.addAll(CLAUDE_MODELS);
                    }
                    callback.onResult(true, "تم فحص مفتاح Anthropic Claude بنجاح!", modelsFound);

                } else if (PROVIDER_GROQ.equalsIgnoreCase(provider)) {
                    queryGroqModels(cleanKey, modelsFound);
                    if (modelsFound.isEmpty()) {
                        modelsFound.addAll(GROQ_MODELS);
                    }
                    sKnownGroqModels = new ArrayList<>(modelsFound);
                    callback.onResult(true, "تم فحص مفتاح Groq بنجاح! (" + modelsFound.size() + " نموذج)", modelsFound);
                } else {
                    callback.onResult(false, "مزود الذكاء الاصطناعي غير معروف", Collections.emptyList());
                }

            } catch (Exception e) {
                Log.e(TAG, "Error fetching models for " + provider, e);
                callback.onResult(false, "حدث خطأ أثناء فحص النماذج: " + e.getMessage(), Collections.emptyList());
            }
        }).start();
    }

    private static void queryGeminiModels(String version, String apiKey, List<String> resultList) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://generativelanguage.googleapis.com/" + version + "/models?pageSize=1000");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("x-goog-api-key", apiKey);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                }
                JSONObject root = new JSONObject(sb.toString());
                JSONArray models = root.optJSONArray("models");
                if (models != null) {
                    for (int i = 0; i < models.length(); i++) {
                        JSONObject m = models.getJSONObject(i);
                        String name = m.optString("name", "").replace("models/", "");
                        JSONArray methods = m.optJSONArray("supportedGenerationMethods");
                        boolean canGenerate = false;
                        if (methods != null) {
                            for (int j = 0; j < methods.length(); j++) {
                                if ("generateContent".equals(methods.getString(j))) canGenerate = true;
                            }
                        }
                        if (canGenerate && !resultList.contains(name)) {
                            resultList.add(name);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Gemini models query error: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void queryOpenAiModels(String apiKey, List<String> resultList) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://api.openai.com/v1/models");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                }
                JSONObject root = new JSONObject(sb.toString());
                JSONArray data = root.optJSONArray("data");
                if (data != null) {
                    for (int i = 0; i < data.length(); i++) {
                        String id = data.getJSONObject(i).optString("id", "");
                        if (id.contains("whisper") || id.contains("audio") || id.startsWith("gpt-4o")) {
                            if (!resultList.contains(id)) resultList.add(id);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "OpenAI models query error: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void queryGroqModels(String apiKey, List<String> resultList) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://api.groq.com/openai/v1/models");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                }
                JSONObject root = new JSONObject(sb.toString());
                JSONArray data = root.optJSONArray("data");
                if (data != null) {
                    for (int i = 0; i < data.length(); i++) {
                        String id = data.getJSONObject(i).optString("id", "");
                        if (id.contains("whisper") && !resultList.contains(id)) {
                            resultList.add(id);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Groq models query error: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void queryClaudeModels(String apiKey, List<String> resultList) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://api.anthropic.com/v1/models");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("x-api-key", apiKey);
            conn.setRequestProperty("anthropic-version", "2023-06-01");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                }
                JSONObject root = new JSONObject(sb.toString());
                JSONArray data = root.optJSONArray("data");
                if (data != null) {
                    for (int i = 0; i < data.length(); i++) {
                        String id = data.getJSONObject(i).optString("id", "");
                        if (!resultList.contains(id)) resultList.add(id);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Claude models query error: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ==========================================
    // Unified Audio Transcription
    // ==========================================

    /** Live model ids discovered via fetchAvailableModels(), used to enrich the failover queue. */
    private static volatile List<String> sKnownGeminiModels = null;
    private static volatile List<String> sKnownOpenAiModels = null;
    private static volatile List<String> sKnownGroqModels = null;

    public static void transcribe(File audioFile, String mimeType, String provider, String chosenModel, String apiKey, boolean splitSpeakers, TranscribeCallback callback) {
        new Thread(() -> {
            try {
                if (apiKey == null || apiKey.trim().isEmpty()) {
                    callback.onError("لم يتم إدخال مفتاح API. يرجى إدخال المفتاح من صفحة الإعدادات.");
                    return;
                }
                if (audioFile == null || !audioFile.exists() || audioFile.length() == 0) {
                    callback.onError("الملف الصوتي فارغ أو غير موجود.");
                    return;
                }

                String cleanKey = apiKey.trim();
                String targetProvider = (provider != null && !provider.isEmpty()) ? provider : PROVIDER_GEMINI;

                if (PROVIDER_OPENAI.equalsIgnoreCase(targetProvider)) {
                    List<String> q = buildModelQueue(OPENAI_MODELS, sKnownOpenAiModels, chosenModel,
                            chosenModel == null || chosenModel.isEmpty() || MODEL_AUTO.equalsIgnoreCase(chosenModel));
                    transcribeWithOpenAiFallback(audioFile, "https://api.openai.com/v1/audio/transcriptions", q, OPENAI_MODELS.get(0), cleanKey, splitSpeakers, callback);
                } else if (PROVIDER_GROQ.equalsIgnoreCase(targetProvider)) {
                    List<String> q = buildModelQueue(GROQ_MODELS, sKnownGroqModels, chosenModel,
                            chosenModel == null || chosenModel.isEmpty() || MODEL_AUTO.equalsIgnoreCase(chosenModel));
                    transcribeWithOpenAiFallback(audioFile, "https://api.groq.com/openai/v1/audio/transcriptions", q, GROQ_MODELS.get(0), cleanKey, splitSpeakers, callback);
                } else if (PROVIDER_CLAUDE.equalsIgnoreCase(targetProvider)) {
                    callback.onError("نموذج Claude يتخصص في التلخيص ومعالجة النصوص. يرجى اختيار Gemini أو OpenAI أو Groq للتفريغ الصوتي.");
                } else {
                    transcribeWithGemini(audioFile, mimeType, chosenModel, cleanKey, splitSpeakers, callback);
                }

            } catch (Exception e) {
                Log.e(TAG, "Transcribe error", e);
                callback.onError("حدث خطأ أثناء التفريغ: " + e.getMessage());
            }
        }).start();
    }

    /** Max bytes allowed for a single inline (base64) upload attempt (~20MB is the API request cap). */
    private static final long MAX_INLINE_FILE_BYTES = 20L * 1024 * 1024;
    /** Files above this size are uploaded via the Gemini Files API (real multipart, memory-safe). */
    private static final long LARGE_FILE_FILES_API_BYTES = 25L * 1024 * 1024;
    /** Chunk size (raw audio bytes) per request when large files fall back to sequential chunks. */
    private static final int CHUNK_RAW_BYTES = 5 * 1024 * 1024;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static void transcribeWithGemini(File audioFile, String mimeType, String userChosenModel, String apiKey, boolean splitSpeakers, TranscribeCallback callback) {
        try {
            // ---- Smart model queue: skips blacklisted (404) and cooling (429/503) models first ----
            boolean isAutoMode = (userChosenModel == null || userChosenModel.isEmpty() || MODEL_AUTO.equalsIgnoreCase(userChosenModel));
            List<String> modelQueue = buildModelQueue(GEMINI_MODELS, sKnownGeminiModels, userChosenModel, isAutoMode);
            if (modelQueue.isEmpty()) {
                callback.onError("لا توجد نماذج Gemini متاحة لهذا المفتاح حالياً.");
                return;
            }

            // ---- MIME detection from magic header bytes only (no full-file read) ----
            byte[] head = new byte[16];
            int headRead = 0;
            try (FileInputStream fis = new FileInputStream(audioFile)) {
                headRead = fis.read(head);
            }
            String effectiveMime = mimeType;
            if (effectiveMime == null || effectiveMime.isEmpty() || effectiveMime.contains("octet")) {
                effectiveMime = detectMime(Arrays.copyOf(head, Math.max(headRead, 0)));
            }

            long fileSize = audioFile.length();
            StringBuilder accumulated = new StringBuilder();

            // ---- Very large files: one real multipart upload through the official Files API ----
            if (fileSize > LARGE_FILE_FILES_API_BYTES &&
                    tryGeminiFileApiUpload(audioFile, effectiveMime, modelQueue, apiKey, splitSpeakers, accumulated, callback)) {
                return; // success or terminal error already delivered
            }

            if (fileSize <= MAX_INLINE_FILE_BYTES) {
                // Single-pass upload: base64 streams directly into the request body without
                // ever materializing the whole encoded file in memory (OOM-safe).
                GeminiResult res = runGeminiAttempt(modelQueue, apiKey, audioFile, -1, -1, effectiveMime,
                        splitSpeakers, accumulated, callback);
                if (res == GeminiResult.SUCCESS) {
                    callback.onSuccess(accumulated.toString().trim());
                    return;
                }
                if (res == GeminiResult.FATAL) return; // onError already delivered
                String learned = LAST_GEMINI_ERROR != null ? LAST_GEMINI_ERROR : "";
                callback.onError("تعذر التفريغ عبر Gemini على جميع النماذج المتاحة (" + modelQueue.size() + " نموذج). " + learned + " جرّب لاحقاً بعد إعادة تعيين الحصة اليومية أو مزوداً آخر مثل Groq المجاني.");
                return;
            }

            // ---- Large file fallback: automatic sequential chunking ----
            long chunkCount = (fileSize + CHUNK_RAW_BYTES - 1) / CHUNK_RAW_BYTES;
            callback.onPartialChunk("ملف كبير: يتم التفريغ على " + chunkCount + " أجزاء متتالية...\n");
            long offset = 0;
            int partNo = 1;
            while (offset < fileSize) {
                int len = (int) Math.min(CHUNK_RAW_BYTES, fileSize - offset);
                GeminiResult res = runGeminiAttempt(modelQueue, apiKey, audioFile, offset, len, effectiveMime,
                        splitSpeakers, accumulated, callback);
                if (res == GeminiResult.FATAL) return;
                if (res != GeminiResult.SUCCESS) {
                    callback.onError("تعذر إكمال الجزء " + partNo + "/" + chunkCount + " من الملف. تأكد من توفر رصيد في أحد النماذج أو استخدم ملفاً أقصر.");
                    return;
                }
                offset += len;
                partNo++;
                if (offset < fileSize) accumulated.append("\n");
            }
            callback.onSuccess(accumulated.toString().trim());

        } catch (Exception e) {
            callback.onError("خطأ: " + e.getMessage());
        }
    }

    private enum GeminiResult { SUCCESS, RETRY_NEXT_MODEL, FATAL }

    private static volatile String LAST_GEMINI_ERROR = null;

    /**
     * Runs one transcription pass through the model queue. Streams base64 audio straight into
     * the HTTP body (fixed-size buffering, no giant allocations), parses SSE tokens, and records
     * learned model health (blacklist/cooldown) automatically.
     */
    private static GeminiResult runGeminiAttempt(List<String> modelQueue, String apiKey, File audioFile,
                                                long rangeStart, long rangeLen, String mime,
                                                boolean splitSpeakers, StringBuilder accumulated,
                                                TranscribeCallback callback) {
        final int b64Len;
        if (rangeLen >= 0) {
            b64Len = (int) (((rangeLen + 2) / 3) * 4);
        } else {
            b64Len = (int) (((audioFile.length() + 2) / 3) * 4);
        }
        byte[] headBuf = new byte[4096];
        byte[] tailBuf = new byte[4096];
        final int prefixLen, suffixLen;
        try {
            JSONObject probe = buildGeminiPayload(mime, splitSpeakers, true);
            String probeStr = probe.toString();
            int idx = probeStr.indexOf("@@BASE64@@");
            byte[] pre = probeStr.substring(0, idx).getBytes(UTF8);
            byte[] suf = probeStr.substring(idx + "@@BASE64@@".length()).getBytes(UTF8);
            System.arraycopy(pre, 0, headBuf, 0, pre.length);
            System.arraycopy(suf, 0, tailBuf, 0, suf.length);
            prefixLen = pre.length;
            suffixLen = suf.length;
        } catch (Exception e) {
            callback.onError("خطأ في تجهيز الطلب: " + e.getMessage());
            return GeminiResult.FATAL;
        }
        final int totalLen = prefixLen + b64Len + suffixLen;

        String lastError = null;
        for (String modelName : modelQueue) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/" + modelName + ":streamGenerateContent?alt=sse");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("x-goog-api-key", apiKey); // key in header: never leaks via URLs/logs
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("Accept", "text/event-stream");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(60000);
                conn.setFixedLengthStreamingMode(totalLen);
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(headBuf, 0, prefixLen);
                    writeBase64Range(os, audioFile, rangeStart, rangeLen);
                    os.write(tailBuf, 0, suffixLen);
                    os.flush();
                }

                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    StringBuilder errSink = new StringBuilder();
                    if (parseSseStream(conn, accumulated, callback, errSink)) return GeminiResult.SUCCESS;
                    markModelUnavailable(modelName, code, errSink.toString());
                    lastError = "انقطع الرد من " + modelName + " دون نص مفيد.";
                    continue;
                }

                String errBody = readStream(conn.getErrorStream());
                Log.e(TAG, "Gemini API error (" + code + ") on [" + modelName + "]: " + errBody);

                String errMsg = errBody;
                try {
                    JSONObject errObj = new JSONObject(errBody).optJSONObject("error");
                    if (errObj != null) errMsg = errObj.optString("message", errBody);
                } catch (Exception ignored) {}

                if (code == 401 || code == 403 || errMsg.contains("API key") || errMsg.contains("API_KEY_INVALID")) {
                    callback.onError("مفتاح API غير صالح أو لا يملك صلاحية الطلب. يرجى التحقق من المفتاح في الإعدادات.");
                    return GeminiResult.FATAL;
                }
                if (code == 429 || errMsg.contains("Resource has been exhausted") || errMsg.contains("Quota exceeded") || errMsg.contains("rate limit")) {
                    markModelUnavailable(modelName, 429, errBody);
                    lastError = "تم استنفاد حصة استخدام " + modelName + "، جاري تجربة النموذج التالي...";
                    continue;
                }
                if (code == 404 || errMsg.contains("not found") || errMsg.contains("is not supported")) {
                    markModelUnavailable(modelName, 404, errBody);
                    lastError = "النموذج غير متوفر (" + modelName + ")";
                    continue;
                }
                if (code == 503) {
                    markModelUnavailable(modelName, 503, errBody);
                    lastError = "النموذج قيد الصيانة مؤقتاً (" + modelName + ")";
                    continue;
                }
                if (code >= 500) {
                    markModelUnavailable(modelName, code, errBody);
                    lastError = "خطأ خادم مؤقت من Google (" + modelName + ")";
                    continue;
                }
                // Other client errors (e.g. 400 Bad Request) are payload issues - identical for every model.
                callback.onError("خطأ من Google (" + modelName + "): " + errMsg);
                return GeminiResult.FATAL;

            } catch (SocketTimeoutException ste) {
                lastError = "انتهت مهلة الاستجابة (" + modelName + "). قد يكون المقطع طويلاً جداً لهذا النموذج.";
            } catch (UnknownHostException uhe) {
                callback.onError("تعذر الوصول إلى خوادم Google. تحقق من اتصال الإنترنت.");
                return GeminiResult.FATAL;
            } catch (Exception e) {
                lastError = "خطأ في الاتصال (" + modelName + "): " + e.getMessage();
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        LAST_GEMINI_ERROR = lastError;
        return GeminiResult.RETRY_NEXT_MODEL;
    }

    /**
     * Uploads the whole file once through the Gemini Files API, then transcribes it by file_uri
     * with SSE streaming. Returns true if the outcome was terminal (success or fatal onError),
     * false to fall back to inline/chunked mode.
     */
    private static boolean tryGeminiFileApiUpload(File audioFile, String mime, List<String> modelQueue, String apiKey,
                                                  boolean splitSpeakers, StringBuilder accumulated, TranscribeCallback callback) {
        String fileUri = null;
        try {
            callback.onPartialChunk("ملف كبير جداً: يتم الرفع عبر Files API بدون ترميز Base64...\n");
            fileUri = geminiUploadFile(audioFile, mime, apiKey);
            if (fileUri == null) return false; // upload failed -> fallback to chunked inline mode

            String lastError = null;
            for (String modelName : modelQueue) {
                HttpURLConnection conn = null;
                try {
                    JSONObject rootPayload = buildGeminiPayload(mime, splitSpeakers, false);
                    JSONArray partsArr = rootPayload.getJSONArray("contents").getJSONObject(0).getJSONArray("parts");
                    partsArr.put(0, fileDataPart(mime, fileUri));

                    URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/" + modelName + ":streamGenerateContent?alt=sse");
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("x-goog-api-key", apiKey);
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                    conn.setRequestProperty("Accept", "text/event-stream");
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(120000);
                    conn.setDoOutput(true);
                    byte[] body = rootPayload.toString().getBytes(UTF8);
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(body);
                        os.flush();
                    }

                    int code = conn.getResponseCode();
                    if (code >= 200 && code < 300) {
                        StringBuilder errSink = new StringBuilder();
                        if (parseSseStream(conn, accumulated, callback, errSink)) {
                            callback.onSuccess(accumulated.toString().trim());
                            return true;
                        }
                        markModelUnavailable(modelName, code, errSink.toString());
                        lastError = "انقطع الرد من " + modelName + " دون نص مفيد.";
                        continue;
                    }
                    String errBody = readStream(conn.getErrorStream());
                    if (code == 401 || code == 403) {
                        callback.onError("مفتاح API غير صالح. يرجى التحقق من المفتاح في الإعدادات.");
                        return true;
                    }
                    if (code == 429 || code == 404 || code == 503 || code >= 500) {
                        markModelUnavailable(modelName, code, errBody);
                        lastError = "النموذج " + modelName + " غير متاح حالياً (" + code + ")، جاري تجربة البديل...";
                        continue;
                    }
                    callback.onError("خطأ من Google (" + modelName + "): " + errBody);
                    return true;
                } catch (Exception e) {
                    lastError = "خطأ في الاتصال (" + modelName + "): " + e.getMessage();
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
            callback.onError(lastError != null ? lastError : "تعذر التفريغ عبر Files API على جميع النماذج المتاحة.");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Files API path failed, falling back to inline chunks: " + e.getMessage());
            return false;
        } finally {
            if (fileUri != null) geminiDeleteFile(fileUri, apiKey);
        }
    }

    /** Streams the audio file as multipart/related to upload/*; returns the file URI or null. */
    private static String geminiUploadFile(File f, String mime, String apiKey) {
        HttpURLConnection conn = null;
        try {
            String boundary = "VocalixBoundary" + System.currentTimeMillis();
            byte[] meta = multipartMetadata(boundary, mime);
            byte[] trailer = ("\r\n--" + boundary + "--\r\n").getBytes(UTF8);
            URL url = new URL("https://generativelanguage.googleapis.com/upload/v1beta/files");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("x-goog-api-key", apiKey);
            conn.setRequestProperty("X-Goog-Upload-Protocol", "multipart");
            conn.setRequestProperty("X-Goog-Upload-Command", "upload, finalize");
            conn.setRequestProperty("X-Goog-Upload-Header-Content-Length", String.valueOf(f.length()));
            conn.setRequestProperty("Content-Type", "multipart/related; boundary=" + boundary);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(300000);
            conn.setFixedLengthStreamingMode(meta.length + f.length() + trailer.length);
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(meta);
                try (FileInputStream fis = new FileInputStream(f)) {
                    byte[] buf = new byte[64 * 1024];
                    int r;
                    while ((r = fis.read(buf)) != -1) os.write(buf, 0, r);
                }
                os.write(trailer);
                os.flush();
            }

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                String resp = readStream(conn.getInputStream());
                JSONObject fileObj = new JSONObject(resp).optJSONObject("file");
                if (fileObj != null) {
                    if ("FAILED".equals(fileObj.optString("state", ""))) return null;
                    String uri = fileObj.optString("uri", null);
                    return (uri == null || uri.isEmpty()) ? null : uri;
                }
            } else {
                Log.w(TAG, "Files API upload failed (" + code + "): " + readStream(conn.getErrorStream()));
            }
        } catch (Exception e) {
            Log.w(TAG, "Files API upload error: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private static byte[] multipartMetadata(String boundary, String mime) throws Exception {
        JSONObject meta = new JSONObject();
        meta.put("file", new JSONObject().put("display_name", "vocalix_audio"));
        String part = "--" + boundary + "\r\n" +
                "Content-Type: application/json; charset=UTF-8\r\n" +
                "Content-ID: header\r\n\r\n" +
                meta.toString() + "\r\n" +
                "--" + boundary + "\r\n" +
                "Content-Type: " + mime + "\r\n\r\n";
        return part.getBytes(UTF8);
    }

    private static JSONObject fileDataPart(String mime, String fileUri) throws Exception {
        JSONObject fileRef = new JSONObject();
        fileRef.put("mimeType", mime);
        fileRef.put("file_uri", fileUri);
        JSONObject refPart = new JSONObject();
        refPart.put("file_data", fileRef);
        return refPart;
    }

    private static void geminiDeleteFile(String fileUri, String apiKey) {
        HttpURLConnection conn = null;
        try {
            String name = fileUri.substring(fileUri.lastIndexOf("/files/") + 1); // -> files/xxxx
            URL url = new URL("https://generativelanguage.googleapis.com/v1beta/" + name);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("DELETE");
            conn.setRequestProperty("x-goog-api-key", apiKey);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.getResponseCode();
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static JSONObject buildGeminiPayload(String mime, boolean splitSpeakers, boolean withAudioPlaceholder) throws Exception {
        String systemInstructionText;
        String userPromptText;
        if (splitSpeakers) {
            systemInstructionText = "You are Vocalix AI, an elite multilingual audio transcription and multi-speaker diarization engine.\n\n" +
                    "CRITICAL SPEAKER DIARIZATION REQUIREMENT:\n" +
                    "1. You MUST identify and separate EVERY speaker in the audio (whether 2, 3, 4, 5 or more distinct voices/turns).\n" +
                    "2. Every change of speaker or turn MUST start on a new paragraph with their speaker label:\n" +
                    "   If Arabic:\n" +
                    "     المتحدث 1: [كلام المتحدث الأول]\n" +
                    "     المتحدث 2: [كلام المتحدث الثاني]\n" +
                    "     المتحدث 3: [كلام المتحدث الثالث]\n" +
                    "     المتحدث 4: [كلام المتحدث الرابع]\n" +
                    "   If English:\n" +
                    "     Speaker 1: [Text]\n" +
                    "     Speaker 2: [Text]\n" +
                    "     Speaker 3: [Text]\n"
                    + "   Or corresponding native language speaker label.\n" +
                    "3. Even if speech is rapid or overlapping, analyze pitch, tone, and conversational turns to distinguish between different speakers.\n" +
                    "4. Never merge different speakers into a single block of text.\n" +
                    "5. Output ONLY the transcription with speaker tags. No preamble, no notes, no metadata.\n" +
                    "6. This audio may be one segment of a longer recording: do NOT greet and do NOT comment on it being partial, just transcribe.";
            userPromptText = "Carefully listen to this audio recording and transcribe it with STRICT SPEAKER SEPARATION (Diarization).\n" +
                    "Distinguish between all voices (Speaker 1, Speaker 2, Speaker 3, Speaker 4, etc.).\n" +
                    "For Arabic audio, format every speaker turn on a new line as:\n" +
                    "المتحدث 1: ...\nالمتحدث 2: ...\nالمتحدث 3: ...\n\n" +
                    "Start transcription directly:";
        } else {
            systemInstructionText = "You are Vocalix AI, an elite multilingual speech transcription system. Transcribe the spoken audio with maximum fidelity into its native spoken language with accurate punctuation. Output the clean transcribed text only without commentary.";
            userPromptText = "Transcribe this audio recording into clean text with punctuation in its spoken language. Output text only.";
        }

        JSONObject rootPayload = new JSONObject();
        JSONObject sysPart = new JSONObject();
        sysPart.put("text", systemInstructionText);
        JSONArray sysParts = new JSONArray();
        sysParts.put(sysPart);
        JSONObject sysInstruction = new JSONObject();
        sysInstruction.put("parts", sysParts);
        rootPayload.put("systemInstruction", sysInstruction);

        JSONObject inlineData = new JSONObject();
        inlineData.put("mimeType", mime);
        inlineData.put("data", withAudioPlaceholder ? "@@BASE64@@" : "");
        JSONObject partAudio = new JSONObject();
        partAudio.put("inlineData", inlineData);
        JSONObject partPrompt = new JSONObject();
        partPrompt.put("text", userPromptText);

        JSONArray partsArray = new JSONArray();
        partsArray.put(partAudio);
        partsArray.put(partPrompt);

        JSONObject contentObj = new JSONObject();
        contentObj.put("parts", partsArray);
        JSONArray contentsArray = new JSONArray();
        contentsArray.put(contentObj);
        rootPayload.put("contents", contentsArray);

        JSONObject genConfig = new JSONObject();
        genConfig.put("temperature", 0.1);
        genConfig.put("maxOutputTokens", 32768);
        // Explicitly disable Thinking mode: direct answers without thinking delay
        JSONObject thinkingConfig = new JSONObject();
        thinkingConfig.put("thinking_budget", 0);
        genConfig.put("thinkingConfig", thinkingConfig);
        rootPayload.put("generationConfig", genConfig);
        return rootPayload;
    }

    /** Streams base64 of (part of) a file into the output without loading the file fully in RAM. */
    private static void writeBase64Range(OutputStream os, File f, long start, long len) throws Exception {
        final int IN_CHUNK = 3 * 1024 * 1024; // multiple of 3 -> aligned base64 blocks
        byte[] buf = new byte[IN_CHUNK];
        try (FileInputStream fis = new FileInputStream(f);
             android.util.Base64OutputStream b64 = new android.util.Base64OutputStream(os, Base64.NO_WRAP)) {
            if (start > 0) {
                long skip = start;
                while (skip > 0) {
                    long s = fis.skip(skip);
                    if (s <= 0) break;
                    skip -= s;
                }
            }
            long remaining = len >= 0 ? len : Long.MAX_VALUE;
            while (remaining > 0) {
                int toRead = (int) Math.min(IN_CHUNK, remaining);
                int r = fis.read(buf, 0, toRead);
                if (r <= 0) break;
                b64.write(buf, 0, r);
                remaining -= r;
            }
        }
    }

    /** Parses an SSE response, appending transcript chunks. Returns true if usable text arrived. */
    private static boolean parseSseStream(HttpURLConnection conn, StringBuilder accumulated,
                                          TranscribeCallback callback, StringBuilder errSink) {
        int beforeLen = accumulated.length();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), UTF8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.startsWith("data:")) continue;
                String jsonStr = line.substring(5).trim();
                if (jsonStr.isEmpty() || "[DONE]".equalsIgnoreCase(jsonStr)) continue;
                try {
                    JSONObject respJson = new JSONObject(jsonStr);
                    JSONObject errObj = respJson.optJSONObject("error");
                    if (errObj != null) errSink.append(errObj.optString("message", ""));
                    JSONArray candidates = respJson.optJSONArray("candidates");
                    if (candidates != null && candidates.length() > 0) {
                        JSONObject candidate = candidates.getJSONObject(0);
                        JSONObject content = candidate.optJSONObject("content");
                        if (content != null) {
                            JSONArray respParts = content.optJSONArray("parts");
                            if (respParts != null) {
                                for (int p = 0; p < respParts.length(); p++) {
                                    JSONObject pObj = respParts.optJSONObject(p);
                                    if (pObj != null) {
                                        String chunk = pObj.optString("text", "");
                                        if (!chunk.isEmpty()) {
                                            accumulated.append(chunk);
                                            callback.onPartialChunk(accumulated.toString());
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            errSink.append(e.getMessage() == null ? "" : e.getMessage());
        }
        return accumulated.length() > beforeLen;
    }

    private static String readStream(InputStream is) {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, UTF8))) {
            String l;
            while ((l = reader.readLine()) != null) sb.append(l);
        } catch (Exception ignored) {}
        return sb.toString();
    }

    /** Tries each healthy model in the queue; only fatal errors (bad key / payload) stop iteration. */
    private static void transcribeWithOpenAiFallback(File audioFile, String endpointUrl, List<String> modelQueue,
                                                     String defaultModel, String apiKey, boolean splitSpeakers,
                                                     TranscribeCallback callback) {
        if (modelQueue.isEmpty()) modelQueue = new ArrayList<>(Arrays.asList(defaultModel));
        String lastError = null;
        for (String m : modelQueue) {
            int[] outcome = new int[1]; // 0 success, 1 retry next, 2 fatal
            lastError = transcribeWithOpenAi(audioFile, endpointUrl, m, apiKey, splitSpeakers, callback, outcome);
            if (outcome[0] == 0 || outcome[0] == 2) return;
        }
        callback.onError(lastError != null ? lastError : "تعذر التفريغ عبر هذا المزود على جميع النماذج المتاحة.");
    }

    private static String transcribeWithOpenAi(File audioFile, String endpointUrl, String modelName, String apiKey, boolean splitSpeakers, TranscribeCallback callback, int[] outcome) {
        outcome[0] = 1;
        HttpURLConnection conn = null;
        try {
            String boundary = "===Vocalix" + System.currentTimeMillis() + "===";
            URL url = new URL(endpointUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                // model parameter
                String effectiveModel = modelName;
                if (effectiveModel == null || effectiveModel.isEmpty() || MODEL_AUTO.equalsIgnoreCase(effectiveModel)) {
                    effectiveModel = endpointUrl.contains("groq.com") ? "whisper-large-v3" : "whisper-1";
                }
                os.write(("--" + boundary + "\r\n").getBytes("UTF-8"));
                os.write(("Content-Disposition: form-data; name=\"model\"\r\n\r\n").getBytes("UTF-8"));
                os.write((effectiveModel + "\r\n").getBytes("UTF-8"));

                // prompt parameter (for multi-speaker and transcription guiding)
                if (splitSpeakers) {
                    String diarPrompt = "المتحدث 1: السلام عليكم. المتحدث 2: وعليكم السلام. المتحدث 3: أهلاً بكم.";
                    os.write(("--" + boundary + "\r\n").getBytes("UTF-8"));
                    os.write(("Content-Disposition: form-data; name=\"prompt\"\r\n\r\n").getBytes("UTF-8"));
                    os.write((diarPrompt + "\r\n").getBytes("UTF-8"));
                }

                // response_format
                os.write(("--" + boundary + "\r\n").getBytes("UTF-8"));
                os.write(("Content-Disposition: form-data; name=\"response_format\"\r\n\r\n").getBytes("UTF-8"));
                os.write(("json\r\n").getBytes("UTF-8"));

                // audio file
                String filename = audioFile.getName();
                os.write(("--" + boundary + "\r\n").getBytes("UTF-8"));
                os.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n").getBytes("UTF-8"));
                os.write(("Content-Type: audio/wav\r\n\r\n").getBytes("UTF-8"));

                try (FileInputStream fis = new FileInputStream(audioFile)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = fis.read(buf)) != -1) {
                        os.write(buf, 0, len);
                    }
                }
                os.write(("\r\n--" + boundary + "--\r\n").getBytes("UTF-8"));
                os.flush();
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder resp = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) resp.append(line);
            }

            if (code >= 200 && code < 300) {
                JSONObject json = new JSONObject(resp.toString());
                String text = json.optString("text", "");
                if (!text.trim().isEmpty()) {
                    outcome[0] = 0;
                    callback.onSuccess(text.trim());
                    return null;
                }
                outcome[0] = 2;
                callback.onError("لم يتم استخراج أي نص من الملف الصوتي (قد يكون صامتاً أو طويلاً جداً).");
                return null;
            }

            String body = resp.toString();
            Log.e(TAG, providerNameOf(endpointUrl) + " error (" + code + ") on [" + modelName + "]: " + body);
            if (code == 401 || code == 403 || body.contains("invalid_api_key") || body.contains("API key")) {
                outcome[0] = 2;
                callback.onError("مفتاح API غير صالح لهذا المزود. يرجى التحقق من المفتاح في الإعدادات.");
                return null;
            }
            if (code == 404 || body.contains("model_not_found") || body.contains("does not exist") || body.contains("is not supported")) {
                BLACKLISTED_MODELS.add(modelName);
                return "النموذج " + modelName + " غير متوفر، جاري تجربة بديل...";
            }
            if (code == 429) {
                MODEL_COOLDOWN_UNTIL.put(modelName, System.currentTimeMillis() + 60_000L);
                return "تم استنفاد حصة " + modelName + "، جاري تجربة النموذج التالي...";
            }
            if (code >= 500) {
                return "خطأ خادم مؤقت من " + providerNameOf(endpointUrl) + " (" + modelName + ")";
            }
            // 4xx payload issues (e.g. file too large) are model-independent -> fail fast with guidance
            outcome[0] = 2;
            String hint = (code == 413 || body.toLowerCase().contains("too large") || body.toLowerCase().contains("max"))
                    ? " الملف الصوتي كبير جداً لهذا المزود (الحد ~25MB لـ Whisper). جرّب Gemini مع التقسيم التلقائي أو ملفاً أقصر."
                    : "";
            callback.onError("خطأ من " + providerNameOf(endpointUrl) + " (" + code + "):" + hint + " " + body);
            return null;

        } catch (Exception e) {
            outcome[0] = 2;
            callback.onError("خطأ في الاتصال بالمزود: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String providerNameOf(String endpointUrl) {
        return endpointUrl.contains("groq.com") ? "Groq" : "OpenAI";
    }

    // ==========================================
    // Unified Summarization Engine
    // ==========================================

    public static void summarizeText(String textToSummarize, String provider, String model, String apiKey, SummarizeCallback callback) {
        new Thread(() -> {
            try {
                if (textToSummarize == null || textToSummarize.trim().isEmpty()) {
                    callback.onError("لا يوجد نص لتلخيصه.");
                    return;
                }
                if (apiKey == null || apiKey.trim().isEmpty()) {
                    callback.onError("لم يتم إدخال مفتاح API.");
                    return;
                }

                String cleanKey = apiKey.trim();
                String targetProvider = (provider != null && !provider.isEmpty()) ? provider : PROVIDER_GEMINI;

                if (PROVIDER_CLAUDE.equalsIgnoreCase(targetProvider)) {
                    summarizeWithClaude(textToSummarize, model != null ? model : "claude-3-5-haiku-latest", cleanKey, callback);
                } else if (PROVIDER_OPENAI.equalsIgnoreCase(targetProvider)) {
                    summarizeWithOpenAi(textToSummarize, model != null ? model : "gpt-4o-mini", cleanKey, callback);
                } else {
                    summarizeWithGemini(textToSummarize, model, cleanKey, callback);
                }

            } catch (Exception e) {
                callback.onError("خطأ: " + e.getMessage());
            }
        }).start();
    }

    private static void summarizeWithGemini(String textToSummarize, String model, String apiKey, SummarizeCallback callback) {
        try {
            JSONObject rootPayload = new JSONObject();
            JSONObject sysPart = new JSONObject();
            sysPart.put("text", "You are a professional audio transcript summarizer.\n\n" +
                    "MANDATORY MONOLINGUAL RULE:\n" +
                    "1. Write the summary EXCLUSIVELY in the EXACT SAME language and script as the transcript.\n" +
                    "2. Structure clearly with bullet points directly in that language.\n" +
                    "3. Output bullet points directly without greetings.");
            JSONArray sysParts = new JSONArray();
            sysParts.put(sysPart);
            JSONObject sysInstruction = new JSONObject();
            sysInstruction.put("parts", sysParts);
            rootPayload.put("systemInstruction", sysInstruction);

            JSONObject userPart = new JSONObject();
            userPart.put("text", "Summarize this transcript concisely and in its own native language:\n\n" + textToSummarize);
            JSONArray parts = new JSONArray();
            parts.put(userPart);
            JSONObject contentObj = new JSONObject();
            contentObj.put("parts", parts);
            JSONArray contentsArray = new JSONArray();
            contentsArray.put(contentObj);
            rootPayload.put("contents", contentsArray);

            JSONObject genConfig = new JSONObject();
            genConfig.put("temperature", 0.2);
            JSONObject thinkingConfig = new JSONObject();
            thinkingConfig.put("thinking_budget", 0);
            genConfig.put("thinkingConfig", thinkingConfig);
            rootPayload.put("generationConfig", genConfig);

            byte[] postData = rootPayload.toString().getBytes("UTF-8");

            boolean isAutoMode = (model == null || model.isEmpty() || MODEL_AUTO.equalsIgnoreCase(model));
            List<String> queue = buildModelQueue(GEMINI_MODELS, sKnownGeminiModels, model, isAutoMode);

            String lastSumError = null;
            for (String mName : queue) {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/" + mName + ":generateContent");
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("x-goog-api-key", apiKey);
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(20000);
                    conn.setDoOutput(true);

                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(postData);
                        os.flush();
                    }

                    int code = conn.getResponseCode();
                    InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                    StringBuilder resp = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"))) {
                        String line;
                        while ((line = reader.readLine()) != null) resp.append(line);
                    }

                    if (code >= 200 && code < 300) {
                        JSONObject respJson = new JSONObject(resp.toString());
                        JSONArray candidates = respJson.optJSONArray("candidates");
                        if (candidates != null && candidates.length() > 0) {
                            JSONObject candidate = candidates.getJSONObject(0);
                            JSONObject content = candidate.optJSONObject("content");
                            if (content != null) {
                                JSONArray respParts = content.optJSONArray("parts");
                                if (respParts != null && respParts.length() > 0) {
                                    StringBuilder full = new StringBuilder();
                                    for (int p = 0; p < respParts.length(); p++) {
                                        JSONObject pObj = respParts.optJSONObject(p);
                                        if (pObj != null) full.append(pObj.optString("text", ""));
                                    }
                                    String sumStr = full.toString().trim();
                                    if (!sumStr.isEmpty()) {
                                        callback.onSuccess(sumStr);
                                        return;
                                    }
                                }
                            }
                        }
                    } else {
                        if (code == 401 || code == 403) {
                            callback.onError("مفتاح API غير صالح. يرجى التحقق من المفتاح في الإعدادات.");
                            return;
                        }
                        if (code == 429 || code == 404 || code == 503 || code >= 500) markModelUnavailable(mName, code, resp.toString());
                        Log.w(TAG, "Summarize failed on " + mName + " (" + code + "), trying next model...");
                        lastSumError = "النموذج " + mName + " (" + code + ")";
                    }
                } catch (Exception e) {
                    lastSumError = e.getMessage();
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }

            callback.onError("تعذر توليد الملخص.");
        } catch (Exception e) {
            callback.onError(e.getMessage());
        }
    }

    private static void summarizeWithOpenAi(String textToSummarize, String model, String apiKey, SummarizeCallback callback) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://api.openai.com/v1/chat/completions");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(25000);
            conn.setDoOutput(true);

            JSONObject root = new JSONObject();
            root.put("model", model.contains("whisper") ? "gpt-4o-mini" : model);
            JSONArray messages = new JSONArray();

            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", "You are a transcript summarizer. Summarize concisely in the transcript's native language using clean bullet points.");
            messages.put(sys);

            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", textToSummarize);
            messages.put(user);

            root.put("messages", messages);
            root.put("temperature", 0.2);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(root.toString().getBytes("UTF-8"));
                os.flush();
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }

            if (code >= 200 && code < 300) {
                JSONObject json = new JSONObject(sb.toString());
                JSONArray choices = json.optJSONArray("choices");
                if (choices != null && choices.length() > 0) {
                    String result = choices.getJSONObject(0).getJSONObject("message").optString("content", "");
                    callback.onSuccess(result.trim());
                    return;
                }
            }
            callback.onError("تعذر توليد الملخص من OpenAI: " + sb.toString());
        } catch (Exception e) {
            callback.onError("خطأ: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void summarizeWithClaude(String textToSummarize, String model, String apiKey, SummarizeCallback callback) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://api.anthropic.com/v1/messages");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("x-api-key", apiKey);
            conn.setRequestProperty("anthropic-version", "2023-06-01");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(25000);
            conn.setDoOutput(true);

            String effectiveModel = model;
            if (effectiveModel == null || effectiveModel.isEmpty() || MODEL_AUTO.equalsIgnoreCase(effectiveModel)) {
                effectiveModel = "claude-3-5-haiku-latest";
            }
            JSONObject root = new JSONObject();
            root.put("model", effectiveModel);
            root.put("max_tokens", 1024);
            root.put("system", "Summarize this audio transcript cleanly in bullet points in its own exact native language.");

            JSONArray messages = new JSONArray();
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", textToSummarize);
            messages.put(userMsg);
            root.put("messages", messages);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(root.toString().getBytes("UTF-8"));
                os.flush();
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }

            if (code >= 200 && code < 300) {
                JSONObject json = new JSONObject(sb.toString());
                JSONArray content = json.optJSONArray("content");
                if (content != null && content.length() > 0) {
                    String text = content.getJSONObject(0).optString("text", "");
                    callback.onSuccess(text.trim());
                    return;
                }
            }
            callback.onError("تعذر التلخيص عبر Claude: " + sb.toString());
        } catch (Exception e) {
            callback.onError("خطأ: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String detectMime(byte[] data) {
        if (data == null || data.length < 4) return "audio/ogg";
        if (data[0] == 'O' && data[1] == 'g' && data[2] == 'g' && data[3] == 'S') {
            return "audio/ogg";
        }
        if (data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F') {
            return "audio/wav";
        }
        if ((data[0] == 'I' && data[1] == 'I' && data[2] == '3') ||
                ((data[0] & 0xFF) == 0xFF && (data[1] & 0xE0) == 0xE0)) {
            return "audio/mp3";
        }
        if (data.length > 8 && data[4] == 'f' && data[5] == 't' && data[6] == 'y' && data[7] == 'p') {
            return "audio/m4a";
        }
        return "audio/ogg";
    }
}
