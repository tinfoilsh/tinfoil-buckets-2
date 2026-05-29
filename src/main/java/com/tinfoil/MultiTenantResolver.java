package com.tinfoil;

import java.util.Base64;
import java.util.regex.Pattern;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import io.javalin.http.Context;

/**
 * Reads X-Tinfoil-Tenant-Id + X-Tinfoil-Encryption-Key off each request,
 * validates them, and resolves to the per-tenant encryption client from the cache.
 * Writes an S3-shaped 400 error on any header problem and returns null.
 */
public final class MultiTenantResolver implements TenantResolver {
    public static final String HDR_TENANT_ID = "X-Tinfoil-Tenant-Id";
    public static final String HDR_ENC_KEY = "X-Tinfoil-Encryption-Key";
    private static final Pattern TENANT_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final TenantClients tenantClients;

    public MultiTenantResolver(TenantClients tenantClients) {
        this.tenantClients = tenantClients;
    }

    @Override
    public TenantCtx resolve(Context ctx) {
        String tenantId = ctx.header(HDR_TENANT_ID);
        if (tenantId == null || tenantId.isEmpty()) {
            S3Routes.writeS3Error(ctx, 400, "InvalidArgument",
                    "Missing " + HDR_TENANT_ID + " header (multitenant mode is enabled).");
            return null;
        }
        if (!TENANT_ID_PATTERN.matcher(tenantId).matches()) {
            S3Routes.writeS3Error(ctx, 400, "InvalidArgument",
                    HDR_TENANT_ID + " must match [A-Za-z0-9_-]{1,64}.");
            return null;
        }
        String keyB64 = ctx.header(HDR_ENC_KEY);
        if (keyB64 == null || keyB64.isEmpty()) {
            S3Routes.writeS3Error(ctx, 400, "InvalidArgument",
                    "Missing " + HDR_ENC_KEY + " header (multitenant mode is enabled).");
            return null;
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(keyB64);
        } catch (IllegalArgumentException e) {
            S3Routes.writeS3Error(ctx, 400, "InvalidArgument",
                    HDR_ENC_KEY + " is not valid base64.");
            return null;
        }
        if (keyBytes.length != 32) {
            S3Routes.writeS3Error(ctx, 400, "InvalidArgument",
                    HDR_ENC_KEY + " must decode to 32 bytes (AES-256), got " + keyBytes.length + ".");
            return null;
        }
        SecretKey aes = new SecretKeySpec(keyBytes, "AES");
        return new TenantCtx(tenantClients.get(aes), tenantId);
    }

    @Override
    public void close() {
        tenantClients.close();
    }
}
