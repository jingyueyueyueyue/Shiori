package org.catsystem2.player;

import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.HttpsURLConnection;

/** Actual host consumer for the shared Rust Tweet protocol. All JNI calls stay
 * on the owning player HandlerThread, including receipts arriving in background.
 * No OAuth state, signing, tokens, or automatic post retries live in this UI. */
final class AndroidTweetHost {
    private static native byte[] nativeTake(long session);
    private static native int nativeComplete(long session, long request, int outcome, int value, byte[] bytes);
    private static native int nativeCancel(long session);
    static native int nativeCount(byte[] text, byte[] suffix);
    private static final int RESPONSE_LIMIT = 1024 * 1024;
    private static final String KEY_ALIAS = "cs2-tweet-private-v1";
    private static final Object KEY_LOCK = new Object();
    static final ConcurrentHashMap<String, Editor> EDITORS = new ConcurrentHashMap<>();

    static final class Editor {
        final AndroidTweetHost host;
        final long request;
        final String key, title, prompt, suffix;
        final int limit;
        String draft;
        boolean completed;
        WeakReference<TweetActivity> activity = new WeakReference<>(null);
        Editor(AndroidTweetHost host, long request, String title, String prompt, String text, int limit, String suffix) {
            this.host = host; this.request = request; this.title = title;
            this.prompt = prompt; this.draft = text; this.limit = limit;
            this.suffix = suffix;
            this.key = host.identity + ":" + request;
        }
        void forget() {
            if (completed || suffix == null) return;
            completed = true; EDITORS.remove(key, this);
            host.complete(request, 3, 0, new byte[0]);
        }
        void finish(boolean accepted, String text) {
            if (completed) return;
            completed = true;
            EDITORS.remove(key, this);
            if (accepted && text.length() > limit) {
                host.error(request, "Editor result exceeds UTF16 limit");
            } else {
                host.complete(request, accepted ? 0 : 1, 0,
                    accepted ? text.getBytes(StandardCharsets.UTF_8) : new byte[0]);
            }
        }
    }

    private final PlayerEngineActivity owner;
    private final Handler engine, ui;
    private final long session;
    private final Consumer<Exception> failure;
    private final String identity = UUID.randomUUID().toString();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Set<HttpsURLConnection> connections = ConcurrentHashMap.newKeySet();
    private final ArrayDeque<Runnable> foregroundWork = new ArrayDeque<>(); // UI thread only
    private boolean foreground, browserReturnRequired, browserPaused;
    private volatile boolean closed;

    AndroidTweetHost(PlayerEngineActivity owner, Handler engine, Handler ui, long session,
                     Consumer<Exception> failure) {
        this.owner = owner; this.engine = engine; this.ui = ui;
        this.session = session; this.failure = failure;
        ui.post(() -> { foreground = owner.tweetForeground(); drainForeground(); });
        engine.post(poll);
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (closed) return;
            try {
                for (int i = 0; i < 32; i++) {
                    byte[] bytes = nativeTake(session);
                    if (bytes == null) break;
                    receive(bytes);
                }
                // Deliberately independent of nativeStep, Surface and onPause.
                engine.postDelayed(this, 100);
            } catch (Exception error) {
                // Losing a bridge is fatal; do not leave a worker asleep forever.
                close();
                failure.accept(error);
            }
        }
    };

    void resumed() {
        ui.post(() -> {
            foreground = true;
            if (browserReturnRequired && browserPaused) browserReturnRequired = false;
            drainForeground();
        });
    }
    void paused() {
        ui.post(() -> { foreground = false; if (browserReturnRequired) browserPaused = true; });
    }
    private void drainForeground() {
        if (closed || !foreground || browserReturnRequired) return;
        Runnable action = foregroundWork.poll();
        if (action != null) action.run();
    }
    private void foreground(Runnable action) {
        ui.post(() -> { if (!closed) { foregroundWork.add(action); drainForeground(); } });
    }

    private void receive(byte[] bytes) throws Exception {
        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (data.remaining() < 20 || data.getInt() != 1) throw new IOException("Invalid Tweet protocol version/header");
        long request = data.getLong();
        data.getInt(); // task identity, not an Activity handle
        int operation = data.getInt();
        try {
            switch (operation) {
                case 1: {
                    String method = text(data, 8), url = text(data, 4096);
                    String authorization = text(data, 16384), content = text(data, 1024);
                    byte[] body = field(data, 65 * 1024 * 1024);
                    exhausted(data);
                    io.execute(() -> https(request, method, url, authorization, content, body));
                    break;
                }
                case 2: case 6: {
                    if (data.remaining() < 4) throw new IOException("Truncated Tweet edit limit");
                    int limit = data.getInt();
                    if (limit < 1 || limit > 1023) throw new IOException("Invalid Tweet edit limit");
                    String title = text(data, 4096), prompt = text(data, 65536), initial = text(data, 4092);
                    String suffix = operation == 6 ? text(data, 4092) : null;
                    exhausted(data);
                    if (initial.length() > limit) throw new IOException("Tweet initial text exceeds UTF16 limit");
                    foreground(() -> {
                        Editor editor = new Editor(this, request, title, prompt, initial, limit, suffix);
                        EDITORS.put(editor.key, editor);
                        try {
                            owner.startActivity(new Intent(owner, TweetActivity.class).putExtra("request", editor.key));
                            // Serialize editors even before onPause is dispatched.
                            foreground = false;
                        } catch (Exception error) {
                            EDITORS.remove(editor.key, editor);
                            error(request, "Unable to open Tweet editor: " + error.getClass().getSimpleName());
                            drainForeground();
                        }
                    });
                    break;
                }
                case 3: {
                    String url = text(data, 4096); exhausted(data);
                    URI parsed = new URI(url);
                    if (!"https".equals(parsed.getScheme()) || !"api.twitter.com".equals(parsed.getHost())
                        || parsed.getPort() != -1 || parsed.getRawUserInfo() != null || parsed.getRawFragment() != null
                        || !"/oauth/authorize".equals(parsed.getRawPath())
                        || parsed.getRawQuery() == null || !parsed.getRawQuery().matches("oauth_token=[A-Za-z0-9._~%\\-]+")) {
                        throw new IOException("Unexpected Tweet authorization URL");
                    }
                    foreground(() -> {
                        try {
                            browserReturnRequired = true; browserPaused = false;
                            owner.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                            // This acknowledges only ACTION_VIEW, never OAuth or posting.
                            complete(request, 0, 0, new byte[0]);
                        } catch (Exception error) {
                            browserReturnRequired = false;
                            error(request, "Authorization browser unavailable: " + error.getClass().getSimpleName());
                            drainForeground();
                        }
                    });
                    break;
                }
                case 4: case 5: {
                    byte[] value = field(data, 32768); exhausted(data);
                    io.execute(() -> protect(request, value, operation == 4));
                    break;
                }
                default: throw new IOException("Unimplemented Tweet host operation");
            }
        } catch (Exception error) {
            error(request, "Tweet host request rejected: " + error.getClass().getSimpleName());
        }
    }
    private static byte[] field(ByteBuffer data, int limit) throws IOException {
        if (data.remaining() < 4) throw new IOException("Truncated Tweet field");
        int size = data.getInt();
        if (size < 0 || size > limit || size > data.remaining()) throw new IOException("Invalid Tweet field size");
        byte[] result = new byte[size]; data.get(result); return result;
    }
    private static String text(ByteBuffer data, int limit) throws Exception {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(field(data, limit))).toString();
    }
    private static void exhausted(ByteBuffer data) throws IOException {
        if (data.hasRemaining()) throw new IOException("Trailing Tweet operation data");
    }

    private void https(long request, String method, String url, String authorization, String content, byte[] body) {
        HttpsURLConnection connection = null;
        try {
            if (closed) return;
            URI uri = new URI(url);
            if (!"https".equals(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getPort() != -1
                || uri.getRawFragment() != null || uri.getRawQuery() != null
                || !("api.twitter.com".equals(uri.getHost()) || "upload.twitter.com".equals(uri.getHost()))
                || !("GET".equals(method) || "POST".equals(method))
                || authorization.indexOf('\r') >= 0 || authorization.indexOf('\n') >= 0
                || content.indexOf('\r') >= 0 || content.indexOf('\n') >= 0) {
                throw new IOException("Unexpected Tweet HTTPS request");
            }
            connection = (HttpsURLConnection) uri.toURL().openConnection();
            connections.add(connection);
            if (closed) return;
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(30000); connection.setReadTimeout(60000);
            connection.setUseCaches(false); connection.setRequestMethod(method);
            connection.setRequestProperty("Authorization", authorization);
            connection.setRequestProperty("Content-Type", content);
            connection.setRequestProperty("Connection", "close");
            if ("POST".equals(method)) {
                connection.setDoOutput(true); connection.setFixedLengthStreamingMode(body.length);
                try (java.io.OutputStream output = connection.getOutputStream()) { output.write(body); }
            } else if (body.length != 0) throw new IOException("Tweet GET has a body");
            int status = connection.getResponseCode();
            InputStream incoming = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (incoming != null) try (InputStream input = incoming) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (closed) return;
                    if (output.size() + count > RESPONSE_LIMIT) throw new IOException("Tweet response exceeds1 MiB");
                    output.write(buffer, 0, count);
                }
            }
            complete(request, 0, status, output.toByteArray());
        } catch (Exception error) {
            // A response may have been lost after the server accepted a post.
            // Never retry or include the credential-bearing URL/headers in logs.
            error(request, "Tweet HTTPS failed; not retried: " + error.getClass().getSimpleName());
        } finally {
            Arrays.fill(body, (byte)0);
            if (connection != null) { connections.remove(connection); connection.disconnect(); }
        }
    }

    private static SecretKey privateKey() throws Exception {
        synchronized (KEY_LOCK) {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
            if (!store.containsAlias(KEY_ALIAS)) {
                KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
                generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
                return generator.generateKey();
            }
            return (SecretKey)store.getKey(KEY_ALIAS, null);
        }
    }
    private void protect(long request, byte[] input, boolean seal) {
        try {
            if (closed) return;
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] result;
            if (seal) {
                cipher.init(Cipher.ENCRYPT_MODE, privateKey());
                byte[] nonce = cipher.getIV(), encrypted = cipher.doFinal(input);
                if (nonce.length != 12) throw new IOException("Unexpected Tweet GCM nonce size");
                result = ByteBuffer.allocate(16 + encrypted.length).put(new byte[]{'A','K','G','1'})
                    .put(nonce).put(encrypted).array();
            } else {
                if (input.length < 32 || input[0] != 'A' || input[1] != 'K' || input[2] != 'G' || input[3] != '1') {
                    throw new IOException("Unsupported private Tweet credential envelope");
                }
                cipher.init(Cipher.DECRYPT_MODE, privateKey(), new GCMParameterSpec(128, input, 4, 12));
                result = cipher.doFinal(input, 16, input.length - 16);
            }
            complete(request, 0, 0, result);
        } catch (Exception error) {
            error(request, "Private Tweet credential protection failed: " + error.getClass().getSimpleName());
        } finally { Arrays.fill(input, (byte)0); }
    }
    private void complete(long request, int outcome, int value, byte[] result) {
        engine.post(() -> {
            try {
                if (!closed && nativeComplete(session, request, outcome, value, result) < 0) {
                    close(); failure.accept(new IOException("Tweet host receipt rejected"));
                }
            } finally { Arrays.fill(result, (byte)0); }
        });
    }
    private void error(long request, String error) { complete(request, 2, 0, error.getBytes(StandardCharsets.UTF_8)); }

    /** Engine thread: cancellation in cs2_android_close wakes the Rust worker.
     * UI/network shutdown never waits for an Activity receipt or posts a retry. */
    void close() {
        if (closed) return;
        closed = true; engine.removeCallbacks(poll);
        nativeCancel(session);
        io.shutdownNow();
        for (HttpsURLConnection connection : connections) connection.disconnect();
        ui.post(() -> {
            foregroundWork.clear();
            for (Editor editor : EDITORS.values()) if (editor.host == this) {
                editor.completed = true; EDITORS.remove(editor.key, editor);
                TweetActivity activity = editor.activity.get();
                if (activity != null) activity.finish();
            }
        });
    }
}
