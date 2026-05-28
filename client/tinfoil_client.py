"""Tamper-detecting GET helpers for the tinfoil-buckets sidecar.

The sidecar streams GET responses chunk-by-chunk and emits an HTTP trailer
`X-Tinfoil-Auth: ok|fail` after the body, where `ok` means AES-GCM
authentication of the encrypted object succeeded.

Plain boto3 ignores trailers. These helpers read the trailer via urllib3 and
raise `TamperingDetected` when it isn't `ok` — letting callers discard data
that may have been modified in transit or at rest.
"""

from typing import Iterator


class TamperingDetected(Exception):
    """Raised when the sidecar's X-Tinfoil-Auth trailer is missing or != 'ok'.

    The bytes already yielded/buffered MUST NOT be trusted. The recommended
    pattern is to stage to a temp location and only commit (rename) after
    the helper returns/finishes without raising.
    """


def _read_trailer(raw_stream) -> str:
    """Pull the X-Tinfoil-Auth trailer off the urllib3 response.

    urllib3 stores trailers (when present) on the underlying response after
    the chunked body is fully consumed. The attribute name varies slightly
    by version; we probe a few.
    """
    for attr in ("trailers", "_trailers"):
        trailers = getattr(raw_stream, attr, None)
        if trailers:
            value = trailers.get("X-Tinfoil-Auth") or trailers.get("x-tinfoil-auth")
            if value:
                return value
    return "missing"


def verified_get(s3, *, Bucket: str, Key: str) -> bytes:
    """Buffer-and-return: drain the object, verify the trailer, return bytes.

    Convenient drop-in for small/medium objects (everything fits in client RAM).
    For large objects, use `verified_iter` and stage to disk instead.
    """
    resp = s3.get_object(Bucket=Bucket, Key=Key)
    body = resp["Body"]
    raw = body._raw_stream  # urllib3.HTTPResponse — private attr but stable
    data = bytearray()
    for chunk in body.iter_chunks():
        data.extend(chunk)
    result = _read_trailer(raw)
    if result != "ok":
        raise TamperingDetected(f"X-Tinfoil-Auth={result!r}")
    return bytes(data)


def verified_iter(
    s3, *, Bucket: str, Key: str, chunk_size: int = 1024 * 1024
) -> Iterator[bytes]:
    """Stream chunks; raise TamperingDetected at the end if auth failed.

    Constant client memory regardless of object size. Caller MUST NOT commit
    consumed bytes (rename temp file, etc.) until the iterator completes
    cleanly. On TamperingDetected, discard everything already yielded.

    Example:

        tmp = open("staging.bin", "wb")
        try:
            for chunk in verified_iter(s3, Bucket=B, Key=K):
                tmp.write(chunk)
            tmp.close()
            os.rename("staging.bin", "final.bin")
        except TamperingDetected:
            tmp.close()
            os.remove("staging.bin")
    """
    resp = s3.get_object(Bucket=Bucket, Key=Key)
    body = resp["Body"]
    raw = body._raw_stream
    for chunk in body.iter_chunks(chunk_size=chunk_size):
        yield chunk
    result = _read_trailer(raw)
    if result != "ok":
        raise TamperingDetected(f"X-Tinfoil-Auth={result!r}")
