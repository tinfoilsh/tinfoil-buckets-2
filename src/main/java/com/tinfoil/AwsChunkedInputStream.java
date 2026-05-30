package com.tinfoil;

import java.io.IOException;
import java.io.InputStream;

/**
 * Decodes the {@code aws-chunked} content encoding used by AWS SDKs for
 * SigV4 streaming PUTs (PutObject / UploadPart). The wire format is defined
 * at <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/sigv4-streaming.html">
 * docs.aws.amazon.com/AmazonS3/latest/API/sigv4-streaming.html</a> and is the
 * same across boto3, aws-sdk-java, aws-sdk-go, aws-sdk-js, and aws-sdk-net
 * regardless of which payload mode they pick — they all use RFC 7230 chunked
 * framing with optional chunk-signature extensions and optional trailers:
 *
 * <pre>
 *   &lt;hex-length&gt;[;chunk-signature=&hellip;]\r\n
 *   &lt;bytes&gt;\r\n
 *   &hellip;
 *   0[;chunk-signature=&hellip;]\r\n
 *   [trailer-name:trailer-value\r\n]*
 *   \r\n
 * </pre>
 *
 * Extensions are stripped and trailer values are read past but not validated:
 * TLS plus our outbound S3-side checksums already cover transport integrity,
 * and the caller has already been authenticated upstream.
 */
final class AwsChunkedInputStream extends InputStream {

    private final InputStream in;
    private long chunkRemaining = 0;
    private boolean ended = false;

    AwsChunkedInputStream(InputStream in) {
        this.in = in;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n == -1 ? -1 : (one[0] & 0xff);
    }

    @Override
    public int read(byte[] dst, int off, int len) throws IOException {
        if (len == 0) return 0;
        if (ended) return -1;
        if (chunkRemaining == 0) {
            long size = readChunkSize();
            if (size == 0) {
                consumeTrailers();
                ended = true;
                return -1;
            }
            chunkRemaining = size;
        }
        int toRead = (int) Math.min(len, chunkRemaining);
        int n = in.read(dst, off, toRead);
        if (n == -1) throw new IOException("aws-chunked: unexpected EOF mid-chunk");
        chunkRemaining -= n;
        if (chunkRemaining == 0) expectCRLF();
        return n;
    }

    private long readChunkSize() throws IOException {
        String line = readLine();
        int semi = line.indexOf(';');
        String hex = (semi < 0 ? line : line.substring(0, semi)).trim();
        if (hex.isEmpty()) throw new IOException("aws-chunked: empty chunk-size line");
        try {
            return Long.parseLong(hex, 16);
        } catch (NumberFormatException e) {
            throw new IOException("aws-chunked: invalid chunk size: " + hex);
        }
    }

    private void consumeTrailers() throws IOException {
        // Trailer lines until empty line. Lines are <name>:<value>\r\n.
        while (!readLine().isEmpty()) { /* discard */ }
    }

    private void expectCRLF() throws IOException {
        int c1 = in.read();
        int c2 = in.read();
        if (c1 != '\r' || c2 != '\n') {
            throw new IOException("aws-chunked: missing CRLF after chunk data");
        }
    }

    private String readLine() throws IOException {
        StringBuilder sb = new StringBuilder(16);
        while (true) {
            int c = in.read();
            if (c == -1) throw new IOException("aws-chunked: unexpected EOF in framing line");
            if (c == '\r') {
                int n = in.read();
                if (n != '\n') throw new IOException("aws-chunked: bare CR in framing line");
                return sb.toString();
            }
            sb.append((char) c);
        }
    }
}
