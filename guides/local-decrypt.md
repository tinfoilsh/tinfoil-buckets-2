# Local decrypt without running the sidecar

For operator-side tooling — viewing runs, auditing, migrating — you don't need to run Docker. A short Python helper (~50 lines) decrypts S3 objects directly, given the same master key the sidecar was started with.

**Scope:** decrypt only. Encrypt is more code. Just use the sidecar.

## The helper

A reference implementation lives in [`tinfoil-persistent-storage-example/tinfoil_crypto.py`](https://github.com/tinfoilsh/tinfoil-persistent-storage-example/blob/main/tinfoil_crypto.py). Drop it next to your script — single file, depends only on `cryptography` and `boto3`.

Targets the v4 default suite (`ALG_AES_256_GCM_HKDF_SHA512_COMMIT_KEY`, id 0x0073). If the sidecar ever moves to a different suite, the helper has to follow.

## Usage

```python
import base64, os, boto3
import tinfoil_crypto

s3 = boto3.client("s3", region_name="us-east-2")   # direct AWS, no sidecar
master_key = base64.b64decode(os.environ["ENCRYPTION_KEY"])  # same key the sidecar uses

obj = s3.get_object(Bucket="real-bucket", Key="path/to/file")
plaintext = tinfoil_crypto.decrypt_object(
    master_key,
    obj["Metadata"],     # envelope headers (x-amz-3, x-amz-i, x-amz-d, …)
    obj["Body"].read(),  # ciphertext
)
```

`LIST` doesn't need decryption — it just returns metadata. Only the body bytes go through `decrypt_object`.

## What the envelope looks like

The encryption client stores its state as S3 user-metadata on the object. boto3 surfaces it under `response["Metadata"]` (lowercased, `x-amz-meta-` prefix stripped):

| Header    | Meaning                                                                 |
| --------- | ----------------------------------------------------------------------- |
| `x-amz-3` | Wrapped data key (12-byte IV ‖ 32-byte ciphertext ‖ 16-byte tag = 60 B) |
| `x-amz-i` | Message ID (28 B, HKDF salt)                                            |
| `x-amz-d` | Key commitment value (28 B, verified before decrypt)                    |
| `x-amz-c` | Suite ID as decimal string (`"115"`)                                    |
| `x-amz-w` | Wrap algorithm, compressed (`"02"` = AES/GCM)                           |

The decrypt flow:

1. AES-GCM unwrap `x-amz-3` with master key → 32-byte plaintext data key (PDK).
2. HKDF-SHA512(salt=`x-amz-i`, ikm=PDK):
   - `info = b"\x00\x73COMMITKEY", length=28` → must equal `x-amz-d` (constant-time compare).
   - `info = b"\x00\x73DERIVEKEY", length=32` → content encryption key.
3. AES-GCM decrypt body. IV = twelve `0x01` bytes (fixed). AAD = `b"\x00\x73"`.
