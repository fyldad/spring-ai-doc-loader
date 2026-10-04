package ru.anblazhnov.springaidocloader;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

final class SourceIdentity {
    static String hash(String text) { return hash(text.getBytes(StandardCharsets.UTF_8)); }

    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    // Length prefixes avoid collisions when identifiers themselves contain delimiters.
    static String uuid(String... fields) {
        StringBuilder key = new StringBuilder("source-contract-v1");
        for (String field : fields) key.append('|').append(field.length()).append(':').append(field);
        return UUID.nameUUIDFromBytes(key.toString().getBytes(StandardCharsets.UTF_8)).toString();
    }
}
