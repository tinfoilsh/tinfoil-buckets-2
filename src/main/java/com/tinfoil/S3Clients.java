package com.tinfoil;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.encryption.s3.S3EncryptionClient;

public final class S3Clients {
    private S3Clients() {}

    /**
     * Builds the single S3 encryption client used by the sidecar. Mode is
     * fixed at startup by Config.delayedAuth:
     *
     *  - DELAYED_AUTH=false (default): buffered decryption. The encryption
     *    client materializes plaintext in JVM heap up to BUFFER_SIZE,
     *    verifies the GCM tag, then releases. Objects larger than
     *    BUFFER_SIZE fail with a clear error — operator must restart the
     *    sidecar with DELAYED_AUTH=true to handle them.
     *
     *  - DELAYED_AUTH=true: streaming decryption. Plaintext is released
     *    to the response as it decrypts; auth is verified at end-of-stream
     *    and surfaced via the X-Tinfoil-Auth HTTP trailer. Clients must
     *    use the verified_get / verified_iter helpers in
     *    client/tinfoil_client.py to detect tampering.
     */
    public static S3Client encrypted(Config config) {
        var builder = S3EncryptionClient.builderV4()
                .aesKey(config.aesKey())
                .region(config.region())
                .credentialsProvider(config.creds());
        if (config.delayedAuth()) {
            builder.enableDelayedAuthenticationMode(true);
        } else {
            builder.setBufferSize(config.bufferSize());
        }
        return builder.build();
    }
}
