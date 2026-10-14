package com.deepseek.neoforbric.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;


public final class Hashes {
    private Hashes() {}

    public static String sha1(Path file) throws IOException {
        return digest(file, "SHA-1");
    }


    public static String sha256(String text) {
        MessageDigest digest = digestOf("SHA-256");
        return HexFormat.of().formatHex(digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }


    private static MessageDigest digestOf(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM lacks digest algorithm " + algorithm, e);
        }
    }

    private static String digest(Path file, String algorithm) throws IOException {
        MessageDigest digest = digestOf(algorithm);
        byte[] buffer = new byte[8192];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
