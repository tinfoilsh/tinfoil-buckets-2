package com.tinfoil;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import software.amazon.awssdk.services.s3.model.CompletedPart;

public class MultipartSession {
    public final String uploadId;
    public final String key;
    public final Instant createdAt = Instant.now();
    public final ReentrantLock lock = new ReentrantLock();

    public int nextExpectedPartNumber = 1;
    public int pendingPartNumber = -1;
    public byte[] pendingPartBytes = null;
    public final List<CompletedPart> completedParts = new ArrayList<>();

    public MultipartSession(String uploadId, String key) {
        this.uploadId = uploadId;
        this.key = key;
    }
}
