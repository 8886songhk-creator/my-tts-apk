package com.novel.ttsreader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TtsService extends Service implements TextToSpeech.OnInitListener {
    private static final String CHANNEL_ID = "novel_tts_channel";

    public interface ProgressCallback {
        void onSentenceStart(int index);
        void onFinished();
    }

    private static ProgressCallback callback;
    public static void setProgressCallback(ProgressCallback cb) {
        callback = cb;
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private volatile boolean isPlaying = false;
    private PowerManager.WakeLock wakeLock;
    private final List<String> sentences = new ArrayList<>();
    private int currentIndex = 0;
    private float speechRate = 1.0f;
    private boolean pendingSpeak = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TtsReader::WakeLock");
            wakeLock.setReferenceCounted(false);
        }
        tts = new TextToSpeech(getApplicationContext(), this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "ACTION_STOP".equals(intent.getAction())) {
            stopReading();
            return START_NOT_STICKY;
        }

        startForegroundCompat("내장 음성 준비 중...");
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(6 * 60 * 60 * 1000L);
        }

        if (intent != null) {
            speechRate = intent.getFloatExtra("rate", 1.0f);
            currentIndex = intent.getIntExtra("startIndex", 0);
            String jsonList = intent.getStringExtra("sentences");
            String singleText = intent.getStringExtra("singleText");

            sentences.clear();
            if (jsonList != null) {
                try {
                    JSONArray arr = new JSONArray(jsonList);
                    for (int i = 0; i < arr.length(); i++) {
                        sentences.add(arr.getString(i));
                    }
                } catch (Exception ignored) {}
            } else if (singleText != null && !singleText.trim().isEmpty()) {
                sentences.add(singleText);
                currentIndex = 0;
            }

            isPlaying = true;
            if (ttsReady) {
                speakCurrent();
            } else {
                pendingSpeak = true;
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            tts.setLanguage(Locale.KOREAN);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                    if (isPlaying && callback != null) {
                        callback.onSentenceStart(currentIndex);
                    }
                }

                @Override
                public void onDone(String utteranceId) {
                    if (!isPlaying) return;
                    mainHandler.post(() -> {
                        if (!isPlaying) return;
                        currentIndex++;
                        if (currentIndex < sentences.size()) {
                            speakCurrent();
                        } else {
                            if (callback != null) callback.onFinished();
                            stopReading();
                        }
                    });
                }

                @Override
                public void onError(String utteranceId) {
                    if (!isPlaying) return;
                    mainHandler.post(() -> {
                        if (!isPlaying) return;
                        currentIndex++;
                        if (currentIndex < sentences.size()) {
                            speakCurrent();
                        } else {
                            stopReading();
                        }
                    });
                }
            });
            ttsReady = true;
            if (pendingSpeak && isPlaying) {
                pendingSpeak = false;
                speakCurrent();
            }
        }
    }

    private void speakCurrent() {
        if (!isPlaying || !ttsReady || currentIndex < 0 || currentIndex >= sentences.size()) {
            stopReading();
            return;
        }
        String text = sentences.get(currentIndex);
        if (text == null || text.trim().isEmpty()) {
            currentIndex++;
            speakCurrent();
            return;
        }
        tts.setSpeechRate(speechRate);
        updateNotification((currentIndex + 1) + " / " + sentences.size() + " 문장 읽는 중");
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utt_" + currentIndex);
    }

    private void stopReading() {
        isPlaying = false;
        pendingSpeak = false;
        sentences.clear();
        if (tts != null) {
            tts.stop();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void startForegroundCompat(String text) {
        Notification notification = buildNotification(text);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(1, notification);
        }
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(1, buildNotification(text));
        }
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(
                this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, TtsService.class);
        stopIntent.setAction("ACTION_STOP");
        PendingIntent stopPi = PendingIntent.getService(
                this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Action stopAction = new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_pause), "정지", stopPi).build();

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("원고 오디오 재생 중")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(openPi)
                .setOngoing(true)
                .addAction(stopAction)
                .build();
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "원고 음성 낭독", NotificationManager.IMPORTANCE_LOW);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        isPlaying = false;
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
