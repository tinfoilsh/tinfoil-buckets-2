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
- Docker: `docker build -t tinfoil-buckets . && docker run --rm -p 9000:9000 -v $(pwd)/.env:/app/.env tinfoil-buckets`

## Test

`./test.sh` runs PUT / HEAD / GET / DELETE against a running server and checks the round-trip.
`client/` contains the python S3 sdk

## Design Decisions (constraints)

1. Force the client to send parts sequentially on multipart `(max_concurrency=1)`. This is because the encryption client iterates through input sequentially - it needs linear, in order access to the input to do encryption.
2. The low-level multipart upload exposed by S3 sdk requires that you mark the final uploadPart w/ `isLastPart=true`. The S3 protocol doesn't tell us this - we only know once the user sends the `CompleteMultipartUpload` that it was the last, or if another part arrives that this wasn't the last.
   1. This means that before we upload part N we need to recieve part N+1.
3. Normally, on upload of each part S3, an `ETag` is returned that can be used to check the validity of a part in the future. `CompleteMultipartUpload` requires the `ETag` of the last part in the request. This means that for the penultimate part the server enters a deadlock where it's waiting for the client, but the client can't send the complete.
   1. For this reason, we have decided to completely do false ETags.
   2. Also the client can't even use `ETags`, as they're only useful if you hold the ciphertext. We'll just keep track of these on the server.
4. Multi-part constraints
   1. Setting a 1GB per part cap for now, as we have to hold the full thing in the server.
   2. Can have as many active sessions as you want. User beware.
5. Multi-part state is local to the server, because we need to have the running AES-GCM cipher state so we can continue uploading. If this ever restarts, we lose this session, so there's no point in trying to use the S3 as the SSOT for session state.
6. GET needs to buffer until the end so we can check the authentication tag. The user can set this with an evnironment var (max 2gb) of buffering. The user is responsible for memory consumption.
   1. The implementation of this looks like: we turn on delayedAuthentication, and we manually buffer in the server if size < environment var. If it's greater than we stream it to the client, but the client needs to use our special `tinfoil_client` to handle the signal at the end if the data is bad & needs to be thrown away. `enableDelayedAuthenticationMode`
