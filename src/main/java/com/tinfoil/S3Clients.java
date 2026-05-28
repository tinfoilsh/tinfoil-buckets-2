package com.tinfoil;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.encryption.s3.S3EncryptionClient;

public final class S3Clients {
    private S3Clients() {}

    public static S3Client encrypted(Config config) {
        return S3EncryptionClient.builderV4()
                .aesKey(config.aesKey())
                .region(config.region())
                .credentialsProvider(config.creds())
                .enableDelayedAuthenticationMode(true)
                .build();
    }
}
