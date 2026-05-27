package com.tinfoil;

import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import io.github.cdimascio.dotenv.Dotenv;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
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
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.encryption.s3.S3EncryptionClient;

public class Main {
    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();

        String upstreamBucket = require(dotenv, "BUCKET");
        SecretKey aesKey = loadAesKey(require(dotenv, "ENCRYPTION_KEY"));
        Region region = resolveRegion(dotenv);
        AwsCredentialsProvider creds = resolveCreds(dotenv);
        int port = parsePort(dotenv.get("PORT"));

        S3Client s3 = S3EncryptionClient.builderV4()
                .aesKey(aesKey)
                .region(region)
                .credentialsProvider(creds)
                .build();

        Javalin app = Javalin.create();

        app.put("/{bucket}/<key>", ctx -> {
            String key = ctx.pathParam("key");
            byte[] body = ctx.bodyAsBytes();
            PutObjectRequest.Builder b = PutObjectRequest.builder()
                    .bucket(upstreamBucket).key(key);
            String contentType = ctx.header("Content-Type");
            if (contentType != null) b.contentType(contentType);
            PutObjectResponse resp = s3.putObject(b.build(), RequestBody.fromBytes(body));
            if (resp.eTag() != null) ctx.header("ETag", resp.eTag());
            ctx.status(HttpStatus.OK);
        });

        app.get("/{bucket}/<key>", ctx -> {
            String key = ctx.pathParam("key");
            try {
                ResponseBytes<GetObjectResponse> resp = s3.getObjectAsBytes(b -> b
                        .bucket(upstreamBucket).key(key));
                GetObjectResponse meta = resp.response();
                if (meta.contentType() != null) ctx.contentType(meta.contentType());
                if (meta.eTag() != null) ctx.header("ETag", meta.eTag());
                ctx.result(resp.asByteArray());
            } catch (NoSuchKeyException e) {
                writeS3Error(ctx, 404, "NoSuchKey", "The specified key does not exist.");
            }
        });

        app.head("/{bucket}/<key>", ctx -> {
            String key = ctx.pathParam("key");
            try {
                HeadObjectResponse resp = s3.headObject(b -> b
                        .bucket(upstreamBucket).key(key));
                String plainLen = resp.metadata() != null
                        ? resp.metadata().get("x-amz-unencrypted-content-length")
                        : null;
                long len = plainLen != null ? Long.parseLong(plainLen) : resp.contentLength();
                ctx.header("Content-Length", String.valueOf(len));
                if (resp.contentType() != null) ctx.contentType(resp.contentType());
                if (resp.eTag() != null) ctx.header("ETag", resp.eTag());
                ctx.status(HttpStatus.OK);
            } catch (NoSuchKeyException e) {
                ctx.status(HttpStatus.NOT_FOUND);
            }
        });

        app.delete("/{bucket}/<key>", ctx -> {
            s3.deleteObject(b -> b.bucket(upstreamBucket).key(ctx.pathParam("key")));
            ctx.status(HttpStatus.NO_CONTENT);
        });

        app.start(port);
        System.out.println("tinfoil-buckets listening on :" + port + " -> s3://" + upstreamBucket);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            app.stop();
            s3.close();
        }));
    }

    private static void writeS3Error(Context ctx, int status, String code, String message) {
        ctx.status(status);
        ctx.contentType("application/xml");
        ctx.result("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Error><Code>" + code + "</Code><Message>" + message + "</Message></Error>");
    }

    private static int parsePort(String s) {
        return (s == null || s.isEmpty()) ? 9000 : Integer.parseInt(s);
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
        return DefaultCredentialsProvider.builder().build();
    }

    private static String require(Dotenv dotenv, String name) {
        String v = dotenv.get(name);
        if (v == null || v.isEmpty()) {
            throw new IllegalStateException("Missing " + name + " (set it in .env)");
        }
        return v;
    }
}
