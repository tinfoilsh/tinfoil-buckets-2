# Local decrypt without running the sidecar

For operator-side tooling — viewing runs, auditing, migrating — you don't need to run Docker. A short Python helper (~50 lines) decrypts S3 objects directly, given the same master key the sidecar was started with.

**Scope:** decrypt only. Encrypt is more code & easier to mess up. Just use the sidecar for that.

## The helper

Drop this next to your script — single file, depends only on `cryptography` and `boto3`. Targets the v4 default suite (`ALG_AES_256_GCM_HKDF_SHA512_COMMIT_KEY`, id 0x0073). If the sidecar ever moves to a different suite, this has to follow.

```python
# tinfoil_crypto.py
import base64
import hmac

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

_SUITE_ID_BYTES = b"\x00\x73"            # content-AAD + first 2 bytes of HKDF info
_SUITE_ID_DEC = b"115"                   # key-wrap AAD (decimal int as UTF-8)
_DERIVE_KEY_INFO = _SUITE_ID_BYTES + b"DERIVEKEY"
_COMMIT_KEY_INFO = _SUITE_ID_BYTES + b"COMMITKEY"
_COMMIT_LEN = 28
_DK_LEN = 32
_FIXED_CONTENT_IV = b"\x01" * 12


def decrypt_object(master_key: bytes, metadata: dict, body: bytes) -> bytes:
    """Decrypt one S3 object body given the master key, boto3 metadata dict, and ciphertext."""
    edk = base64.b64decode(metadata["x-amz-3"])
    iv, edk_ct = edk[:12], edk[12:]
    pdk = AESGCM(master_key).decrypt(iv, edk_ct, _SUITE_ID_DEC)
    if len(pdk) != _DK_LEN:
        raise ValueError(f"unexpected data key length {len(pdk)}")

    message_id = base64.b64decode(metadata["x-amz-i"])
    stored_commitment = base64.b64decode(metadata["x-amz-d"])

    derived_commitment = HKDF(
        algorithm=hashes.SHA512(),
        length=_COMMIT_LEN,
        salt=message_id,
        info=_COMMIT_KEY_INFO,
    ).derive(pdk)
    if not hmac.compare_digest(derived_commitment, stored_commitment):
        raise ValueError("key commitment mismatch — wrong key or tampered object")

    cek = HKDF(
        algorithm=hashes.SHA512(),
        length=_DK_LEN,
        salt=message_id,
        info=_DERIVE_KEY_INFO,
    ).derive(pdk)

    return AESGCM(cek).decrypt(_FIXED_CONTENT_IV, body, _SUITE_ID_BYTES)
```

A working example that uses this against real S3 objects: [`view.py` in tinfoil-persistent-storage-example](https://github.com/tinfoilsh/tinfoil-persistent-storage-example/blob/main/view.py).

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
