package org.neosteam.revival.ota;

import android.system.ErrnoException;
import android.system.Os;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Downloads immutable bundles and atomically publishes the manifest that selects them. */
public final class ContentUpdater {
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final int BUFFER_BYTES = 64 * 1024;
    private static final long PROGRESS_INTERVAL_NS = 100_000_000;
    private static final Pattern CONTENT_RANGE = Pattern.compile(
        "bytes ([0-9]{1,10})-([0-9]{1,10})/([0-9]{1,10})", Pattern.CASE_INSENSITIVE);
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final File root;
    private final File bundles;
    private final File parts;
    private final File activeManifest;
    private final boolean allowLocalHttp;

    public ContentUpdater(File privateRoot, boolean allowLocalHttp) {
        root = Objects.requireNonNull(privateRoot).getAbsoluteFile();
        bundles = new File(root, "bundles");
        parts = new File(root, "parts");
        activeManifest = new File(root, "active-manifest.json");
        this.allowLocalHttp = allowLocalHttp;
    }

    public record Progress(String stage, String path, long completedBytes, long totalBytes,
                           int completedAssets, int totalAssets) {}

    public record UpdateResult(int assetCount, int downloadedBundles, long downloadedBytes) {}

    public static final class Cancellation {
        private volatile boolean cancelled;
        private HttpURLConnection connection;

        public void cancel() {
            HttpURLConnection active;
            synchronized (this) {
                if (cancelled) return;
                cancelled = true;
                active = connection;
            }
            if (active != null) {
                // Disconnect outside the UI thread. The transport may close a blocked read.
                new Thread(active::disconnect, "ota-disconnect").start();
            }
        }

        public boolean isCancelled() { return cancelled; }

        private void check() throws InterruptedIOException {
            if (cancelled || Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Content update paused");
            }
        }

        private synchronized void attach(HttpURLConnection active) throws InterruptedIOException {
            check();
            connection = active;
        }

        private synchronized void detach(HttpURLConnection active) {
            if (connection == active) connection = null;
        }

        private synchronized void publish(File source, File target) throws IOException {
            check();
            replace(source, target);
        }
    }

    /** An immutable view of the selected logical paths and their verified bundle locations. */
    public static final class ActiveContent {
        private final File bundles;
        private final ContentManifest manifest;
        private final List<String> paths;

        private ActiveContent(File bundles, ContentManifest manifest) {
            this.bundles = bundles;
            this.manifest = manifest;
            paths = manifest == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(manifest.assets.keySet()));
        }

        public boolean installed() { return manifest != null; }
        public List<String> paths() { return paths; }

        public File asset(String logicalPath) {
            ContentManifest.Asset asset = manifest == null ? null : manifest.assets.get(logicalPath);
            if (asset == null) return null;
            File bundle = new File(bundles, asset.sha256());
            return bundle.isFile() && bundle.length() == asset.bytes() ? bundle : null;
        }
    }

    public File activeRoot() { return root; }
    public boolean hasActiveManifest() { return activeManifest.isFile(); }
    public List<String> activePaths() throws IOException { return activeContent().paths(); }
    public File activeAsset(String logicalPath) throws IOException { return activeContent().asset(logicalPath); }

    public ActiveContent activeContent() throws IOException {
        if (!hasActiveManifest()) return new ActiveContent(bundles, null);
        try (FileInputStream input = new FileInputStream(activeManifest)) {
            return new ActiveContent(bundles, ContentManifest.read(readManifest(input, null)));
        }
    }

    /**
     * Performs a foreground check on the calling worker thread.
     * Progress bytes count each distinct bundle once, including existing verified bytes.
     */
    public UpdateResult update(URL manifestUrl, Cancellation cancellation, Consumer<Progress> progress)
            throws IOException {
        Objects.requireNonNull(cancellation).check();
        Objects.requireNonNull(progress);
        validateUrl(manifestUrl);
        ensureDirectory(root);
        ensureDirectory(bundles);
        ensureDirectory(parts);
        try (RandomAccessFile lockFile = new RandomAccessFile(new File(root, "update.lock"), "rw");
                FileLock lock = lockFile.getChannel().tryLock()) {
            if (lock == null) throw new IOException("Another content update is running");
            return updateLocked(manifestUrl, cancellation, progress);
        } catch (OverlappingFileLockException error) {
            throw new IOException("Another content update is running", error);
        }
    }

    private UpdateResult updateLocked(URL manifestUrl, Cancellation cancellation, Consumer<Progress> progress)
            throws IOException {
        progress.accept(new Progress("checking", null, 0, 0, 0, 0));
        byte[] bytes = fetchManifest(manifestUrl, cancellation);
        ContentManifest manifest = ContentManifest.read(bytes);
        UpdateProgress report = new UpdateProgress(manifest, progress);
        byte[] buffer = new byte[BUFFER_BYTES];
        Set<String> checked = new HashSet<>();
        int downloadedBundles = 0;
        long downloadedBytes = 0;
        for (ContentManifest.Asset asset : manifest.assets.values()) {
            cancellation.check();
            if (checked.add(asset.sha256())) {
                long transferred = installBundle(manifestUrl, asset, cancellation, report, buffer);
                if (transferred > 0) {
                    downloadedBundles++;
                    downloadedBytes += transferred;
                }
                report.completeBundle(asset.bytes());
            }
            report.completeAsset(asset.path());
        }
        report.publish();
        publishManifest(bytes, cancellation);
        return new UpdateResult(manifest.assets.size(), downloadedBundles, downloadedBytes);
    }

    private long installBundle(URL manifestUrl, ContentManifest.Asset asset, Cancellation cancellation,
                               UpdateProgress report, byte[] buffer) throws IOException {
        File bundle = new File(bundles, asset.sha256());
        report.emit("verifying", asset.path(), 0, false);
        if (verify(bundle, asset, cancellation, buffer)) return 0;

        File partial = new File(parts, asset.sha256() + ".part");
        long offset = partial.length();
        if (offset >= asset.bytes()) {
            if (verify(partial, asset, cancellation, buffer)) {
                cancellation.publish(partial, bundle);
                return 0;
            }
            remove(partial);
            offset = 0;
        }

        long downloaded = fetchBundle(new URL(manifestUrl, asset.bundle()), partial, asset, offset,
            cancellation, report, buffer);
        report.emit("verifying", asset.path(), asset.bytes(), true);
        if (!verify(partial, asset, cancellation, buffer)) {
            remove(partial);
            throw new IOException("SHA-256 verification failed for " + asset.path());
        }
        cancellation.publish(partial, bundle);
        return downloaded;
    }

    private long fetchBundle(URL url, File partial, ContentManifest.Asset asset, long offset,
                             Cancellation cancellation, UpdateProgress report, byte[] buffer) throws IOException {
        HttpURLConnection connection = open(url, cancellation);
        try {
            if (offset > 0) connection.setRequestProperty("Range", "bytes=" + offset + "-");
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_OK) {
                // A server that ignores Range sends the complete body. Replace the prefix in this case.
                offset = 0;
            } else if (status == HttpURLConnection.HTTP_PARTIAL && offset > 0) {
                verifyRange(connection.getHeaderField("Content-Range"), offset, asset.bytes());
            } else {
                throw new IOException("Bundle request returned HTTP " + status + " for " + asset.path());
            }
            verifyIdentityEncoding(connection);
            long expected = asset.bytes() - offset;
            long declared = contentLength(connection);
            if (declared >= 0 && declared != expected) throw new IOException("Incorrect bundle Content-Length");
            report.emit("downloading", asset.path(), offset, true);
            cancellation.check();
            boolean extra;
            try (InputStream input = connection.getInputStream();
                    FileOutputStream output = new FileOutputStream(partial, offset > 0)) {
                long position = offset;
                while (position < asset.bytes()) {
                    cancellation.check();
                    int read = input.read(buffer, 0, (int) Math.min(buffer.length, asset.bytes() - position));
                    if (read < 0) throw new IOException("Bundle download ended early for " + asset.path());
                    output.write(buffer, 0, read);
                    position += read;
                    report.emit("downloading", asset.path(), position, false);
                }
                cancellation.check();
                extra = input.read() != -1;
                if (!extra) output.getFD().sync();
            }
            if (extra) {
                remove(partial);
                throw new IOException("Bundle exceeded its declared size for " + asset.path());
            }
            return expected;
        } catch (IOException error) {
            cancellation.check();
            throw error;
        } finally {
            close(connection, cancellation);
        }
    }

    private byte[] fetchManifest(URL url, Cancellation cancellation) throws IOException {
        HttpURLConnection connection = open(url, cancellation);
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) throw new IOException("Manifest request returned HTTP " + status);
            verifyIdentityEncoding(connection);
            if (contentLength(connection) > ContentManifest.MAX_MANIFEST_BYTES) {
                throw new IOException("OTA manifest exceeds 2 MiB");
            }
            try (InputStream input = connection.getInputStream()) {
                return readManifest(input, cancellation);
            }
        } catch (IOException error) {
            cancellation.check();
            throw error;
        } finally {
            close(connection, cancellation);
        }
    }

    private static byte[] readManifest(InputStream input, Cancellation cancellation) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (true) {
            if (cancellation != null) cancellation.check();
            int read = input.read(buffer);
            if (read < 0) return output.toByteArray();
            if (output.size() + read > ContentManifest.MAX_MANIFEST_BYTES) {
                throw new IOException("OTA manifest exceeds 2 MiB");
            }
            output.write(buffer, 0, read);
        }
    }

    private HttpURLConnection open(URL url, Cancellation cancellation) throws IOException {
        cancellation.check();
        validateUrl(url);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept-Encoding", "identity");
        try {
            cancellation.attach(connection);
        } catch (InterruptedIOException interrupted) {
            connection.disconnect();
            throw interrupted;
        }
        return connection;
    }

    private void validateUrl(URL url) throws IOException {
        String host = url.getHost();
        if (host == null || host.isEmpty() || url.getUserInfo() != null || url.getQuery() != null
                || url.getRef() != null) {
            throw new IOException("Invalid content server URL");
        }
        if ("https".equals(url.getProtocol())) return;
        if (allowLocalHttp && "http".equals(url.getProtocol())
                && ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "[::1]".equals(host))) return;
        throw new IOException("Content updates require HTTPS");
    }

    private static void close(HttpURLConnection connection, Cancellation cancellation) {
        cancellation.detach(connection);
        connection.disconnect();
    }

    private static long contentLength(HttpURLConnection connection) throws IOException {
        String value = connection.getHeaderField("Content-Length");
        if (value == null) return -1;
        try {
            long length = Long.parseLong(value);
            if (length >= 0) return length;
        } catch (NumberFormatException ignored) {
            // A malformed size cannot be used to validate the response.
        }
        throw new IOException("Invalid HTTP Content-Length");
    }

    private static void verifyIdentityEncoding(HttpURLConnection connection) throws IOException {
        String encoding = connection.getHeaderField("Content-Encoding");
        if (encoding != null && !"identity".equalsIgnoreCase(encoding)) {
            throw new IOException("The content server returned an encoded response");
        }
    }

    private static void verifyRange(String header, long offset, long size) throws IOException {
        Matcher range = header == null ? null : CONTENT_RANGE.matcher(header);
        if (range == null || !range.matches() || Long.parseLong(range.group(1)) != offset
                || Long.parseLong(range.group(2)) != size - 1 || Long.parseLong(range.group(3)) != size) {
            throw new IOException("The content server returned an invalid Content-Range");
        }
    }

    private static boolean verify(File file, ContentManifest.Asset asset, Cancellation cancellation,
                                  byte[] buffer) throws IOException {
        if (!file.isFile() || file.length() != asset.bytes()) return false;
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
        long bytes = 0;
        try (InputStream input = new FileInputStream(file)) {
            while (true) {
                cancellation.check();
                int read = input.read(buffer);
                if (read < 0) break;
                bytes += read;
                sha256.update(buffer, 0, read);
            }
        }
        byte[] digest = sha256.digest();
        char[] text = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) {
            text[2 * i] = HEX[(digest[i] & 0xff) >>> 4];
            text[2 * i + 1] = HEX[digest[i] & 0x0f];
        }
        return bytes == asset.bytes() && asset.sha256().equals(new String(text));
    }

    private void publishManifest(byte[] manifest, Cancellation cancellation) throws IOException {
        File staging = new File(root, "active-manifest.new");
        try {
            cancellation.check();
            try (FileOutputStream output = new FileOutputStream(staging)) {
                output.write(manifest);
                output.getFD().sync();
            }
            cancellation.publish(staging, activeManifest);
        } finally {
            if (staging.isFile()) remove(staging);
        }
    }

    private static void replace(File source, File target) throws IOException {
        try {
            Os.rename(source.getPath(), target.getPath());
        } catch (ErrnoException error) {
            throw new IOException("Could not activate verified content", error);
        }
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Could not create content storage");
        }
    }

    private static void remove(File file) throws IOException {
        if (!file.delete() && file.exists()) throw new IOException("Could not remove an incomplete content file");
    }

    private static final class UpdateProgress {
        private final Consumer<Progress> callback;
        private final long totalBytes;
        private final int totalAssets;
        private long completedBytes;
        private int completedAssets;
        private long last;

        UpdateProgress(ContentManifest manifest, Consumer<Progress> callback) {
            this.callback = callback;
            totalAssets = manifest.assets.size();
            Set<String> seen = new HashSet<>();
            long bytes = 0;
            for (ContentManifest.Asset asset : manifest.assets.values()) {
                if (seen.add(asset.sha256())) bytes += asset.bytes();
            }
            totalBytes = bytes;
        }

        void completeBundle(long bytes) { completedBytes += bytes; }

        void completeAsset(String path) {
            completedAssets++;
            emit("verifying", path, 0, false);
        }

        void publish() { emit("publishing", null, 0, true); }

        void emit(String stage, String path, long current, boolean force) {
            long now = System.nanoTime();
            if (force || last == 0 || now - last >= PROGRESS_INTERVAL_NS) {
                callback.accept(new Progress(stage, path, completedBytes + current, totalBytes,
                    completedAssets, totalAssets));
                last = now;
            }
        }
    }
}
