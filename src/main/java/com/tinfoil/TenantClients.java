package com.tinfoil;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.SecretKey;

import software.amazon.awssdk.services.s3.S3Client;

/**
 * Bounded LRU cache of per-tenant S3 encryption clients (multitenant mode).
 *
 * Keyed by sha256(aesKey.getEncoded()) so raw key bytes never live in the map.
 * On eviction (or close), the underlying client is closed so its HTTP pool is
 * released. Access is synchronized, but the process is quick.
 */
public class TenantClients implements AutoCloseable {
    private static final int MAX_ENTRIES = 256;

    private final Config config;
    private final LinkedHashMap<String, S3Client> cache;

    public TenantClients(Config config) {
        this.config = config;
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, S3Client> eldest) {
                if (size() > MAX_ENTRIES) {
                    try { eldest.getValue().close(); } catch (Exception ignored) {}
                    return true;
                }
                return false;
            }
        };
    }

    public synchronized S3Client get(SecretKey aesKey) {
        String fp = sha256Hex(aesKey.getEncoded());
        S3Client client = cache.get(fp);
        if (client == null) {
            client = S3Clients.encryptedFor(config, aesKey);
            cache.put(fp, client);
        }
        return client;
    }

    @Override
    public synchronized void close() {
        for (S3Client c : cache.values()) {
            try { c.close(); } catch (Exception ignored) {}
        }
        cache.clear();
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(bytes);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
