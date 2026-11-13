package org.neosteam.revival.ota;

import android.util.JsonReader;
import android.util.JsonToken;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class ContentManifest {
    static final int MAX_MANIFEST_BYTES = 2 * 1024 * 1024;
    private static final int MAX_ASSETS = 10_000;
    private static final long MAX_ASSET_BYTES = 1024L * 1024 * 1024;
    private static final Pattern DIGEST = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern INTEGER = Pattern.compile("0|[1-9][0-9]{0,9}");
    private static final Pattern WINDOWS_DEVICE = Pattern.compile(
        "(?:con|prn|aux|nul|com[1-9¹²³]|lpt[1-9¹²³])(?:\\..*)?");

    record Asset(String path, long bytes, String sha256) {
        String bundle() { return "bundles/" + sha256; }
    }

    final Map<String, Asset> assets;

    private ContentManifest(Map<String, Asset> assets) {
        this.assets = Collections.unmodifiableMap(assets);
    }

    static ContentManifest read(byte[] bytes) throws IOException {
        if (bytes.length > MAX_MANIFEST_BYTES) throw invalid("manifest exceeds 2 MiB");
        var input = new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8.newDecoder());
        try (JsonReader reader = new JsonReader(input)) {
            reader.beginObject();
            Long version = null;
            Map<String, Asset> assets = null;
            while (reader.hasNext()) {
                switch (reader.nextName()) {
                    case "schema_version":
                        if (version != null) throw invalid("duplicate version");
                        version = integer(reader);
                        break;
                    case "assets":
                        if (assets != null) throw invalid("duplicate assets");
                        assets = readAssets(reader);
                        break;
                    default: throw invalid("unknown manifest field");
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT || version == null || version != 1 || assets == null) {
                throw invalid("expected exactly one version 1 manifest");
            }
            return new ContentManifest(assets);
        } catch (IllegalStateException error) {
            throw invalid("unexpected JSON value");
        }
    }

    private static Map<String, Asset> readAssets(JsonReader reader) throws IOException {
        Map<String, Asset> assets = new LinkedHashMap<>();
        Set<String> paths = new HashSet<>();
        Map<String, Long> sizes = new HashMap<>();
        reader.beginArray();
        while (reader.hasNext()) {
            if (assets.size() == MAX_ASSETS) throw invalid("too many assets");
            Asset asset = readAsset(reader);
            if (!paths.add(asset.path.toLowerCase(Locale.ROOT))) throw invalid("duplicate asset path");
            Long previous = sizes.putIfAbsent(asset.sha256, asset.bytes);
            if (previous != null && previous != asset.bytes) throw invalid("inconsistent bundle size");
            assets.put(asset.path, asset);
        }
        reader.endArray();
        return assets;
    }

    private static Asset readAsset(JsonReader reader) throws IOException {
        String path = null, digest = null, bundle = null;
        Long bytes = null;
        Set<String> fields = new HashSet<>();
        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            if (!fields.add(field)) throw invalid("duplicate asset field");
            switch (field) {
                case "path": path = string(reader); break;
                case "bytes": bytes = integer(reader); break;
                case "sha256": digest = string(reader); break;
                case "bundle": bundle = string(reader); break;
                default: throw invalid("unknown asset field");
            }
        }
        reader.endObject();
        if (path == null || !validPath(path) || bytes == null || bytes < 1 || bytes > MAX_ASSET_BYTES
                || digest == null || !DIGEST.matcher(digest).matches()) {
            throw invalid("asset path, size or digest is invalid");
        }
        Asset asset = new Asset(path, bytes, digest);
        if (!asset.bundle().equals(bundle)) throw invalid("bundle path does not match SHA-256");
        return asset;
    }

    static boolean validPath(String path) {
        int filenameStart = path.lastIndexOf('/') + 1;
        if (!path.startsWith("converted/") || path.getBytes(StandardCharsets.UTF_8).length > 1024
                || !Normalizer.isNormalized(path, Normalizer.Form.NFC)
                || path.lastIndexOf('.') <= filenameStart
                || !(path.endsWith(".glb") || path.endsWith(".ktx2") || path.endsWith(".ogg"))) return false;
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.endsWith(".") || segment.getBytes(StandardCharsets.UTF_8).length > 255
                    || space(segment.codePointAt(0)) || space(segment.codePointBefore(segment.length()))
                    || WINDOWS_DEVICE.matcher(segment.toLowerCase(Locale.ROOT)).matches()) return false;
            for (int i = 0; i < segment.length();) {
                int cp = segment.codePointAt(i);
                int type = Character.getType(cp);
                if (type == Character.CONTROL || type == Character.FORMAT || type == Character.SURROGATE
                        || "\\:\"<>|?*".indexOf(cp) >= 0) return false;
                i += Character.charCount(cp);
            }
        }
        return true;
    }

    private static boolean space(int cp) { return Character.isSpaceChar(cp); }

    private static String string(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.STRING) throw invalid("expected string");
        return reader.nextString();
    }

    private static long integer(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.NUMBER) throw invalid("expected integer");
        String token = reader.nextString();
        if (!INTEGER.matcher(token).matches()) throw invalid("expected nonnegative integer");
        return Long.parseLong(token);
    }

    private static IOException invalid(String reason) { return new IOException("Invalid OTA manifest: " + reason); }
}
