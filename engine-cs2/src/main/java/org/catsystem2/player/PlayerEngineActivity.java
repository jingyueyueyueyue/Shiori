package org.catsystem2.player;

import static org.catsystem2.player.PlayerActivity.*;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.text.InputType;
import android.text.InputFilter;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ListView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.webkit.MimeTypeMap;

import androidx.activity.ComponentActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Game loop and Android platform adapters used by the Kotlin activity. */
public abstract class PlayerEngineActivity extends ComponentActivity implements SurfaceHolder.Callback {

    public static final String EXTRA_GAME_PATH = "extra_game_path";
    public static final String EXTRA_SAVES_PATH = "extra_saves_path";
    public static final String EXTRA_GAME_TITLE = "extra_game_title";
    public static final String EXTRA_LANGUAGE = "extra_language";
    public static final String EXTRA_ISOLATED = "extra_isolated";
    public static final String EXTRA_HUD_ENABLED = "extra_hud_enabled";

    private static final int SELECT_GAME = 1;
    private static final int EXPORT_DIAGNOSTICS = 2;
    private static final long DIAGNOSTIC_INTERVAL_MS = 5000;
    private static final long DIAGNOSTIC_LOG_LIMIT = 4 * 1024 * 1024;
    private static final int FRAME_CHANGED = 1;
    private static final int FRAME_UNCHANGED = 4;
    private static final int FRAME_MOVIE = 5;
    private static final int GAME_MENU_EFFECT_SETTINGS = 40027;
    private static final int GAME_MENU_MESSAGE_LIST = 40047;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean importing = new AtomicBoolean(false);
    private final AtomicBoolean hudSamplePending = new AtomicBoolean(false);
    private final Object surfaceLock = new Object();
    private boolean surfaceNeedsDetach;
    private HandlerThread playerThread;
    private Handler worker;
    private HandlerThread presenterThread;
    private Handler presenter;
    private volatile AndroidTweetHost tweetHost;
    private FrameLayout root;
    private SurfaceView surfaceView;
    private GamePointer gamePointer;
    private LinearLayout panel;
    private TextView status;
    private GameLibrary library;
    private GridView gameShelf;
    private long libraryRefreshGeneration;
    private File isolatedSaveRoot;
    private Button addFolder;
    private Button resumeGame;
    private Button exitGame;
    private Button keyboard;
    private FrameLayout playbackControls;
    private long heldCtrlSession;
    private boolean exportingDiagnostics;
    private boolean debugDiagnostics;
    private long nextDiagnostic;
    private volatile long nativeStepStarted;
    private long reportedStep;
    private long gameKeyboardSession;
    private boolean gameKeyboardWasVisible;
    private long inputConnectionId;
    private boolean gameReady;
    private boolean endingGame;
    private boolean externalLaunch;
    private String launchingTitle;
    private Uri pendingFolder;
    private AlertDialog exitDialog;
    private volatile long playGeneration;
    private volatile boolean paused = true;
    private volatile RenderSurface renderSurface;
    private volatile boolean destroyed;
    private volatile boolean audioFocusGranted;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocus;
    private volatile int logicalWidth;
    private volatile int logicalHeight;
    private int activePointerId = -1;
    private long pointerDownTime;
    private int pointerButton;
    // UI-owned inspectors. Their actions are posted back to the engine owner.
    private long messageUiSession;
    private boolean gameFullscreen;
    private volatile int generalWindowPolicy = -1;
    private String generalCamouflageTitle;
    private CharSequence generalNormalTitle;
    private boolean generalStopped;
    private Button gameMenuButton;
    private AlertDialog effectSettingsDialog;
    private AlertDialog menuConfirmationDialog;
    private AlertDialog textEntryDialog;
    private long gameMenuRequest;
    private ArrayList<GameMenuItem> gameMenuItems;
    private final HashMap<Integer, MessageInspector> messageInspectors = new HashMap<>();
    private final HashMap<Integer, MessageInspector> sceneInspectors = new HashMap<>();
    private final HashMap<Long, AlertDialog> hostDialogs = new HashMap<>();
    private final ArrayList<AlertDialog> directoryDialogs = new ArrayList<>();
    // The following fields are only accessed on playerThread.
    private long session;
    private long sessionGeneration;
    private long activeInputConnectionId;
    private boolean running;
    private boolean frameScheduled;
    private long nextFrame;
    private boolean presenting;
    private long latestFrame;
    private long postedFrame;
    private long movieFrame;
    private long nextPresent;
    private RenderSurface presentedSurface;
    private int frameWidth, frameHeight;
    private final int[] pointerOutput = new int[2];
    // Only the one outstanding presentation may write this receipt.
    private final long[] presentationReceipt = new long[7];

    private static final class RenderSurface {
        final Surface surface;

        RenderSurface(Surface surface) { this.surface = surface; }
    }

    protected abstract View createPerformanceAction();
    protected abstract void onGameViewVisibilityChanged(boolean visible);

    /** These counters are advanced on the engine thread. */
    protected abstract void onGameTickCompleted();
    protected abstract void onGameFrameSubmitted();

    protected final void setGameHudCollection(boolean enabled) {
        if (worker != null) worker.post(() -> nativeSetHudEnabled(enabled));
        if (presenter != null) presenter.post(() -> nativeSetDisplayStatsEnabled(enabled));
    }

    protected final boolean hasGameMonitorSurface() {
        return renderSurface != null && !paused && !destroyed;
    }

    protected final void requestGameHudSample(java.util.function.Consumer<long[]> callback) {
        if (worker == null || destroyed || !hudSamplePending.compareAndSet(false, true)) return;
        if (!worker.post(() -> {
            final long handle = session;
            final RenderSurface target = renderSurface;
            if (handle == 0 || !running || paused || target == null || !presenter.post(() -> {
                long[] output = new long[16];
                if (paused || renderSurface != target || nativeReadDisplayStats(handle, output) != 1
                    || !worker.post(() -> {
                        try {
                            if (session == handle && !paused && renderSurface == target && nativeReadHud(output) == 1) {
                                callback.accept(output);
                            }
                        } finally {
                            hudSamplePending.set(false);
                        }
                    })) hudSamplePending.set(false);
            })) {
                hudSamplePending.set(false);
            }
        })) hudSamplePending.set(false);
    }

    protected final FrameLayout getPlayerRoot() { return root; }

    private void setLibraryVisible(boolean visible) {
        if (visible) releaseHeldCtrl();
        panel.setVisibility(visible ? View.VISIBLE : View.GONE);
        onGameViewVisibilityChanged(!visible);
    }

    @Override public void onCreate(Bundle state) {
        externalLaunch = getIntent().getStringExtra(EXTRA_GAME_PATH) != null;
        super.onCreate(state);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        playerThread = new HandlerThread("CS2 player");
        playerThread.start();
        worker = new Handler(playerThread.getLooper());
        debugDiagnostics = isDebuggable(this);
        worker.post(this::recordPreviousProcessExit);
        if (debugDiagnostics) ui.postDelayed(watchNativeStep, 1000);
        presenterThread = new HandlerThread("CS2 Surface");
        presenterThread.start();
        presenter = new Handler(presenterThread.getLooper());
        root = new FrameLayout(this);
        root.setBackgroundColor(0xff000000);
        surfaceView = new PlayerSurface();
        surfaceView.getHolder().addCallback(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        gamePointer = new GamePointer();
        root.addView(gamePointer, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        library = new GameLibrary(this);
        library.adoptPreviousImport(getSharedPreferences("player", MODE_PRIVATE));
        buildLibraryPanel();
        root.addView(panel, new FrameLayout.LayoutParams(-1, -1));
        root.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> resizeSurface());
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                if (gameKeyboardSession != 0) {
                    if (insets.isVisible(android.view.WindowInsets.Type.ime())) gameKeyboardWasVisible = true;
                    else if (gameKeyboardWasVisible) endGameKeyboard(false);
                }
                android.graphics.Insets visible = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                root.setPadding(visible.left, visible.top, visible.right, visible.bottom);
            } else {
                root.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            resizeSurface();
            return insets;
        });
        surfaceView.setOnTouchListener(this::touch);
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                this::onBackPressed);
        }
        audioManager = (AudioManager)getSystemService(AUDIO_SERVICE);
        audioFocus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(change -> {
                audioFocusGranted = change == AudioManager.AUDIOFOCUS_GAIN;
                worker.post(() -> {
                    if (session != 0 && nativeInput(session, 8, audioFocusGranted ? 0 : 1, 0) < 0) {
                        stopWithError("声音状态更新失败", new IOException(nativeErrorText()));
                    }
                });
            }, ui).build();
        if (externalLaunch) {
            String gamePath = getIntent().getStringExtra(EXTRA_GAME_PATH);
            String savesPath = getIntent().getStringExtra(EXTRA_SAVES_PATH);
            if (gamePath == null || savesPath == null) {
                showFailure("启动参数不完整", "缺少游戏目录或存档目录");
                return;
            }
            File game = new File(gamePath);
            File saves = new File(savesPath);
            String title = getIntent().getStringExtra(EXTRA_GAME_TITLE);
            if (title == null || title.trim().isEmpty()) title = game.getName();
            GameLibrary.Entry entry = new GameLibrary.Entry(saves.getName(), title, null, "", game, saves, "", null);
            setLibraryVisible(false);
            launch(entry, getIntent().getBooleanExtra(EXTRA_ISOLATED, false));
        } else {
            status.setText("正在检查游戏目录…");
            List<Uri> folders = library.persistedFolders();
            if (!folders.isEmpty()) scanFolders(folders, null);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView libraryText(CharSequence value, int size, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        return text;
    }

    private void buildLibraryPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(28), dp(20), dp(28), dp(16));
        panel.setBackgroundColor(0xff181b1d);
        panel.setClickable(true);
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = libraryText("CatSystem2", 21, 0xffeaf3ee);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        toolbar.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        keyboard = new Button(this);
        keyboard.setText("键盘");
        keyboard.setOnClickListener(view -> showGameKeyboard());
        toolbar.addView(keyboard);
        resumeGame = new Button(this);
        resumeGame.setText("继续游戏");
        resumeGame.setOnClickListener(view -> resumeFromLibrary());
        toolbar.addView(resumeGame);
        exitGame = new Button(this);
        exitGame.setText("退出游戏");
        exitGame.setOnClickListener(view -> confirmExit());
        toolbar.addView(exitGame);
        addFolder = new Button(this);
        addFolder.setText("添加文件夹");
        addFolder.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff9cddbd));
        addFolder.setTextColor(0xff132b22);
        addFolder.setOnClickListener(view -> chooseGame());
        toolbar.addView(addFolder);
        panel.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));

        TextView title = libraryText("游戏库", 29, 0xffeaf3ee);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(0, dp(22), 0, dp(4));
        LinearLayout libraryHeading = new LinearLayout(this);
        libraryHeading.setGravity(Gravity.CENTER_VERTICAL);
        libraryHeading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        libraryHeading.addView(createPerformanceAction());
        Button diagnostics = new Button(this);
        diagnostics.setText("导出诊断日志");
        diagnostics.setOnClickListener(view -> chooseDiagnosticsDestination());
        libraryHeading.addView(diagnostics);
        panel.addView(libraryHeading);
        status = libraryText("", 14, 0xffb2c4bb);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setPadding(0, 0, 0, dp(16));
        panel.addView(status);
        gameShelf = new GridView(this);
        gameShelf.setColumnWidth(dp(210));
        gameShelf.setNumColumns(GridView.AUTO_FIT);
        gameShelf.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        gameShelf.setHorizontalSpacing(dp(12));
        gameShelf.setVerticalSpacing(dp(12));
        gameShelf.setOnItemClickListener((parent, view, position, id) -> {
            if (gameShelf.getAdapter().isEnabled(position)) {
                launch((GameLibrary.Entry)parent.getItemAtPosition(position), false);
            }
        });
        gameShelf.setOnItemLongClickListener((parent, view, position, id) -> {
            if (gameShelf.getAdapter().isEnabled(position) && !importing.get()
                    && launchingTitle == null && !endingGame) {
                showGameLanguage((GameLibrary.Entry)parent.getItemAtPosition(position));
            }
            return true;
        });
        panel.addView(gameShelf, new LinearLayout.LayoutParams(-1, 0, 1));
        updateLibraryActions();
    }

    private void refreshLibrary(String notice) {
        if (destroyed || importing.get()) return;
        long refresh = ++libraryRefreshGeneration;
        new Thread(() -> {
            ArrayList<GameLibrary.Entry> games = library.entries();
            HashMap<String, GameLibrary.DirectoryStatus> unavailable = new HashMap<>();
            for (GameLibrary.Entry game : games) {
                if (destroyed) return;
                try {
                    library.gameDirectory(game);
                } catch (GameLibrary.DirectoryFailure error) {
                    unavailable.put(game.key, error.status);
                    logLibraryIssue("refresh", "check-directory", game, error);
                }
            }
            ui.post(() -> {
                if (destroyed || importing.get() || refresh != libraryRefreshGeneration) return;
                renderLibrary(games, unavailable);
                if (launchingTitle == null) {
                    String summary = games.isEmpty() ? "暂无游戏" : games.size() + " 个游戏";
                    if (!unavailable.isEmpty()) summary += " · " + unavailable.size() + " 个不可启动";
                    status.setText(notice == null ? summary : notice + " · " + summary);
                }
            });
        }, "CS2 library check").start();
    }

    private void renderLibrary(ArrayList<GameLibrary.Entry> games,
                               HashMap<String, GameLibrary.DirectoryStatus> unavailable) {
        gameShelf.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return games.size(); }
            @Override public GameLibrary.Entry getItem(int position) { return games.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public boolean areAllItemsEnabled() { return unavailable.isEmpty(); }
            @Override public boolean isEnabled(int position) { return !unavailable.containsKey(getItem(position).key); }
            @Override public View getView(int position, View recycled, android.view.ViewGroup parent) {
                GameLibrary.Entry game = getItem(position);
                LinearLayout card = new LinearLayout(PlayerEngineActivity.this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setGravity(Gravity.CENTER_VERTICAL);
                card.setPadding(dp(16), dp(16), dp(16), dp(16));
                card.setMinimumHeight(dp(112));
                GradientDrawable background = new GradientDrawable();
                background.setColor(0xff272e2c);
                background.setCornerRadius(dp(8));
                card.setBackground(background);
                TextView name = libraryText(game.title, 16, 0xffeaf3ee);
                name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                name.setMinLines(2);
                name.setMaxLines(2);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                card.addView(name);
                GameLibrary.DirectoryStatus issue = unavailable.get(game.key);
                if (issue == null) {
                    card.addView(libraryText(game.tree == null ? "旧版副本" : "从原目录打开", 12, 0xffb2c4bb));
                } else {
                    card.addView(libraryText(issue.label + " · 不可启动", 12, 0xfff3bb99));
                }
                return card;
            }
        });
        updateLibraryActions();
    }

    private void updateLibraryActions() {
        addFolder.setEnabled(!importing.get() && launchingTitle == null && !endingGame);
        resumeGame.setVisibility(gameReady ? View.VISIBLE : View.GONE);
        exitGame.setVisibility(gameReady ? View.VISIBLE : View.GONE);
        keyboard.setVisibility(gameReady ? View.VISIBLE : View.GONE);
    }

    private void chooseGame() {
        if (importing.get() || launchingTitle != null || endingGame) return;
        if (!ensureStorageAccess()) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        Uri initial = library.lastFolder();
        if (initial != null) intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial);
        startActivityForResult(intent, SELECT_GAME);
    }

    private boolean ensureStorageAccess() {
        if (library.hasStorageAccess()) return true;
        new AlertDialog.Builder(this)
            .setTitle("开启文件访问")
            .setMessage("允许文件访问后，播放器会直接读取所选目录里的游戏资源。")
            .setNegativeButton("取消", null)
            .setPositiveButton("继续", (dialog, which) -> {
                if (Build.VERSION.SDK_INT >= 30) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.fromParts("package", getPackageName(), null)));
                } else {
                    requestPermissions(new String[] {Manifest.permission.READ_EXTERNAL_STORAGE}, SELECT_GAME);
                }
            }).show();
        return false;
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (request == EXPORT_DIAGNOSTICS) {
            exportDiagnostics(data.getData());
            return;
        }
        if (request != SELECT_GAME) return;
        Uri tree = data.getData();
        int access = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        if (access != 0) {
            try {
                getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // The picker may have granted read access for this session only.
            }
        }
        if (importing.get()) pendingFolder = tree;
        else scanFolders(java.util.Collections.singletonList(tree), tree);
    }

    private File diagnosticsLog() {
        return new File(getFilesDir(), "player-error.log");
    }

    private synchronized void appendDiagnostic(String message) {
        File log = diagnosticsLog();
        if (log.length() >= DIAGNOSTIC_LOG_LIMIT
                && !log.renameTo(new File(getFilesDir(), "player-error.previous.log"))) {
            android.util.Log.w("CS2Player", "Could not rotate player diagnostics");
        }
        try (FileOutputStream output = new FileOutputStream(log, true)) {
            output.write((message + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {}
    }

    private void runtimeDiagnostic(String message) {
        String record = "time_ms=" + System.currentTimeMillis() + " pid=" + android.os.Process.myPid()
            + " " + message;
        android.util.Log.i("CS2Diag", record);
        appendDiagnostic(record);
    }

    private void recordPreviousProcessExit() {
        if (Build.VERSION.SDK_INT < 30) return;
        ActivityManager manager = getSystemService(ActivityManager.class);
        if (manager == null) return;
        android.content.SharedPreferences history = getSharedPreferences("diagnostic-history", MODE_PRIVATE);
        long last = history.getLong("exit_time", 0);
        long newest = last;
        for (ApplicationExitInfo exit : manager.getHistoricalProcessExitReasons(getPackageName(), 0, 8)) {
            long timestamp = exit.getTimestamp();
            if (timestamp <= last) continue;
            newest = Math.max(newest, timestamp);
            int reason = exit.getReason();
            if (reason == ApplicationExitInfo.REASON_CRASH || reason == ApplicationExitInfo.REASON_CRASH_NATIVE
                    || reason == ApplicationExitInfo.REASON_ANR || reason == ApplicationExitInfo.REASON_LOW_MEMORY) {
                runtimeDiagnostic("previous_exit time_ms=" + timestamp + " process=" + exit.getProcessName()
                    + " pid=" + exit.getPid() + " reason=" + reason + " status=" + exit.getStatus()
                    + " pss_kib=" + exit.getPss() + " rss_kib=" + exit.getRss()
                    + " description=" + exit.getDescription());
            }
        }
        if (newest > last) history.edit().putLong("exit_time", newest).apply();
    }

    private final Runnable watchNativeStep = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            long started = nativeStepStarted;
            long elapsed = SystemClock.uptimeMillis() - started;
            if (started != 0 && started != reportedStep && elapsed >= DIAGNOSTIC_INTERVAL_MS) {
                reportedStep = started;
                runtimeDiagnostic("native_step_wait elapsed_ms=" + elapsed);
            }
            ui.postDelayed(this, 1000);
        }
    };

    private void logLibraryIssue(String phase, String stage, GameLibrary.Entry entry, Exception error) {
        String source = entry.tree == null ? "legacyPath=" + entry.game.getAbsolutePath()
            : "tree=" + entry.tree + " document=" + entry.document;
        String message = "library phase=" + phase + " stage=" + stage + " key=" + entry.key
            + " title=" + entry.title + " " + source + " error=" + error;
        android.util.Log.w("CS2Player", message);
        appendDiagnostic(message);
    }

    private void chooseDiagnosticsDestination() {
        if (exportingDiagnostics) return;
        if (diagnosticsLog().length() == 0) {
            Toast.makeText(this, "当前没有可导出的诊断日志", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("导出诊断日志")
            .setMessage("诊断日志可能包含游戏路径。请选择保存位置，分享前请检查文件内容。")
            .setNegativeButton("取消", null)
            .setPositiveButton("选择位置", (dialog, which) -> {
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TITLE, "cs2-player-diagnostics.txt");
                try {
                    startActivityForResult(intent, EXPORT_DIAGNOSTICS);
                } catch (android.content.ActivityNotFoundException error) {
                    Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_LONG).show();
                }
            }).show();
    }

    private void exportDiagnostics(Uri destination) {
        if (exportingDiagnostics) return;
        exportingDiagnostics = true;
        new Thread(() -> {
            String result;
            try (FileInputStream input = new FileInputStream(diagnosticsLog())) {
                long remaining = input.getChannel().size();
                if (remaining == 0) throw new IOException("诊断日志为空");
                try (OutputStream output = getContentResolver().openOutputStream(destination, "wt")) {
                    if (output == null) throw new IOException("无法写入所选文件");
                    byte[] buffer = new byte[8192];
                    while (remaining > 0) {
                        int count = input.read(buffer, 0, (int)Math.min(buffer.length, remaining));
                        if (count < 0) throw new IOException("诊断日志在导出期间发生变化");
                        output.write(buffer, 0, count);
                        remaining -= count;
                    }
                }
                result = "诊断日志已导出";
            } catch (IOException | SecurityException error) {
                result = "诊断日志导出失败";
                android.util.Log.w("CS2Player", result, error);
            }
            String message = result;
            ui.post(() -> {
                exportingDiagnostics = false;
                if (!destroyed) Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            });
        }, "CS2 diagnostics export").start();
    }

    private void scanFolders(List<Uri> trees, Uri selected) {
        if (!importing.compareAndSet(false, true)) return;
        status.setText("正在扫描文件夹…");
        updateLibraryActions();
        new Thread(() -> {
            try {
                GameLibrary.ScanResult scanned = library.scan(trees, selected);
                ui.post(() -> {
                    if (destroyed) return;
                    importing.set(false);
                    if (scanPendingFolder()) return;
                    updateLibraryActions();
                    String notice = selected != null && scanned.found == 0
                        ? "文件夹内未找到 CatSystem2 游戏" : null;
                    if (scanned.unreadable != 0) notice = (notice == null ? "" : notice + " · ")
                        + scanned.unreadable + " 个目录无法扫描";
                    refreshLibrary(notice);
                });
            } catch (Exception error) {
                ui.post(() -> {
                    if (destroyed) return;
                    importing.set(false);
                    if (!scanPendingFolder()) fail("扫描文件夹失败", error);
                });
            }
        }, "CS2 game scan").start();
    }

    private boolean scanPendingFolder() {
        if (pendingFolder == null) return false;
        Uri tree = pendingFolder;
        pendingFolder = null;
        scanFolders(java.util.Collections.singletonList(tree), tree);
        return true;
    }

    private static boolean isDebuggable(Context context) {
        return (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    private static File isolatedProfiles(Context context) throws IOException {
        File cache = context.getCacheDir();
        if (cache == null) throw new IOException("缓存目录不可用");
        return GameFilesProvider.checkedFile(cache, new File(cache, "isolated-profiles"));
    }

    private void confirmIsolatedLaunch(GameLibrary.Entry entry) {
        new AlertDialog.Builder(this)
            .setTitle("隔离验收：" + entry.title)
            .setMessage("本次使用全新的临时存档，不读取或覆盖正式存档。")
            .setNegativeButton("取消", null)
            .setPositiveButton("隔离启动", (dialog, which) -> launch(entry, true))
            .show();
    }

    private void showGameLanguage(GameLibrary.Entry entry) {
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
            .setTitle(entry.title + " · 语种")
            .setSingleChoiceItems(new String[] {"自动", "日语原文", "本地汉化"}, library.language(entry),
                (window, language) -> {
                    library.setLanguage(entry, language);
                    window.dismiss();
                })
            .setNegativeButton("关闭", null);
        if (isDebuggable(this)) {
            dialog.setNeutralButton("隔离启动", (window, which) -> confirmIsolatedLaunch(entry));
        }
        dialog.show();
    }

    private void launch(GameLibrary.Entry entry, boolean isolated) {
        if (importing.get() || launchingTitle != null || endingGame) return;
        if (isolated && !isDebuggable(this) && !externalLaunch) return;
        long generation = playGeneration;
        String title = isolated ? entry.title + "（隔离验收）" : entry.title;
        String phase = isolated ? "launch-isolated" : "launch";
        launchingTitle = title;
        updateLibraryActions();
        status.setText("正在准备 " + title + "…");
        new Thread(() -> {
            String stage = "check-directory";
            try {
                File game = library.gameDirectory(entry);
                if (!isCurrent(generation)) return;
                stage = "prepare-saves";
                File saves;
                if (isolated) {
                    File root = isolatedProfiles(this);
                    Files.createDirectories(root.toPath());
                    saves = GameFilesProvider.checkedFile(root, Files.createTempDirectory(root.toPath(), "run-").toFile());
                } else {
                    saves = entry.saves;
                    if (!saves.isDirectory() && !saves.mkdirs() && !saves.isDirectory()) {
                        throw new IOException("无法创建存档目录: " + saves.getAbsolutePath());
                    }
                }
                ui.post(() -> {
                    if (!isCurrent(generation)) return;
                    long opening = ++playGeneration;
                    resetGameUi();
                    launchingTitle = title;
                    status.setText("正在启动 " + title + "…");
                    updateLibraryActions();
                    worker.post(this::closeSession);
                    open(entry, game, saves, isolated, opening);
                });
            } catch (Exception error) {
                logLibraryIssue(phase, stage, entry, error);
                ui.post(() -> {
                    if (!isCurrent(generation)) return;
                    launchingTitle = null;
                    if (error instanceof GameLibrary.DirectoryFailure) {
                        GameLibrary.DirectoryFailure failure = (GameLibrary.DirectoryFailure)error;
                        showFailure("准备游戏失败", failure.status.label);
                    } else {
                        showFailure("准备游戏失败", error.getMessage());
                    }
                });
            }
        }, "CS2 game open").start();
    }

    private boolean isCurrent(long generation) {
        return !destroyed && playGeneration == generation;
    }

    private void resetGameUi() {
        endGameKeyboard(true);
        if (exitDialog != null) exitDialog.dismiss();
        clearMessageInspectors();
        if (gameFullscreen) setGameFullscreen(false);
        launchingTitle = null;
        isolatedSaveRoot = null;
        gameReady = false;
        hidePlaybackControls();
        logicalWidth = logicalHeight = 0;
        activePointerId = -1;
        gamePointer.hide();
        gamePointer.setCursorVisible(true);
        PointerIcon cursor = PointerIcon.getSystemIcon(this, PointerIcon.TYPE_DEFAULT);
        surfaceView.setPointerIcon(cursor);
        gamePointer.setPointerIcon(cursor);
        setLibraryVisible(!externalLaunch);
        updateLibraryActions();
    }

    private void confirmExit() {
        if (!gameReady || exitDialog != null) return;
        exitDialog = new AlertDialog.Builder(this)
            .setTitle("退出当前游戏？")
            .setNegativeButton("取消", null)
            .setPositiveButton("退出", (dialog, which) -> {
                long generation = ++playGeneration;
                endingGame = true;
                resetGameUi();
                worker.post(() -> {
                    closeSession();
                    ui.post(() -> {
                        if (!isCurrent(generation)) return;
                        endingGame = false;
                        if (externalLaunch) {
                            finish();
                            return;
                        }
                        refreshLibrary("游戏已退出");
                        status.setText("游戏已退出");
                    });
                });
            }).create();
        exitDialog.setOnDismissListener(dialog -> exitDialog = null);
        exitDialog.show();
    }

    private void closeSession() {
        running = false;
        worker.removeCallbacks(frame);
        frameScheduled = false;
        latestFrame = postedFrame = movieFrame = nextPresent = 0;
        presentedSurface = null;
        long closing = session;
        session = 0;
        activeInputConnectionId = 0;
        AndroidTweetHost host = tweetHost;
        tweetHost = null;
        try {
            if (host != null) host.close();
        } catch (RuntimeException error) {
            android.util.Log.e("CS2Player", "Closing the Tweet host failed", error);
        } finally {
            if (closing != 0) presenter.post(this::resetPresentationSurface);
            if (closing != 0 && nativeClose(closing) < 0) {
                android.util.Log.e("CS2Player", "Closing the native session failed: " + nativeErrorText());
            }
        }
    }

    private void stopWithError(String title, Exception error) {
        long generation = sessionGeneration;
        closeSession();
        reportGameError(generation, title, error);
    }

    private void reportGameError(long generation, String title, Exception error) {
        ui.post(() -> {
            if (!isCurrent(generation)) return;
            ++playGeneration;
            resetGameUi();
            fail(title, error);
        });
    }

    private void open(GameLibrary.Entry entry, File game, File saves, boolean isolated, long generation) {
        worker.post(() -> {
            if (!isCurrent(generation)) return;
            sessionGeneration = generation;
            try {
                String mode = isolated ? "isolated" : "standard";
                String details = " mode=" + mode + " key=" + entry.key + " game=" + game.getAbsolutePath()
                    + " saves=" + saves.getAbsolutePath();
                int language = externalLaunch
                    ? Math.max(0, Math.min(2, getIntent().getIntExtra(EXTRA_LANGUAGE, 0)))
                    : library.language(entry);
                ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
                ActivityManager manager = getSystemService(ActivityManager.class);
                if (manager != null) manager.getMemoryInfo(memory);
                session = nativeOpen(game.getAbsolutePath().getBytes(StandardCharsets.UTF_8),
                    saves.getAbsolutePath().getBytes(StandardCharsets.UTF_8), language, memory.lowMemory ? 0 : memory.availMem);
                if (session == 0) {
                    IOException error = new IOException(nativeErrorText());
                    logLibraryIssue(isolated ? "launch-isolated" : "launch", "native-open" + details, entry, error);
                    throw error;
                }
                android.util.Log.i("CS2Player", "Opened " + game + " language=" + language);
                if (debugDiagnostics) {
                    nextDiagnostic = 0;
                    runtimeDiagnostic("opened session=" + session + " mode=" + mode + " key=" + entry.key
                        + " title=" + entry.title + " language=" + language);
                }
                if (isolated) {
                    String record = "library phase=launch stage=native-open result=opened" + details;
                    android.util.Log.i("CS2Player", record);
                    appendDiagnostic(record);
                }
                if (!isCurrent(generation)) {
                    closeSession();
                    return;
                }
                if (nativeInput(session, 8, audioFocusGranted ? 0 : 1, 0) < 0) {
                    throw new IOException(nativeErrorText());
                }
                if (nativeInput(session, 5, paused ? 0 : 1, 0) < 0) {
                    throw new IOException(nativeErrorText());
                }
                final int width = frameWidth = nativeSize(session, 0);
                final int height = frameHeight = nativeSize(session, 1);
                if (width <= 0 || height <= 0) throw new IOException("游戏画面尺寸无效");
                final long openedSession = session;
                tweetHost = new AndroidTweetHost(this, worker, ui, session,
                    error -> { if (session == openedSession) stopWithError("Tweet host failed", error); });
                ui.post(() -> {
                    if (!isCurrent(generation)) return;
                    isolatedSaveRoot = isolated ? saves : null;
                    messageUiSession = openedSession;
                    logicalWidth = width;
                    logicalHeight = height;
                    resizeSurface();
                    status.setText("正在启动 " + launchingTitle + "…");
                    boolean focused = hasWindowFocus();
                    worker.post(() -> {
                        if (session != openedSession || !isCurrent(generation)) return;
                        if (nativeInput(session, 11, focused ? 1 : 0, 0) < 0) {
                            stopWithError("Window focus failed", new IOException(nativeErrorText()));
                            return;
                        }
                        running = true;
                        nextFrame = SystemClock.uptimeMillis();
                        scheduleFrame();
                    });
                });
            } catch (Exception error) { stopWithError("打开游戏失败", error); }
        });
    }

    private void scheduleFrame() {
        if (frameScheduled || !running || paused || renderSurface == null
                || !isCurrent(sessionGeneration)) return;
        frameScheduled = true;
        worker.postAtTime(frame, Math.max(SystemClock.uptimeMillis(), nextFrame));
    }

    private void requestPresentation(RenderSurface target) {
        if (presenting || session == 0 || !running || paused || target == null || latestFrame == 0
            || !isCurrent(sessionGeneration) || (postedFrame == latestFrame && presentedSurface == target)) return;
        long generation = sessionGeneration;
        long handle = session;
        presenting = presenter.postAtTime(() -> {
            int result;
            synchronized (surfaceLock) {
                if (renderSurface != target || paused || !isCurrent(generation)
                    || !detachSurfaceLocked() || !target.surface.isValid()) {
                    result = -2;
                } else {
                    result = nativePresent(handle, target.surface, presentationReceipt);
                    if (result != 0 && !target.surface.isValid()) result = -2;
                }
            }
            final int status = result;
            final String failure = status != 0 && status != -2 ? nativeErrorText() : null;
            worker.post(() -> finishPresentation(generation, handle, target, status, failure));
        }, Math.max(SystemClock.uptimeMillis(), nextPresent));
    }

    private void finishPresentation(long generation, long handle, RenderSurface target, int result,
                                    String failure) {
        presenting = false;
        if (session != handle || !isCurrent(generation)) {
            requestPresentation(renderSurface);
            return;
        }
        if (result == -2) {
            nextPresent = SystemClock.uptimeMillis() + 16;
            requestPresentation(renderSurface);
            return;
        }
        if (result != 0) {
            stopWithError("游戏运行中断", new IOException("画面提交失败 (" + result + ")：" + failure));
            return;
        }
        nextPresent = 0;
        postedFrame = presentationReceipt[0];
        nativeRecordPresent(handle, presentationReceipt);
        onGameFrameSubmitted();
        if (movieFrame != 0 && postedFrame >= movieFrame) {
            movieFrame = 0;
            scheduleFrame();
        }
        if (paused) return;
        boolean targetChanged = presentedSurface != target;
        presentedSurface = target;
        requestPresentation(renderSurface);
        if (!targetChanged) return;
        ui.post(() -> {
            if (!isCurrent(generation) || messageUiSession != handle || renderSurface != target || gameReady) return;
            launchingTitle = null;
            gameReady = true;
            updateLibraryActions();
            setLibraryVisible(false);
            surfaceView.requestFocus();
        });
    }

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            frameScheduled = false;
            RenderSurface target = renderSurface;
            final long generation = sessionGeneration;
            if (!running || paused || target == null || !isCurrent(generation) || session == 0) return;
            try {
                requestPresentation(target);
                if (movieFrame != 0) return;
                if (debugDiagnostics) nativeStepStarted = SystemClock.uptimeMillis();
                int state;
                try {
                    state = nativeStep(session, 20000);
                } finally {
                    nativeStepStarted = 0;
                }
                if (state == FRAME_CHANGED || state == FRAME_UNCHANGED || state == FRAME_MOVIE) onGameTickCompleted();
                if (state == 3) runtimeDiagnostic("engine_tick_stalled\n" + nativeErrorText());
                if (state < 0) throw new IOException(nativeErrorText());
                if (state == 2) {
                    closeSession();
                    ui.post(() -> {
                        if (!isCurrent(generation)) return;
                        ++playGeneration;
                        resetGameUi();
                        refreshLibrary("游戏已退出");
                        status.setText("游戏已退出");
                    });
                    return;
                }
                long now = SystemClock.uptimeMillis();
                if (debugDiagnostics && now >= nextDiagnostic) {
                    nextDiagnostic = now + DIAGNOSTIC_INTERVAL_MS;
                    byte[] snapshot = nativeDiagnostic(session);
                    if (snapshot != null) runtimeDiagnostic("engine_state step_result=" + state
                        + " frame_published=" + latestFrame + " frame_presented=" + postedFrame
                        + " native_heap_bytes=" + android.os.Debug.getNativeHeapAllocatedSize()
                        + "\n" + new String(snapshot, StandardCharsets.UTF_8));
                }
                byte[] lists = nativeMessageLists(session);
                if (lists != null) {
                    final long listSession = session;
                    final ArrayList<MessageListData> views = decodeMessageLists(lists);
                    ui.post(() -> applyMessageLists(listSession, views));
                }
                byte[] sceneTools = nativeSceneTools(session);
                if (sceneTools != null) {
                    final long toolSession = session;
                    final ArrayList<MessageListData> views = decodeSceneTools(sceneTools);
                    ui.post(() -> applyInspectorViews(toolSession, views, sceneInspectors));
                }
                for (int request = 0; request < 64; request++) {
                    byte[] pending = nativeSystemOpen(session);
                    if (pending == null) break;
                    final long openSession = session;
                    final ByteBuffer data = ByteBuffer.wrap(pending).order(ByteOrder.LITTLE_ENDIAN);
                    if (data.remaining() < 8) throw new IOException("外部打开请求被截断");
                    final long requestId = data.getLong();
                    final String openTarget = messageString(data), parameters = messageString(data);
                    final String directory = messageString(data), gameRoot = messageString(data);
                    if (data.hasRemaining()) throw new IOException("外部打开请求包含多余数据");
                    ui.post(() -> openExternal(openSession, requestId, openTarget, parameters, directory, gameRoot));
                }
                for (int request = 0; request < 64; request++) {
                    byte[] pending = nativeDialog(session);
                    if (pending == null) break;
                    final long dialogSession = session;
                    final ByteBuffer data = ByteBuffer.wrap(pending).order(ByteOrder.LITTLE_ENDIAN);
                    if (data.remaining() < 8) throw new IOException("对话框请求被截断");
                    final long requestId = data.getLong();
                    final String title = messageString(data), text = messageString(data);
                    if (data.hasRemaining()) throw new IOException("对话框请求包含多余数据");
                    ui.post(() -> showHostDialog(dialogSession, requestId, title, text));
                }
                final byte[] entry = nativeTextEntry(session);
                if (entry != null) {
                    final long entrySession = session;
                    final ByteBuffer data = ByteBuffer.wrap(entry).order(ByteOrder.LITTLE_ENDIAN);
                    if (data.remaining() < 16) throw new IOException("Text entry is truncated");
                    final long request = data.getLong();
                    final int limit = messageWord(data);
                    final boolean allowCancel = messageWord(data) != 0;
                    final String title = messageString(data), initial = messageString(data);
                    if (data.hasRemaining() || limit < 1 || limit > 1023) throw new IOException("Invalid text entry");
                    ui.post(() -> showTextEntry(entrySession, request, title, initial, limit, allowCancel));
                }
                int pointer = nativePointer(session, pointerOutput);
                if (pointer < 0) throw new IOException(nativeErrorText());
                if (pointer == 1) {
                    final int x = pointerOutput[0], y = pointerOutput[1];
                    ui.post(() -> { if (isCurrent(generation)) gamePointer.move(x, y); });
                }
                int cursor = nativeCursorVisibility(session);
                if (cursor < 0) throw new IOException(nativeErrorText());
                if (cursor != 0) {
                    final boolean visible = cursor == 2;
                    final long cursorSession = session;
                    ui.post(() -> {
                        if (!destroyed && messageUiSession == cursorSession) {
                            gamePointer.setCursorVisible(visible);
                        }
                    });
                }
                final byte[] cursorImage = nativeCursorImage(session);
                if (cursorImage != null) {
                    final long cursorSession = session;
                    final ByteBuffer image = ByteBuffer.wrap(cursorImage).order(ByteOrder.LITTLE_ENDIAN);
                    final int width = messageWord(image);
                    if (width == 0 && !image.hasRemaining()) {
                        ui.post(() -> {
                            if (!destroyed && messageUiSession == cursorSession) gamePointer.setCursorImage(null, 0, 0);
                        });
                    } else {
                        final int height = messageWord(image), x = messageWord(image), y = messageWord(image);
                        if (width < 1 || width > 256 || height < 1 || height > 256 || x < 0 || x >= width
                            || y < 0 || y >= height || image.remaining() != width * height * 4) {
                            throw new IOException("Invalid cursor image");
                        }
                        final int[] pixels = new int[width * height];
                        for (int i = 0; i < pixels.length; i++) {
                            int rgba = image.getInt();
                            pixels[i] = (rgba & 0xff00ff00) | ((rgba >> 16) & 0xff) | ((rgba & 0xff) << 16);
                        }
                        ui.post(() -> {
                            if (!destroyed && messageUiSession == cursorSession) {
                                gamePointer.setCursorImage(Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888), x, y);
                            }
                        });
                    }
                }
                int generalPolicy = nativeGeneralWindowPolicy(session);
                if (generalPolicy < 0) throw new IOException(nativeErrorText());
                final long generalSession = session;
                if (generalPolicy != generalWindowPolicy) {
                    final int policy = generalPolicy;
                    final byte[] caption = (policy & 2) != 0 ? nativeGeneralWindowCaption(session) : null;
                    if ((policy & 2) != 0 && caption == null) throw new IOException(nativeErrorText());
                    ui.post(() -> applyGeneralWindowPolicy(generalSession, policy, caption));
                }
                int windowRequest = nativeWindowRequest(session);
                if (windowRequest < 0) throw new IOException(nativeErrorText());
                if (windowRequest != 0) {
                    final long windowSession = session;
                    final int command = windowRequest;
                    if (command >= 3 && command <= 7) {
                        final byte[] payload = nativeWindowUi(windowSession);
                        if (payload == null) throw new IOException(nativeErrorText());
                        ui.post(() -> applyHostWindowUi(windowSession, command, payload));
                    } else {
                        ui.post(() -> applyHostWindowRequest(windowSession, command));
                    }
                }
                for (int request = 0; request < 64; request++) {
                    char[] clipboard = nativeClipboard(session);
                    if (clipboard == null) break;
                    String text = new String(clipboard);
                    ui.post(() -> {
                        if (isCurrent(generation)) {
                            try {
                                ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE))
                                    .setPrimaryClip(ClipData.newPlainText("Game", text));
                            } catch (RuntimeException error) {
                                fail("复制文字失败", error);
                            }
                        }
                    });
                }
                if (state == FRAME_CHANGED || state == FRAME_MOVIE) {
                    int width = nativeSize(session, 0);
                    int height = nativeSize(session, 1);
                    if (width > 0 && height > 0 && (frameWidth != width || frameHeight != height)) {
                        frameWidth = width;
                        frameHeight = height;
                        ui.post(() -> {
                            if (!isCurrent(generation)) return;
                            logicalWidth = width;
                            logicalHeight = height;
                            resizeSurface();
                        });
                    }
                    if (nativePublishFrame(session, ++latestFrame) != 0) throw new IOException(nativeErrorText());
                    if (state == FRAME_MOVIE) movieFrame = latestFrame;
                    requestPresentation(renderSurface);
                }
                if (state == FRAME_CHANGED || state == FRAME_UNCHANGED || state == FRAME_MOVIE) {
                    // The portable fixed-step clock retains bounded tick debt.
                    // A late draw must not reset game time on every iteration.
                    int delay = nativeTickDelay(session, false);
                    if (delay < 0) throw new IOException(nativeErrorText());
                    nextFrame = SystemClock.uptimeMillis() + delay;
                } else {
                    // Budget continuations yield to queued messages at the
                    // same tick. Pending dependencies keep their poll delay.
                    int delay = nativeRetryDelay(session);
                    if (delay < 0) throw new IOException(nativeErrorText());
                    nextFrame = SystemClock.uptimeMillis() + delay;
                }
                if (movieFrame == 0) scheduleFrame();
            } catch (Exception error) { stopWithError("游戏运行中断", error); }
        }
    };

    private void setGameFullscreen(boolean fullscreen) {
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController controller = getWindow().getInsetsController();
            if (controller == null) throw new IllegalStateException("Window insets controller is unavailable");
            if (fullscreen) {
                controller.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(android.view.WindowInsets.Type.systemBars());
            } else {
                controller.show(android.view.WindowInsets.Type.systemBars());
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(fullscreen
                ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                : View.SYSTEM_UI_FLAG_VISIBLE);
        }
        gameFullscreen = fullscreen;
    }

    private void applyGeneralWindowPolicy(long handle, int policy, byte[] caption) {
        if (destroyed || messageUiSession != handle) return;
        generalWindowPolicy = policy;
        if ((policy & 1) != 0) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (caption != null) generalCamouflageTitle = new String(caption, java.nio.charset.StandardCharsets.UTF_8);
        applyGeneralCamouflage();
    }

    private void applyGeneralCamouflage() {
        boolean active = generalStopped && generalWindowPolicy >= 0 && (generalWindowPolicy & 2) != 0;
        if (active) {
            if (generalNormalTitle == null) generalNormalTitle = getTitle();
            setTitle(generalCamouflageTitle);
            setTaskDescription(new android.app.ActivityManager.TaskDescription(generalCamouflageTitle));
        } else if (generalNormalTitle != null) {
            setTitle(generalNormalTitle);
            setTaskDescription(new android.app.ActivityManager.TaskDescription(generalNormalTitle.toString()));
            generalNormalTitle = null;
        }
    }

    private void applyHostWindowRequest(long handle, int command) {
        if (destroyed || messageUiSession != handle) return;
        try {
            final int windowed;
            if (command == 1) {
                setGameFullscreen(!gameFullscreen);
                windowed = gameFullscreen ? 0 : 1;
            } else if (command == 2) {
                if (!moveTaskToBack(true)) throw new IOException("Unable to minimize the game activity");
                windowed = -1;
            } else {
                throw new IOException("Unknown platform window command");
            }
            worker.post(() -> {
                if (session != handle) return;
                if (nativeWindowMode(handle, windowed) < 0) {
                    stopWithError("窗口操作结果提交失败", new IOException(nativeErrorText()));
                }
            });
        } catch (Exception error) {
            worker.post(() -> { if (session == handle) stopWithError("窗口操作失败", error); });
        }
    }

    private void submitWindowUi(long handle, long request, int action, int a, int b, int c) {
        worker.post(() -> {
            if (session != handle) return;
            if (nativeWindowUiAction(handle, request, action, a, b, c) < 0) {
                stopWithError("Window UI operation failed", new IOException(nativeErrorText()));
            }
        });
    }

    private static final class GameMenuItem {
        int command;
        boolean enabled, checked, separator;
        String label;
        ArrayList<GameMenuItem> children;
    }

    private static ArrayList<GameMenuItem> readGameMenu(ByteBuffer data, int depth, int[] total) throws IOException {
        if (depth > 16) throw new IOException("Menu nesting exceeds limit");
        int count = messageCount(data, 4096);
        ArrayList<GameMenuItem> items = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            if (++total[0] > 4096) throw new IOException("Menu item count exceeds limit");
            GameMenuItem item = new GameMenuItem();
            item.command = messageWord(data);
            item.enabled = messageWord(data) != 0;
            item.checked = messageWord(data) != 0;
            item.separator = messageWord(data) != 0;
            item.label = messageString(data);
            item.children = readGameMenu(data, depth + 1, total);
            items.add(item);
        }
        return items;
    }

    private void showGameMenu(long handle, long request, ArrayList<GameMenuItem> items) {
        if (destroyed || inputSession() != handle || gameMenuRequest != request
                || gameMenuButton == null || gameMenuButton.getVisibility() != View.VISIBLE) return;
        android.widget.PopupMenu popup = new android.widget.PopupMenu(this, gameMenuButton);
        populateGameMenu(handle, request, popup.getMenu(), items);
        popup.show();
    }

    private void populateGameMenu(long handle, long request, android.view.Menu menu, ArrayList<GameMenuItem> items) {
        int group = 0;
        for (GameMenuItem source : items) {
            if (source.separator) { group++; continue; }
            android.view.MenuItem item;
            if (source.children.isEmpty()) {
                item = menu.add(group, source.command, android.view.Menu.NONE, source.label);
                final int command = source.command;
                item.setOnMenuItemClickListener(selected -> {
                    if (!destroyed && messageUiSession == handle && gameMenuRequest == request) {
                        submitWindowUi(handle, request, 3, command, 0, 0);
                    }
                    return true;
                });
            } else {
                android.view.SubMenu submenu = menu.addSubMenu(group, source.command, android.view.Menu.NONE, source.label);
                populateGameMenu(handle, request, submenu, source.children);
                item = submenu.getItem();
            }
            item.setEnabled(source.enabled);
            item.setCheckable(source.checked);
            item.setChecked(source.checked);
        }
    }

    private static final class SettingsField {
        int command, value, defaultValue, enabledBy, kind, minimum, maximum, scale;
        boolean hidden, disabled, resetChangesValue, enabledValue;
        String label;
        String[] options;
        int[][] presets;
        View view;
    }

    private static final class SettingsPage {
        int resource;
        boolean resetPending;
        String title, reset;
        ArrayList<SettingsField> fields = new ArrayList<>();
        InputSettingsPanel input;
        Runnable refreshInput;
        FontSettingsPanel font;
        Runnable refreshFont;
        SoundSettingsPanel sound;
        Runnable refreshSound;
        volatile boolean previewActive = true;
        volatile int previewGeneration;
    }
    private static final class FontSettingsPanel {
        String sample, defaultFace;
        String[] faces;
        boolean[] monospace;
    }
    private static final class SoundSettingsPanel {
        String allOn, allOff;
        int firstRole;
        int[] resetRoles;
    }
    private View soundRoleSettingsView(SettingsPage page, boolean[] filling, boolean[] modified) {
        SoundSettingsPanel sound = page.sound;
        LinearLayout rows = new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL);
        String[] names = new String[page.fields.size() - sound.firstRole];
        for (int row = 0; row < names.length; row++) names[row] = page.fields.get(sound.firstRole + row).label;
        android.widget.ListView list = new android.widget.ListView(this);
        list.setChoiceMode(android.widget.ListView.CHOICE_MODE_MULTIPLE);
        list.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_list_item_multiple_choice, names));
        rows.addView(list, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            (int)(240 * getResources().getDisplayMetrics().density)));
        page.refreshSound = () -> {
            for (int row = 0; row < names.length; row++) list.setItemChecked(row, page.fields.get(sound.firstRole + row).value != 0);
        };
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (filling[0] || position < 0 || position >= names.length) return;
            page.fields.get(sound.firstRole + position).value = list.isItemChecked(position) ? 1 : 0;
            modified[0] = true;
        });
        for (int value = 1; value >= 0; value--) {
            final int checked = value;
            Button all = new Button(this); all.setText(checked != 0 ? sound.allOn : sound.allOff);
            all.setOnClickListener(view -> {
                for (int index = sound.firstRole; index < page.fields.size(); index++) {
                    boolean present = !page.resetPending;
                    if (!present) for (int resetIndex : sound.resetRoles) if (resetIndex == index) { present = true; break; }
                    if (present) page.fields.get(index).value = checked;
                }
                page.refreshSound.run(); modified[0] = true;
            });
            rows.addView(all);
        }
        page.refreshSound.run(); return rows;
    }
    private static int selectFont(FontSettingsPanel font, int proportional, String name) {
        int first = -1;
        for (int index = 0; index < font.faces.length; index++) {
            if (proportional == 0 && !font.monospace[index]) continue;
            if (first < 0) first = index;
            if (font.faces[index].equalsIgnoreCase(name)) return index;
        }
        return first;
    }
    private void settingsFontPreview(long handle, long request, SettingsPage page,
        android.widget.ImageView image, TextView error) {
        int generation = ++page.previewGeneration;
        int face = page.fields.get(0).value, bold = page.fields.get(3).value == 0 ? 0 : 1;
        worker.post(() -> {
            if (session != handle || !page.previewActive || page.previewGeneration != generation) return;
            android.graphics.Bitmap bitmap = null;
            String problem = null;
            try {
                byte[] bytes = nativeSettingsFontPreview(handle, request, face, bold);
                if (bytes == null) throw new IOException("Font preview returned no pixels");
                ByteBuffer packet = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                int width = messageWord(packet), height = messageWord(packet);
                if (width != 640 || height != 48 || packet.remaining() != width * height * 4) throw new IOException("Invalid font pixel packet");
                int[] colors = new int[width * height];
                for (int index = 0; index < colors.length; index++) {
                    int r = packet.get() & 255, g = packet.get() & 255, b = packet.get() & 255, a = packet.get() & 255;
                    colors[index] = (a << 24) | (r << 16) | (g << 8) | b;
                }
                bitmap = android.graphics.Bitmap.createBitmap(colors, width, height, android.graphics.Bitmap.Config.ARGB_8888);
            } catch (Exception failure) { problem = failure.getMessage(); }
            final android.graphics.Bitmap result = bitmap;
            final String failure = problem;
            runOnUiThread(() -> {
                if (destroyed || messageUiSession != handle || !page.previewActive || page.previewGeneration != generation) {
                    if (result != null) result.recycle();
                    return;
                }
                image.setImageBitmap(result); error.setText(failure == null ? "" : failure);
            });
        });
    }
    private View fontSettingsView(long handle, long request, SettingsPage page, boolean[] filling, boolean[] modified) {
        FontSettingsPanel font = page.font;
        LinearLayout rows = new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL);
        TextView label = new TextView(this); label.setText(page.fields.get(0).label); rows.addView(label);
        android.widget.Spinner faces = new android.widget.Spinner(this); page.fields.get(0).view = faces; rows.addView(faces);
        android.widget.ImageView sample = new android.widget.ImageView(this);
        sample.setAdjustViewBounds(true); sample.setContentDescription(font.sample); rows.addView(sample);
        TextView error = new TextView(this); rows.addView(error);
        final ArrayList<Integer> choices = new ArrayList<>();
        for (int index = 1; index < page.fields.size(); index++) {
            SettingsField field = page.fields.get(index);
            CheckBox box = new CheckBox(this); box.setText(field.label); field.view = box; rows.addView(box);
            box.setOnCheckedChangeListener((view, checked) -> {
                if (filling[0]) return;
                field.value = checked ? 1 : 0; modified[0] = true;
                if (field.command == 1300) {
                    int previous = page.fields.get(0).value;
                    String name = previous < 0 || previous >= font.faces.length ? "" : font.faces[previous];
                    page.fields.get(0).value = selectFont(font, field.value, name);
                    page.refreshFont.run();
                } else {
                    settingsDependencies(page);
                    settingsFontPreview(handle, request, page, sample, error);
                }
            });
        }
        page.refreshFont = () -> {
            filling[0] = true;
            choices.clear(); ArrayList<String> names = new ArrayList<>();
            for (int index = 0; index < font.faces.length; index++) {
                if (page.fields.get(4).value == 0 && !font.monospace[index]) continue;
                choices.add(index); names.add(font.faces[index]);
            }
            faces.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
            faces.setSelection(choices.indexOf(page.fields.get(0).value));
            for (int index = 1; index < page.fields.size(); index++) {
                SettingsField field = page.fields.get(index); ((CheckBox)field.view).setChecked(field.value != 0);
            }
            settingsDependencies(page); filling[0] = false;
        };
        faces.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (filling[0] || position < 0 || position >= choices.size() || parent.getSelectedItemPosition() != position) return;
                int face = choices.get(position);
                if (page.fields.get(0).value == face) return;
                page.fields.get(0).value = face; modified[0] = true;
                settingsFontPreview(handle, request, page, sample, error);
            }
        });
        page.refreshFont.run(); settingsFontPreview(handle, request, page, sample, error);
        // Reset uses the same refresh but needs a new real rasterization.
        Runnable refresh = page.refreshFont;
        page.refreshFont = () -> { refresh.run(); settingsFontPreview(handle, request, page, sample, error); };
        return rows;
    }
    private static final class InputSettingsPanel {
        String keyLabel, deviceLabel, repeatLabel;
        int[] keys;
        String[] keyNames, devices, bindingLabels;
        int[][] codes;
        String[][] codeNames;
    }

    private View inputSettingsView(SettingsPage page, boolean[] filling, boolean[] modified) {
        InputSettingsPanel input = page.input;
        LinearLayout rows = new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL);
        TextView keyLabel = new TextView(this); keyLabel.setText(input.keyLabel); rows.addView(keyLabel);
        android.widget.Spinner key = new android.widget.Spinner(this);
        key.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, input.keyNames));
        rows.addView(key);
        TextView deviceLabel = new TextView(this); deviceLabel.setText(input.deviceLabel); rows.addView(deviceLabel);
        android.widget.Spinner device = new android.widget.Spinner(this);
        device.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, input.devices));
        rows.addView(device);
        android.widget.Spinner[] bindings = new android.widget.Spinner[4];
        CheckBox[] repeats = new CheckBox[4];
        int[] selected = {0, 0}, shown = {-2, -2, -2, -2};
        for (int slot = 0; slot < 4; slot++) {
            TextView label = new TextView(this); label.setText(input.bindingLabels[slot]); rows.addView(label);
            bindings[slot] = new android.widget.Spinner(this); rows.addView(bindings[slot]);
            repeats[slot] = new CheckBox(this); repeats[slot].setText(input.repeatLabel); rows.addView(repeats[slot]);
            final int group = slot;
            bindings[slot].setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                public void onNothingSelected(android.widget.AdapterView<?> parent) {}
                public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                    if (filling[0] || shown[group] == position || parent.getSelectedItemPosition() != position || input.keys.length == 0) return;
                    int source = selected[1], at = input.keys[selected[0]] * 6 + source * 2;
                    if (source == 2 && page.fields.get(480).value == 0) return;
                    if (position < 0 || position >= input.codes[source].length) return;
                    int shift = group * 8;
                    SettingsField field = page.fields.get(at);
                    field.value = (field.value & ~(255 << shift)) | (input.codes[source][position] << shift);
                    shown[group] = position; modified[0] = true;
                }
            });
            repeats[slot].setOnCheckedChangeListener((view, checked) -> {
                if (filling[0] || input.keys.length == 0) return;
                int source = selected[1], at = input.keys[selected[0]] * 6 + source * 2 + 1;
                if (source == 2 && page.fields.get(480).value == 0) return;
                int shift = group * 8;
                SettingsField field = page.fields.get(at);
                field.value = (field.value & ~(255 << shift)) | ((checked ? 1 : 0) << shift);
                modified[0] = true;
            });
        }
        SettingsField joy = page.fields.get(480);
        CheckBox joyEnabled = new CheckBox(this); joyEnabled.setText(joy.label); joyEnabled.setChecked(joy.value != 0);
        joy.view = joyEnabled; rows.addView(joyEnabled);
        page.refreshInput = () -> {
            filling[0] = true;
            joyEnabled.setChecked(joy.value != 0);
            for (int slot = 0; slot < 4; slot++) {
                int source = selected[1];
                bindings[slot].setAdapter(new android.widget.ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_dropdown_item, input.codeNames[source]));
                int code = input.keys.length == 0 ? 0 : (page.fields.get(input.keys[selected[0]] * 6 + source * 2).value >>> (slot * 8)) & 255;
                int repeat = input.keys.length == 0 ? 0 : (page.fields.get(input.keys[selected[0]] * 6 + source * 2 + 1).value >>> (slot * 8)) & 255;
                // Original489dd0: mouse passes the code to CheckDlgButton;
                // joypad gates all four checks on code2, then passes each code.
                int check = source == 0 ? (code == 0 ? 0 : repeat) : code;
                if (source == 2 && input.keys.length != 0 &&
                    ((page.fields.get(input.keys[selected[0]] * 6 + source * 2).value >>> 8) & 255) == 0) check = 0;
                if (input.keys.length != 0) {
                    SettingsField repeatWord = page.fields.get(input.keys[selected[0]] * 6 + source * 2 + 1);
                    int shift = slot * 8;
                    repeatWord.value = (repeatWord.value & ~(255 << shift)) | ((check != 0 ? 1 : 0) << shift);
                }
                int index = -1;
                for (int option = 0; option < input.codes[source].length; option++) if (input.codes[source][option] == code) { index = option; break; }
                shown[slot] = index; bindings[slot].setSelection(index, false);
                repeats[slot].setChecked(check != 0);
                boolean enabled = input.keys.length != 0 && (source != 2 || joy.value != 0);
                bindings[slot].setEnabled(enabled); repeats[slot].setEnabled(enabled);
            }
            filling[0] = false;
        };
        key.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < input.keys.length && selected[0] != position) {
                    selected[0] = position; page.refreshInput.run();
                }
            }
        });
        device.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < 3 && selected[1] != position) {
                    selected[1] = position; page.refreshInput.run();
                }
            }
        });
        joyEnabled.setOnCheckedChangeListener((view, checked) -> {
            if (!filling[0]) { joy.value = checked ? 1 : 0; modified[0] = true; page.refreshInput.run(); }
        });
        page.refreshInput.run();
        return rows;
    }

    private static void settingsDependencies(SettingsPage page) {
        for (SettingsField field : page.fields) {
            if (field.enabledBy == 0) continue;
            boolean enabled = false;
            for (SettingsField dependency : page.fields) {
                if (dependency.command == field.enabledBy) enabled = (dependency.value != 0) == field.enabledValue;
            }
            field.view.setEnabled(enabled && !field.disabled);
        }
    }

    private static void showSettingsValue(SettingsField field, int value) {
        if (field.kind == 0) ((CheckBox)field.view).setChecked(value != 0);
        else if (field.kind == 1) {
            android.widget.RadioGroup group = (android.widget.RadioGroup)field.view;
            if (value >= 0 && value < group.getChildCount()) group.check(group.getChildAt(value).getId());
            else group.clearCheck();
        } else if (field.kind == 4) {
            long raw = Integer.toUnsignedLong(value);
            ((EditText)field.view).setText(String.format(java.util.Locale.ROOT,
                field.scale == 10 ? "%d.%01d" : "%d.%02d", raw / field.scale, raw % field.scale));
        } else ((EditText)field.view).setText(Integer.toUnsignedString(value));
    }

    private static void settingsPresetEdit(SettingsPage page, int command, boolean[] filling) {
        SettingsField preset = null;
        for (SettingsField field : page.fields) if (field.kind == 3) { preset = field; break; }
        if (preset == null || (command != preset.command && command != 1127)
            || preset.value < 0 || preset.value >= preset.presets.length) return;
        int[] pair = preset.presets[preset.value];
        filling[0] = true;
        for (SettingsField field : page.fields) {
            if (field.command == 1129 || field.command == 1080) {
                field.value = pair[field.command == 1129 ? 0 : 1];
                showSettingsValue(field, field.value);
            }
        }
        filling[0] = false;
    }

    private static long settingsNumber(SettingsField field, String text) {
        if (field.kind != 4) return Long.parseLong(text);
        text = text.trim();
        if (text.isEmpty()) throw new NumberFormatException("Empty wait time");
        int point = text.indexOf('.');
        String whole = point < 0 ? text : text.substring(0, point);
        String fraction = point < 0 ? "" : text.substring(point + 1);
        int places = field.scale == 10 ? 1 : 2;
        if (fraction.length() > places) fraction = fraction.substring(0, places);
        // Same bounded decimal digits as original4892c0; no implicit padding.
        long integer = whole.isEmpty() ? 0 : Long.parseLong(whole);
        return Math.addExact(Math.multiplyExact(integer, field.scale), fraction.isEmpty() ? 0 : Long.parseLong(fraction));
    }

    private static void settingsSoundEdit(SettingsPage page, int command, boolean[] filling) {
        if (page.resource != 0x418 && page.resource != 0x41e) return;
        SettingsField bgm = null, duringVoice = null;
        for (SettingsField field : page.fields) {
            if (field.command == 1078) bgm = field;
            else if (field.command == 1095) duringVoice = field;
        }
        if (bgm == null || duringVoice == null) return;
        SettingsField changed = null;
        if (command == 1078 && Integer.compareUnsigned(duringVoice.value, bgm.value) > 0) {
            duringVoice.value = bgm.value; changed = duringVoice;
        } else if (command == 1095 && Integer.compareUnsigned(bgm.value, duringVoice.value) < 0) {
            bgm.value = duringVoice.value; changed = bgm;
        }
        if (changed != null) {
            filling[0] = true; showSettingsValue(changed, changed.value); filling[0] = false;
        }
    }

    private void showSettings(long handle, long request, ByteBuffer data) throws IOException {
        String title = messageString(data);
        int count = messageCount(data, 16);
        if (count == 0) throw new IOException("Settings has no inspected pages");
        ArrayList<SettingsPage> pages = new ArrayList<>();
        int fieldCount = 0;
        for (int index = 0; index < count; index++) {
            SettingsPage page = new SettingsPage();
            page.resource = messageWord(data); page.title = messageString(data); page.reset = messageString(data);
            if (messageWord(data) != 0) {
                InputSettingsPanel input = new InputSettingsPanel();
                input.keyLabel = messageString(data); input.deviceLabel = messageString(data);
                int keys = messageCount(data, 80); input.keys = new int[keys]; input.keyNames = new String[keys];
                for (int key = 0; key < keys; key++) {
                    input.keys[key] = messageWord(data); input.keyNames[key] = messageString(data);
                    if (input.keys[key] < 0 || input.keys[key] >= 80) throw new IOException("Invalid input binding identity");
                }
                if (messageCount(data, 3) != 3) throw new IOException("Input settings need the original three device menus");
                input.devices = new String[3]; input.codes = new int[3][]; input.codeNames = new String[3][];
                for (int device = 0; device < 3; device++) {
                    input.devices[device] = messageString(data); int codes = messageCount(data, 256);
                    if (codes == 0) throw new IOException("Input code menu is empty");
                    input.codes[device] = new int[codes]; input.codeNames[device] = new String[codes];
                    for (int code = 0; code < codes; code++) { input.codes[device][code] = messageWord(data); input.codeNames[device][code] = messageString(data); }
                }
                if (messageCount(data, 4) != 4) throw new IOException("Input settings need four binding slots");
                input.bindingLabels = new String[4];
                for (int slot = 0; slot < 4; slot++) input.bindingLabels[slot] = messageString(data);
                input.repeatLabel = messageString(data); page.input = input;
            }
            if (messageWord(data) != 0) {
                FontSettingsPanel font = new FontSettingsPanel(); font.sample = messageString(data); font.defaultFace = messageString(data);
                int faces = messageCount(data, 4096);
                if (faces == 0) throw new IOException("Font page has no actual font faces");
                font.faces = new String[faces]; font.monospace = new boolean[faces];
                for (int face = 0; face < faces; face++) { font.faces[face] = messageString(data); font.monospace[face] = messageWord(data) != 0; }
                page.font = font;
            }
            if (messageWord(data) != 0) {
                SoundSettingsPanel sound = new SoundSettingsPanel(); sound.allOn = messageString(data); sound.allOff = messageString(data);
                sound.firstRole = messageWord(data); sound.resetRoles = new int[messageCount(data, 48)];
                for (int row = 0; row < sound.resetRoles.length; row++) sound.resetRoles[row] = messageWord(data);
                page.sound = sound;
            }
            int fields = messageCount(data, 512);
            fieldCount += fields;
            if (fieldCount > 1024 || (page.input != null && fields != 481) || (page.font != null && fields != 6)) throw new IOException("Settings controls exceed limit or page record is invalid");
            for (int slot = 0; slot < fields; slot++) {
                SettingsField field = new SettingsField();
                field.command = messageWord(data); field.label = messageString(data);
                field.value = messageWord(data); field.defaultValue = messageWord(data);
                field.hidden = messageWord(data) != 0; field.disabled = messageWord(data) != 0;
                field.enabledBy = messageWord(data); field.enabledValue = messageWord(data) != 0;
                field.resetChangesValue = messageWord(data) != 0; field.kind = messageWord(data);
                if (field.kind == 1) {
                    field.options = new String[messageCount(data, 32)];
                    for (int option = 0; option < field.options.length; option++) field.options[option] = messageString(data);
                } else if (field.kind == 2) {
                    field.minimum = messageWord(data); field.maximum = messageWord(data);
                } else if (field.kind == 3) {
                    field.presets = new int[messageCount(data, 32)][2];
                    if (field.presets.length == 0) throw new IOException("Automatic-wait preset table is empty");
                    for (int[] pair : field.presets) { pair[0] = messageWord(data); pair[1] = messageWord(data); }
                    field.minimum = 0; field.maximum = field.presets.length - 1;
                } else if (field.kind == 4) {
                    field.scale = messageWord(data); field.minimum = messageWord(data); field.maximum = messageWord(data);
                    if (field.scale != 10 && field.scale != 100) throw new IOException("Invalid original decimal time scale");
                } else if (field.kind != 0) throw new IOException("Unknown inspected settings control");
                page.fields.add(field);
            }
            pages.add(page);
            if (page.sound != null) {
                if (page.sound.firstRole != 11 || fields < 11 || fields > 59) throw new IOException("Invalid sound role layout");
                for (int role : page.sound.resetRoles) if (role < 11 || role >= fields) throw new IOException("Invalid Reset role index");
            }
        }
        if (data.hasRemaining()) throw new IOException("Settings payload has trailing bytes");
        final boolean[] modified = {false}, filling = {false};
        LinearLayout controls = new LinearLayout(this);
        controls.setPadding(24, 12, 24, 12); controls.setOrientation(LinearLayout.VERTICAL);
        for (SettingsPage page : pages) {
            TextView heading = new TextView(this); heading.setText(page.title); heading.setTextSize(18); controls.addView(heading);
            if (page.input != null) controls.addView(inputSettingsView(page, filling, modified));
            else if (page.font != null) controls.addView(fontSettingsView(handle, request, page, filling, modified));
            else for (SettingsField field : page.fields) {
                if (page.sound != null && field.command >= 1400) continue;
                LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.VERTICAL);
                if (!field.label.isEmpty() && field.kind != 0) {
                    TextView label = new TextView(this); label.setText(field.label); row.addView(label);
                }
                if (field.kind == 0) {
                    CheckBox box = new CheckBox(this); box.setText(field.label); field.view = box;
                    showSettingsValue(field, field.value);
                    box.setOnCheckedChangeListener((view, checked) -> {
                        if (!filling[0]) {
                            field.value = checked ? 1 : 0; modified[0] = true;
                            settingsPresetEdit(page, field.command, filling); settingsDependencies(page);
                        }
                    });
                } else if (field.kind == 1) {
                    android.widget.RadioGroup group = new android.widget.RadioGroup(this); field.view = group;
                    for (int option = 0; option < field.options.length; option++) {
                        android.widget.RadioButton button = new android.widget.RadioButton(this);
                        button.setId(View.generateViewId()); button.setText(field.options[option]); group.addView(button);
                        final int selected = option;
                        // A click on an already checked radio is still an original BN_CLICKED.
                        button.setOnClickListener(view -> { field.value = selected; modified[0] = true; });
                    }
                    showSettingsValue(field, field.value);
                } else {
                    EditText edit = new EditText(this); field.view = edit;
                    edit.setInputType(InputType.TYPE_CLASS_NUMBER | (field.kind == 4 ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
                    showSettingsValue(field, field.value);
                    edit.addTextChangedListener(new TextWatcher() {
                        public void beforeTextChanged(CharSequence s, int start, int before, int after) {}
                        public void onTextChanged(CharSequence s, int start, int before, int after) {
                            if (filling[0]) return;
                            try {
                                long value = settingsNumber(field, s.toString());
                                field.value = (int)Math.max(Integer.toUnsignedLong(field.minimum),
                                    Math.min(Integer.toUnsignedLong(field.maximum), value));
                                settingsPresetEdit(page, field.command, filling);
                                settingsSoundEdit(page, field.command, filling);
                                modified[0] = true;
                            } catch (NumberFormatException | ArithmeticException ignored) { /* Incomplete input keeps scratch value until a valid edit. */ }
                        }
                        public void afterTextChanged(Editable s) {}
                    });
                }
                if (field.disabled) field.view.setEnabled(false);
                row.addView(field.view); if (field.hidden) row.setVisibility(View.GONE); controls.addView(row);
            }
            if (page.sound != null) controls.addView(soundRoleSettingsView(page, filling, modified));
            settingsDependencies(page);
            Button reset = new Button(this); reset.setText(page.reset);
            reset.setOnClickListener(view -> {
                page.resetPending = true;
                if (page.font != null) {
                    page.fields.get(4).value = 1;
                    page.fields.get(0).value = selectFont(page.font, page.fields.get(4).value, page.font.defaultFace);
                    for (int index : new int[]{1, 2, 3, 5}) page.fields.get(index).value = page.fields.get(index).defaultValue;
                    page.refreshFont.run(); modified[0] = true; return;
                }
                filling[0] = true;
                for (SettingsField field : page.fields) {
                    if (page.input == null && field.view != null) showSettingsValue(field, field.defaultValue);
                    if (field.resetChangesValue) field.value = field.defaultValue;
                }
                filling[0] = false;
                if (page.input != null) page.refreshInput.run(); else settingsDependencies(page);
                if (page.sound != null) page.refreshSound.run();
                modified[0] = true;
            });
            controls.addView(reset);
        }
        android.widget.ScrollView scroll = new android.widget.ScrollView(this); scroll.addView(controls);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(scroll)
            .setPositiveButton(android.R.string.ok, null).setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton("Apply", null).create();
        effectSettingsDialog = dialog;
        final boolean[] closed = {false};
        dialog.setOnDismissListener(ignored -> {
            for (SettingsPage page : pages) { page.previewActive = false; page.previewGeneration++; }
            if (effectSettingsDialog == dialog) effectSettingsDialog = null;
            if (!closed[0]) { closed[0] = true; submitWindowUi(handle, request, 1, 0, 0, 0); }
        });
        dialog.show();
        Runnable apply = () -> {
            if (!modified[0]) return;
            for (SettingsPage page : pages) {
                if (page.font == null) continue;
                int face = page.fields.get(0).value;
                if (face < 0 || face >= page.font.faces.length || (page.fields.get(4).value == 0 && !page.font.monospace[face])) {
                    new AlertDialog.Builder(this).setTitle(page.title)
                        .setMessage("No supported font is selected in this filter.")
                        .setPositiveButton(android.R.string.ok, null).show();
                    return;
                }
            }
            int length = 4;
            for (SettingsPage page : pages) length += 12 + page.fields.size() * 4;
            ByteBuffer record = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN); record.putInt(pages.size());
            for (SettingsPage page : pages) {
                record.putInt(page.resource).putInt(page.fields.size());
                record.putInt(page.resetPending ? 1 : 0); page.resetPending = false;
                for (SettingsField field : page.fields) record.putInt(field.value);
            }
            byte[] bytes = record.array();
            worker.post(() -> {
                if (session != handle) return;
                if (nativeSettingsApply(handle, request, bytes) < 0) {
                    stopWithError("Settings Apply failed", new IOException(nativeErrorText()));
                }
            });
            modified[0] = false;
        };
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> apply.run());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            apply.run(); if (!modified[0]) dialog.dismiss();
        });
    }

    private void applyHostWindowUi(long handle, int command, byte[] payload) {
        if (destroyed || messageUiSession != handle) return;
        try {
            ByteBuffer data = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
            if (messageWord(data) != command || data.remaining() < 8) throw new IOException("Window UI kind mismatch");
            final long request = data.getLong();
            if (command == 3) {
                showSettings(handle, request, data);
            } else if (command == 4) {
                messageWord(data); // Original menu resource identity68/69.
                boolean visible = messageWord(data) != 0;
                ArrayList<GameMenuItem> items = readGameMenu(data, 0, new int[]{0});
                if (data.hasRemaining()) throw new IOException("Menu payload has trailing bytes");
                if (gameMenuButton == null) {
                    gameMenuButton = new Button(this);
                    gameMenuButton.setText("\u22ee");
                    FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(56, 56, Gravity.TOP | Gravity.END);
                    root.addView(gameMenuButton, root.indexOfChild(panel), layout);
                }
                gameMenuRequest = request;
                gameMenuItems = items;
                gameMenuButton.setVisibility(visible ? View.VISIBLE : View.GONE);
                gameMenuButton.setOnClickListener(view -> showGameMenu(handle, request, gameMenuItems));
                submitWindowUi(handle, request, 2, 0, 0, 0);
            } else if (command == 5) {
                String title = messageString(data), message = messageString(data);
                if (data.hasRemaining()) throw new IOException("Menu confirmation has trailing bytes");
                final boolean[] completed = {false};
                AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setMessage(message)
                    .setPositiveButton(android.R.string.yes, (ignored, which) -> {
                        if (!completed[0]) { completed[0] = true; submitWindowUi(handle, request, 4, 1, 0, 0); }
                    })
                    .setNegativeButton(android.R.string.no, (ignored, which) -> {
                        if (!completed[0]) { completed[0] = true; submitWindowUi(handle, request, 4, 0, 0, 0); }
                    }).create();
                menuConfirmationDialog = dialog;
                dialog.setOnDismissListener(ignored -> {
                    if (menuConfirmationDialog == dialog) menuConfirmationDialog = null;
                    if (!completed[0]) { completed[0] = true; submitWindowUi(handle, request, 4, 0, 0, 0); }
                });
                dialog.show();
            } else if (command == 6) {
                String title = messageString(data);
                if (data.hasRemaining()) throw new IOException("System-volume payload has trailing bytes");
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_SOUND_SETTINGS));
                    submitWindowUi(handle, request, 5, 1, 0, 0);
                } catch (Exception error) {
                    submitWindowUi(handle, request, 5, 0, 0, 0);
                    new AlertDialog.Builder(this).setTitle(title).setMessage(error.toString())
                        .setPositiveButton(android.R.string.ok, null).show();
                }
            } else if (command == 7) {
                String title = messageString(data), message = messageString(data);
                if (data.hasRemaining()) throw new IOException("Menu information has trailing bytes");
                AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setMessage(message)
                    .setPositiveButton(android.R.string.ok, null).create();
                menuConfirmationDialog = dialog;
                dialog.setOnDismissListener(ignored -> {
                    if (menuConfirmationDialog == dialog) menuConfirmationDialog = null;
                    submitWindowUi(handle, request, 6, 0, 0, 0);
                });
                dialog.show();
            } else throw new IOException("Unknown window UI command");
        } catch (Exception error) {
            worker.post(() -> { if (session == handle) stopWithError("Window UI failed", error); });
        }
    }

    private boolean touch(View view, MotionEvent event) {
        long handle = inputSession();
        if (handle == 0 || paused || logicalWidth <= 0 || view.getWidth() <= 0 || view.getHeight() <= 0) return true;
        int action = event.getActionMasked();
        boolean down = action == MotionEvent.ACTION_DOWN;
        if (down && event.isFromSource(android.view.InputDevice.SOURCE_MOUSE)
                && (event.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0) {
            showPlaybackControls();
            return true;
        }
        boolean cancelled = action == MotionEvent.ACTION_CANCEL;
        boolean up = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP;
        int index = event.getActionIndex();
        if (down) {
            if (activePointerId != -1 && pointerDownTime == event.getDownTime()) return true;
            activePointerId = event.getPointerId(index);
            pointerDownTime = event.getDownTime();
            pointerButton = (event.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0 ? 1 : 0;
        } else if (activePointerId == -1 || pointerDownTime != event.getDownTime()) {
            return true;
        } else if (up) {
            if (event.getPointerId(index) != activePointerId) return true;
        } else if (action == MotionEvent.ACTION_MOVE) {
            index = event.findPointerIndex(activePointerId);
            if (index < 0) return true;
        } else if (!cancelled) {
            return true;
        }
        gamePointer.hide();
        int button = pointerButton;
        boolean primaryDown = down && button == 0;
        long eventToUiMs = primaryDown ? Math.max(0, SystemClock.uptimeMillis() - event.getEventTime()) : -1;
        long inputUiMonoUs = primaryDown ? System.nanoTime() / 1000 : 0;
        if (primaryDown) {
            android.util.Log.i("CS2Perf", "primary_down_ui session=" + handle
                + " input_ui_mono_us=" + inputUiMonoUs + " event_to_ui_ms=" + eventToUiMs);
        }
        int result = 0;
        if (!cancelled) {
            int x = Math.max(0, Math.min(logicalWidth - 1, (int)(event.getX(index) * logicalWidth / view.getWidth())));
            int y = Math.max(0, Math.min(logicalHeight - 1, (int)(event.getY(index) * logicalHeight / view.getHeight())));
            result = nativeEnqueueInput(handle, 0, x, y, -1, 0);
        }
        if (down) {
            result |= nativeEnqueueInput(handle, 1, button, 0, eventToUiMs, inputUiMonoUs);
        }
        if (up || cancelled) {
            activePointerId = -1;
            result |= nativeEnqueueInput(handle, 2, button, 0, -1, 0);
        }
        if (result < 0) {
            worker.post(() -> {
                if (session == handle) stopWithError("触摸输入队列失败", new IOException("Android input mailbox rejected an event"));
            });
        }
        return true;
    }

    private void openExternal(long handle, long request, String target,
                              String parameters, String directory, String gameRoot) {
        byte[] failure = null;
        try {
            if (destroyed || messageUiSession != handle) throw new IOException("游戏会话已关闭");
            if (!parameters.isEmpty()) throw new IOException("此 Android 打开方式不支持 Windows 命令行参数");
            String normalized = target.replace('\\', '/');
            Uri uri = Uri.parse(normalized);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            if (uri.getScheme() == null || uri.getScheme().equalsIgnoreCase("file")) {
                File base = directory.isEmpty() ? new File(gameRoot) : new File(directory.replace('\\', '/'));
                if (!base.isAbsolute()) base = new File(gameRoot, directory.replace('\\', '/'));
                File file = uri.getScheme() == null ? new File(normalized) : new File(uri.getPath());
                if (!file.isAbsolute()) file = new File(base, normalized);
                file = checkedGameDocument(file);
                if (file.isDirectory()) {
                    showReadOnlyDirectory(file, file);
                    intent = null;
                } else {
                if (!file.isFile()) throw new IOException("打开的游戏文档不存在");
                String extension = GameFilesProvider.extension(file);
                if (extension.equals("exe") || extension.equals("dll") || extension.equals("com")
                    || extension.equals("bat") || extension.equals("cmd") || extension.equals("msi")) {
                    throw new IOException("Android 宿主不能执行游戏附带的 Windows 程序");
                }
                uri = new Uri.Builder().scheme("content").authority(getPackageName() + ".gamefiles")
                    .path(file.getPath()).build();
                intent.setDataAndType(uri, GameFilesProvider.mime(file));
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.setClipData(ClipData.newRawUri("Game document", uri));
                }
            } else {
                intent.setData(uri);
            }
            if (intent != null) startActivity(intent);
        } catch (Exception error) {
            String message = error.getMessage() == null ? error.toString() : error.getMessage();
            // The result crosses the UTF8 ABI, including all surrogate pairs.
            if (message.length() > 4000) message = message.substring(0, 4000);
            failure = message.getBytes(StandardCharsets.UTF_8);
        }
        final byte[] result = failure;
        worker.post(() -> {
            if (session != handle) return;
            if (nativeSystemOpenResult(handle, request, result) < 0) {
                reportOptionalHostFailure(handle, "外部打开结果提交失败", nativeErrorText());
            }
        });
    }

    private File checkedGameDocument(File requested) throws IOException {
        if (isolatedSaveRoot == null) return GameFilesProvider.checkedFile(getFilesDir(), requested);
        File canonical = requested.getCanonicalFile();
        return canonical.equals(isolatedSaveRoot) ? canonical
            : GameFilesProvider.checkedFile(isolatedSaveRoot, canonical);
    }

    /** Real directory receiver for the active private save directory. */
    private void showReadOnlyDirectory(File boundary, File requested) throws IOException {
        File base = checkedGameDocument(boundary);
        File directory = checkedGameDocument(requested);
        if (!directory.equals(base) && !directory.getPath().startsWith(base.getPath() + File.separator)) {
            throw new IOException("Directory escapes its opened root");
        }
        File[] entries = directory.listFiles();
        if (entries == null) throw new IOException("Cannot read opened directory");
        if (entries.length > 4096) throw new IOException("Opened directory contains more than4096 entries");
        java.util.Arrays.sort(entries, (left, right) -> {
            int kind = Boolean.compare(right.isDirectory(), left.isDirectory());
            return kind != 0 ? kind : left.getName().compareToIgnoreCase(right.getName());
        });
        ArrayList<File> paths = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        if (!directory.equals(base)) { paths.add(directory.getParentFile()); names.add(".."); }
        for (File entry : entries) { paths.add(entry); names.add(entry.getName() + (entry.isDirectory() ? "/" : "")); }
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(directory.getName())
            .setItems(names.toArray(new String[0]), (window, selected) -> {
                try {
                    File file = checkedGameDocument(paths.get(selected));
                    if (!file.equals(base) && !file.getPath().startsWith(base.getPath() + File.separator)) {
                        throw new IOException("Selected document escapes its opened root");
                    }
                    if (file.isDirectory()) { showReadOnlyDirectory(base, file); return; }
                    String extension = GameFilesProvider.extension(file);
                    if (extension.equals("exe") || extension.equals("dll") || extension.equals("com")
                        || extension.equals("bat") || extension.equals("cmd") || extension.equals("msi")
                        || extension.equals("scr") || extension.equals("pif") || extension.equals("lnk")) {
                        throw new IOException("The document receiver cannot execute Windows programs");
                    }
                    Uri uri = new Uri.Builder().scheme("content").authority(getPackageName() + ".gamefiles")
                        .path(file.getPath()).build();
                    Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, GameFilesProvider.mime(file));
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    intent.setClipData(ClipData.newRawUri("Game document", uri));
                    startActivity(intent);
                } catch (Exception error) {
                    android.widget.Toast.makeText(this, error.toString(), android.widget.Toast.LENGTH_LONG).show();
                }
            }).setNegativeButton(android.R.string.cancel, null).create();
        directoryDialogs.add(dialog);
        dialog.setOnDismissListener(ignored -> directoryDialogs.remove(dialog));
        dialog.show();
    }

    private void finishHostDialog(long handle, long request, boolean shown) {
        hostDialogs.remove(request);
        worker.post(() -> {
            if (session != handle) return;
            if (nativeDialogResult(handle, request, shown ? 1 : 0) < 0) {
                reportOptionalHostFailure(handle, "对话框结果提交失败", nativeErrorText());
            }
        });
    }

    private void showHostDialog(long handle, long request, String title, String text) {
        if (destroyed || messageUiSession != handle) {
            finishHostDialog(handle, request, false);
            return;
        }
        try {
            AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setMessage(text)
                .setPositiveButton("确定", (window, which) -> finishHostDialog(handle, request, true)).create();
            dialog.setOnCancelListener(window -> finishHostDialog(handle, request, true));
            hostDialogs.put(request, dialog);
            dialog.show();
        } catch (RuntimeException error) { finishHostDialog(handle, request, false); }
    }

    private void finishTextEntry(long handle, long request, byte[] value) {
        worker.post(() -> {
            if (session != handle || nativeTextEntryResult(handle, request, value) >= 0) return;
            String detail = nativeErrorText();
            if (value != null) nativeTextEntryResult(handle, request, null);
            reportOptionalHostFailure(handle, "文字输入结果提交失败", detail);
        });
    }

    private void showTextEntry(long handle, long request, String title, String initial, int limit, boolean allowCancel) {
        if (destroyed || messageUiSession != handle) {
            finishTextEntry(handle, request, null);
            return;
        }
        AlertDialog dialog = null;
        try {
            EditText editor = new EditText(this);
            editor.setSingleLine(true);
            editor.setFilters(new InputFilter[] { new InputFilter.LengthFilter(limit) });
            editor.setText(initial);
            editor.selectAll();
            final byte[][] result = { null };
            AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(title).setView(editor)
                .setCancelable(allowCancel).setPositiveButton(android.R.string.ok, (window, which) -> {
                    result[0] = editor.getText().toString().getBytes(StandardCharsets.UTF_8);
                });
            if (allowCancel) builder.setNegativeButton(android.R.string.cancel, null);
            final AlertDialog entry = builder.create();
            dialog = entry;
            textEntryDialog = entry;
            entry.setOnDismissListener(ignored -> {
                if (textEntryDialog == entry) textEntryDialog = null;
                finishTextEntry(handle, request, result[0]);
            });
            dialog.show();
            editor.requestFocus();
            if (dialog.getWindow() != null) {
                dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            }
        } catch (RuntimeException error) {
            finishTextEntry(handle, request, null);
            if (dialog != null) {
                dialog.setOnDismissListener(null);
                if (textEntryDialog == dialog) textEntryDialog = null;
                if (dialog.isShowing()) dialog.dismiss();
            }
            reportOptionalHostFailure(handle, "无法打开文字输入", error.toString());
        }
    }

    /** Read-only per-URI grants for a selected game document. */
    public static final class GameFilesProvider extends ContentProvider {
        static File checkedFile(File root, File requested) throws IOException {
            File canonicalRoot = root.getCanonicalFile(), file = requested.getCanonicalFile();
            if (!file.getPath().startsWith(canonicalRoot.getPath() + File.separator)) {
                throw new IOException("游戏文档超出应用文件目录");
            }
            return file;
        }
        private File file(Uri uri) throws FileNotFoundException {
            try {
                Context context = getContext();
                if (context == null || uri.getPath() == null) throw new IOException("文档路径为空");
                File requested = new File(uri.getPath());
                if (isDebuggable(context) && context.getCacheDir() != null) {
                    File cache = context.getCacheDir().getCanonicalFile();
                    if (requested.toPath().startsWith(cache.toPath())) {
                        return checkedFile(isolatedProfiles(context), requested);
                    }
                }
                return checkedFile(context.getFilesDir(), requested);
            } catch (IOException error) { throw new FileNotFoundException(error.getMessage()); }
        }
        static String extension(File file) {
            String name = file.getName();
            int dot = name.lastIndexOf('.');
            return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        }
        static String mime(File file) {
            String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension(file));
            return type == null ? "application/octet-stream" : type;
        }
        @Override public boolean onCreate() { return true; }
        @Override public String getType(Uri uri) {
            try { return mime(file(uri)); } catch (FileNotFoundException error) { return null; }
        }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            if (!mode.equals("r")) throw new FileNotFoundException("文档只允许读取");
            return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
        }
        @Override public Cursor query(Uri uri, String[] projection, String selection,
                                      String[] arguments, String order) {
            try {
                File item = file(uri);
                String[] columns = projection == null
                    ? new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE } : projection;
                MatrixCursor cursor = new MatrixCursor(columns, 1);
                Object[] values = new Object[columns.length];
                for (int index = 0; index < columns.length; index++) {
                    if (columns[index].equals(OpenableColumns.DISPLAY_NAME)) values[index] = item.getName();
                    if (columns[index].equals(OpenableColumns.SIZE)) values[index] = item.length();
                }
                cursor.addRow(values);
                return cursor;
            } catch (FileNotFoundException error) { return null; }
        }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
            throw new UnsupportedOperationException();
        }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    }

    private static final class MessageRow {
        final int input;
        final String name, text;
        MessageRow(int input, String name, String text) {
            this.input = input; this.name = name; this.text = text;
        }
    }
    private static final class MessageListData {
        final int task, kind;
        final boolean visible;
        final ArrayList<MessageRow> rows;
        MessageListData(int task, boolean visible, ArrayList<MessageRow> rows) {
            this(task, 47, visible, rows);
        }
        MessageListData(int task, int kind, boolean visible, ArrayList<MessageRow> rows) {
            this.task = task; this.kind = kind; this.visible = visible; this.rows = rows;
        }
    }

    private static int messageWord(ByteBuffer buffer) throws IOException {
        if (buffer.remaining() < 4) throw new IOException("消息列表数据被截断");
        return buffer.getInt();
    }
    private static int messageCount(ByteBuffer buffer, int limit) throws IOException {
        int count = messageWord(buffer);
        if (count < 0 || count > limit) throw new IOException("消息列表数据超出范围");
        return count;
    }
    private static String messageString(ByteBuffer buffer) throws IOException {
        int length = messageCount(buffer, 64 * 1024 * 1024);
        if (length > buffer.remaining()) throw new IOException("消息列表文字被截断");
        byte[] text = new byte[length];
        buffer.get(text);
        return new String(text, StandardCharsets.UTF_8);
    }
    private static ArrayList<MessageListData> decodeMessageLists(byte[] bytes) throws IOException {
        if (bytes.length > 64 * 1024 * 1024) throw new IOException("消息列表过大");
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int count = messageCount(buffer, 4096);
        ArrayList<MessageListData> views = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int task = messageWord(buffer);
            boolean visible = messageWord(buffer) != 0;
            int rows = messageCount(buffer, 1000000);
            ArrayList<MessageRow> items = new ArrayList<>(rows);
            for (int row = 0; row < rows; row++) {
                items.add(new MessageRow(messageWord(buffer), messageString(buffer), messageString(buffer)));
            }
            views.add(new MessageListData(task, visible, items));
        }
        if (buffer.hasRemaining()) throw new IOException("消息列表包含多余数据");
        return views;
    }

    private static ArrayList<MessageListData> decodeSceneTools(byte[] bytes) throws IOException {
        if (bytes.length > 64 * 1024 * 1024) throw new IOException("场景窗口数据过大");
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int count = messageCount(buffer, 4096);
        ArrayList<MessageListData> views = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int task = messageWord(buffer), kind = messageWord(buffer);
            if (kind != 32 && kind != 33) throw new IOException("场景窗口类型无效");
            boolean visible = messageWord(buffer) != 0;
            int rows = messageCount(buffer, 3000000);
            ArrayList<MessageRow> items = new ArrayList<>(rows);
            for (int row = 0; row < rows; row++) {
                items.add(new MessageRow(messageWord(buffer), messageString(buffer), messageString(buffer)));
            }
            views.add(new MessageListData(task, kind, visible, items));
        }
        if (buffer.hasRemaining()) throw new IOException("场景窗口包含多余数据");
        return views;
    }

    private void clearMessageInspectors() {
        messageUiSession = 0;
        for (AlertDialog dialog : new ArrayList<>(directoryDialogs)) {
            dialog.setOnDismissListener(null); dialog.dismiss();
        }
        directoryDialogs.clear();
        generalWindowPolicy = -1;
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        generalStopped = false;
        applyGeneralCamouflage();
        generalCamouflageTitle = null;
        gameMenuRequest = 0;
        gameMenuItems = null;
        if (gameMenuButton != null) gameMenuButton.setVisibility(View.GONE);
        if (effectSettingsDialog != null) {
            effectSettingsDialog.setOnDismissListener(null);
            effectSettingsDialog.dismiss();
            effectSettingsDialog = null;
        }
        if (menuConfirmationDialog != null) {
            menuConfirmationDialog.setOnDismissListener(null);
            menuConfirmationDialog.dismiss();
            menuConfirmationDialog = null;
        }
        if (textEntryDialog != null) {
            textEntryDialog.setOnDismissListener(null);
            textEntryDialog.dismiss();
            textEntryDialog = null;
        }
        if (gamePointer != null) gamePointer.setCursorImage(null, 0, 0);
        for (MessageInspector inspector : messageInspectors.values()) inspector.close();
        messageInspectors.clear();
        for (MessageInspector inspector : sceneInspectors.values()) inspector.close();
        sceneInspectors.clear();
        for (AlertDialog dialog : hostDialogs.values()) dialog.dismiss();
        hostDialogs.clear();
    }
    private void applyMessageLists(long handle, ArrayList<MessageListData> views) {
        applyInspectorViews(handle, views, messageInspectors);
    }
    private void applyInspectorViews(long handle, ArrayList<MessageListData> views,
                                     HashMap<Integer, MessageInspector> inspectors) {
        if (destroyed || handle != messageUiSession) return;
        HashSet<Integer> live = new HashSet<>();
        for (MessageListData data : views) {
            live.add(data.task);
            MessageInspector inspector = inspectors.get(data.task);
            if (inspector != null && inspector.kind != data.kind) {
                inspector.close(); inspectors.remove(data.task); inspector = null;
            }
            if (inspector == null && data.visible) {
                inspector = new MessageInspector(handle, data.task, data.kind);
                inspectors.put(data.task, inspector);
            }
            if (inspector != null) {
                inspector.replace(data.rows);
                if (data.visible && !inspector.dialog.isShowing()) inspector.dialog.show();
                if (!data.visible) inspector.close();
            }
        }
        for (Integer task : new ArrayList<>(inspectors.keySet())) {
            if (!live.contains(task)) inspectors.remove(task).close();
        }
    }
    private void messageAction(long handle, int task, int operation, int value) {
        worker.post(() -> {
            if (session != handle) return;
            if (nativeMessageListAction(handle, task, operation, value) < 0) {
                fail("消息列表操作失败", new IOException(nativeErrorText()));
            }
        });
    }
    private void sceneAction(long handle, int task, int operation, int parameter, int value) {
        worker.post(() -> {
            if (session != handle) return;
            if (nativeSceneToolAction(handle, task, operation, parameter, value) < 0) {
                fail("场景窗口操作失败", new IOException(nativeErrorText()));
            }
        });
    }

    /** Virtualized native list over the actual CST input rows. */
    private final class MessageInspector {
        final long handle;
        final int task, kind;
        final AlertDialog dialog;
        final EditText nameFilter, textFilter;
        final CheckBox monologue;
        final RowsAdapter adapter = new RowsAdapter();
        ArrayList<MessageRow> rows = new ArrayList<>();
        final ArrayList<MessageRow> filtered = new ArrayList<>();
        final ArrayList<AlertDialog> editors = new ArrayList<>();

        MessageInspector(long handle, int task, int kind) {
            this.handle = handle; this.task = task; this.kind = kind;
            LinearLayout content = new LinearLayout(PlayerEngineActivity.this);
            content.setOrientation(LinearLayout.VERTICAL);
            int padding = (int)(12 * getResources().getDisplayMetrics().density);
            content.setPadding(padding, padding, padding, padding);
            nameFilter = new EditText(PlayerEngineActivity.this);
            nameFilter.setSingleLine(true);
            nameFilter.setHint(kind == 47 ? "姓名筛选" : kind == 33 ? "分组筛选" : "类别筛选");
            textFilter = new EditText(PlayerEngineActivity.this);
            textFilter.setSingleLine(true); textFilter.setHint("正文筛选");
            monologue = new CheckBox(PlayerEngineActivity.this); monologue.setText("仅旁白");
            if (kind != 47) monologue.setVisibility(View.GONE);
            content.addView(nameFilter); content.addView(textFilter); content.addView(monologue);
            ListView list = new ListView(PlayerEngineActivity.this);
            list.setAdapter(adapter);
            content.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
            list.setOnItemClickListener((parent, view, position, id) -> {
                if (position < 0 || position >= filtered.size()) return;
                MessageRow row = filtered.get(position);
                if (kind == 47) messageAction(handle, task, 0, row.input);
                else if (kind == 33) sceneAction(handle, task, 0, row.input, 0);
                else if (row.input >= 1000000 && row.input < 2000000) editVariable(row);
            });
            TextWatcher watcher = new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                public void onTextChanged(CharSequence s, int start, int before, int count) { filter(); }
                public void afterTextChanged(Editable text) {}
            };
            nameFilter.addTextChangedListener(watcher); textFilter.addTextChangedListener(watcher);
            monologue.setOnCheckedChangeListener((button, checked) -> filter());
            dialog = new AlertDialog.Builder(PlayerEngineActivity.this)
                .setTitle(kind == 47 ? "消息列表" : kind == 33 ? "场景列表" : "场景调试")
                .setView(content).setNegativeButton("关闭", (window, which) ->
                    hideInspector()).create();
            dialog.setOnCancelListener(window -> hideInspector());
            dialog.setOnShowListener(window -> dialog.getWindow().setLayout(
                Math.max(1, root.getWidth() * 9 / 10), Math.max(1, root.getHeight() * 9 / 10)));
        }
        void hideInspector() {
            for (AlertDialog editor : new ArrayList<>(editors)) editor.dismiss();
            if (kind == 47) messageAction(handle, task, 1, 0);
            else sceneAction(handle, task, 1, 0, 0);
        }
        void close() {
            for (AlertDialog editor : new ArrayList<>(editors)) editor.dismiss();
            editors.clear();
            dialog.dismiss();
        }
        void editVariable(MessageRow row) {
            int index = row.input - 1000000;
            EditText value = new EditText(PlayerEngineActivity.this);
            value.setSingleLine(true);
            int start = row.text.indexOf(": "), end = row.text.indexOf(" (0x");
            value.setText(start >= 0 && end > start ? row.text.substring(start + 2, end) : "");
            AlertDialog editor = new AlertDialog.Builder(PlayerEngineActivity.this)
                .setTitle("变量 " + index).setView(value)
                .setNegativeButton("取消", null).setPositiveButton("写入", null).create();
            editor.setOnShowListener(window -> editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
                try {
                    String text = value.getText().toString().trim();
                    long number = text.startsWith("0x") || text.startsWith("0X")
                        ? Long.parseLong(text.substring(2), 16) : Long.parseLong(text);
                    if (number < Integer.MIN_VALUE || number > 0xffffffffL) throw new NumberFormatException();
                    sceneAction(handle, task, 2, index, (int)number);
                    editor.dismiss();
                } catch (NumberFormatException error) { value.setError("输入32位整数或0x十六进制值"); }
            }));
            editor.setOnDismissListener(window -> editors.remove(editor));
            editors.add(editor);
            editor.show();
        }
        void replace(ArrayList<MessageRow> rows) { this.rows = rows; filter(); }
        void filter() {
            String name = nameFilter.getText().toString(), text = textFilter.getText().toString();
            filtered.clear();
            for (MessageRow row : rows) {
                if ((!monologue.isChecked() || row.name.isEmpty())
                    && row.name.contains(name) && row.text.contains(text)) filtered.add(row);
            }
            adapter.notifyDataSetChanged();
        }
        final class RowsAdapter extends BaseAdapter {
            public int getCount() { return filtered.size(); }
            public Object getItem(int position) { return filtered.get(position); }
            public long getItemId(int position) { return Integer.toUnsignedLong(filtered.get(position).input); }
            public boolean hasStableIds() { return true; }
            public View getView(int position, View previous, android.view.ViewGroup parent) {
                TextView label = previous instanceof TextView ? (TextView)previous : new TextView(PlayerEngineActivity.this);
                MessageRow row = filtered.get(position);
                label.setPadding(12, 8, 12, 8);
                label.setText((row.name.isEmpty() ? "" : row.name + "\n") + row.text);
                return label;
            }
        }
    }

    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        long handle = inputSession();
        if (handle != 0 && event.getAction() == MotionEvent.ACTION_SCROLL) {
            int delta = Math.round(event.getAxisValue(MotionEvent.AXIS_VSCROLL) * 120);
            worker.post(() -> { if (session == handle && !paused) nativeInput(session, 6, delta, 0); });
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    @Override public boolean onKeyDown(int code, KeyEvent event) {
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.getRepeatCount() == 0) onBackPressed();
            return true;
        }
        long handle = inputSession();
        if (handle == 0) return super.onKeyDown(code, event);
        int scalar = event.getUnicodeChar();
        if (scalar >= 32 && Character.isValidCodePoint(scalar) && !event.isCtrlPressed()) {
            byte[] text = new String(Character.toChars(scalar)).getBytes(StandardCharsets.UTF_8);
            worker.post(() -> {
                if (session == handle && !paused) nativeText(session, 2, text, -1, -1);
            });
        }
        return key(handle, code, true) || super.onKeyDown(code, event);
    }

    @Override public void onBackPressed() {
        if (gameKeyboardSession != 0) endGameKeyboard(true);
        else if (playbackControls != null) hidePlaybackControls();
        else if (inputSession() != 0) showPlaybackControls();
        else super.onBackPressed();
    }

    private void showPlaybackControls() {
        long handle = inputSession();
        if (handle == 0 || paused || destroyed) return;

        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0x88000000);
        overlay.setOnClickListener(view -> hidePlaybackControls());
        overlay.setFocusable(false);
        overlay.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        ScrollView rail = new ScrollView(this);
        rail.setFillViewport(true);
        rail.setBackgroundColor(0xff182427);
        rail.setClickable(true);
        if (Build.VERSION.SDK_INT >= 28) rail.setAccessibilityPaneTitle("游戏控制");
        int availableWidth = root.getWidth() - root.getPaddingLeft() - root.getPaddingRight();
        overlay.addView(rail, new FrameLayout.LayoutParams(Math.max(1, Math.min(dp(300), availableWidth)),
            FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(dp(16), dp(12), dp(16), dp(12));
        rail.addView(actions);

        TextView heading = libraryText("游戏控制", 22, 0xffeaf3ee);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setPadding(dp(8), 0, dp(8), dp(8));
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        actions.addView(heading);

        Button resume = addPlaybackAction(actions, "继续游戏", () -> {});
        resume.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff9cddbd));
        resume.setTextColor(0xff172522);
        if (hasVisibleGameMenu()) {
            addPlaybackAction(actions, "原版菜单", () -> showGameMenu(handle, gameMenuRequest, gameMenuItems));
            addPlaybackMenuAction(actions, handle, "游戏内设置", GAME_MENU_EFFECT_SETTINGS);
            addPlaybackMenuAction(actions, handle, "消息列表", GAME_MENU_MESSAGE_LIST);
        }
        addPlaybackAction(actions, "游戏内返回（右键）", () -> tapGameInput(handle, 1, 2, 1));
        addPlaybackAction(actions, "取消 / 返回（Esc）", () -> tapGameInput(handle, 3, 4, 27));
        addPlaybackAction(actions, "确认（Enter）", () -> tapGameInput(handle, 3, 4, 13));
        addPlaybackAction(actions, heldCtrlSession == handle ? "松开 Ctrl" : "长按 Ctrl", () -> {
            if (inputSession() != handle || paused || destroyed) return;
            if (heldCtrlSession == handle) {
                releaseHeldCtrl();
            } else {
                heldCtrlSession = handle;
                sendHeldCtrl(handle, true);
            }
        });
        addPlaybackAction(actions, "上一页（Page Up）", () -> tapGameInput(handle, 3, 4, 33));
        addPlaybackAction(actions, "下一页（Page Down）", () -> tapGameInput(handle, 3, 4, 34));
        addPlaybackAction(actions, "软键盘", this::showGameKeyboard);
        addPlaybackAction(actions, externalLaunch ? "返回启动器" : "打开游戏库", () -> {
            if (externalLaunch) {
                confirmExit();
            } else {
                setLibraryVisible(true);
                refreshLibrary(null);
            }
        });
        addPlaybackAction(actions, "退出当前游戏", this::confirmExit);

        playbackControls = overlay;
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        resume.setFocusableInTouchMode(true);
        resume.requestFocus();
    }

    private boolean hasVisibleGameMenu() {
        return gameMenuItems != null && gameMenuButton != null && gameMenuButton.getVisibility() == View.VISIBLE;
    }

    private static boolean hasEnabledGameCommand(ArrayList<GameMenuItem> items, int command) {
        for (GameMenuItem item : items) {
            if (!item.enabled || item.separator) continue;
            if (item.children.isEmpty() ? item.command == command : hasEnabledGameCommand(item.children, command)) {
                return true;
            }
        }
        return false;
    }

    private void addPlaybackMenuAction(LinearLayout actions, long handle, String label, int command) {
        if (!hasEnabledGameCommand(gameMenuItems, command)) return;
        addPlaybackAction(actions, label, () -> {
            if (inputSession() == handle && hasVisibleGameMenu() && hasEnabledGameCommand(gameMenuItems, command)) {
                submitWindowUi(handle, gameMenuRequest, 3, command, 0, 0);
            }
        });
    }

    private Button addPlaybackAction(LinearLayout actions, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setMinimumHeight(dp(48));
        button.setOnClickListener(view -> {
            if (playbackControls == null) return;
            hidePlaybackControls();
            action.run();
        });
        actions.addView(button, new LinearLayout.LayoutParams(-1, -2));
        return button;
    }

    private void hidePlaybackControls() {
        if (playbackControls == null) return;
        root.removeView(playbackControls);
        playbackControls = null;
        restorePlaybackFocus();
    }

    private void resumeFromLibrary() {
        setLibraryVisible(false);
        restorePlaybackFocus();
    }

    private void restorePlaybackFocus() {
        if (!paused && !destroyed && inputSession() != 0 && !surfaceView.hasFocus()) {
            surfaceView.requestFocus();
        }
    }

    private void showGameKeyboard() {
        resumeFromLibrary();
        long handle = inputSession();
        if (handle == 0 || paused || destroyed || !surfaceView.hasFocus()) return;
        gameKeyboardSession = handle;
        InputMethodManager keyboardManager = (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        keyboardManager.restartInput(surfaceView);
        surfaceView.post(() -> {
            if (isGameKeyboardRequested() && gameKeyboardSession == handle
                    && !keyboardManager.showSoftInput(surfaceView, InputMethodManager.SHOW_IMPLICIT)) {
                endGameKeyboard(false);
            }
        });
    }

    private boolean isGameKeyboardRequested() {
        return gameKeyboardSession != 0 && gameKeyboardSession == inputSession() && !paused && !destroyed;
    }

    private void endGameKeyboard(boolean hideKeyboard) {
        long handle = gameKeyboardSession;
        if (handle == 0) return;
        gameKeyboardSession = 0;
        gameKeyboardWasVisible = false;
        inputConnectionId++;
        worker.post(() -> {
            if (session == handle) {
                activeInputConnectionId = 0;
                nativeInput(session, 9, 0, 0);
            }
        });
        InputMethodManager keyboardManager = (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        if (hideKeyboard) keyboardManager.hideSoftInputFromWindow(surfaceView.getWindowToken(), 0);
        keyboardManager.restartInput(surfaceView);
    }

    private void tapGameInput(long handle, int downKind, int upKind, int value) {
        worker.post(() -> {
            if (session != handle || paused) return;
            if (nativeInput(handle, downKind, value, 0) < 0
                    || nativeInput(handle, upKind, value, 0) < 0) {
                stopWithError("游戏快捷键输入失败", new IOException(nativeErrorText()));
            }
        });
    }

    private void releaseHeldCtrl() {
        long handle = heldCtrlSession;
        if (handle == 0) return;
        heldCtrlSession = 0;
        sendHeldCtrl(handle, false);
    }

    private void sendHeldCtrl(long handle, boolean down) {
        worker.post(() -> {
            if (session != handle || (down && paused)) return;
            if (nativeInput(handle, down ? 3 : 4, 17, 0) < 0) {
                stopWithError("Ctrl 输入失败", new IOException(nativeErrorText()));
            }
        });
    }
    @Override public boolean onKeyUp(int code, KeyEvent event) {
        return key(inputSession(), code, false) || super.onKeyUp(code, event);
    }
    private long inputSession() {
        return gameReady && playbackControls == null && panel.getVisibility() != View.VISIBLE ? messageUiSession : 0;
    }

    private boolean key(long handle, int code, boolean down) {
        return key(handle, code, down, 0);
    }

    private boolean key(long handle, int code, boolean down, long connectionId) {
        if (handle == 0) return false;
        int key;
        if (code == KeyEvent.KEYCODE_ENTER) key = 13;
        else if (code == KeyEvent.KEYCODE_TAB) key = 9;
        else if (code == KeyEvent.KEYCODE_DEL) key = 8;
        else if (code == KeyEvent.KEYCODE_SPACE) key = 32;
        else if (code == KeyEvent.KEYCODE_ESCAPE) key = 27;
        else if (code == KeyEvent.KEYCODE_CTRL_LEFT || code == KeyEvent.KEYCODE_CTRL_RIGHT) {
            if (heldCtrlSession == handle) return true;
            key = 17;
        }
        else if (code == KeyEvent.KEYCODE_DPAD_LEFT) key = 37;
        else if (code == KeyEvent.KEYCODE_DPAD_UP) key = 38;
        else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) key = 39;
        else if (code == KeyEvent.KEYCODE_DPAD_DOWN) key = 40;
        else if (code == KeyEvent.KEYCODE_PAGE_UP) key = 33;
        else if (code == KeyEvent.KEYCODE_PAGE_DOWN) key = 34;
        else if (code == KeyEvent.KEYCODE_SHIFT_LEFT || code == KeyEvent.KEYCODE_SHIFT_RIGHT) key = 16;
        else if (code >= KeyEvent.KEYCODE_A && code <= KeyEvent.KEYCODE_Z) key = 65 + code - KeyEvent.KEYCODE_A;
        else if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) key = 48 + code - KeyEvent.KEYCODE_0;
        else if (code >= KeyEvent.KEYCODE_F1 && code <= KeyEvent.KEYCODE_F12) key = 112 + code - KeyEvent.KEYCODE_F1;
        else return false;
        worker.post(() -> {
            if (session == handle && !paused && (connectionId == 0 || activeInputConnectionId == connectionId)) {
                nativeInput(session, down ? 3 : 4, key, 0);
            }
        });
        return true;
    }

    /** Host cursor overlay; excluded from the game's RGBA and save pixels. */
    private final class GamePointer extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();
        private int x, y;
        private boolean shown;
        private boolean cursorVisible = true;
        private Bitmap cursorImage;
        private int hotspotX, hotspotY;
        GamePointer() {
            super(PlayerEngineActivity.this);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        void move(int x, int y) { this.x = x; this.y = y; shown = true; invalidate(); }
        void hide() { shown = false; invalidate(); }
        void setCursorVisible(boolean visible) { cursorVisible = visible; updatePointerIcon(); }
        void setCursorImage(Bitmap image, int x, int y) {
            cursorImage = image;
            hotspotX = x;
            hotspotY = y;
            updatePointerIcon();
        }
        private void updatePointerIcon() {
            PointerIcon icon = cursorVisible && cursorImage != null
                ? PointerIcon.create(cursorImage, hotspotX, hotspotY)
                : PointerIcon.getSystemIcon(PlayerEngineActivity.this,
                    cursorVisible ? PointerIcon.TYPE_DEFAULT : PointerIcon.TYPE_NULL);
            surfaceView.setPointerIcon(icon);
            setPointerIcon(icon);
            invalidate();
        }
        @Override protected void onDraw(Canvas canvas) {
            if (!shown || !cursorVisible || logicalWidth <= 0 || logicalHeight <= 0) return;
            float density = getResources().getDisplayMetrics().density;
            float px = (float)x * getWidth() / logicalWidth;
            float py = (float)y * getHeight() / logicalHeight;
            canvas.save();
            canvas.translate(px, py);
            canvas.scale(density, density);
            if (cursorImage != null) {
                canvas.drawBitmap(cursorImage, -hotspotX, -hotspotY, null);
                canvas.restore();
                return;
            }
            arrow.reset();
            arrow.moveTo(0, 0); arrow.lineTo(0, 17); arrow.lineTo(4, 13);
            arrow.lineTo(8, 20); arrow.lineTo(11, 18); arrow.lineTo(7, 11);
            arrow.lineTo(13, 11); arrow.close();
            paint.setStyle(Paint.Style.FILL); paint.setColor(0xffffffff);
            canvas.drawPath(arrow, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1);
            paint.setColor(0xff000000); canvas.drawPath(arrow, paint);
            canvas.restore();
        }
    }

    private final class PlayerSurface extends SurfaceView {
        PlayerSurface() {
            super(PlayerEngineActivity.this);
            setFocusable(true);
            setFocusableInTouchMode(true);
        }
        @Override public boolean onCheckIsTextEditor() { return isGameKeyboardRequested(); }
        @Override public boolean onKeyPreIme(int code, KeyEvent event) {
            if (Build.VERSION.SDK_INT < 30 && gameKeyboardSession != 0 && code == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) endGameKeyboard(true);
                return true;
            }
            return super.onKeyPreIme(code, event);
        }
        @Override public InputConnection onCreateInputConnection(EditorInfo info) {
            if (!isGameKeyboardRequested()) return null;
            long handle = gameKeyboardSession;
            long connectionId = ++inputConnectionId;
            info.inputType = InputType.TYPE_CLASS_TEXT;
            info.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_DONE;
            worker.post(() -> {
                if (session == handle && !paused) {
                    if (activeInputConnectionId != 0) nativeInput(session, 9, 0, 0);
                    activeInputConnectionId = connectionId;
                    nativeInput(session, 9, 1, 0);
                }
            });
            return new BaseInputConnection(this, false) {
                private String composing = "";
                private boolean acceptsInput() { return inputConnectionId == connectionId && isGameKeyboardRequested(); }
                private boolean isActiveOnWorker() {
                    return session == handle && activeInputConnectionId == connectionId && !paused;
                }
                @Override public boolean commitText(CharSequence text, int cursor) {
                    if (!acceptsInput()) return false;
                    composing = "";
                    byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
                    worker.post(() -> {
                        if (isActiveOnWorker() && nativeText(session, 0, bytes, -1, -1) < 0) {
                            fail("文字输入失败", new IOException(nativeErrorText()));
                        }
                    });
                    return true;
                }
                @Override public boolean setComposingText(CharSequence text, int cursor) {
                    if (!acceptsInput()) return false;
                    composing = text.toString();
                    byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
                    int end = text.length();
                    worker.post(() -> {
                        if (isActiveOnWorker() && nativeText(session, 1, bytes, end, end) < 0) {
                            fail("文字组合失败", new IOException(nativeErrorText()));
                        }
                    });
                    return true;
                }
                @Override public boolean finishComposingText() {
                    if (!acceptsInput()) return false;
                    if (!composing.isEmpty()) {
                        String completed = composing;
                        composing = "";
                        commitText(completed, 1);
                    }
                    worker.post(() -> { if (isActiveOnWorker()) nativeInput(session, 10, 0, 0); });
                    return true;
                }
                @Override public void closeConnection() {
                    composing = "";
                    super.closeConnection();
                    worker.post(() -> {
                        if (session == handle && activeInputConnectionId == connectionId) {
                            activeInputConnectionId = 0;
                            nativeInput(session, 9, 0, 0);
                        }
                    });
                }
                @Override public boolean deleteSurroundingText(int before, int after) {
                    if (!acceptsInput()) return false;
                    if (before > 0) {
                        key(handle, KeyEvent.KEYCODE_DEL, true, connectionId);
                        key(handle, KeyEvent.KEYCODE_DEL, false, connectionId);
                    }
                    return true;
                }
                @Override public boolean sendKeyEvent(KeyEvent event) {
                    return acceptsInput() && key(handle, event.getKeyCode(), event.getAction() == KeyEvent.ACTION_DOWN, connectionId);
                }
                @Override public boolean performEditorAction(int action) {
                    if (!acceptsInput()) return false;
                    key(handle, KeyEvent.KEYCODE_ENTER, true, connectionId);
                    key(handle, KeyEvent.KEYCODE_ENTER, false, connectionId);
                    return true;
                }
            };
        }
    }

    private void resizeSurface() {
        if (root == null || logicalWidth <= 0 || logicalHeight <= 0) return;
        int width = root.getWidth() - root.getPaddingLeft() - root.getPaddingRight();
        int height = root.getHeight() - root.getPaddingTop() - root.getPaddingBottom();
        if (width <= 0 || height <= 0) return;
        double scale = Math.min((double)width / logicalWidth, (double)height / logicalHeight);
        FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(
            Math.max(1, (int)(logicalWidth * scale)), Math.max(1, (int)(logicalHeight * scale)), Gravity.CENTER);
        if (surfaceView.getWidth() != layout.width || surfaceView.getHeight() != layout.height) {
            surfaceView.setLayoutParams(layout);
            gamePointer.setLayoutParams(new FrameLayout.LayoutParams(layout.width, layout.height, Gravity.CENTER));
        }
    }

    boolean tweetForeground() { return !paused && !destroyed; }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (worker == null || destroyed) return;
        if (!focused) releaseHeldCtrl();
        final long focusSession = messageUiSession;
        if (focusSession == 0) return;
        worker.post(() -> {
            if (session == focusSession && nativeInput(session, 11, focused ? 1 : 0, 0) < 0) {
                stopWithError("Window focus failed", new IOException(nativeErrorText()));
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        if (panel != null && panel.getVisibility() == View.VISIBLE) refreshLibrary(null);
        generalStopped = false;
        applyGeneralCamouflage();
        paused = false;
        restorePlaybackFocus();
        if (tweetHost != null) tweetHost.resumed();
        if (audioManager != null && audioFocus != null) {
            audioFocusGranted = audioManager.requestAudioFocus(audioFocus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
        if (worker != null) worker.post(() -> {
            if (session != 0 && nativeInput(session, 8, audioFocusGranted ? 0 : 1, 0) < 0) {
                stopWithError("恢复声音失败", new IOException(nativeErrorText()));
                return;
            }
            if (session != 0 && nativeInput(session, 5, 1, 0) < 0) {
                stopWithError("恢复游戏失败", new IOException(nativeErrorText()));
                return;
            }
            nextFrame = SystemClock.uptimeMillis();
            if (session != 0 && nativeTickDelay(session, true) < 0) {
                stopWithError("恢复游戏时钟失败", new IOException(nativeErrorText()));
                return;
            }
            scheduleFrame();
        });
    }
    @Override protected void onPause() {
        paused = true;
        releaseHeldCtrl();
        activePointerId = -1;
        endGameKeyboard(true);
        if (tweetHost != null) tweetHost.paused();
        if (audioManager != null && audioFocus != null) {
            audioManager.abandonAudioFocusRequest(audioFocus);
            audioFocusGranted = false;
        }
        if (worker != null) worker.post(() -> {
            worker.removeCallbacks(frame);
            frameScheduled = false;
            presentedSurface = null;
            final long handle = session;
            presenter.post(() -> {
                resetPresentationSurface();
                worker.post(() -> {
                    if (session != handle) return;
                    presentedSurface = null;
                    requestPresentation(renderSurface);
                });
            });
            if (session != 0 && nativeInput(session, 5, 0, 0) < 0) {
                stopWithError("暂停游戏失败", new IOException(nativeErrorText()));
            }
        });
        super.onPause();
    }
    @Override protected void onStop() {
        generalStopped = true;
        applyGeneralCamouflage();
        super.onStop();
    }
    @Override protected void onDestroy() {
        destroyed = true;
        ui.removeCallbacks(watchNativeStep);
        synchronized (surfaceLock) {
            renderSurface = null;
        }
        resetGameUi();
        if (worker != null) worker.post(() -> {
            try {
                closeSession();
            } finally {
                presenter.post(this::resetPresentationSurface);
                presenterThread.quitSafely();
                playerThread.quitSafely();
                if (externalLaunch && isFinishing()) {
                    ui.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 250);
                }
            }
        });
        super.onDestroy();
    }
    @Override public void surfaceCreated(SurfaceHolder holder) {}

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        synchronized (surfaceLock) {
            renderSurface = !destroyed && width > 0 && height > 0
                ? new RenderSurface(holder.getSurface()) : null;
        }
        worker.post(this::scheduleFrame);
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        // SurfaceView requires the renderer to finish using this Surface
        // before the callback returns.
        synchronized (surfaceLock) {
            renderSurface = null;
            surfaceNeedsDetach = true;
        }
        presenter.post(() -> {
            synchronized (surfaceLock) {
                detachSurfaceLocked();
            }
        });
    }

    private boolean detachSurfaceLocked() {
        if (surfaceNeedsDetach && nativeDetachSurface() == 0) surfaceNeedsDetach = false;
        return !surfaceNeedsDetach;
    }

    private void resetPresentationSurface() {
        synchronized (surfaceLock) {
            surfaceNeedsDetach = true;
            detachSurfaceLocked();
        }
    }

    private String nativeErrorText() { return new String(nativeError(), StandardCharsets.UTF_8); }
    private void reportOptionalHostFailure(long handle, String title, String detail) {
        String message = title + ": " + detail;
        android.util.Log.w("CS2Player", message);
        appendDiagnostic(message);
        ui.post(() -> {
            if (!destroyed && messageUiSession == handle) Toast.makeText(this, title, Toast.LENGTH_LONG).show();
        });
    }

    private void fail(String title, Exception error) {
        String message = error.getMessage() == null ? error.toString() : error.getMessage();
        android.util.Log.e("CS2Player", title, error);
        appendDiagnostic(title + ": " + message);
        ui.post(() -> showFailure(title, error instanceof GameLibrary.DirectoryFailure
            ? ((GameLibrary.DirectoryFailure)error).status.label : message));
    }

    private void showFailure(String title, String message) {
        if (destroyed) return;
        endGameKeyboard(true);
        if (!externalLaunch) refreshLibrary(title);
        status.setText(title);
        updateLibraryActions();
        setLibraryVisible(!externalLaunch);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton("确定", externalLaunch ? (window, which) -> finish() : null)
            .create();
        if (externalLaunch) dialog.setOnCancelListener(window -> finish());
        dialog.show();
    }
}
