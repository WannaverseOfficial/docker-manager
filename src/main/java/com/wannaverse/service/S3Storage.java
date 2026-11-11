package com.wannaverse.service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * A minimal client for S3-compatible object storage (AWS S3, Cloudflare R2, Backblaze B2, MinIO,
 * Wasabi...): put, get and delete one object, signed with AWS Signature Version 4 and addressed
 * path-style ({@code https://endpoint/bucket/key}), which every one of them accepts.
 */
public final class S3Storage {
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final HttpClient HTTP =
            HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(20))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

    private final String scheme;
    private final String host;
    private final String region;
    private final String bucket;
    private final String accessKey;
    private final String secretKey;

    public S3Storage(
            String endpoint, String region, String bucket, String accessKey, String secretKey) {
        if (endpoint == null || endpoint.isBlank())
            throw new IllegalArgumentException("Enter the endpoint");
        if (bucket == null || !bucket.matches("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$")) {
            throw new IllegalArgumentException("Enter a valid bucket name");
        }
        if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) {
            throw new IllegalArgumentException("Enter the access key and secret key");
        }
        String e = endpoint.trim();
        if (!e.contains("://")) e = "https://" + e;
        URI uri = URI.create(e);
        if (uri.getHost() == null) throw new IllegalArgumentException("Invalid endpoint");
        this.scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw new IllegalArgumentException("The endpoint must start with https://");
        }
        int port = uri.getPort();
        boolean defaultPort =
                port == -1
                        || (scheme.equals("https") && port == 443)
                        || (scheme.equals("http") && port == 80);
        this.host = uri.getHost().toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port);
        this.region = region == null || region.isBlank() ? "us-east-1" : region.trim();
        this.bucket = bucket;
        this.accessKey = accessKey.trim();
        this.secretKey = secretKey.trim();
    }

    public void put(String key, Path file, String sha256Hex) throws IOException {
        HttpResponse<String> r =
                send(
                        "PUT",
                        key,
                        sha256Hex,
                        HttpRequest.BodyPublishers.ofFile(file),
                        Duration.ofHours(6));
        if (r.statusCode() / 100 != 2) throw failure("Upload", r.statusCode(), r.body());
    }

    public void putBytes(String key, byte[] body) throws IOException {
        HttpResponse<String> r =
                send(
                        "PUT",
                        key,
                        sha256(body),
                        HttpRequest.BodyPublishers.ofByteArray(body),
                        Duration.ofSeconds(60));
        if (r.statusCode() / 100 != 2) throw failure("Upload", r.statusCode(), r.body());
    }

    public void get(String key, Path target) throws IOException {
        HttpRequest request =
                request(
                        "GET",
                        key,
                        sha256(new byte[0]),
                        HttpRequest.BodyPublishers.noBody(),
                        Duration.ofHours(6));
        HttpResponse<InputStream> r;
        try {
            r = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted");
        }
        try (InputStream in = r.body()) {
            if (r.statusCode() != 200) {
                throw failure(
                        "Download",
                        r.statusCode(),
                        new String(in.readNBytes(4000), StandardCharsets.UTF_8));
            }
            Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public void delete(String key) throws IOException {
        HttpResponse<String> r =
                send(
                        "DELETE",
                        key,
                        sha256(new byte[0]),
                        HttpRequest.BodyPublishers.noBody(),
                        Duration.ofSeconds(60));
        if (r.statusCode() / 100 != 2 && r.statusCode() != 404)
            throw failure("Delete", r.statusCode(), r.body());
    }

    private HttpResponse<String> send(
            String method,
            String key,
            String payloadHash,
            HttpRequest.BodyPublisher body,
            Duration timeout)
            throws IOException {
        try {
            return HTTP.send(
                    request(method, key, payloadHash, body, timeout),
                    HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted");
        }
    }

    private HttpRequest request(
            String method,
            String key,
            String payloadHash,
            HttpRequest.BodyPublisher body,
            Duration timeout) {
        String path = "/" + encode(bucket) + "/" + encodePath(key);
        String amzDate = ZonedDateTime.now(ZoneOffset.UTC).format(AMZ_DATE);
        String date = amzDate.substring(0, 8);
        String scope = date + "/" + region + "/s3/aws4_request";
        String signedHeaders = "host;x-amz-content-sha256;x-amz-date";
        String canonicalRequest =
                method
                        + "\n"
                        + path
                        + "\n"
                        + ""
                        + "\n"
                        + "host:"
                        + host
                        + "\n"
                        + "x-amz-content-sha256:"
                        + payloadHash
                        + "\n"
                        + "x-amz-date:"
                        + amzDate
                        + "\n"
                        + "\n"
                        + signedHeaders
                        + "\n"
                        + payloadHash;
        String stringToSign =
                "AWS4-HMAC-SHA256\n"
                        + amzDate
                        + "\n"
                        + scope
                        + "\n"
                        + sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        byte[] k = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        k = hmac(k, region);
        k = hmac(k, "s3");
        k = hmac(k, "aws4_request");
        String signature = HexFormat.of().formatHex(hmac(k, stringToSign));
        return HttpRequest.newBuilder(URI.create(scheme + "://" + host + path))
                .timeout(timeout)
                .header("x-amz-content-sha256", payloadHash)
                .header("x-amz-date", amzDate)
                .header(
                        "Authorization",
                        "AWS4-HMAC-SHA256 Credential="
                                + accessKey
                                + "/"
                                + scope
                                + ", SignedHeaders="
                                + signedHeaders
                                + ", Signature="
                                + signature)
                .method(method, body)
                .build();
    }

    private static IOException failure(String what, int status, String body) {
        String code = between(body, "<Code>", "</Code>");
        String message = between(body, "<Message>", "</Message>");
        String detail =
                code == null ? "HTTP " + status : code + (message == null ? "" : ": " + message);
        return new IOException(what + " failed (" + detail + ")");
    }

    private static String between(String s, String a, String b) {
        if (s == null) return null;
        int i = s.indexOf(a);
        int j = i < 0 ? -1 : s.indexOf(b, i + a.length());
        return i < 0 || j < 0 ? null : s.substring(i + a.length(), j);
    }

    /** RFC 3986 encoding of each path segment, as SigV4 expects for S3 (slashes kept). */
    static String encodePath(String key) {
        StringBuilder sb = new StringBuilder();
        for (String part : key.split("/", -1)) {
            if (!sb.isEmpty()) sb.append('/');
            sb.append(encode(part));
        }
        return sb.toString();
    }

    static String encode(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '-'
                    || c == '.'
                    || c == '_'
                    || c == '~') {
                sb.append(c);
            } else {
                sb.append('%').append(String.format("%02X", b & 0xff));
            }
        }
        return sb.toString();
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
