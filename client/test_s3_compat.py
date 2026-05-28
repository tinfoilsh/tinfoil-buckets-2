"""S3 protocol compatibility tests.

Requires a running sidecar (default http://localhost:9000) and a real upstream
bucket configured via the server's BUCKET env var.

  ./.venv/bin/pytest -v test_s3_compat.py
"""

import os
import uuid

import boto3
import pytest
from botocore.config import Config
from botocore.exceptions import ClientError

ENDPOINT = os.environ.get("ENDPOINT", "http://localhost:9000")
BUCKET = os.environ.get("TEST_BUCKET", "anybucket")


@pytest.fixture(scope="session")
def s3():
    return boto3.client(
        "s3",
        endpoint_url=ENDPOINT,
        aws_access_key_id="test",
        aws_secret_access_key="test",
        region_name="us-east-1",
        config=Config(s3={"addressing_style": "path"}),
    )


@pytest.fixture
def key(s3):
    k = f"compat/{uuid.uuid4()}.bin"
    yield k
    try:
        s3.delete_object(Bucket=BUCKET, Key=k)
    except ClientError:
        pass


# --- Happy-path response shapes ----------------------------------------------


def test_put_returns_etag_and_200(s3, key):
    resp = s3.put_object(Bucket=BUCKET, Key=key, Body=b"hello")
    assert resp["ResponseMetadata"]["HTTPStatusCode"] == 200
    assert "ETag" in resp
    # S3 returns ETag as a quoted string
    assert resp["ETag"].startswith('"') and resp["ETag"].endswith('"')


def test_get_returns_body_and_metadata(s3, key):
    body = b"hello world"
    put = s3.put_object(Bucket=BUCKET, Key=key, Body=body, ContentType="text/plain")
    resp = s3.get_object(Bucket=BUCKET, Key=key)
    assert resp["ResponseMetadata"]["HTTPStatusCode"] == 200
    assert resp["Body"].read() == body
    assert resp["ContentType"] == "text/plain"
    assert resp["ContentLength"] == len(body)
    assert resp["ETag"] == put["ETag"]


def test_head_returns_plaintext_content_length(s3, key):
    body = b"head me"
    put = s3.put_object(Bucket=BUCKET, Key=key, Body=body, ContentType="text/plain")
    resp = s3.head_object(Bucket=BUCKET, Key=key)
    assert resp["ResponseMetadata"]["HTTPStatusCode"] == 200
    # Content-Length should reflect plaintext, not ciphertext
    assert resp["ContentLength"] == len(body)
    assert resp["ContentType"] == "text/plain"
    assert resp["ETag"] == put["ETag"]


def test_delete_returns_204(s3, key):
    s3.put_object(Bucket=BUCKET, Key=key, Body=b"x")
    resp = s3.delete_object(Bucket=BUCKET, Key=key)
    assert resp["ResponseMetadata"]["HTTPStatusCode"] == 204


def test_delete_missing_key_is_idempotent(s3):
    # S3 DeleteObject is idempotent — deleting a non-existent key returns 204.
    resp = s3.delete_object(Bucket=BUCKET, Key=f"missing/{uuid.uuid4()}")
    assert resp["ResponseMetadata"]["HTTPStatusCode"] == 204


# --- Error mapping ----------------------------------------------------------


def test_get_missing_raises_no_such_key(s3):
    with pytest.raises(ClientError) as exc:
        s3.get_object(Bucket=BUCKET, Key=f"missing/{uuid.uuid4()}")
    assert exc.value.response["Error"]["Code"] == "NoSuchKey"
    assert exc.value.response["ResponseMetadata"]["HTTPStatusCode"] == 404


def test_head_missing_returns_404(s3):
    # HEAD on missing key: boto3 raises ClientError with 404 (no body to parse).
    with pytest.raises(ClientError) as exc:
        s3.head_object(Bucket=BUCKET, Key=f"missing/{uuid.uuid4()}")
    assert exc.value.response["ResponseMetadata"]["HTTPStatusCode"] == 404


# --- Content / key edge cases ------------------------------------------------


def test_empty_body_roundtrip(s3, key):
    s3.put_object(Bucket=BUCKET, Key=key, Body=b"")
    resp = s3.get_object(Bucket=BUCKET, Key=key)
    assert resp["Body"].read() == b""
    assert resp["ContentLength"] == 0


def test_binary_roundtrip(s3, key):
    body = bytes(range(256)) * 16  # 4 KiB of mixed bytes
    s3.put_object(Bucket=BUCKET, Key=key, Body=body)
    resp = s3.get_object(Bucket=BUCKET, Key=key)
    assert resp["Body"].read() == body


def test_utf8_content(s3, key):
    body = "héllo wörld 🔐 中文".encode("utf-8")
    s3.put_object(
        Bucket=BUCKET, Key=key, Body=body, ContentType="text/plain; charset=utf-8"
    )
    resp = s3.get_object(Bucket=BUCKET, Key=key)
    assert resp["Body"].read() == body


def test_key_with_path_separators(s3):
    k = f"deeply/nested/path/{uuid.uuid4()}.txt"
    s3.put_object(Bucket=BUCKET, Key=k, Body=b"nested")
    try:
        resp = s3.get_object(Bucket=BUCKET, Key=k)
        assert resp["Body"].read() == b"nested"
    finally:
        s3.delete_object(Bucket=BUCKET, Key=k)


def test_content_type_passthrough(s3, key):
    s3.put_object(Bucket=BUCKET, Key=key, Body=b"{}", ContentType="application/json")
    resp = s3.get_object(Bucket=BUCKET, Key=key)
    assert resp["ContentType"] == "application/json"


# --- Documented gaps (features not yet implemented) --------------------------


@pytest.mark.xfail(reason="ListObjectsV2 not implemented yet", strict=True)
def test_list_objects_v2(s3):
    s3.list_objects_v2(Bucket=BUCKET)


@pytest.mark.xfail(reason="HeadBucket not implemented yet", strict=True)
def test_head_bucket(s3):
    s3.head_bucket(Bucket=BUCKET)


@pytest.mark.xfail(
    reason="x-amz-meta-* user metadata passthrough not implemented", strict=True
)
def test_user_metadata_passthrough(s3, key):
    s3.put_object(
        Bucket=BUCKET,
        Key=key,
        Body=b"x",
        Metadata={"author": "tinfoil", "version": "1"},
    )
    resp = s3.head_object(Bucket=BUCKET, Key=key)
    assert resp["Metadata"]["author"] == "tinfoil"
    assert resp["Metadata"]["version"] == "1"


def test_multipart_upload_single_part(s3, key):
    body = b"x" * (5 * 1024 * 1024 + 7)  # 5 MiB + 7
    mp = s3.create_multipart_upload(Bucket=BUCKET, Key=key)
    part = s3.upload_part(
        Bucket=BUCKET,
        Key=key,
        UploadId=mp["UploadId"],
        PartNumber=1,
        Body=body,
    )
    s3.complete_multipart_upload(
        Bucket=BUCKET,
        Key=key,
        UploadId=mp["UploadId"],
        MultipartUpload={"Parts": [{"ETag": part["ETag"], "PartNumber": 1}]},
    )
    resp = s3.get_object(Bucket=BUCKET, Key=key)
    assert resp["Body"].read() == body


def test_multipart_upload_three_parts(s3, key):
    p1 = b"A" * (5 * 1024 * 1024)
    p2 = b"B" * (5 * 1024 * 1024)
    p3 = b"C" * (1024)  # last can be small
    mp = s3.create_multipart_upload(Bucket=BUCKET, Key=key)
    e1 = s3.upload_part(
        Bucket=BUCKET, Key=key, UploadId=mp["UploadId"], PartNumber=1, Body=p1
    )["ETag"]
    e2 = s3.upload_part(
        Bucket=BUCKET, Key=key, UploadId=mp["UploadId"], PartNumber=2, Body=p2
    )["ETag"]
    e3 = s3.upload_part(
        Bucket=BUCKET, Key=key, UploadId=mp["UploadId"], PartNumber=3, Body=p3
    )["ETag"]
    s3.complete_multipart_upload(
        Bucket=BUCKET,
        Key=key,
        UploadId=mp["UploadId"],
        MultipartUpload={
            "Parts": [
                {"ETag": e1, "PartNumber": 1},
                {"ETag": e2, "PartNumber": 2},
                {"ETag": e3, "PartNumber": 3},
            ]
        },
    )
    resp = s3.get_object(Bucket=BUCKET, Key=key)
    assert resp["Body"].read() == p1 + p2 + p3


def test_multipart_abort(s3, key):
    mp = s3.create_multipart_upload(Bucket=BUCKET, Key=key)
    s3.upload_part(
        Bucket=BUCKET,
        Key=key,
        UploadId=mp["UploadId"],
        PartNumber=1,
        Body=b"x" * (5 * 1024 * 1024),
    )
    s3.abort_multipart_upload(Bucket=BUCKET, Key=key, UploadId=mp["UploadId"])
    # Object should not exist after abort
    with pytest.raises(ClientError) as exc:
        s3.get_object(Bucket=BUCKET, Key=key)
    assert exc.value.response["Error"]["Code"] == "NoSuchKey"


def test_multipart_out_of_order_rejected(s3, key):
    mp = s3.create_multipart_upload(Bucket=BUCKET, Key=key)
    try:
        with pytest.raises(ClientError) as exc:
            s3.upload_part(
                Bucket=BUCKET,
                Key=key,
                UploadId=mp["UploadId"],
                PartNumber=2,
                Body=b"x" * (5 * 1024 * 1024),
            )
        assert exc.value.response["Error"]["Code"] == "InvalidPart"
        assert exc.value.response["ResponseMetadata"]["HTTPStatusCode"] == 400
    finally:
        s3.abort_multipart_upload(Bucket=BUCKET, Key=key, UploadId=mp["UploadId"])


@pytest.mark.xfail(reason="Range requests not implemented yet", strict=True)
def test_range_get(s3, key):
    s3.put_object(Bucket=BUCKET, Key=key, Body=b"0123456789")
    resp = s3.get_object(Bucket=BUCKET, Key=key, Range="bytes=2-5")
    assert resp["Body"].read() == b"2345"
