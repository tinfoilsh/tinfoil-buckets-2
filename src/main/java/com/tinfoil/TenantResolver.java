package com.tinfoil;

import io.javalin.http.Context;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Picks the encryption client and namespace for a request. The two impls
 * ({@link SingleTenantResolver}, {@link MultiTenantResolver}) hide the
 * mode-specific wiring so S3Routes never branches on multitenant vs not.
 *
 * Contract: returns the resolved context, or null if the request was malformed
 * (in which case an S3 error XML response has already been written). Routes
 * just check `tenant == null` and bail.
 */
public interface TenantResolver extends AutoCloseable {
    TenantCtx resolve(Context ctx);

    @Override
    default void close() {}

    /**
     * Per-request crypto + namespacing context. `tenantId` is null in single-
     * tenant mode (no prefixing, sessions keyed by uploadId only). The helper
     * methods centralize prefix handling so route code stays readable.
     */
    record TenantCtx(S3Client client, String tenantId) {
        /** Storage key for an S3 op: prepends the tenant prefix in multitenant mode. */
        public String s3Key(String userKey) {
            return tenantId == null ? userKey : tenantId + "/" + userKey;
        }

        /** Prefix used to scope List* operations. Empty in single-tenant mode. */
        public String prefix() {
            return tenantId == null ? "" : tenantId + "/";
        }

        /** Map key for the multipart-session table. Different tenants' uploadIds never collide. */
        public String sessionKey(String uploadId) {
            return tenantId == null ? uploadId : tenantId + ":" + uploadId;
        }

        /** Strips the tenant prefix off an S3 key before echoing back in a list response. */
        public String stripPrefix(String key) {
            if (tenantId == null || key == null) return key;
            String p = prefix();
            return key.startsWith(p) ? key.substring(p.length()) : key;
        }
    }
}
