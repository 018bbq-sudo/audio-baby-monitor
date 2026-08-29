package com.example.audiobabymonitor;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST = 42;
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private EditText serverUrl;
    private EditText roomCode;
    private CheckBox economyMode;
    private TextView status;
    private String pendingRole;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String message = intent.getStringExtra(MonitorService.EXTRA_STATUS);
            if (message != null) status.setText(message);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        serverUrl = findViewById(R.id.serverUrl);
        roomCode = findViewById(R.id.roomCode);
        economyMode = findViewById(R.id.economyMode);
        status = findViewById(R.id.status);
        Button startBaby = findViewById(R.id.startBaby);
        Button startParent = findViewById(R.id.startParent);
        Button stop = findViewById(R.id.stop);

        SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
        serverUrl.setText(prefs.getString("server", "ws://192.168.1.10:8080/audio"));
        roomCode.setText(prefs.getString("room", randomRoomCode()));
        economyMode.setChecked(prefs.getBoolean("economy", true));

        startBaby.setOnClickListener(v -> requestStart(MonitorService.ROLE_BABY));
        startParent.setOnClickListener(v -> requestStart(MonitorService.ROLE_PARENT));
        stop.setOnClickListener(v -> {
            Intent intent = new Intent(this, MonitorService.class);
            intent.setAction(MonitorService.ACTION_STOP);
            startService(intent);
            status.setText("Остановлено");
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(MonitorService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterReceiver(statusReceiver);
    }

    private void requestStart(String role) {
        String server = serverUrl.getText().toString().trim();
        String room = roomCode.getText().toString().trim().toUpperCase();
        if (!(server.startsWith("ws://") || server.startsWith("wss://"))) {
            Toast.makeText(this, "Адрес должен начинаться с ws:// или wss://", Toast.LENGTH_LONG).show();
            return;
        }
        if (room.length() < 8) {
            Toast.makeText(this, "Код комнаты должен содержать не менее 8 символов", Toast.LENGTH_LONG).show();
            return;
        }

        saveSettings(server, room);
        List<String> missing = new ArrayList<>();
        if (MonitorService.ROLE_BABY.equals(role)
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        if (!missing.isEmpty()) {
            pendingRole = role;
            requestPermissions(missing.toArray(new String[0]), PERMISSION_REQUEST);
        } else {
            startMonitor(role);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQUEST || pendingRole == null) return;
        if (MonitorService.ROLE_BABY.equals(pendingRole)
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Без доступа к микрофону режим детской не работает", Toast.LENGTH_LONG).show();
            pendingRole = null;
            return;
        }
        String role = pendingRole;
        pendingRole = null;
        startMonitor(role);
    }

    private void startMonitor(String role) {
        Intent intent = new Intent(this, MonitorService.class);
        intent.setAction(MonitorService.ACTION_START);
        intent.putExtra(MonitorService.EXTRA_ROLE, role);
        intent.putExtra(MonitorService.EXTRA_SERVER, serverUrl.getText().toString().trim());
        intent.putExtra(MonitorService.EXTRA_ROOM, roomCode.getText().toString().trim().toUpperCase());
        intent.putExtra(MonitorService.EXTRA_ECONOMY, economyMode.isChecked());
        startForegroundService(intent);
        status.setText(MonitorService.ROLE_BABY.equals(role)
                ? "Запускаю микрофон…" : "Подключаю прослушивание…");
    }

    private void saveSettings(String server, String room) {
        getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putString("server", server)
                .putString("room", room)
                .putBoolean("economy", economyMode.isChecked())
                .apply();
    }

    private static String randomRoomCode() {
        SecureRandom random = new SecureRandom();
        StringBuilder value = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            value.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return value.toString();
    }
}
