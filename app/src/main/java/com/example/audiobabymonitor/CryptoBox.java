package com.example.audiobabymonitor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class CryptoBox {
    private static final byte[] DOMAIN = "AudioBabyMonitor:v1:".getBytes(StandardCharsets.UTF_8);
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    CryptoBox(String roomCode) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(DOMAIN);
        key = new SecretKeySpec(digest.digest(normalize(roomCode)), "AES");
    }

    byte[] encrypt(byte[] plain) throws Exception {
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        byte[] encrypted = cipher.doFinal(plain);
        byte[] packet = new byte[nonce.length + encrypted.length];
        System.arraycopy(nonce, 0, packet, 0, nonce.length);
        System.arraycopy(encrypted, 0, packet, nonce.length, encrypted.length);
        return packet;
    }

    byte[] decrypt(byte[] packet) throws Exception {
        if (packet.length < 29) throw new IllegalArgumentException("Encrypted packet is too short");
        byte[] nonce = new byte[12];
        System.arraycopy(packet, 0, nonce, 0, nonce.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        return cipher.doFinal(packet, nonce.length, packet.length - nonce.length);
    }

    static String roomId(String roomCode) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(normalize(roomCode));
        StringBuilder hex = new StringBuilder(32);
        for (int i = 0; i < 16; i++) hex.append(String.format("%02x", hash[i]));
        return hex.toString();
    }

    private static byte[] normalize(String roomCode) {
        return roomCode.trim().toUpperCase().getBytes(StandardCharsets.UTF_8);
    }
}
