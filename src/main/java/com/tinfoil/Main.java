package com.tinfoil;

import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import io.github.cdimascio.dotenv.Dotenv;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.encryption.s3.S3EncryptionClient;

public class Main {
    public static void main(String[] args) throws Exception {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();

        String bucket = require(dotenv, "BUCKET");
        SecretKey aesKey = loadAesKey(require(dotenv, "ENCRYPTION_KEY"));
        Region region = resolveRegion(dotenv);
        AwsCredentialsProvider creds = resolveCreds(dotenv);

        String objectKey = "tinfoil-buckets/hello.txt";
        String plaintext = "Hello, encrypted world!";

        try (S3Client s3 = S3EncryptionClient.builderV4()
                .aesKey(aesKey)
                .region(region)
                .credentialsProvider(creds)
                .build()) {

            s3.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(objectKey).build(),
                    RequestBody.fromString(plaintext));
            System.out.println("PUT  s3://" + bucket + "/" + objectKey);

            ResponseBytes<GetObjectResponse> resp = s3.getObjectAsBytes(b -> b
                    .bucket(bucket)
                    .key(objectKey));
            String roundtrip = resp.asUtf8String();
            System.out.println("GET  s3://" + bucket + "/" + objectKey + " -> " + roundtrip);

            if (!roundtrip.equals(plaintext)) {
                throw new IllegalStateException("Roundtrip mismatch");
            }
            System.out.println("OK   plaintext matches");
        }
    }

    private static SecretKey loadAesKey(String b64) {
        byte[] bytes = Base64.getDecoder().decode(b64);
        if (bytes.length != 32) {
            throw new IllegalArgumentException(
                    "ENCRYPTION_KEY must decode to 32 bytes (AES-256), got " + bytes.length);
        }
        return new SecretKeySpec(bytes, "AES");
    }

    private static Region resolveRegion(Dotenv dotenv) {
        String r = dotenv.get("AWS_REGION");
        if (r != null && !r.isEmpty()) return Region.of(r);
        return new DefaultAwsRegionProviderChain().getRegion();
    }

    private static AwsCredentialsProvider resolveCreds(Dotenv dotenv) {
        String key = dotenv.get("AWS_ACCESS_KEY_ID");
        String secret = dotenv.get("AWS_SECRET_ACCESS_KEY");
        if (key != null && secret != null) {
            String token = dotenv.get("AWS_SESSION_TOKEN");
            if (token != null && !token.isEmpty()) {
                return StaticCredentialsProvider.create(
                        AwsSessionCredentials.create(key, secret, token));
            }
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(key, secret));
        }
        String profile = dotenv.get("AWS_PROFILE");
        if (profile != null && !profile.isEmpty()) {
            return ProfileCredentialsProvider.create(profile);
        }
        return DefaultCredentialsProvider.create();
    }

    private static String require(Dotenv dotenv, String name) {
        String v = dotenv.get(name);
        if (v == null || v.isEmpty()) {
            throw new IllegalStateException("Missing " + name + " (set it in .env)");
        }
        return v;
    }
}
