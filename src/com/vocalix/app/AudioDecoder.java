package com.vocalix.app;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;

/**
 * Native decoder supporting OGG/Opus (WhatsApp), MP3/OGG (Telegram), AAC, M4A, WAV.
 * Decodes to 16kHz 16-bit Mono PCM and converts to WAV.
 */
public class AudioDecoder {
    private static final String TAG = "AudioDecoder";
    public static final int TARGET_SAMPLE_RATE = 16000;
    public static final int TARGET_CHANNELS = 1; // Mono

    public interface DecodeCallback {
        void onProgress(String message);
        void onSuccess(File pcmFile, File wavFile, long durationMs);
        void onError(String error);
    }

    public static void copyUriToFile(Context context, Uri uri, File dest) throws Exception {
        try (InputStream is = context.getContentResolver().openInputStream(uri);
             FileOutputStream fos = new FileOutputStream(dest)) {
            if (is == null) throw new Exception("Cannot open audio stream from URI");
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) != -1) {
                fos.write(buf, 0, len);
            }
            fos.flush();
        }
    }

    public static void decodeToPcmAndWav(Context context, Uri audioUri, File pcmOut, File wavOut, DecodeCallback callback) {
        new Thread(() -> {
            MediaExtractor extractor = new MediaExtractor();
            MediaCodec codec = null;
            FileOutputStream fos = null;

            try {
                callback.onProgress("ط¬ط§ط±ظٹ ظپط­طµ ط§ظ„ظ…ط³ط§ط± ط§ظ„طµظˆطھظٹ...");
                extractor.setDataSource(context, audioUri, null);

                int audioTrackIndex = -1;
                MediaFormat inputFormat = null;
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat format = extractor.getTrackFormat(i);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("audio/")) {
                        audioTrackIndex = i;
                        inputFormat = format;
                        break;
                    }
                }

                if (audioTrackIndex < 0 || inputFormat == null) {
                    callback.onError("ظ„ظ… ظٹطھظ… ط§ظ„ط¹ط«ظˆط± ط¹ظ„ظ‰ ظ…ط³ط§ط± طµظˆطھظٹ طµط§ظ„ط­ ظپظٹ ط§ظ„ظ…ظ„ظپ");
                    return;
                }

                extractor.selectTrack(audioTrackIndex);
                String mime = inputFormat.getString(MediaFormat.KEY_MIME);
                int sampleRate = inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE) ?
                        inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
                int channelCount = inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ?
                        inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
                long durationUs = inputFormat.containsKey(MediaFormat.KEY_DURATION) ?
                        inputFormat.getLong(MediaFormat.KEY_DURATION) : 0;

                callback.onProgress("ط¬ط§ط±ظٹ ظپظƒ طھط´ظپظٹط± ط§ظ„طµظˆطھ (" + mime + ")...");

                codec = MediaCodec.createDecoderByType(mime);
                codec.configure(inputFormat, null, null, 0);
                codec.start();

                fos = new FileOutputStream(pcmOut);

                MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                boolean isInputEOS = false;
                boolean isOutputEOS = false;
                long timeoutUs = 10000;

                double resampleRatio = (double) sampleRate / TARGET_SAMPLE_RATE;
                double samplePos = 0;

                while (!isOutputEOS) {
                    if (!isInputEOS) {
                        int inIndex = codec.dequeueInputBuffer(timeoutUs);
                        if (inIndex >= 0) {
                            ByteBuffer inputBuffer = codec.getInputBuffer(inIndex);
                            if (inputBuffer != null) {
                                int sampleSize = extractor.readSampleData(inputBuffer, 0);
                                if (sampleSize < 0) {
                                    codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                    isInputEOS = true;
                                } else {
                                    codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.getSampleTime(), 0);
                                    extractor.advance();
                                }
                            }
                        }
                    }

                    int outIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs);
                    if (outIndex >= 0) {
                        ByteBuffer outputBuffer = codec.getOutputBuffer(outIndex);
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset);
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size);

                            byte[] chunk = new byte[bufferInfo.size];
                            outputBuffer.get(chunk);

                            int numSamplesInChunk = chunk.length / 2;
                            short[] rawSamples = new short[numSamplesInChunk];
                            for (int i = 0; i < numSamplesInChunk; i++) {
                                int low = chunk[i * 2] & 0xFF;
                                int high = chunk[i * 2 + 1] & 0xFF;
                                rawSamples[i] = (short) ((high << 8) | low);
                            }

                            // Downmix to mono
                            short[] monoSamples;
                            if (channelCount > 1) {
                                int monoLen = numSamplesInChunk / channelCount;
                                monoSamples = new short[monoLen];
                                for (int i = 0; i < monoLen; i++) {
                                    int sum = 0;
                                    for (int c = 0; c < channelCount; c++) {
                                        sum += rawSamples[i * channelCount + c];
                                    }
                                    monoSamples[i] = (short) (sum / channelCount);
                                }
                            } else {
                                monoSamples = rawSamples;
                            }

                            // Resample to 16kHz
                            if (sampleRate == TARGET_SAMPLE_RATE) {
                                byte[] pcm16k = new byte[monoSamples.length * 2];
                                for (int i = 0; i < monoSamples.length; i++) {
                                    pcm16k[i * 2] = (byte) (monoSamples[i] & 0xFF);
                                    pcm16k[i * 2 + 1] = (byte) ((monoSamples[i] >> 8) & 0xFF);
                                }
                                fos.write(pcm16k);
                            } else {
                                int outEstimatedLen = (int) (monoSamples.length / resampleRatio) + 1;
                                byte[] resampledBytes = new byte[outEstimatedLen * 2];
                                int outByteIndex = 0;

                                while ((int) samplePos < monoSamples.length) {
                                    int idx = (int) samplePos;
                                    short s1 = monoSamples[idx];
                                    short s2 = (idx + 1 < monoSamples.length) ? monoSamples[idx + 1] : s1;
                                    double frac = samplePos - idx;
                                    short interpolated = (short) (s1 + frac * (s2 - s1));

                                    if (outByteIndex + 1 < resampledBytes.length) {
                                        resampledBytes[outByteIndex++] = (byte) (interpolated & 0xFF);
                                        resampledBytes[outByteIndex++] = (byte) ((interpolated >> 8) & 0xFF);
                                    }
                                    samplePos += resampleRatio;
                                }
                                samplePos -= monoSamples.length;

                                if (outByteIndex > 0) {
                                    fos.write(resampledBytes, 0, outByteIndex);
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false);

                        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            isOutputEOS = true;
                        }
                    }
                }

                fos.flush();
                fos.close();
                fos = null;

                // Create standard WAV file with 44-byte RIFF header
                callback.onProgress("ط¬ط§ط±ظٹ ط­ظپط¸ ط§ظ„ظ…ظ„ظپ ط§ظ„طµظˆطھظٹ ط§ظ„ظ…ط¹ط§ظ„ط¬...");
                convertPcmToWav(pcmOut, wavOut, TARGET_SAMPLE_RATE, TARGET_CHANNELS);

                long durationMs = durationUs / 1000;
                callback.onSuccess(pcmOut, wavOut, durationMs);

            } catch (Exception e) {
                Log.e(TAG, "Decode error", e);
                callback.onError("ط®ط·ط£ ظپظٹ ظپظƒ طھط´ظپظٹط± ط§ظ„طµظˆطھ: " + e.getMessage());
            } finally {
                try {
                    if (fos != null) fos.close();
                    if (codec != null) {
                        codec.stop();
                        codec.release();
                    }
                    extractor.release();
                } catch (Exception ignored) {}
            }
        }).start();
    }

    public static void convertPcmToWav(File pcmFile, File wavFile, int sampleRate, int channels) throws Exception {
        long pcmSize = pcmFile.length();
        long totalDataLen = pcmSize + 36;
        long byteRate = sampleRate * channels * 2; // 16-bit = 2 bytes per sample

        byte[] header = new byte[44];
        // RIFF header
        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        header[4] = (byte) (totalDataLen & 0xff);
        header[5] = (byte) ((totalDataLen >> 8) & 0xff);
        header[6] = (byte) ((totalDataLen >> 16) & 0xff);
        header[7] = (byte) ((totalDataLen >> 24) & 0xff);
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        // 'fmt ' chunk
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0; // SubChunk1Size (16 for PCM)
        header[20] = 1; header[21] = 0; // AudioFormat (1 for PCM)
        header[22] = (byte) channels; header[23] = 0;
        header[24] = (byte) (sampleRate & 0xff);
        header[25] = (byte) ((sampleRate >> 8) & 0xff);
        header[26] = (byte) ((sampleRate >> 16) & 0xff);
        header[27] = (byte) ((sampleRate >> 24) & 0xff);
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        header[32] = (byte) (channels * 2); header[33] = 0; // BlockAlign
        header[34] = 16; header[35] = 0; // BitsPerSample
        // 'data' chunk
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        header[40] = (byte) (pcmSize & 0xff);
        header[41] = (byte) ((pcmSize >> 8) & 0xff);
        header[42] = (byte) ((pcmSize >> 16) & 0xff);
        header[43] = (byte) ((pcmSize >> 24) & 0xff);

        try (FileOutputStream wavFos = new FileOutputStream(wavFile);
             FileInputStream pcmFis = new FileInputStream(pcmFile)) {
            wavFos.write(header);
            byte[] buf = new byte[8192];
            int len;
            while ((len = pcmFis.read(buf)) != -1) {
                wavFos.write(buf, 0, len);
            }
            wavFos.flush();
        }
    }
}

