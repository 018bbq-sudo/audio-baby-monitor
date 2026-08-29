package com.example.audiobabymonitor;

import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.net.ssl.SSLSocketFactory;

final class WebSocketConnection {
    interface Listener {
        void onBinary(byte[] payload);
        void onText(String text);
        void onClosed(Exception error);
    }

    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private final SecureRandom random = new SecureRandom();
    private final Listener listener;
    private Socket socket;
    private InputStream input;
    private OutputStream output;
    private volatile boolean open;

    WebSocketConnection(Listener listener) {
        this.listener = listener;
    }

    void connect(String address) throws Exception {
        URI uri = new URI(address);
        boolean secure = "wss".equalsIgnoreCase(uri.getScheme());
        if (!secure && !"ws".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Only ws:// and wss:// are supported");
        }
        int port = uri.getPort() >= 0 ? uri.getPort() : secure ? 443 : 80;
        socket = secure ? SSLSocketFactory.getDefault().createSocket() : new Socket();
        socket.connect(new InetSocketAddress(uri.getHost(), port), 10_000);
        socket.setTcpNoDelay(true);
        input = new BufferedInputStream(socket.getInputStream());
        output = new BufferedOutputStream(socket.getOutputStream());

        byte[] keyBytes = new byte[16];
        random.nextBytes(keyBytes);
        String key = Base64.encodeToString(keyBytes, Base64.NO_WRAP);
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
        String hostHeader = uri.getHost() + ((secure && port == 443) || (!secure && port == 80) ? "" : ":" + port);
        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + hostHeader + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        output.write(request.getBytes(StandardCharsets.US_ASCII));
        output.flush();

        String headers = readHttpHeaders();
        if (!headers.startsWith("HTTP/1.1 101") && !headers.startsWith("HTTP/1.0 101")) {
            throw new IllegalStateException("WebSocket server rejected connection: " + headers.split("\r\n")[0]);
        }
        String expected = Base64.encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).getBytes(StandardCharsets.US_ASCII)),
                Base64.NO_WRAP);
        if (!headers.toLowerCase().contains("sec-websocket-accept: " + expected.toLowerCase())) {
            throw new IllegalStateException("Invalid WebSocket handshake");
        }

        open = true;
        Thread reader = new Thread(this::readLoop, "websocket-reader");
        reader.setDaemon(true);
        reader.start();
    }

    boolean isOpen() {
        return open;
    }

    void sendBinary(byte[] payload) throws Exception {
        sendFrame(2, payload);
    }

    void close() {
        if (!open && socket == null) return;
        try {
            if (open) sendFrame(8, new byte[0]);
        } catch (Exception ignored) {
        }
        open = false;
        try {
            socket.close();
        } catch (Exception ignored) {
        }
    }

    private synchronized void sendFrame(int opcode, byte[] payload) throws Exception {
        if (!open && opcode != 8) throw new EOFException("WebSocket is closed");
        output.write(0x80 | opcode);
        int length = payload.length;
        if (length < 126) {
            output.write(0x80 | length);
        } else if (length <= 65535) {
            output.write(0x80 | 126);
            output.write((length >> 8) & 0xff);
            output.write(length & 0xff);
        } else {
            output.write(0x80 | 127);
            for (int shift = 56; shift >= 0; shift -= 8) output.write((length >> shift) & 0xff);
        }
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        output.write(mask);
        for (int i = 0; i < payload.length; i++) output.write(payload[i] ^ mask[i & 3]);
        output.flush();
    }

    private void readLoop() {
        Exception failure = null;
        try {
            while (open) {
                int first = input.read();
                int second = input.read();
                if (first < 0 || second < 0) throw new EOFException("Connection closed");
                int opcode = first & 0x0f;
                boolean masked = (second & 0x80) != 0;
                long length = second & 0x7f;
                if (length == 126) length = (readByte() << 8) | readByte();
                if (length == 127) {
                    length = 0;
                    for (int i = 0; i < 8; i++) length = (length << 8) | readByte();
                }
                if (length > 1_048_576) throw new IllegalStateException("Frame is too large");
                byte[] mask = masked ? readExactly(4) : null;
                byte[] payload = readExactly((int) length);
                if (mask != null) {
                    for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
                }
                if (opcode == 2) listener.onBinary(payload);
                else if (opcode == 1) listener.onText(new String(payload, StandardCharsets.UTF_8));
                else if (opcode == 8) break;
                else if (opcode == 9) sendFrame(10, payload);
            }
        } catch (Exception error) {
            failure = error;
        } finally {
            open = false;
            try { socket.close(); } catch (Exception ignored) {}
            listener.onClosed(failure);
        }
    }

    private String readHttpHeaders() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int state = 0;
        while (bytes.size() < 16384) {
            int value = input.read();
            if (value < 0) throw new EOFException("Connection closed during handshake");
            bytes.write(value);
            if ((state == 0 || state == 2) && value == '\r') state++;
            else if ((state == 1 || state == 3) && value == '\n') state++;
            else state = value == '\r' ? 1 : 0;
            if (state == 4) return bytes.toString(StandardCharsets.US_ASCII.name());
        }
        throw new IllegalStateException("HTTP headers are too large");
    }

    private int readByte() throws Exception {
        int value = input.read();
        if (value < 0) throw new EOFException("Connection closed");
        return value;
    }

    private byte[] readExactly(int length) throws Exception {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(data, offset, length - offset);
            if (count < 0) throw new EOFException("Connection closed");
            offset += count;
        }
        return data;
    }
}
