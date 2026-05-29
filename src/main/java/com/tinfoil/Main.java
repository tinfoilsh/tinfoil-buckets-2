package com.tinfoil;

import io.javalin.Javalin;
import software.amazon.awssdk.services.s3.S3Client;

public class Main {
    public static void main(String[] args) {
        Config config = Config.load();

        // In single-tenant mode, build one client up front.
        // In multitenant mode, the cache builds clients lazily per key.
        S3Client s3 = config.multitenant() ? null : S3Clients.encrypted(config);
        TenantClients tenants = config.multitenant() ? new TenantClients(config) : null;

        // Non-encryption S3 client used for housekeeping ops
        S3Client housekeeping = S3Client.builder()
                .region(config.region())
                .credentialsProvider(config.creds())
                .build();

        Javalin app = Javalin.create(cfg -> {
            cfg.http.maxRequestSize = S3Routes.MAX_PART_BYTES;
        });
        S3Routes routes = new S3Routes(s3, tenants, housekeeping, config);
        routes.register(app);
        app.start(config.port());

        System.out.println("tinfoil-buckets-sidecar listening on :" + config.port()
                + " -> s3://" + config.bucket()
                + (config.multitenant() ? " [multitenant]" : ""));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            app.stop();
            routes.shutdown();
            if (s3 != null) s3.close();
            if (tenants != null) tenants.close();
            housekeeping.close();
        }));
    }
}
