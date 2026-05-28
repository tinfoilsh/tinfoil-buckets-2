package com.tinfoil;

import io.javalin.Javalin;
import software.amazon.awssdk.services.s3.S3Client;

public class Main {
    public static void main(String[] args) {
        Config config = Config.load();
        S3Client s3 = S3Clients.encrypted(config);

        Javalin app = Javalin.create();
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
