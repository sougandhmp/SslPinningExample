# SSL Pinning & Key Rotation Sample (Android)

A minimal Kotlin app and a local HTTPS server for testing public-key (SPKI) pinning and key rotation.
It tests both common ways to pin:

| Layer                          | Where                                 | Pins                                                                  |
|--------------------------------|---------------------------------------|-----------------------------------------------------------------------|
| **OkHttp `CertificatePinner`** | `MainActivity.kt`                     | Picked at runtime from a dropdown (A+B, A only, B only, C only, none) |
| **Network Security Config**    | `res/xml/network_security_config.xml` | A + B, with an `expiration` date                                      |

The NSC pins are enforced by the platform `TrustManager`, so they apply to **both** OkHttp and `HttpsURLConnection`.

## Keys

`scripts/gen_certs.sh` creates one local test CA and three server keys, each with a CA-signed cert:

- **A**: the current key (pinned)
- **B**: the backup or next key (pinned). Rotating means switching the server to this key.
- **C**: a "rogue" key. Its cert is valid and signed by the trusted CA, but its key is **not** pinned. This simulates a mis-issued or MITM certificate.

The project ships with keys already generated for the emulator (`10.0.2.2`).

## Run it

```bash
# 1. (Optional) regenerate. For a physical device, pass your computer's LAN IP:
./scripts/gen_certs.sh            # emulator, uses 10.0.2.2
./scripts/gen_certs.sh 192.168.1.20

# 2. Start the server with the current key
python3 scripts/server.py --key a
```

1. Open the project in Android Studio (Ladybug or newer) and let it sync. It downloads the Gradle wrapper.
2. Run the app on an emulator (API 24 or newer) and tap the test buttons.
3. To rotate, stop the server (Ctrl+C) and restart it with `--key b` or `--key c`.

> Rebuild the app whenever you rerun `gen_certs.sh`. It rewrites `pins.properties`, the NSC XML and `res/raw/test_ca.pem`.

## Test matrix

| Server key | OkHttp pin set  | Expected                          | What it proves                                                                             |
|------------|-----------------|-----------------------------------|--------------------------------------------------------------------------------------------|
| A          | A + B           | ✅ pass                           | Normal operation                                                                           |
| **B**      | **A + B**       | ✅ pass                           | **Rotation works because the backup pin shipped ahead of time**                            |
| B          | A only          | ❌ `Certificate pinning failure!` | An app without a backup pin breaks when the server rotates                                 |
| A          | B only          | ❌ fail                           | A post-rotation build that dropped the old pin too early breaks against servers still on A |
| C          | any             | ❌ fail                           | A trusted-but-unpinned cert (MITM/mis-issuance) is blocked                                 |
| C          | None (NSC only) | ❌ `Pin verification failed`      | NSC catches it even without OkHttp pins                                                    |
| B          | None (NSC only) | ✅ pass                           | NSC backup pin works                                                                       |

The log shows which layer rejected a connection:
- `SSLPeerUnverifiedException: Certificate pinning failure!` means OkHttp `CertificatePinner` rejected it. The message lists the server's actual pins.
- `SSLHandshakeException … Pin verification failed` means the NSC `pin-set` rejected it.
- `Trust anchor for certification path not found` means the chain isn't trusted at all.

On success, the OkHttp test also prints the server leaf's pin, which helps with debugging rotations.

## Checking from the command line

```bash
curl --cacert scripts/certs/ca.pem \
     --pinnedpubkey "sha256//<PIN_A>;sha256//<PIN_B>" \
     https://127.0.0.1:8443/hello     # exit code 90 = pin mismatch
```

## Rotation playbook (what this sample models)

1. **Ship backup pins early.** Every release pins the current key (A) and at least one backup key (B). Generate B ahead of time and keep it offline.
2. **Rotate the server** to B. Existing installations keep working because B is already pinned.
3. **Ship a release that pins B plus a new backup C′.** Keep A until you're sure no server still uses it.
4. **Retire A** once old app versions have aged out.
5. **Use an NSC `expiration`** so very old, unupdated installs fail open rather than becoming permanently bricked.

Pinning an intermediate or root CA key is an alternative to pinning the leaf key. It's less brittle, but it gives weaker guarantees.
