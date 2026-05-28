package com.tinfoil;

import io.javalin.Javalin;

public class Main {
    public static void main(String[] args) {
        Config config = Config.load();
        var s3 = S3Clients.encrypted(config);

        Javalin app = Javalin.create(cfg -> {
            cfg.http.maxRequestSize = S3Routes.MAX_PART_BYTES;
        });
        new S3Routes(s3, config).register(app);
        app.start(config.port());

        System.out.println("tinfoil-buckets listening on :" + config.port()
                + " -> s3://" + config.bucket());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            app.stop();
            s3.close();
        }));
    }
}
