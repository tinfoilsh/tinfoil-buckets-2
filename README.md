Simple SOTA AWS implementation of encrypted buckets server. Made to run as a side-car container on an enclave.

## Plan

Boots up with a key (tbd on how it gets there).

Exposes an S3 api to the local network, and sends/gets encrypted requests from S3. All a caller does is the equivalent of S3 but pointing to a different network (and with no auth).

Ideally we can just point the URl & not mess around with the any s3 sdk, so that any language can do this..

## Notes

- **Path-style only.** Configure your S3 SDK with `forcePathStyle: true` (or equivalent). Virtual-hosted (`bucket.s3.amazonaws.com`) URLs are not supported.
- **Single backing bucket for now.** All requests route to the bucket configured on the sidecar server via `BUCKET`; the bucket name in the request URL is currently ignored. (Future: per-request bucket selection.)
- **No auth.** sigv4 signatures from clients are accepted and discarded.

## Configure

Create `.env` in the project root:

```
BUCKET=your-real-s3-bucket
ENCRYPTION_KEY=<base64 32-byte AES-256 key>     # openssl rand -base64 32
AWS_REGION=us-east-2
AWS_ACCESS_KEY_ID=...
AWS_SECRET_ACCESS_KEY=...
PORT=9000
```

## Run

- Local: `mvn compile exec:java`
- Docker: `docker build -t tinfoil-buckets . && docker run --rm -p 9000:9000 --env-file .env tinfoil-buckets`

## Test

`./test.sh` runs PUT / HEAD / GET / DELETE against a running server and checks the round-trip.
