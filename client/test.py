#!/usr/bin/env python3
"""SDK-level smoke test. Points boto3 at the local sidecar"""

import os
import sys
import time

import boto3
from botocore.config import Config
from botocore.exceptions import ClientError

ENDPOINT = os.environ.get("ENDPOINT", "http://localhost:9000")
BUCKET = os.environ.get("TEST_BUCKET", "anybucket")
KEY = f"hello-{int(time.time())}.txt"
PAYLOAD = b"Hello, encrypted world via boto3!"

s3 = boto3.client(
    "s3",
    endpoint_url=ENDPOINT,
    aws_access_key_id="test",
    aws_secret_access_key="test",
    region_name="us-east-1",
    config=Config(s3={"addressing_style": "path"}),
)


def step(name, fn):
    print(f"--- {name} ---")
    fn()
    print()


def put():
    resp = s3.put_object(Bucket=BUCKET, Key=KEY, Body=PAYLOAD, ContentType="text/plain")
    print(f"ETag={resp.get('ETag')}")


def head():
    resp = s3.head_object(Bucket=BUCKET, Key=KEY)
    print(
        f"ContentLength={resp.get('ContentLength')} ContentType={resp.get('ContentType')}"
    )


def get():
    resp = s3.get_object(Bucket=BUCKET, Key=KEY)
    body = resp["Body"].read()
    print(f"body={body!r}")
    assert body == PAYLOAD, f"round-trip mismatch: {body!r} != {PAYLOAD!r}"
    print("PASS round-trip matches")


def delete():
    s3.delete_object(Bucket=BUCKET, Key=KEY)
    print("deleted")


def get_after_delete():
    try:
        s3.get_object(Bucket=BUCKET, Key=KEY)
    except ClientError as e:
        code = e.response["Error"]["Code"]
        status = e.response["ResponseMetadata"]["HTTPStatusCode"]
        print(f"got expected error: Code={code} HTTPStatus={status}")
        assert code == "NoSuchKey", f"expected NoSuchKey, got {code}"
        assert status == 404, f"expected 404, got {status}"
        print("PASS NoSuchKey mapped correctly")
        return
    sys.exit("FAIL: GET after DELETE did not raise")


step("PUT", put)
step("HEAD", head)
step("GET", get)
step("DELETE", delete)
step("GET after DELETE (expect NoSuchKey)", get_after_delete)
