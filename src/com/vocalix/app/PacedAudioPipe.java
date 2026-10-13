package com.vocalix.app;

import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Streams raw PCM audio through a pipe at real-time pacing so Google's SpeechRecognizer
 * receives it continuously as if spoken into a live microphone.
 */
public class PacedAudioPipe {
    private static final String TAG = "PacedAudioPipe";

    public interface PipeCallback {
        void onStarted();
        void onFinished();
        void onError(Exception e);
    }

    public static ParcelFileDescriptor createPacedPipe(File pcmFile, PipeCallback callback) throws IOException {
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        ParcelFileDescriptor readEnd = pipe[0];
        ParcelFileDescriptor writeEnd = pipe[1];

        new Thread(() -> {
            FileInputStream fis = null;
            FileOutputStream fos = null;
            try {
                if (callback != null) callback.onStarted();
                fis = new FileInputStream(pcmFile);
                fos = new FileOutputStream(writeEnd.getFileDescriptor());

                // 16,000 samples/sec * 2 bytes/sample = 32,000 bytes/sec
                // 3,200 bytes per 100ms
                byte[] buffer = new byte[3200];
                int bytesRead;

                while ((bytesRead = fis.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                    fos.flush();
                    try {
                        Thread.sleep(90); // Paced to simulate live speech
                    } catch (InterruptedException e) {
                        break;
                    }
                }

                if (callback != null) callback.onFinished();
            } catch (Exception e) {
                Log.e(TAG, "Error writing to pipe", e);
                if (callback != null) callback.onError(e);
            } finally {
                try { if (fis != null) fis.close(); } catch (Exception ignored) {}
                try { if (fos != null) fos.close(); } catch (Exception ignored) {}
                try { writeEnd.close(); } catch (Exception ignored) {}
            }
        }).start();

        return readEnd;
    }
}

