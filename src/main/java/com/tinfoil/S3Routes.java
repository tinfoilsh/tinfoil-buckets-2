package com.tinfoil;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import io.javalin.http.HttpStatus;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.encryption.s3.S3EncryptionClientException;

public class S3Routes {
    private final S3Client s3;
    private final String bucket;

    public S3Routes(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    public void register(Javalin app) {
        app.put("/{bucket}/<key>", this::put);
        app.get("/{bucket}/<key>", this::get);
        app.head("/{bucket}/<key>", this::head);
        app.delete("/{bucket}/<key>", this::delete);

        app.exception(S3Exception.class, S3Routes::handleS3Exception);
        app.exception(S3EncryptionClientException.class, (e, ctx) -> {
            if (e.getCause() instanceof S3Exception s3e) {
                handleS3Exception(s3e, ctx);
            } else {
                writeS3Error(ctx, 500, "InternalError", e.getMessage());
            }
        });
    }

    private void put(Context ctx) {
        String key = ctx.pathParam("key");
        byte[] body = ctx.bodyAsBytes();
        PutObjectRequest.Builder b = PutObjectRequest.builder().bucket(bucket).key(key);
        String contentType = ctx.header("Content-Type");
        if (contentType != null) b.contentType(contentType);
        PutObjectResponse resp = s3.putObject(b.build(), RequestBody.fromBytes(body));
        if (resp.eTag() != null) ctx.header("ETag", resp.eTag());
        ctx.status(HttpStatus.OK);
    }

    private void get(Context ctx) {
        String key = ctx.pathParam("key");
        ResponseBytes<GetObjectResponse> resp = s3.getObjectAsBytes(b -> b
                .bucket(bucket).key(key));
        GetObjectResponse meta = resp.response();
        if (meta.contentType() != null) ctx.contentType(meta.contentType());
        if (meta.eTag() != null) ctx.header("ETag", meta.eTag());
        ctx.result(resp.asByteArray());
    }

    private void head(Context ctx) {
        String key = ctx.pathParam("key");
        HeadObjectResponse resp = s3.headObject(b -> b
                .bucket(bucket).key(key));
        String plainLen = resp.metadata() != null
                ? resp.metadata().get("x-amz-unencrypted-content-length")
                : null;
        long len = plainLen != null ? Long.parseLong(plainLen) : resp.contentLength();
        ctx.header("Content-Length", String.valueOf(len));
        if (resp.contentType() != null) ctx.contentType(resp.contentType());
        if (resp.eTag() != null) ctx.header("ETag", resp.eTag());
        ctx.status(HttpStatus.OK);
    }

    private void delete(Context ctx) {
        s3.deleteObject(b -> b.bucket(bucket).key(ctx.pathParam("key")));
        ctx.status(HttpStatus.NO_CONTENT);
    }

    private static void handleS3Exception(S3Exception e, Context ctx) {
        int status = e.statusCode() > 0 ? e.statusCode() : 500;
        String code = e.awsErrorDetails() != null && e.awsErrorDetails().errorCode() != null
                ? e.awsErrorDetails().errorCode()
                : "InternalError";
        String message = e.awsErrorDetails() != null && e.awsErrorDetails().errorMessage() != null
                ? e.awsErrorDetails().errorMessage()
                : e.getMessage();
        if (ctx.method() == HandlerType.HEAD) {
            ctx.status(status);
            return;
        }
        writeS3Error(ctx, status, code, message);
    }

    private static void writeS3Error(Context ctx, int status, String code, String message) {
        ctx.status(status);
        ctx.contentType("application/xml");
        ctx.result("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Error><Code>" + code + "</Code><Message>" + message + "</Message></Error>");
    }
}
