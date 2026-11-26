package org.catsystem2.player;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Collator;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONException;

final class GameLibrary {
    private static final Object LIBRARY_WRITE = new Object();
    private static final String LOCAL_STORAGE = "com.android.externalstorage.documents";
    private static final String[] DOCUMENT_COLUMNS = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE
    };
    private final ContentResolver resolver;
    private final SharedPreferences settings;
    private final File files;
    private final StorageManager storage;
    private final Context context;

    GameLibrary(Context context) {
        this.context = context.getApplicationContext();
        resolver = context.getContentResolver();
        settings = context.getSharedPreferences("game-library", Context.MODE_PRIVATE);
        files = context.getFilesDir();
        storage = context.getSystemService(StorageManager.class);
    }

    static final class Entry {
        final String key;
        final String title;
        final Uri tree;
        final String document;
        final File game;
        final File saves;
        final String systemDocument;
        final File systemMirror;

        Entry(String key, String title, Uri tree, String document, File game, File saves,
              String systemDocument, File systemMirror) {
            this.key = key;
            this.title = title;
            this.tree = tree;
            this.document = document;
            this.game = game;
            this.saves = saves;
            this.systemDocument = systemDocument;
            this.systemMirror = systemMirror;
        }

    }

    enum DirectoryStatus {
        MISSING("目录不存在"),
        DENIED("无权读取目录"),
        UNAVAILABLE("目录不可用");

        final String label;

        DirectoryStatus(String label) { this.label = label; }
    }

    static final class DirectoryFailure extends IOException {
        final DirectoryStatus status;

        DirectoryFailure(DirectoryStatus status, String stage, File path, Throwable cause) {
            super("state=" + status + " stage=" + stage + " path=" + path
                + (cause == null ? "" : " cause=" + cause.getMessage()), cause);
            this.status = status;
        }
    }

    private static final class Contents {
        final ArrayList<Folder> children = new ArrayList<>();
        boolean startup;
    }

    private static final class GameFolder {
        final Uri tree;
        final Folder root;
        final Folder system;

        GameFolder(Uri tree, Folder root, Folder system) {
            this.tree = tree;
            this.root = root;
            this.system = system;
        }
    }

    static final class ScanResult {
        int found;
        int unreadable;
    }

    private static final class Folder {
        final String id;
        final String title;
        final int depth;

        Folder(String id, String title, int depth) {
            this.id = id;
            this.title = title;
            this.depth = depth;
        }
    }

    ArrayList<Entry> entries() {
        ArrayList<Entry> games = new ArrayList<>();
        for (Map.Entry<String, ?> row : settings.getAll().entrySet()) {
            if (!row.getKey().startsWith("entry.") || !(row.getValue() instanceof String)) continue;
            try {
                String key = row.getKey().substring(6);
                JSONArray fields = new JSONArray((String)row.getValue());
                String tree = fields.getString(1);
                String systemMirror = fields.optString(6);
                games.add(new Entry(key, fields.getString(0), tree.isEmpty() ? null : Uri.parse(tree),
                    fields.getString(2), new File(fields.getString(3)), new File(fields.getString(4)),
                    fields.optString(5), systemMirror.isEmpty() ? null : new File(systemMirror)));
            } catch (JSONException ignored) {
                // An unreadable record cannot prevent the rest of the library opening.
            }
        }
        Collator titles = Collator.getInstance();
        games.sort((a, b) -> {
            int order = titles.compare(a.title, b.title);
            return order == 0 ? a.key.compareTo(b.key) : order;
        });
        return games;
    }

    int language(Entry game) {
        int language = settings.getInt("language." + game.key, 0);
        return language >= 0 && language <= 2 ? language : 0;
    }

    void setLanguage(Entry game, int language) {
        settings.edit().putInt("language." + game.key, language).apply();
    }

    void adoptPreviousImport(SharedPreferences previous) {
        String game = previous.getString("game", null);
        String saves = previous.getString("saves", null);
        if (game == null || saves == null) return;
        File gameDir = new File(game);
        File saveDir = new File(saves);
        String key = saveDir.getName();
        if (!gameDir.isAbsolute() || !saveDir.isAbsolute() || !key.matches("[0-9a-f]{64}")) return;
        // Keep the import visible so refresh can report why its directory is unavailable.
        synchronized (LIBRARY_WRITE) {
            if (settings.contains("entry." + key) || settings.contains("alias." + key)) return;
            save(settings.edit(), new Entry(key, "已导入游戏", null, "", gameDir, saveDir, "", null)).apply();
        }
    }

    ArrayList<Uri> persistedFolders() {
        ArrayList<Uri> folders = new ArrayList<>();
        for (UriPermission permission : resolver.getPersistedUriPermissions()) {
            if (permission.isReadPermission() && DocumentsContract.isTreeUri(permission.getUri())) {
                folders.add(permission.getUri());
            }
        }
        return folders;
    }

    ScanResult scan(List<Uri> trees, Uri selected) throws IOException {
        if (selected != null) localDirectory(selected, DocumentsContract.getTreeDocumentId(selected));
        ArrayList<Uri> grants = persistedFolders();
        if (selected != null && !grants.contains(selected)) grants.add(selected);
        LinkedHashMap<String, GameFolder> games = new LinkedHashMap<>();
        ScanResult result = new ScanResult();
        for (Uri tree : trees) {
            if (!LOCAL_STORAGE.equals(tree.getAuthority())) continue;
            try {
                discover(tree, grants, games, result);
            } catch (IOException | SecurityException error) {
                if (tree.equals(selected)) throw error;
                result.unreadable++;
            }
        }
        synchronized (LIBRARY_WRITE) {
            merge(games, grants, selected, result);
        }
        return result;
    }

    private void discover(Uri tree, List<Uri> grants, Map<String, GameFolder> games, ScanResult result)
            throws IOException {
        String root = DocumentsContract.getTreeDocumentId(tree);
        ArrayDeque<Folder> pending = new ArrayDeque<>();
        pending.add(new Folder(root, folderTitle(tree, root), 0));
        HashSet<String> visited = new HashSet<>();
        HashMap<String, Contents> inspected = new HashMap<>();
        while (!pending.isEmpty()) {
            Folder folder = pending.removeFirst();
            if (!visited.add(folder.id)) continue;
            if (visited.size() > 4096) throw new IOException("待扫描目录太多，请选择更具体的文件夹");
            Contents contents = inspected.remove(folder.id);
            if (contents == null) {
                try {
                    contents = readFolder(tree, folder);
                } catch (IOException | SecurityException error) {
                    if (folder.depth == 0) throw error;
                    result.unreadable++;
                    continue;
                }
            }
            Folder system = null;
            for (Folder child : contents.children) {
                if (!child.title.equalsIgnoreCase("system") || visited.contains(child.id)) continue;
                try {
                    Contents nested = readFolder(tree, child);
                    if (nested.startup) {
                        system = child;
                        break;
                    }
                    inspected.put(child.id, nested);
                } catch (IOException | SecurityException error) {
                    if (visited.add(child.id)) result.unreadable++;
                }
            }
            if (contents.startup || system != null) {
                GameFolder game = new GameFolder(tree, folder, system);
                if (folder.depth == 0 && contents.startup && folder.title.equalsIgnoreCase("system")) {
                    GameFolder parent = authorizedParent(tree, folder, grants);
                    if (parent != null) game = parent;
                }
                String identity = key(game.tree, game.root.id);
                GameFolder known = games.get(identity);
                if (known == null || (known.system == null && game.system != null)
                        || (!grants.contains(known.tree) && grants.contains(game.tree))) {
                    games.put(identity, game);
                }
            } else if (folder.depth < 8) {
                pending.addAll(contents.children);
            }
        }
    }

    private String folderTitle(Uri tree, String document) {
        try (Cursor row = resolver.query(DocumentsContract.buildDocumentUriUsingTree(tree, document),
                DOCUMENT_COLUMNS, null, null, null)) {
            if (row != null && row.moveToFirst() && row.getString(1) != null) return row.getString(1);
        }
        return document;
    }

    private Contents readFolder(Uri tree, Folder folder) throws IOException {
        Contents contents = new Contents();
        Uri uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folder.id);
        try (Cursor rows = resolver.query(uri, DOCUMENT_COLUMNS, null, null, null)) {
            if (rows == null) throw new IOException("无法读取目录：" + folder.title);
            while (rows.moveToNext()) {
                String id = rows.getString(0);
                String name = rows.getString(1);
                if (id == null || name == null) continue;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(rows.getString(2))) {
                    contents.children.add(new Folder(id, name, folder.depth + 1));
                } else {
                    contents.startup |= isStartup(name);
                }
            }
        }
        return contents;
    }

    private static boolean isStartup(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals("kcs.int") || lower.equals("main.kcs")
            || lower.equals("start.kcs") || lower.equals("startup.kcs");
    }

    private GameFolder authorizedParent(Uri source, Folder child, List<Uri> grants) {
        for (Uri grant : grants) {
            if (!source.getAuthority().equals(grant.getAuthority())) continue;
            String root = DocumentsContract.getTreeDocumentId(grant);
            if (root.equals(child.id)) continue;
            String parent = root;
            try {
                DocumentsContract.Path path = DocumentsContract.findDocumentPath(resolver,
                    DocumentsContract.buildDocumentUriUsingTree(grant, child.id));
                if (path != null) {
                    List<String> ids = path.getPath();
                    int last = ids.size() - 1;
                    if (last > 0 && child.id.equals(ids.get(last))) parent = ids.get(last - 1);
                }
            } catch (FileNotFoundException | RuntimeException unsupported) {
                // Some providers only support listing the granted root.
            }
            if (parent.equals(root) && "com.android.externalstorage.documents".equals(grant.getAuthority())) {
                int separator = child.id.lastIndexOf('/');
                if (separator < 0) separator = child.id.indexOf(':') + 1;
                if (separator > 0 && separator < child.id.length()) parent = child.id.substring(0, separator);
            }
            try {
                Folder folder = new Folder(parent, folderTitle(grant, parent), 0);
                for (Folder listed : readFolder(grant, folder).children) {
                    if (listed.id.equals(child.id) && listed.title.equalsIgnoreCase("system")) {
                        return new GameFolder(grant, folder, listed);
                    }
                }
            } catch (IOException | SecurityException | IllegalArgumentException inaccessible) {
                // A document ID alone does not confer a SAF grant.
            }
        }
        return null;
    }

    private void merge(Map<String, GameFolder> games, List<Uri> grants, Uri selected, ScanResult result) {
        HashMap<String, Entry> existing = new HashMap<>();
        HashMap<String, Entry> byDocument = new HashMap<>();
        HashSet<String> systems = new HashSet<>();
        for (Entry entry : entries()) {
            existing.put(entry.key, entry);
            if (entry.tree == null) continue;
            byDocument.put(key(entry.tree, entry.document), entry);
            if (!entry.systemDocument.isEmpty()) systems.add(key(entry.tree, entry.systemDocument));
        }
        for (GameFolder game : games.values()) {
            if (game.system != null) systems.add(key(game.tree, game.system.id));
        }
        SharedPreferences.Editor changes = settings.edit();
        for (Map.Entry<String, GameFolder> item : games.entrySet()) {
            String rootKey = item.getKey();
            GameFolder game = item.getValue();
            if (game.system == null && systems.contains(rootKey)) continue;
            Entry root = byDocument.get(rootKey);
            if (root == null) root = previousEntry(existing, grants, game.tree, game.root.id, rootKey);
            String systemId = game.system == null ? "" : game.system.id;
            String systemKey = game.system == null ? "" : key(game.tree, systemId);
            Entry oldSystem = byDocument.get(systemKey);
            if (oldSystem == null && game.system != null) {
                oldSystem = previousEntry(existing, grants, game.tree, systemId, systemKey);
            }
            // If both records exist, each keeps its own save identity.
            Entry previous = root != null ? root : oldSystem;
            String identity = previous == null ? rootKey : previous.key;
            File mirror = root != null ? root.game : mirror(identity, "current");
            File systemMirror = root == null ? null : root.systemMirror;
            if (game.system != null) {
                if (oldSystem != null && oldSystem.game.isDirectory() && systemMirror == null) {
                    systemMirror = oldSystem.game;
                }
                if (root == null) {
                    if (oldSystem != null) mirror = mirror(identity, "current-root");
                } else if (root.systemDocument.isEmpty() && !new File(root.game, game.system.title).isDirectory()) {
                    mirror = mirror(identity, "current-root");
                }
            }
            File saves = previous == null ? new File(files, "saves/" + identity) : previous.saves;
            Uri tree = previous != null && previous.tree != null && grants.contains(previous.tree)
                    && game.root.id.equals(previous.document) ? previous.tree : game.tree;
            save(changes, new Entry(identity, game.root.title, tree, game.root.id, mirror, saves,
                systemId, systemMirror));
            result.found++;
        }
        if (selected != null) changes.putString("last-folder", selected.toString());
        changes.apply();
    }

    private Entry previousEntry(Map<String, Entry> existing, List<Uri> grants, Uri tree, String id,
                                String canonical) {
        Entry entry = existing.get(canonical);
        if (entry != null) return entry;
        for (Uri grant : grants) {
            if (tree.getAuthority().equals(grant.getAuthority())
                    && id.equals(DocumentsContract.getTreeDocumentId(grant))) {
                entry = existing.get(digest(grant.toString()));
                if (entry != null) return entry;
            }
        }
        // Older APKs hashed the URI returned by DocumentsUI. The canonical
        // tree URI still identifies that mirror if its original grant is gone.
        Uri legacyTree = DocumentsContract.buildTreeDocumentUri(tree.getAuthority(), id);
        Entry legacy = existing.get(digest(legacyTree.toString()));
        if (legacy != null) return legacy;
        File game = mirror(canonical, "current");
        File saves = new File(files, "saves/" + canonical);
        return game.isDirectory() || saves.isDirectory()
            ? new Entry(canonical, "", null, "", game, saves, "", null) : null;
    }

    private File mirror(String identity, String name) {
        return new File(files, "games/" + identity + "/" + name);
    }

    boolean hasStorageAccess() {
        return Build.VERSION.SDK_INT >= 30 ? Environment.isExternalStorageManager()
            : context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    File gameDirectory(Entry entry) throws DirectoryFailure {
        if (entry.tree != null) return localDirectory(entry.tree, entry.document);
        return checkedDirectory(entry.game, null);
    }

    private File localDirectory(Uri tree, String document) throws DirectoryFailure {
        if (!LOCAL_STORAGE.equals(tree.getAuthority())) {
            throw new DirectoryFailure(DirectoryStatus.UNAVAILABLE, "unsupported-provider", null, null);
        }
        int separator = document.indexOf(':');
        if (separator <= 0) {
            throw new DirectoryFailure(DirectoryStatus.UNAVAILABLE, "invalid-document-id", null, null);
        }
        String volumeId = document.substring(0, separator);
        File volume = null;
        if (volumeId.equalsIgnoreCase("primary")) {
            volume = Environment.getExternalStorageDirectory();
        } else if (Build.VERSION.SDK_INT >= 30 && storage != null) {
            for (StorageVolume candidate : storage.getStorageVolumes()) {
                if (volumeId.equalsIgnoreCase(candidate.getUuid())) {
                    volume = candidate.getDirectory();
                    break;
                }
            }
        }
        if (volume == null) {
            throw new DirectoryFailure(DirectoryStatus.UNAVAILABLE, "volume-not-found", null, null);
        }
        File path = new File(volume, document.substring(separator + 1));
        try {
            if (!hasStorageAccess()) {
                throw new DirectoryFailure(DirectoryStatus.DENIED, "storage-permission", path, null);
            }
            String state = Environment.getExternalStorageState(volume);
            if (!mounted(state)) {
                throw new DirectoryFailure(DirectoryStatus.UNAVAILABLE, "volume-state:" + state, path, null);
            }
            File root = volume.getCanonicalFile();
            File directory = path.getCanonicalFile();
            if (!directory.toPath().startsWith(root.toPath())) {
                throw new DirectoryFailure(DirectoryStatus.UNAVAILABLE, "outside-volume", directory, null);
            }
            return checkedDirectory(directory, root);
        } catch (DirectoryFailure error) {
            throw error;
        } catch (SecurityException error) {
            throw new DirectoryFailure(DirectoryStatus.DENIED, "resolve-directory", path, error);
        } catch (IOException error) {
            throw new DirectoryFailure(DirectoryStatus.UNAVAILABLE, "resolve-directory", path, error);
        }
    }

    private File checkedDirectory(File directory, File volume) throws DirectoryFailure {
        String path = directory.getAbsolutePath();
        String stage = "stat-directory";
        try {
            if (!OsConstants.S_ISDIR(Os.stat(path).st_mode)) {
                throw new DirectoryFailure(DirectoryStatus.UNAVAILABLE, "not-a-directory", directory, null);
            }
            stage = "read-directory";
            if (!Os.access(path, OsConstants.R_OK | OsConstants.X_OK)) {
                throw new DirectoryFailure(DirectoryStatus.DENIED, stage, directory, null);
            }
            return directory;
        } catch (ErrnoException error) {
            DirectoryStatus status = DirectoryStatus.UNAVAILABLE;
            if (error.errno == OsConstants.EACCES || error.errno == OsConstants.EPERM) {
                status = DirectoryStatus.DENIED;
            } else if (error.errno == OsConstants.ENOENT) {
                status = absenceStatus(directory, volume);
            }
            throw new DirectoryFailure(status, stage, directory, error);
        } catch (SecurityException error) {
            throw new DirectoryFailure(DirectoryStatus.DENIED, stage, directory, error);
        }
    }

    private DirectoryStatus absenceStatus(File directory, File volume) {
        try {
            if (volume == null && storage != null) {
                StorageVolume external = storage.getStorageVolume(directory);
                if (external != null) {
                    if (!mounted(external.getState())) return DirectoryStatus.UNAVAILABLE;
                    if (!hasStorageAccess()) return DirectoryStatus.DENIED;
                    if (Build.VERSION.SDK_INT >= 30) {
                        volume = external.getDirectory();
                        if (volume == null) return DirectoryStatus.UNAVAILABLE;
                        volume = volume.getCanonicalFile();
                        directory = directory.getCanonicalFile();
                    }
                }
            }
            if (directory.equals(volume)) return DirectoryStatus.UNAVAILABLE;
            if (Build.VERSION.SDK_INT >= 30 && volume != null) {
                String relative = volume.toPath().relativize(directory.toPath()).toString().toLowerCase(Locale.ROOT);
                for (String restricted : new String[] {"android/data", "android/obb"}) {
                    if (relative.equals(restricted)) return DirectoryStatus.DENIED;
                    if (relative.startsWith(restricted + "/")) {
                        String own = restricted + "/" + context.getPackageName().toLowerCase(Locale.ROOT);
                        if (!relative.equals(own) && !relative.startsWith(own + "/")) return DirectoryStatus.DENIED;
                    }
                }
            }
        } catch (SecurityException error) {
            return DirectoryStatus.DENIED;
        } catch (IOException error) {
            return DirectoryStatus.UNAVAILABLE;
        }
        // ENOENT may be returned for hidden paths. Check the nearest existing parent.
        for (File parent = directory.getParentFile(); parent != null; parent = parent.getParentFile()) {
            String path = parent.getAbsolutePath();
            try {
                if (!OsConstants.S_ISDIR(Os.stat(path).st_mode)) return DirectoryStatus.UNAVAILABLE;
                return Os.access(path, OsConstants.R_OK | OsConstants.X_OK)
                    ? DirectoryStatus.MISSING : DirectoryStatus.DENIED;
            } catch (ErrnoException error) {
                if (error.errno == OsConstants.EACCES || error.errno == OsConstants.EPERM) {
                    return DirectoryStatus.DENIED;
                }
                if (error.errno != OsConstants.ENOENT || parent.equals(volume)) return DirectoryStatus.UNAVAILABLE;
            } catch (SecurityException error) {
                return DirectoryStatus.DENIED;
            }
        }
        return DirectoryStatus.UNAVAILABLE;
    }

    private static boolean mounted(String state) {
        return Environment.MEDIA_MOUNTED.equals(state) || Environment.MEDIA_MOUNTED_READ_ONLY.equals(state);
    }

    Uri lastFolder() {
        String tree = settings.getString("last-folder", null);
        return tree == null ? null : Uri.parse(tree);
    }

    private static SharedPreferences.Editor save(SharedPreferences.Editor changes, Entry entry) {
        JSONArray fields = new JSONArray();
        fields.put(entry.title).put(entry.tree == null ? "" : entry.tree.toString()).put(entry.document)
            .put(entry.game.getAbsolutePath()).put(entry.saves.getAbsolutePath()).put(entry.systemDocument)
            .put(entry.systemMirror == null ? "" : entry.systemMirror.getAbsolutePath());
        return changes.putString("entry." + entry.key, fields.toString());
    }

    private static String key(Uri tree, String document) {
        return digest(DocumentsContract.buildTreeDocumentUri(tree.getAuthority(), document).toString());
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder text = new StringBuilder(64);
            for (byte item : bytes) text.append(String.format(Locale.ROOT, "%02x", item & 255));
            return text.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
