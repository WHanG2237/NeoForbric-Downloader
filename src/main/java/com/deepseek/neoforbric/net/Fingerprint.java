package com.deepseek.neoforbric.net;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;


public final class Fingerprint {
    private static final int SEED = 1;

    private Fingerprint() {}


    public static long of(Path jar) throws IOException {
        byte[] raw = Files.readAllBytes(jar);
        byte[] filtered = stripWhitespace(raw);
        return Integer.toUnsignedLong(murmurHash2(filtered, SEED));
    }

    private static byte[] stripWhitespace(byte[] data) {
        byte[] buffer = new byte[data.length];
        int count = 0;
        for (byte b : data) {
            if (b == 0x09 || b == 0x0A || b == 0x0D || b == 0x20) {
                continue;
            }
            buffer[count++] = b;
        }
        byte[] result = new byte[count];
        System.arraycopy(buffer, 0, result, 0, count);
        return result;
    }


    @SuppressWarnings("fallthrough")
    static int murmurHash2(byte[] data, int seed) {
        final int m = 0x5bd1e995;
        final int r = 24;

        int h = seed ^ data.length;
        int index = 0;
        int remaining = data.length;

        while (remaining >= 4) {
            int k = (data[index] & 0xff)
                    | ((data[index + 1] & 0xff) << 8)
                    | ((data[index + 2] & 0xff) << 16)
                    | ((data[index + 3] & 0xff) << 24);
            k *= m;
            k ^= k >>> r;
            k *= m;

            h *= m;
            h ^= k;

            index += 4;
            remaining -= 4;
        }

        switch (remaining) {
            case 3:
                h ^= (data[index + 2] & 0xff) << 16;

            case 2:
                h ^= (data[index + 1] & 0xff) << 8;

            case 1:
                h ^= data[index] & 0xff;
                h *= m;

            default:
                break;
        }

        h ^= h >>> 13;
        h *= m;
        h ^= h >>> 15;
        return h;
    }
}
