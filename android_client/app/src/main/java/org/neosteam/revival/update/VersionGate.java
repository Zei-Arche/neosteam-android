package org.neosteam.revival.update;

import android.util.JsonReader;
import android.util.JsonToken;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Reads the current Android release policy over HTTPS. Call check on a worker thread. */
public final class VersionGate {
    private static final int MAX_MANIFEST_BYTES = 16 * 1024;
    private static final long MAX_VERSION_CODE = 2_100_000_000;
    private static final long APK_SIZE_LIMIT = 2L * 1024 * 1024 * 1024;
    private static final int IO_TIMEOUT_MS = 5_000;
    private static final long CHECK_TIMEOUT_NS = TimeUnit.SECONDS.toNanos(12);
    private static final Pattern INTEGER = Pattern.compile("[1-9][0-9]{0,18}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern VERSION_NAME = Pattern.compile("[0-9A-Za-z][0-9A-Za-z._+-]{0,63}");
    private final boolean allowLocalHttp;

    public record Release(long versionCode, long minimumVersionCode, String versionName,
                          String apkUrl, String sha256, long bytes) {}

    public record Result(boolean updateRequired, Release release) {}

    public VersionGate(boolean allowLocalHttp) {
        this.allowLocalHttp = allowLocalHttp;
    }

    public Result check(String manifestUrl, long installedVersionCode) throws IOException {
        if (installedVersionCode < 1) throw new IllegalArgumentException("Installed version code must be positive");
        URI endpoint = checkedUri(manifestUrl);
        HttpURLConnection connection = (HttpURLConnection) endpoint.toURL().openConnection();
        connection.setConnectTimeout(IO_TIMEOUT_MS);
        connection.setReadTimeout(IO_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("Cache-Control", "no-cache");
        long start = System.nanoTime();
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("Release endpoint returned HTTP " + status);
            }
            String encoding = connection.getHeaderField("Content-Encoding");
            if (encoding != null && !"identity".equalsIgnoreCase(encoding)) {
                throw new IOException("Unsupported release response encoding");
            }
            if (connection.getContentLength() > MAX_MANIFEST_BYTES) throw invalid("response exceeds 16 KiB");
            try (InputStream input = connection.getInputStream()) {
                Release release = parse(readManifest(input, start));
                return new Result(installedVersionCode < release.minimumVersionCode(), release);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] readManifest(InputStream input, long start) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        while (true) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Version check cancelled");
            if (System.nanoTime() - start >= CHECK_TIMEOUT_NS) throw new SocketTimeoutException("Version check timed out");
            int read = input.read(buffer);
            if (read < 0) return output.toByteArray();
            if (output.size() + read > MAX_MANIFEST_BYTES) throw invalid("response exceeds 16 KiB");
            output.write(buffer, 0, read);
        }
    }

    private Release parse(byte[] raw) throws IOException {
        var input = new InputStreamReader(new ByteArrayInputStream(raw), StandardCharsets.UTF_8.newDecoder());
        try (JsonReader reader = new JsonReader(input)) {
            reader.beginObject();
            Set<String> seen = new HashSet<>();
            long schema = 0, version = 0, minimum = 0, bytes = 0;
            String name = null, apk = null, digest = null;
            while (reader.hasNext()) {
                String key = reader.nextName();
                if (!seen.add(key)) throw invalid("duplicate field");
                switch (key) {
                    case "schema_version" -> schema = integer(reader);
                    case "version_code" -> version = integer(reader);
                    case "minimum_version_code" -> minimum = integer(reader);
                    case "version_name" -> name = string(reader);
                    case "apk_url" -> apk = string(reader);
                    case "sha256" -> digest = string(reader);
                    case "bytes" -> bytes = integer(reader);
                    default -> throw invalid("unknown field");
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT || schema != 1 || minimum < 1 || minimum > version
                    || version > MAX_VERSION_CODE || bytes < 1 || bytes >= APK_SIZE_LIMIT
                    || name == null || !VERSION_NAME.matcher(name).matches() || digest == null
                    || !SHA256.matcher(digest).matches() || apk == null) {
                throw invalid("invalid or missing release fields");
            }
            URI download = checkedUri(apk);
            if (!download.getRawPath().endsWith(".apk")) throw invalid("download URL must name an APK");
            return new Release(version, minimum, name, download.toASCIIString(), digest, bytes);
        } catch (IllegalStateException malformed) {
            throw invalid("unexpected JSON value");
        }
    }

    private URI checkedUri(String value) throws IOException {
        if (value == null || value.length() > 2048) throw invalid("invalid URL");
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            String path = uri.getRawPath();
            if (host == null || path == null || path.isEmpty() || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getPort() > 65535
                    || uri.getPort() == 0) {
                throw invalid("invalid URL");
            }
            if ("https".equals(uri.getScheme())) return uri;
            if (allowLocalHttp && "http".equals(uri.getScheme())) {
                String local = host.toLowerCase(Locale.ROOT);
                if ("127.0.0.1".equals(local) || "localhost".equals(local) || "[::1]".equals(local)) return uri;
            }
            throw invalid("URL requires HTTPS");
        } catch (URISyntaxException malformed) {
            throw invalid("invalid URL");
        }
    }

    private static String string(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.STRING) throw invalid("expected string");
        return reader.nextString();
    }

    private static long integer(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.NUMBER) throw invalid("expected integer");
        String value = reader.nextString();
        if (!INTEGER.matcher(value).matches()) throw invalid("expected positive integer");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException overflow) {
            throw invalid("integer exceeds supported range");
        }
    }

    private static IOException invalid(String reason) {
        return new IOException("Invalid Android release metadata: " + reason);
    }
}
