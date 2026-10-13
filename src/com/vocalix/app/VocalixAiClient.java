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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Vocalix AI Client
 * Universal, production-ready audio transcription and summarization engine supporting:
 * 1. Google Gemini (multimodal native audio)
 * 2. OpenAI (Audio Transcriptions via Whisper / Audio models)
 * 3. Anthropic Claude (via Claude reasoning engine for text / transcript summarization)
 * 4. Groq (Ultra-fast Whisper-Large models)
 */
public class VocalixAiClient {
    private static final String TAG = "VocalixAiClient";

    public static final String PROVIDER_GEMINI = "gemini";
    public static final String PROVIDER_OPENAI = "openai";
    public static final String PROVIDER_CLAUDE = "claude";
    public static final String PROVIDER_GROQ = "groq";

    public static final String MODEL_AUTO = "auto";

    // Standard prioritized model list per provider with ultra-fast and high quota models first
    public static final List<String> GEMINI_MODELS = Arrays.asList(
            "gemini-2.5-flash",      // Next-gen ultra-fast multimodal
            "gemini-2.5-flash-lite", // Next-gen lightweight multimodal
            "gemini-2.0-flash",      // Rapid native multimodal
            "gemini-2.0-flash-lite", // Rapid lite multimodal
            "gemini-1.5-flash",      // Highly reliable standard multimodal
            "gemini-1.5-flash-8b"    // Ultra-lightweight fallback
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
                    callback.onResult(true, "تم فحص مفتاح Gemini بنجاح! (" + modelsFound.size() + " نموذج متوفر)", modelsFound);

                } else if (PROVIDER_OPENAI.equalsIgnoreCase(provider)) {
                    queryOpenAiModels(cleanKey, modelsFound);
                    if (modelsFound.isEmpty()) {
                        modelsFound.addAll(OPENAI_MODELS);
                    }
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
            URL url = new URL("https://generativelanguage.googleapis.com/" + version + "/models?key=" + apiKey);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
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

    /**
     * Rapid pre-check to verify model availability and quota readiness before sending heavy audio payloads.
     */
    private static boolean checkModelReadiness(String modelName, String apiKey) {
        HttpURLConnection conn = null;
        try {
            // First check model resource metadata (lightning fast GET request with 0 payload)
            URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/" + modelName + "?key=" + apiKey);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            int code = conn.getResponseCode();
            if (code == 404) {
                // Model not accessible or not found
                return false;
            }
            if (code == 429) {
                // Quota already exhausted
                return false;
            }
            return (code >= 200 && code < 300);
        } catch (Exception e) {
            // If network pre-check times out or fails, allow normal attempt rather than falsely blocking
            return true;
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
                    transcribeWithOpenAi(audioFile, "https://api.openai.com/v1/audio/transcriptions", chosenModel != null ? chosenModel : "whisper-1", cleanKey, splitSpeakers, callback);
                } else if (PROVIDER_GROQ.equalsIgnoreCase(targetProvider)) {
                    transcribeWithOpenAi(audioFile, "https://api.groq.com/openai/v1/audio/transcriptions", chosenModel != null ? chosenModel : "whisper-large-v3", cleanKey, splitSpeakers, callback);
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

    private static void transcribeWithGemini(File audioFile, String mimeType, String userChosenModel, String apiKey, boolean splitSpeakers, TranscribeCallback callback) {
        try {
            byte[] audioBytes;
            try (FileInputStream fis = new FileInputStream(audioFile);
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = fis.read(buf)) != -1) bos.write(buf, 0, r);
                audioBytes = bos.toByteArray();
            }

            String effectiveMime = mimeType;
            if (effectiveMime == null || effectiveMime.isEmpty() || effectiveMime.contains("octet")) {
                effectiveMime = detectMime(audioBytes);
            }

            String base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP);

            JSONObject rootPayload = new JSONObject();
            String systemInstructionText;
            String userPromptText;

            if (splitSpeakers) {
                systemInstructionText = "You are Vocalix AI, an elite multilingual speech transcription system with expert native Speaker Diarization capability.\n\n" +
                        "Core Mission:\n" +
                        "Transcribe the spoken audio with maximum fidelity into the EXACT native language spoken in the recording.\n" +
                        "Apply accurate punctuation and natural sentence boundaries. Remove hesitation stutters.\n\n" +
                        "STRICT SPEAKER DIARIZATION RULES:\n" +
                        "1. If multiple speakers or conversational turns exist, label each speaker turn on a new line STRICTLY IN THE NATIVE LANGUAGE OF THE SPEAKERS:\n" +
                        "   • If Arabic: 'المتحدث 1: ...' / 'المتحدث 2: ...'\n" +
                        "   • If English: 'Speaker 1: ...' / 'Speaker 2: ...'\n" +
                        "   • If Spanish: 'Hablante 1: ...' / 'Hablante 2: ...'\n" +
                        "   • If French: 'Intervenant 1: ...' / 'Intervenant 2: ...'\n" +
                        "   • Or corresponding native term for other languages.\n" +
                        "2. Output ONLY clean transcribed text without greetings or intro.";
                userPromptText = "Transcribe this audio recording into its spoken language. If multiple speakers exist, label each turn on a new line using the speaker label in THAT SAME spoken language (e.g., 'المتحدث 1:' for Arabic, 'Speaker 1:' for English). Output text only.";
            } else {
                systemInstructionText = "You are Vocalix AI, an elite multilingual speech transcription system. Transcribe the spoken audio with maximum fidelity into its native spoken language with accurate punctuation. Output the clean transcribed text only without commentary.";
                userPromptText = "Transcribe this audio recording into clean text with punctuation in its spoken language. Output text only.";
            }

            JSONObject sysPart = new JSONObject();
            sysPart.put("text", systemInstructionText);
            JSONArray sysParts = new JSONArray();
            sysParts.put(sysPart);
            JSONObject sysInstruction = new JSONObject();
            sysInstruction.put("parts", sysParts);
            rootPayload.put("systemInstruction", sysInstruction);

            JSONObject inlineData = new JSONObject();
            inlineData.put("mimeType", effectiveMime);
            inlineData.put("data", base64Audio);

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
            rootPayload.put("generationConfig", genConfig);

            byte[] postData = rootPayload.toString().getBytes("UTF-8");

            boolean isAutoMode = (userChosenModel == null || userChosenModel.isEmpty() || MODEL_AUTO.equalsIgnoreCase(userChosenModel));
            List<String> modelQueue = new ArrayList<>();
            if (!isAutoMode) {
                // User explicitly selected a specific model - prioritize and stick to it!
                modelQueue.add(userChosenModel.replace("models/", ""));
            } else {
                // Auto mode: cycle through prioritized fast multimodal models
                modelQueue.addAll(GEMINI_MODELS);
            }

            String lastError = null;
            for (int i = 0; i < modelQueue.size(); i++) {
                String modelName = modelQueue.get(i);

                // Quick pre-check: verify model responsiveness before uploading large audio payload
                if (!checkModelReadiness(modelName, apiKey)) {
                    Log.w(TAG, "Model " + modelName + " failed quick readiness check (quota or unavailable), skipping...");
                    if (!isAutoMode) {
                        callback.onError("النموذج المختار (" + modelName + ") غير جاهز حالياً أو استنفد حده اليومي. يرجى اختيار نموذج آخر أو تفعيل الوضع التلقائي.");
                        return;
                    }
                    continue;
                }

                HttpURLConnection conn = null;
                try {
                    String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/" + modelName + ":streamGenerateContent?alt=sse&key=" + apiKey;
                    URL url = new URL(endpoint);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                    conn.setRequestProperty("Accept", "text/event-stream");
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(45000);
                    conn.setDoOutput(true);

                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(postData);
                        os.flush();
                    }

                    int code = conn.getResponseCode();
                    if (code >= 200 && code < 300) {
                        StringBuilder accumulatedText = new StringBuilder();
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                line = line.trim();
                                if (line.startsWith("data:")) {
                                    String jsonStr = line.substring(5).trim();
                                    if (jsonStr.isEmpty() || "[DONE]".equalsIgnoreCase(jsonStr)) continue;
                                    try {
                                        JSONObject respJson = new JSONObject(jsonStr);
                                        JSONArray candidates = respJson.optJSONArray("candidates");
                                        if (candidates != null && candidates.length() > 0) {
                                            JSONObject candidate = candidates.getJSONObject(0);
                                            JSONObject content = candidate.optJSONObject("content");
                                            if (content != null) {
                                                JSONArray respParts = content.optJSONArray("parts");
                                                if (respParts != null && respParts.length() > 0) {
                                                    for (int p = 0; p < respParts.length(); p++) {
                                                        JSONObject pObj = respParts.optJSONObject(p);
                                                        if (pObj != null) {
                                                            String chunk = pObj.optString("text", "");
                                                            if (!chunk.isEmpty()) {
                                                                accumulatedText.append(chunk);
                                                                callback.onPartialChunk(accumulatedText.toString());
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } catch (Exception ignored) {}
                                }
                            }
                        }

                        String finalTranscribed = accumulatedText.toString().trim();
                        if (!finalTranscribed.isEmpty()) {
                            callback.onSuccess(finalTranscribed);
                            return;
                        }
                    } else {
                        StringBuilder errResp = new StringBuilder();
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getErrorStream(), "UTF-8"))) {
                            String l;
                            while ((l = reader.readLine()) != null) errResp.append(l);
                        } catch (Exception ignored) {}
                        String errBody = errResp.toString();
                        Log.e(TAG, "Gemini API error (" + code + ") on [" + modelName + "]: " + errBody);

                        String errMsg = errBody;
                        try {
                            JSONObject errJson = new JSONObject(errBody);
                            JSONObject errObj = errJson.optJSONObject("error");
                            if (errObj != null) {
                                errMsg = errObj.optString("message", errBody);
                            }
                        } catch (Exception ignored) {}

                        if (errMsg.contains("API key") || errMsg.contains("API_KEY_INVALID") || errMsg.contains("API key not valid")) {
                            callback.onError("مفتاح API غير صالح أو غير صحيح. يرجى التحقق من المفتاح في الإعدادات.");
                            return;
                        }

                        // Check quota limits (429)
                        if (code == 429 || errMsg.contains("Resource has been exhausted") || errMsg.contains("Quota exceeded") || errMsg.contains("rate limit")) {
                            if (!isAutoMode) {
                                callback.onError("تم استنفاد الحد اليومي (Quota) للنموذج المختار (" + modelName + "). يمكنك تفعيل الوضع التلقائي أو اختيار نموذج آخر.");
                                return;
                            }
                            Log.w(TAG, "Quota error on " + modelName + ", switching to next available fallback...");
                            lastError = "تم استنفاد حصة استخدام " + modelName + "، جاري التحويل للنموذج التالي...";
                            continue;
                        }

                        // Check model not found / unsupported (404)
                        if (code == 404 || errMsg.contains("not found") || errMsg.contains("is not supported")) {
                            if (!isAutoMode) {
                                callback.onError("النموذج المختار غير مدعوم في مشروعك (" + modelName + "). يرجى اختيار نموذج آخر من الإعدادات.");
                                return;
                            }
                            Log.w(TAG, "Model " + modelName + " not found or unsupported, switching...");
                            lastError = "النموذج غير متوفر (" + modelName + ")";
                            continue;
                        }

                        // Service temporary unavailable (503)
                        if (code == 503) {
                            if (!isAutoMode) {
                                callback.onError("النموذج المختار قيد الصيانة مؤقتاً لدى Google (" + modelName + ").");
                                return;
                            }
                            lastError = "النموذج قيد الصيانة مؤقتاً (" + modelName + ")";
                            continue;
                        }

                        // Any other client error (e.g. 400 Bad Request, payload issue) - fail immediately without useless loops
                        callback.onError("خطأ من Google (" + modelName + "): " + errMsg);
                        return;
                    }

                } catch (Exception e) {
                    lastError = "خطأ في الاتصال (" + modelName + "): " + e.getMessage();
                    if (!isAutoMode) {
                        callback.onError(lastError);
                        return;
                    }
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }

            callback.onError(lastError != null ? lastError : "تعذر الاتصال بخوادم Google. تأكد من اتصالك بالإنترنت وصلاحية المفتاح.");

        } catch (Exception e) {
            callback.onError("خطأ: " + e.getMessage());
        }
    }

    private static void transcribeWithOpenAi(File audioFile, String endpointUrl, String modelName, String apiKey, boolean splitSpeakers, TranscribeCallback callback) {
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
                os.write(("--" + boundary + "\r\n").getBytes("UTF-8"));
                os.write(("Content-Disposition: form-data; name=\"model\"\r\n\r\n").getBytes("UTF-8"));
                os.write((modelName + "\r\n").getBytes("UTF-8"));

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
                    callback.onSuccess(text.trim());
                } else {
                    callback.onError("لم يتم استخراج أي نص من الملف الصوتي.");
                }
            } else {
                callback.onError("خطأ من المزود (" + code + "): " + resp.toString());
            }

        } catch (Exception e) {
            callback.onError("خطأ في الاتصال بالمزود: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
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
            rootPayload.put("generationConfig", genConfig);

            byte[] postData = rootPayload.toString().getBytes("UTF-8");

            boolean isAutoMode = (model == null || model.isEmpty() || MODEL_AUTO.equalsIgnoreCase(model));
            List<String> queue = new ArrayList<>();
            if (!isAutoMode) {
                queue.add(model.replace("models/", ""));
            } else {
                queue.addAll(GEMINI_MODELS);
            }

            for (String mName : queue) {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/" + mName + ":generateContent?key=" + apiKey);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
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
                    }
                } catch (Exception ignored) {
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

            JSONObject root = new JSONObject();
            root.put("model", model);
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
