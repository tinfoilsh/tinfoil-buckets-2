package com.tinfoil;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ConcurrentHashMap;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import io.javalin.http.HttpStatus;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.SdkPartType;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;
import software.amazon.encryption.s3.S3EncryptionClientException;

public class S3Routes {
    private static final int GCM_TAG_BYTES = 16;

    private final S3Client s3;
    private final String bucket;
    private final long maxPartBytes;
    private final ConcurrentHashMap<String, MultipartSession> sessions = new ConcurrentHashMap<>();

    public S3Routes(S3Client s3, Config config) {
        this.s3 = s3;
        this.bucket = config.bucket();
        this.maxPartBytes = config.maxPartBytes();
    }

    public void register(Javalin app) {
        app.put("/{bucket}/<key>", this::handlePut);
        app.get("/{bucket}/<key>", this::handleGet);
        app.head("/{bucket}/<key>", this::handleHead);
        app.delete("/{bucket}/<key>", this::handleDelete);
        app.post("/{bucket}/<key>", this::handlePost);

        app.exception(S3Exception.class, S3Routes::handleS3Exception);
        app.exception(S3EncryptionClientException.class, (e, ctx) -> {
            if (e.getCause() instanceof S3Exception s3e) {
                handleS3Exception(s3e, ctx);
            } else {
                writeS3Error(ctx, 500, "InternalError", e.getMessage());
            }
        });
    }

    // --- Dispatchers ---------------------------------------------------------

    private void handlePut(Context ctx) {
        String uploadId = ctx.queryParam("uploadId");
        if (uploadId != null) {
            uploadPart(ctx, uploadId);
        } else {
            putObject(ctx);
        }
    }

    private void handleDelete(Context ctx) {
        String uploadId = ctx.queryParam("uploadId");
        if (uploadId != null) {
            abortMultipartUpload(ctx, uploadId);
        } else {
            deleteObject(ctx);
        }
    }

    private void handlePost(Context ctx) {
        if (ctx.queryParamMap().containsKey("uploads")) {
            createMultipartUpload(ctx);
        } else if (ctx.queryParam("uploadId") != null) {
            completeMultipartUpload(ctx, ctx.queryParam("uploadId"));
        } else {
            writeS3Error(ctx, 400, "InvalidRequest", "Unsupported POST operation.");
        }
    }

    // --- Single-shot object operations ---------------------------------------

    private void putObject(Context ctx) {
        String key = ctx.pathParam("key");
        byte[] body = ctx.bodyAsBytes();
        PutObjectRequest.Builder b = PutObjectRequest.builder().bucket(bucket).key(key);
        String contentType = ctx.header("Content-Type");
        if (contentType != null) b.contentType(contentType);
        PutObjectResponse resp = s3.putObject(b.build(), RequestBody.fromBytes(body));
        if (resp.eTag() != null) ctx.header("ETag", resp.eTag());
        ctx.status(HttpStatus.OK);
    }

    private void handleGet(Context ctx) {
        String key = ctx.pathParam("key");
        ResponseBytes<GetObjectResponse> resp = s3.getObjectAsBytes(b -> b
                .bucket(bucket).key(key));
        GetObjectResponse meta = resp.response();
        if (meta.contentType() != null) ctx.contentType(meta.contentType());
        if (meta.eTag() != null) ctx.header("ETag", meta.eTag());
        ctx.result(resp.asByteArray());
    }

    private void handleHead(Context ctx) {
        String key = ctx.pathParam("key");
        HeadObjectResponse resp = s3.headObject(b -> b
                .bucket(bucket).key(key));
        // AES-GCM (v4 default) appends a 16-byte authentication tag to the ciphertext.
        long len = Math.max(0, resp.contentLength() - GCM_TAG_BYTES);
        ctx.header("Content-Length", String.valueOf(len));
        if (resp.contentType() != null) ctx.contentType(resp.contentType());
        if (resp.eTag() != null) ctx.header("ETag", resp.eTag());
        ctx.status(HttpStatus.OK);
    }

    private void deleteObject(Context ctx) {
        s3.deleteObject(b -> b.bucket(bucket).key(ctx.pathParam("key")));
        ctx.status(HttpStatus.NO_CONTENT);
    }

    // --- Multipart operations ------------------------------------------------

    private void createMultipartUpload(Context ctx) {
        String key = ctx.pathParam("key");
        CreateMultipartUploadRequest.Builder b = CreateMultipartUploadRequest.builder()
                .bucket(bucket).key(key);
        String contentType = ctx.header("Content-Type");
        if (contentType != null) b.contentType(contentType);
        CreateMultipartUploadResponse resp = s3.createMultipartUpload(b.build());
        String uploadId = resp.uploadId();
        sessions.put(uploadId, new MultipartSession(uploadId, key));

        String bucketParam = ctx.pathParam("bucket");
        ctx.contentType("application/xml");
        ctx.result("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<InitiateMultipartUploadResult>"
                + "<Bucket>" + xmlEscape(bucketParam) + "</Bucket>"
                + "<Key>" + xmlEscape(key) + "</Key>"
                + "<UploadId>" + xmlEscape(uploadId) + "</UploadId>"
                + "</InitiateMultipartUploadResult>");
    }

    private void uploadPart(Context ctx, String uploadId) {
        MultipartSession session = sessions.get(uploadId);
        if (session == null) {
            writeS3Error(ctx, 404, "NoSuchUpload", "The specified upload does not exist.");
            return;
        }
        int partNumber = Integer.parseInt(ctx.queryParam("partNumber"));
        long claimedLength = ctx.contentLength();
        if (claimedLength > maxPartBytes) {
            writeS3Error(ctx, 400, "EntityTooLarge",
                    "Part size exceeds MAX_PART_BYTES (" + maxPartBytes + ").");
            return;
        }
        byte[] body = ctx.bodyAsBytes();
        if (body.length > maxPartBytes) {
            writeS3Error(ctx, 400, "EntityTooLarge",
                    "Part size exceeds MAX_PART_BYTES (" + maxPartBytes + ").");
            return;
        }

        session.lock.lock();
        try {
            if (partNumber != session.nextExpectedPartNumber) {
                writeS3Error(ctx, 400, "InvalidPart",
                        "Expected partNumber=" + session.nextExpectedPartNumber
                                + ", got " + partNumber + ". Parts must be sequential.");
                return;
            }

            if (session.pendingPartBytes != null) {
                CompletedPart cp = flushPart(session, false);
                session.completedParts.add(cp);
            }

            session.pendingPartNumber = partNumber;
            session.pendingPartBytes = body;
            session.nextExpectedPartNumber = partNumber + 1;
        } finally {
            session.lock.unlock();
        }

        ctx.header("ETag", "\"" + md5Hex(body) + "\"");
        ctx.status(HttpStatus.OK);
    }

    private void completeMultipartUpload(Context ctx, String uploadId) {
        MultipartSession session = sessions.get(uploadId);
        if (session == null) {
            writeS3Error(ctx, 404, "NoSuchUpload", "The specified upload does not exist.");
            return;
        }

        CompleteMultipartUploadResponse resp;
        session.lock.lock();
        try {
            if (session.pendingPartBytes != null) {
                CompletedPart cp = flushPart(session, true);
                session.completedParts.add(cp);
            }

            CompleteMultipartUploadRequest req = CompleteMultipartUploadRequest.builder()
                    .bucket(bucket).key(session.key).uploadId(uploadId)
                    .multipartUpload(b -> b.parts(session.completedParts))
                    .build();
            resp = s3.completeMultipartUpload(req);
        } finally {
            session.lock.unlock();
        }
        sessions.remove(uploadId);

        String bucketParam = ctx.pathParam("bucket");
        ctx.contentType("application/xml");
        ctx.result("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<CompleteMultipartUploadResult>"
                + "<Location>http://" + xmlEscape(bucketParam) + "/" + xmlEscape(session.key) + "</Location>"
                + "<Bucket>" + xmlEscape(bucketParam) + "</Bucket>"
                + "<Key>" + xmlEscape(session.key) + "</Key>"
                + "<ETag>" + xmlEscape(resp.eTag() != null ? resp.eTag() : "") + "</ETag>"
                + "</CompleteMultipartUploadResult>");
    }

    private void abortMultipartUpload(Context ctx, String uploadId) {
        MultipartSession session = sessions.get(uploadId);
        if (session == null) {
            writeS3Error(ctx, 404, "NoSuchUpload", "The specified upload does not exist.");
            return;
        }
        s3.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                .bucket(bucket).key(session.key).uploadId(uploadId).build());
        sessions.remove(uploadId);
        ctx.status(HttpStatus.NO_CONTENT);
    }

    private CompletedPart flushPart(MultipartSession session, boolean isLast) {
        UploadPartRequest.Builder b = UploadPartRequest.builder()
                .bucket(bucket).key(session.key)
                .uploadId(session.uploadId)
                .partNumber(session.pendingPartNumber);
        if (isLast) {
            b.sdkPartType(SdkPartType.LAST);
        }
        UploadPartResponse resp = s3.uploadPart(b.build(),
                RequestBody.fromBytes(session.pendingPartBytes));
        CompletedPart cp = CompletedPart.builder()
                .partNumber(session.pendingPartNumber)
                .eTag(resp.eTag())
                .build();
        session.pendingPartBytes = null;
        session.pendingPartNumber = -1;
        return cp;
    }

    // --- Helpers -------------------------------------------------------------

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
                + "<Error><Code>" + xmlEscape(code) + "</Code>"
                + "<Message>" + xmlEscape(message) + "</Message></Error>");
    }

    private static String xmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String md5Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
