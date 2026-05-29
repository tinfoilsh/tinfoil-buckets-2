package com.tinfoil;

import io.javalin.http.Context;
import software.amazon.awssdk.services.s3.S3Client;

/** Hands back the one configured encryption client; no prefixing, no header parsing. */
public final class SingleTenantResolver implements TenantResolver {
    private final S3Client s3Client;

    public SingleTenantResolver(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    @Override
    public TenantCtx resolve(Context ctx) {
        return new TenantCtx(s3Client, null);
    }

    @Override
    public void close() {
        s3Client.close();
    }
}
