package com.tinfoil;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import io.javalin.http.HttpStatus;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.DeletedObject;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ListPartsRequest;
import software.amazon.awssdk.services.s3.model.ListPartsResponse;
import software.amazon.awssdk.services.s3.model.MultipartUpload;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.Part;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Error;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.SdkPartType;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;
import software.amazon.encryption.s3.S3EncryptionClientException;

public class S3Routes {
    private static final int GCM_TAG_BYTES = 16;
    public static final long MAX_PART_BYTES = 1024L * 1024L * 1024L;
    private static final long MULTIPART_SESSION_TTL_SECONDS = 3600;
    private static final long MULTIPART_GC_PERIOD_SECONDS = 300;

    private final S3Client s3;
    private final String bucket;
    private final Region region;
    private final ConcurrentHashMap<String, MultipartSession> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService gc = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "tinfoil-mpu-gc");
        t.setDaemon(true);
        return t;
    });

    public S3Routes(S3Client s3, Config config) {
        this.s3 = s3;
        this.bucket = config.bucket();
        this.region = config.region();
    }

    public void register(Javalin app) {
        app.put("/{bucket}/<key>", this::handlePut);
        app.get("/{bucket}/<key>", this::handleGet);
        app.head("/{bucket}/<key>", this::handleHead);
        app.delete("/{bucket}/<key>", this::handleDelete);
        app.post("/{bucket}/<key>", this::handlePost);
        app.get("/{bucket}", this::handleBucketGet);
        app.get("/{bucket}/", this::handleBucketGet);
        app.post("/{bucket}", this::handleBucketPost);
        app.post("/{bucket}/", this::handleBucketPost);
        app.head("/{bucket}", this::handleBucketHead);
        app.head("/{bucket}/", this::handleBucketHead);

        gc.scheduleAtFixedRate(this::sweepSessions,
                MULTIPART_GC_PERIOD_SECONDS, MULTIPART_GC_PERIOD_SECONDS, TimeUnit.SECONDS);

        app.exception(S3Exception.class, S3Routes::handleS3Exception);
        app.exception(S3EncryptionClientException.class, (e, ctx) -> {
            if (e.getCause() instanceof S3Exception s3e) {
                handleS3Exception(s3e, ctx);
            } else if (e.getMessage() != null
                    && e.getMessage().contains("exceeds the maximum buffer size")) {
                writeS3Error(ctx, 413, "EntityTooLarge",
                        "Object exceeds the sidecar's BUFFER_SIZE. "
                        + "Raise BUFFER_SIZE (up to 64 GiB), or set "
                        + "DELAYED_AUTH=true to stream (supports arbitrarily large objects.)");
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
        String lenHeader = ctx.header("Content-Length");
        if (lenHeader == null) {
            writeS3Error(ctx, 411, "MissingContentLength",
                    "Content-Length header is required for PutObject.");
            return;
        }
        long contentLength = Long.parseLong(lenHeader);

        PutObjectRequest.Builder b = PutObjectRequest.builder().bucket(bucket).key(key);
        String contentType = ctx.header("Content-Type");
        if (contentType != null) b.contentType(contentType);
        Map<String, String> userMeta = extractUserMetadata(ctx);
        if (!userMeta.isEmpty()) b.metadata(userMeta);

        PutObjectResponse resp = s3.putObject(b.build(),
                RequestBody.fromInputStream(ctx.bodyInputStream(), contentLength));
        if (resp.eTag() != null) ctx.header("ETag", resp.eTag());
        ctx.status(HttpStatus.OK);
    }

    private void handleGet(Context ctx) {
        String uploadId = ctx.queryParam("uploadId");
        if (uploadId != null) {
            listParts(ctx, uploadId);
            return;
        }
        String key = ctx.pathParam("key");

        // Trailer is always announced — the buffered path will always emit
        // "ok"  for parity; the delayed-auth path emits "ok" or "fail" based on the end-of-stream check.
        AtomicReference<String> authResult = new AtomicReference<>("fail");
        ctx.header("Trailer", "X-Tinfoil-Auth");
        ctx.res().setTrailerFields(() -> Map.of("X-Tinfoil-Auth", authResult.get()));
        try {
            s3.getObject(b -> b.bucket(bucket).key(key), (response, in) -> {
                if (response.contentType() != null) ctx.contentType(response.contentType());
                if (response.eTag() != null) ctx.header("ETag", response.eTag());
                writeUserMetadataHeaders(ctx, response.metadata());
                try (var stream = in) {
                    stream.transferTo(ctx.outputStream());
                }
                return response;
            });
            authResult.set("ok");
        } catch (S3EncryptionClientException e) {
            // Upstream error (NoSuchKey etc.) — throws before bytes flow, so
            // the normal exception path can write a clean error response.
            if (e.getCause() instanceof S3Exception) {
                throw e;
            }
            // Auth/buffer-exceeded failure.
            // If the response is already committed (delayed-auth mode, bytes
            // started flowing) we can't write a fresh error — leave
            // authResult="fail" so the trailer goes out as "fail".
            // If not committed yet (buffered mode failed before transferring)
            // re-throw so the global handler returns an S3 XML error.
            if (!ctx.res().isCommitted()) {
                throw e;
            }
        }
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
        writeUserMetadataHeaders(ctx, resp.metadata());
        ctx.status(HttpStatus.OK);
    }

    private void deleteObject(Context ctx) {
        s3.deleteObject(b -> b.bucket(bucket).key(ctx.pathParam("key")));
        ctx.status(HttpStatus.NO_CONTENT);
    }

    // --- Bucket-level operations ---------------------------------------------

    private void handleBucketHead(Context ctx) {
        s3.headBucket(b -> b.bucket(bucket));
        ctx.status(HttpStatus.OK);
    }

    private void handleBucketGet(Context ctx) {
        if ("2".equals(ctx.queryParam("list-type"))) {
            listObjectsV2(ctx);
        } else if (ctx.queryParamMap().containsKey("uploads")) {
            listMultipartUploads(ctx);
        } else if (ctx.queryParamMap().containsKey("location")) {
            getBucketLocation(ctx);
        } else {
            writeS3Error(ctx, 400, "InvalidRequest",
                    "Unsupported bucket GET operation.");
        }
    }

    private void handleBucketPost(Context ctx) {
        if (ctx.queryParamMap().containsKey("delete")) {
            deleteObjects(ctx);
        } else {
            writeS3Error(ctx, 400, "InvalidRequest",
                    "Unsupported bucket POST operation.");
        }
    }

    private void getBucketLocation(Context ctx) {
        ctx.contentType("application/xml");
        ctx.result("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<LocationConstraint xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                + xmlEscape(region.id())
                + "</LocationConstraint>");
    }

    private void deleteObjects(Context ctx) {
        List<ObjectIdentifier> toDelete = parseDeleteRequestKeys(ctx.bodyAsBytes());
        if (toDelete.isEmpty()) {
            writeS3Error(ctx, 400, "MalformedXML", "Delete request contained no keys.");
            return;
        }
        DeleteObjectsResponse resp = s3.deleteObjects(DeleteObjectsRequest.builder()
                .bucket(bucket)
                .delete(d -> d.objects(toDelete))
                .build());

        StringBuilder xml = new StringBuilder(256);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><DeleteResult>");
        if (resp.deleted() != null) {
            for (DeletedObject d : resp.deleted()) {
                xml.append("<Deleted><Key>").append(xmlEscape(d.key())).append("</Key></Deleted>");
            }
        }
        if (resp.errors() != null) {
            for (S3Error e : resp.errors()) {
                xml.append("<Error>");
                xml.append("<Key>").append(xmlEscape(e.key())).append("</Key>");
                if (e.code() != null) xml.append("<Code>").append(xmlEscape(e.code())).append("</Code>");
                if (e.message() != null) xml.append("<Message>").append(xmlEscape(e.message())).append("</Message>");
                xml.append("</Error>");
            }
        }
        xml.append("</DeleteResult>");
        ctx.contentType("application/xml");
        ctx.result(xml.toString());
    }

    private static List<ObjectIdentifier> parseDeleteRequestKeys(byte[] body) {
        try {
            DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(body));
            NodeList keyNodes = doc.getElementsByTagName("Key");
            List<ObjectIdentifier> keys = new ArrayList<>(keyNodes.getLength());
            for (int i = 0; i < keyNodes.getLength(); i++) {
                keys.add(ObjectIdentifier.builder().key(keyNodes.item(i).getTextContent()).build());
            }
            return keys;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse DeleteObjects request: " + e.getMessage(), e);
        }
    }

    private void listMultipartUploads(Context ctx) {
        ListMultipartUploadsResponse resp = s3.listMultipartUploads(
                ListMultipartUploadsRequest.builder().bucket(bucket).build());
        String bucketParam = ctx.pathParam("bucket");
        StringBuilder xml = new StringBuilder(512);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><ListMultipartUploadsResult>");
        xml.append("<Bucket>").append(xmlEscape(bucketParam)).append("</Bucket>");
        xml.append("<KeyMarker></KeyMarker>");
        xml.append("<UploadIdMarker></UploadIdMarker>");
        xml.append("<NextKeyMarker></NextKeyMarker>");
        xml.append("<NextUploadIdMarker></NextUploadIdMarker>");
        xml.append("<MaxUploads>").append(resp.maxUploads() != null ? resp.maxUploads() : 1000).append("</MaxUploads>");
        xml.append("<IsTruncated>").append(Boolean.TRUE.equals(resp.isTruncated())).append("</IsTruncated>");
        if (resp.uploads() != null) {
            for (MultipartUpload u : resp.uploads()) {
                xml.append("<Upload>");
                xml.append("<Key>").append(xmlEscape(u.key())).append("</Key>");
                xml.append("<UploadId>").append(xmlEscape(u.uploadId())).append("</UploadId>");
                if (u.initiated() != null) {
                    xml.append("<Initiated>").append(u.initiated().toString()).append("</Initiated>");
                }
                xml.append("<StorageClass>STANDARD</StorageClass>");
                xml.append("</Upload>");
            }
        }
        xml.append("</ListMultipartUploadsResult>");
        ctx.contentType("application/xml");
        ctx.result(xml.toString());
    }

    private void listParts(Context ctx, String uploadId) {
        String key = ctx.pathParam("key");
        ListPartsResponse resp = s3.listParts(ListPartsRequest.builder()
                .bucket(bucket).key(key).uploadId(uploadId).build());

        // Snapshot our locally-buffered "pending" part, if any, so we can surface
        // it in the listing. Upstream doesn't see it yet — we hold the most-recent
        // part until we know whether it's the last (see multipart design notes).
        Integer pendingNum = null;
        Integer pendingSize = null;
        String pendingEtag = null;
        MultipartSession session = sessions.get(uploadId);
        if (session != null) {
            session.lock.lock();
            try {
                if (session.pendingPartBytes != null) {
                    pendingNum = session.pendingPartNumber;
                    pendingSize = session.pendingPartBytes.length;
                    pendingEtag = session.pendingPartEtag;
                }
            } finally {
                session.lock.unlock();
            }
        }

        String bucketParam = ctx.pathParam("bucket");
        StringBuilder xml = new StringBuilder(512);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><ListPartsResult>");
        xml.append("<Bucket>").append(xmlEscape(bucketParam)).append("</Bucket>");
        xml.append("<Key>").append(xmlEscape(key)).append("</Key>");
        xml.append("<UploadId>").append(xmlEscape(uploadId)).append("</UploadId>");
        xml.append("<PartNumberMarker>0</PartNumberMarker>");
        int nextMarker = resp.nextPartNumberMarker() != null ? resp.nextPartNumberMarker() : 0;
        if (pendingNum != null && pendingNum > nextMarker) nextMarker = pendingNum;
        xml.append("<NextPartNumberMarker>").append(nextMarker).append("</NextPartNumberMarker>");
        xml.append("<MaxParts>").append(resp.maxParts() != null ? resp.maxParts() : 1000).append("</MaxParts>");
        xml.append("<IsTruncated>").append(Boolean.TRUE.equals(resp.isTruncated())).append("</IsTruncated>");
        xml.append("<StorageClass>STANDARD</StorageClass>");
        if (resp.parts() != null) {
            for (Part p : resp.parts()) {
                xml.append("<Part>");
                xml.append("<PartNumber>").append(p.partNumber()).append("</PartNumber>");
                if (p.lastModified() != null) {
                    xml.append("<LastModified>").append(p.lastModified().toString()).append("</LastModified>");
                }
                if (p.eTag() != null) {
                    xml.append("<ETag>").append(xmlEscape(p.eTag())).append("</ETag>");
                }
                long size = p.size() != null ? Math.max(0, p.size() - GCM_TAG_BYTES) : 0;
                xml.append("<Size>").append(size).append("</Size>");
                xml.append("</Part>");
            }
        }
        if (pendingNum != null) {
            xml.append("<Part>");
            xml.append("<PartNumber>").append(pendingNum).append("</PartNumber>");
            if (pendingEtag != null) {
                xml.append("<ETag>").append(xmlEscape(pendingEtag)).append("</ETag>");
            }
            xml.append("<Size>").append(pendingSize).append("</Size>");
            xml.append("</Part>");
        }
        xml.append("</ListPartsResult>");
        ctx.contentType("application/xml");
        ctx.result(xml.toString());
    }

    private void sweepSessions() {
        Instant cutoff = Instant.now().minusSeconds(MULTIPART_SESSION_TTL_SECONDS);
        List<MultipartSession> expired = new ArrayList<>();
        for (MultipartSession s : sessions.values()) {
            if (s.createdAt.isBefore(cutoff)) expired.add(s);
        }
        for (MultipartSession s : expired) {
            if (sessions.remove(s.uploadId) == null) continue;
            try {
                s3.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                        .bucket(bucket).key(s.key).uploadId(s.uploadId).build());
            } catch (Exception e) {
                System.err.println("multipart GC: abort failed for " + s.uploadId + ": " + e.getMessage());
            }
        }
    }

    private void listObjectsV2(Context ctx) {
        ListObjectsV2Request.Builder b = ListObjectsV2Request.builder().bucket(bucket);
        String prefix = ctx.queryParam("prefix");
        if (prefix != null) b.prefix(prefix);
        String delimiter = ctx.queryParam("delimiter");
        if (delimiter != null) b.delimiter(delimiter);
        String maxKeys = ctx.queryParam("max-keys");
        if (maxKeys != null) b.maxKeys(Integer.parseInt(maxKeys));
        String continuationToken = ctx.queryParam("continuation-token");
        if (continuationToken != null) b.continuationToken(continuationToken);
        String startAfter = ctx.queryParam("start-after");
        if (startAfter != null) b.startAfter(startAfter);

        ListObjectsV2Response resp = s3.listObjectsV2(b.build());

        String bucketParam = ctx.pathParam("bucket");
        StringBuilder xml = new StringBuilder(512);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        xml.append("<ListBucketResult>");
        xml.append("<Name>").append(xmlEscape(bucketParam)).append("</Name>");
        if (prefix != null) {
            xml.append("<Prefix>").append(xmlEscape(prefix)).append("</Prefix>");
        }
        if (delimiter != null) {
            xml.append("<Delimiter>").append(xmlEscape(delimiter)).append("</Delimiter>");
        }
        xml.append("<KeyCount>").append(resp.keyCount() != null ? resp.keyCount() : 0).append("</KeyCount>");
        xml.append("<MaxKeys>").append(resp.maxKeys() != null ? resp.maxKeys() : 1000).append("</MaxKeys>");
        xml.append("<IsTruncated>").append(Boolean.TRUE.equals(resp.isTruncated())).append("</IsTruncated>");
        if (continuationToken != null) {
            xml.append("<ContinuationToken>").append(xmlEscape(continuationToken)).append("</ContinuationToken>");
        }
        if (resp.nextContinuationToken() != null) {
            xml.append("<NextContinuationToken>").append(xmlEscape(resp.nextContinuationToken())).append("</NextContinuationToken>");
        }
        if (resp.contents() != null) {
            for (S3Object obj : resp.contents()) {
                xml.append("<Contents>");
                xml.append("<Key>").append(xmlEscape(obj.key())).append("</Key>");
                if (obj.lastModified() != null) {
                    xml.append("<LastModified>").append(obj.lastModified().toString()).append("</LastModified>");
                }
                if (obj.eTag() != null) {
                    xml.append("<ETag>").append(xmlEscape(obj.eTag())).append("</ETag>");
                }
                // Size is ciphertext size; subtract GCM tag for plaintext.
                long size = obj.size() != null ? Math.max(0, obj.size() - GCM_TAG_BYTES) : 0;
                xml.append("<Size>").append(size).append("</Size>");
                xml.append("<StorageClass>STANDARD</StorageClass>");
                xml.append("</Contents>");
            }
        }
        if (resp.commonPrefixes() != null) {
            for (CommonPrefix cp : resp.commonPrefixes()) {
                xml.append("<CommonPrefixes>");
                xml.append("<Prefix>").append(xmlEscape(cp.prefix())).append("</Prefix>");
                xml.append("</CommonPrefixes>");
            }
        }
        xml.append("</ListBucketResult>");

        ctx.contentType("application/xml");
        ctx.result(xml.toString());
    }

    // --- Multipart operations ------------------------------------------------

    private void createMultipartUpload(Context ctx) {
        String key = ctx.pathParam("key");
        CreateMultipartUploadRequest.Builder b = CreateMultipartUploadRequest.builder()
                .bucket(bucket).key(key);
        String contentType = ctx.header("Content-Type");
        if (contentType != null) b.contentType(contentType);
        Map<String, String> userMeta = extractUserMetadata(ctx);
        if (!userMeta.isEmpty()) b.metadata(userMeta);
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
        if (claimedLength > MAX_PART_BYTES) {
            writeS3Error(ctx, 400, "EntityTooLarge",
                    "Part size exceeds MAX_PART_BYTES (" + MAX_PART_BYTES + ").");
            return;
        }
        byte[] body = ctx.bodyAsBytes();
        if (body.length > MAX_PART_BYTES) {
            writeS3Error(ctx, 400, "EntityTooLarge",
                    "Part size exceeds MAX_PART_BYTES (" + MAX_PART_BYTES + ").");
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

            String etag = "\"" + md5Hex(body) + "\"";
            session.pendingPartNumber = partNumber;
            session.pendingPartBytes = body;
            session.pendingPartEtag = etag;
            session.nextExpectedPartNumber = partNumber + 1;
            ctx.header("ETag", etag);
        } finally {
            session.lock.unlock();
        }

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
        session.pendingPartEtag = null;
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

    private static Map<String, String> extractUserMetadata(Context ctx) {
        Map<String, String> meta = new HashMap<>();
        for (Map.Entry<String, String> h : ctx.headerMap().entrySet()) {
            String name = h.getKey().toLowerCase();
            if (name.startsWith("x-amz-meta-")) {
                meta.put(name.substring("x-amz-meta-".length()), h.getValue());
            }
        }
        return meta;
    }

    private static void writeUserMetadataHeaders(Context ctx, Map<String, String> metadata) {
        if (metadata == null) return;
        for (Map.Entry<String, String> e : metadata.entrySet()) {
            // The encryption client stores its own metadata under x-amz-* keys
            // (e.g. x-amz-d, x-amz-i, x-amz-w). User metadata uses any other name.
            if (!e.getKey().toLowerCase().startsWith("x-amz-")) {
                ctx.header("x-amz-meta-" + e.getKey(), e.getValue());
            }
        }
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
