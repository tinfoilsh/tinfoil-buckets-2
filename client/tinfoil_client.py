"""Tamper-detecting GET helpers for the tinfoil-buckets sidecar.

Only relevant when the sidecar is started with DANGEROUS_DELAYED_AUTH=true.
In that mode plaintext is streamed to the client before the GCM tag is
verified, and the sidecar appends a 4-byte marker to every GET body to
signal the auth outcome:
  - "TFOK" on success
  - "TFNG" on auth failure at end-of-stream

These helpers strip and verify the marker so callers get clean plaintext
or a clear failure signal. Plain boto3 in this mode receives a body that
is 4 bytes longer than the actual plaintext — the trailing 4 bytes are
the marker, which the caller is responsible for stripping (or ignoring).

In DEFAULT (buffered) mode, the sidecar does NOT append a marker — the
encryption client verifies the GCM tag before any byte is released, so
plain boto3 GETs return clean plaintext exactly as stored. These helpers
will misbehave in that mode (they'd treat the last 4 plaintext bytes as
the marker). Use plain boto3 in default mode.
"""

import os
from typing import Iterator

MARKER_OK = b"TFOK"
MARKER_FAIL = b"TFNG"
MARKER_LEN = 4


class TamperingDetected(Exception):
    """Raised when the body's trailing marker indicates auth failure (or is missing).

    Bytes already yielded/buffered MUST NOT be trusted. The recommended
    pattern is to stage to a temp location and only commit (rename) after
    the helper returns/finishes without raising.
    """


def verified_get(s3, *, Bucket: str, Key: str) -> bytes:
    """Buffer-and-return: drain the object, strip + verify the marker, return plaintext.

    Convenient drop-in for small/medium objects (everything fits in client RAM).
    For large objects, use `verified_iter` and stage to disk.
    """
    data = s3.get_object(Bucket=Bucket, Key=Key)["Body"].read()
    return _verify_and_strip(data)


def verified_iter(
    s3, *, Bucket: str, Key: str, chunk_size: int = 1024 * 1024
) -> Iterator[bytes]:
    """Stream chunks; raise TamperingDetected at the end if the marker isn't OK.

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

    Implementation: holds back the last MARKER_LEN bytes so we never yield
    the marker to the caller. The held-back bytes are checked at end-of-stream.
    """
    body = s3.get_object(Bucket=Bucket, Key=Key)["Body"]
    pending = b""
    for chunk in body.iter_chunks(chunk_size=chunk_size):
        buf = pending + chunk
        if len(buf) > MARKER_LEN:
            yield buf[:-MARKER_LEN]
            pending = buf[-MARKER_LEN:]
        else:
            pending = buf
    if pending == MARKER_OK:
        return
    if pending == MARKER_FAIL:
        raise TamperingDetected("server-emitted fail marker (auth tag mismatch)")
    raise TamperingDetected(f"missing/unknown end-of-body marker: {pending!r}")


def _verify_and_strip(data: bytes) -> bytes:
    if len(data) < MARKER_LEN:
        raise TamperingDetected(
            f"response too short for end-of-body marker: {len(data)} bytes"
        )
    marker = data[-MARKER_LEN:]
    if marker == MARKER_OK:
        return data[:-MARKER_LEN]
    if marker == MARKER_FAIL:
        raise TamperingDetected("server-emitted fail marker (auth tag mismatch)")
    raise TamperingDetected(f"unknown end-of-body marker: {marker!r}")


# Backwards-compat shim so the legacy debug env var still works without effect.
if os.environ.get("TINFOIL_TRAILER_DEBUG"):
    import sys

    sys.stderr.write(
        "[tinfoil_client] TINFOIL_TRAILER_DEBUG is set; this build "
        "uses in-body markers, not HTTP trailers. No debug to emit.\n"
    )
