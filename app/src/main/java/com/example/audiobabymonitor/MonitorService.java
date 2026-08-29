package com.example.audiobabymonitor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

public class MonitorService extends Service {
    static final String ACTION_START = "com.example.audiobabymonitor.START";
    static final String ACTION_STOP = "com.example.audiobabymonitor.STOP";
    static final String ACTION_STATUS = "com.example.audiobabymonitor.STATUS";
    static final String EXTRA_ROLE = "role";
    static final String EXTRA_SERVER = "server";
    static final String EXTRA_ROOM = "room";
    static final String EXTRA_ECONOMY = "economy";
    static final String EXTRA_STATUS = "status";
    static final String ROLE_BABY = "baby";
    static final String ROLE_PARENT = "parent";

    private static final String CHANNEL_ID = "audio_monitor";
    private static final int NOTIFICATION_ID = 7;
    private static final int SAMPLE_RATE = 16_000;
    // 641 samples make the first predictor plus 640 ADPCM nibbles an exact frame.
    private static final int FRAME_SAMPLES = 641;
    private static final double SILENCE_RMS = 280.0;

    private volatile boolean running;
    private Thread worker;
    private WebSocketConnection connection;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "Работа аудиорадионяни", NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopMonitor();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;

        String role = intent.getStringExtra(EXTRA_ROLE);
        String server = intent.getStringExtra(EXTRA_SERVER);
        String room = intent.getStringExtra(EXTRA_ROOM);
        boolean economy = intent.getBooleanExtra(EXTRA_ECONOMY, true);
        if ((!ROLE_BABY.equals(role) && !ROLE_PARENT.equals(role)) || server == null || room == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        stopMonitor();
        startAsForeground(role);
        running = true;
        worker = new Thread(() -> runMonitor(role, server, room, economy), "audio-monitor");
        worker.start();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopMonitor();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void runMonitor(String role, String server, String room, boolean economy) {
        while (running) {
            try {
                CryptoBox crypto = new CryptoBox(room);
                String separator = server.contains("?") ? "&" : "?";
                String address = server + separator
                        + "role=" + URLEncoder.encode(role, StandardCharsets.UTF_8.name())
                        + "&room=" + CryptoBox.roomId(room);
                ArrayBlockingQueue<byte[]> audioQueue = new ArrayBlockingQueue<>(12);
                connection = new WebSocketConnection(new WebSocketConnection.Listener() {
                    @Override
                    public void onBinary(byte[] payload) {
                        if (!audioQueue.offer(payload)) {
                            audioQueue.poll();
                            audioQueue.offer(payload);
                        }
                    }

                    @Override
                    public void onText(String text) {
                        if ("BABY_ONLINE".equals(text)) publishStatus("Телефон в детской подключён — слушаю");
                        else if ("BABY_OFFLINE".equals(text)) publishStatus("Жду телефон в детской…");
                    }

                    @Override
                    public void onClosed(Exception error) {
                        if (running) publishStatus("Связь потеряна, переподключаюсь…");
                    }
                });
                publishStatus("Подключаюсь к серверу…");
                connection.connect(address);
                if (ROLE_BABY.equals(role)) runBaby(connection, crypto, economy);
                else runParent(connection, crypto, audioQueue);
            } catch (Exception error) {
                if (running) publishStatus("Нет связи: " + friendlyError(error) + ". Повтор через 3 с");
            } finally {
                if (connection != null) connection.close();
                connection = null;
            }
            if (running) SystemClock.sleep(3_000);
        }
    }

    private void runBaby(WebSocketConnection ws, CryptoBox crypto, boolean economy) throws Exception {
        int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord record = new AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.DEFAULT)
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build())
                .setBufferSizeInBytes(Math.max(minimum, FRAME_SAMPLES * 4))
                .build();
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            throw new IllegalStateException("микрофон не инициализирован");
        }
        short[] frame = new short[FRAME_SAMPLES];
        long lastSent = 0;
        try {
            record.startRecording();
            publishStatus(economy
                    ? "Детская: передаю звук, в тишине экономлю"
                    : "Детская: непрерывно передаю звук");
            while (running && ws.isOpen()) {
                int offset = 0;
                while (offset < frame.length && running) {
                    int read = record.read(frame, offset, frame.length - offset, AudioRecord.READ_BLOCKING);
                    if (read < 0) throw new IllegalStateException("ошибка чтения микрофона: " + read);
                    offset += read;
                }
                long now = SystemClock.elapsedRealtime();
                if (economy && rms(frame) < SILENCE_RMS && now - lastSent < 1_000) continue;
                byte[] encoded = ImaAdpcm.encode(frame, frame.length);
                ws.sendBinary(crypto.encrypt(encoded));
                lastSent = now;
            }
        } finally {
            try { record.stop(); } catch (Exception ignored) {}
            record.release();
        }
    }

    private void runParent(WebSocketConnection ws, CryptoBox crypto,
                           ArrayBlockingQueue<byte[]> queue) throws Exception {
        int minimum = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(Math.max(minimum, FRAME_SAMPLES * 8))
                .build();
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            track.release();
            throw new IllegalStateException("динамик не инициализирован");
        }
        try {
            track.play();
            publishStatus("Родитель: подключено, жду звук из детской…");
            while (running && ws.isOpen()) {
                byte[] packet = queue.poll(1, TimeUnit.SECONDS);
                if (packet == null) continue;
                try {
                    short[] pcm = ImaAdpcm.decode(crypto.decrypt(packet));
                    if (pcm.length > 0) track.write(pcm, 0, pcm.length, AudioTrack.WRITE_BLOCKING);
                } catch (Exception ignored) {
                    publishStatus("Получен звук с другим кодом комнаты");
                }
            }
        } finally {
            try { track.stop(); } catch (Exception ignored) {}
            track.release();
        }
    }

    private void startAsForeground(String role) {
        boolean baby = ROLE_BABY.equals(role);
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent open = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, MonitorService.class).setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_monitor)
                .setContentTitle(baby ? "Аудиорадионяня: детская" : "Аудиорадионяня: родитель")
                .setContentText(baby ? "Микрофон включён, идёт передача" : "Идёт прослушивание")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Остановить", stop).build())
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            int type = baby ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    : ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
            startForeground(NOTIFICATION_ID, notification, type);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void stopMonitor() {
        running = false;
        if (connection != null) connection.close();
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    private void publishStatus(String message) {
        Intent intent = new Intent(ACTION_STATUS).setPackage(getPackageName());
        intent.putExtra(EXTRA_STATUS, message);
        sendBroadcast(intent);
    }

    private static double rms(short[] samples) {
        double sum = 0;
        for (short sample : samples) sum += (double) sample * sample;
        return Math.sqrt(sum / samples.length);
    }

    private static String friendlyError(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return error.getClass().getSimpleName();
        return message.length() > 100 ? message.substring(0, 100) : message;
    }
}
