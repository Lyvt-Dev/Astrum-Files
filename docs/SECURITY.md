# Security notes and threat model

## Vault design

| Item | Protection |
|---|---|
| File contents | AES-256-GCM in 64 KiB chunks. Each file has a random 64-bit nonce prefix and a 32-bit chunk counter, so nonces are unique per key. The AAD binds the chunk index and a "final chunk" flag, so reordering, truncation and tampering are detected (see unit tests). |
| Master key (MK) | 256-bit random key. It is never stored in plaintext. |
| PIN wrap | `GCM(PBKDF2-HMAC-SHA256(PIN, salt, 310 000 iterations), MK)`, then sealed again with a non-exportable Android Keystore AES key. Copying the vault folder to another device is therefore not enough to brute-force the PIN offline. |
| Recovery wrap | `GCM(PBKDF2(recoveryCode, salt2), MK)`. The 24-character code (about 120 bits) is shown once and is the only way to reset the PIN or restore a backup. |
| Biometric wrap | MK sealed by a Keystore key with `setUserAuthenticationRequired(true)` that is invalidated when new biometrics are enrolled. It is unlocked through `BiometricPrompt.CryptoObject`. |
| Index, names, thumbnails | Encrypted with MK (AES-GCM, with AAD bound to the purpose or id). |
| Location | `filesDir/vault` (app-private, excluded from cloud backup and device transfer). |
| Brute force | Exponential back-off after 5 failed PIN attempts. |
| Auto-lock | Configurable timeout after the app goes to the background. Locking clears the in-memory key reference and wipes decrypted preview files. |

### Known limitations (stated plainly)

- **Viewing** decrypts a file into the app-private cache (`cacheDir/vault-view`), never to public storage. It is
  deleted on lock, but it exists in plaintext on disk while the vault is unlocked.
- **Deleting originals** after import uses normal file deletion. On flash storage (wear levelling, TRIM) Lyvt
  **cannot guarantee** that deleted data is unrecoverable. The UI says so.
- JVM `SecretKeySpec` objects cannot be reliably zeroed, so key material may stay in RAM until garbage collection.
- Root access, a compromised OS, or malware with accessibility control can capture the PIN or plaintext.
- **If you lose both the PIN and the recovery code, the data cannot be recovered.** This is intentional and there is
  no back door.
- Uninstalling the app deletes the Keystore keys and therefore the device-bound PIN wrap. An encrypted backup plus
  the recovery code is the only way to move a vault to another device.

## File operations

- Nothing is overwritten silently. Copy, move and extract either create "name (1)", skip, or replace only after an
  explicit choice.
- Copies are written to `*.lyvtpart` and renamed atomically when complete. Cancelled or crashed copies leave no
  truncated file under the real name.
- Text-editor saves write a temporary file and rename it over the original.
- Deletion goes to the app trash by default. Deleting from the trash is permanent and always asks for confirmation.

## Archives

- Entry paths are canonicalised, and anything that resolves outside the destination (absolute paths, `..`) is
  rejected (ZIP Slip).
- There are limits on total extracted bytes (20 GiB), entry count (200 000) and the per-entry compression ratio
  (1000:1).
- Symlinks and hard links inside TAR archives are skipped.
- Restoring a vault backup only accepts an allow-list of entry names.

## Privacy

- Network access is only used by features the user starts (servers, transfer). There is no telemetry.
- No ads, analytics, crash reporters or third-party SDKs that phone home.
- `allowBackup=false` and data-extraction rules exclude all app data from cloud backup and device transfer.
- Package visibility is limited to launcher apps through `<queries>`. `QUERY_ALL_PACKAGES` is not requested.

## Decoy vault

The decoy is a fully separate space (`filesDir/vault-decoy`) with its own random master key, salts and Keystore alias.
Unlocking first tries the real PIN, then the decoy PIN, so both paths take the same PBKDF2 work. The decoy holds no
key material of the real vault. Limitation: the decoy hides content, not existence. A forensic look at app storage
shows that two vault folders exist.

## Network

- Passwords are encrypted with a non-exportable Keystore AES-256-GCM key (`astrum_network_credentials`).
- **SFTP:** the host key fingerprint is shown on first connect and pinned only after you approve it. A changed key is
  refused as a possible MITM.
- **WebDAV over HTTPS** uses the system trust store. Certificate errors are reported and never bypassed.
- **FTP** is plaintext and labelled insecure in the UI.
- **SMB:** smbj with default (SMB2/3) dialect negotiation.

## Astrum Transfer

- A local HTTP server on a random port bound to the LAN address. A 128-bit random token in the URL/QR code is
  required for every request.
- Sessions expire after 15 minutes. Repeated wrong-token requests from an address are blocked.
- Every upload must be accepted on the phone (2-minute timeout means decline). Uploads never overwrite and are written
  via `.lyvtpart` files.
- **Limitation:** traffic is plain HTTP, so it is not encrypted on the wire. Anyone sniffing the same Wi-Fi could read
  transferred files and the token. Use it on trusted networks. TLS with certificate pinning through the QR code is
  planned.
