# Permanent Android APK signing — required for seamless updates

GitHub Actions runners are disposable. Their default debug keystore changes across runs, so a new APK often cannot update an installed APK. Every delivered APK must retain the original applicationId, increase versionCode, and be signed with the **same private key and signing certificate**.

This workflow builds the existing Android variant, then re-signs the final APK with a retained app-specific keystore from encrypted GitHub Actions secrets. It checks the public SHA-256 fingerprint from an Actions variable before allowing the APK artifact to be published.


## Phone/tablet-only setup (no Windows computer)

If you do not have access to Windows, a GitHub Codespace opened in a mobile browser can run Java's `keytool`. Use a trusted Codespace that belongs to your own GitHub account and ensure Java is installed.

1. On GitHub in your browser, open **Code → Codespaces → Create codespace** for your repository. Open its terminal.
2. If you already possess the original private signing keystore, reuse it. Do **not** generate a new one. If you only have an installed APK, its public certificate cannot recreate that key.
3. For a brand-new key only when a controlled first-time migration is acceptable, run:
   ```bash
   keytool -genkeypair -keystore "$HOME/LabelSheet-signing.jks" -storetype PKCS12 -alias app -keyalg RSA -keysize 3072 -validity 10950 -dname "CN=LabelSheet, O=Personal Apps, C=TT"
   ```
   Enter a strong password when prompted. Do not put passwords on the command line.
4. In GitHub repository **Settings → Secrets and variables → Actions**, create secret `ANDROID_SIGNING_KEYSTORE_B64` using the *one-line* output of:
   ```bash
   base64 -w 0 "$HOME/LabelSheet-signing.jks"
   ```
   Treat that Base64 output as a private secret; do not paste it in an issue, commit, or chat message.
5. Create secret `ANDROID_SIGNING_STORE_PASSWORD` from the password you set.
6. Get the public fingerprint using `keytool -list -v -keystore "$HOME/LabelSheet-signing.jks" -alias app` and create variable `ANDROID_SIGNING_CERT_SHA256` using the SHA-256 certificate fingerprint.
7. Save a secure, private backup of your `.jks` file and its password outside that temporary Codespace. Never delete the sole usable private key. Clear terminal scrollback and any temporary exported Base64 files.

The 1.8 workflow is at `.github/workflows/verify-redesign.yml`. Its compile step can pass without signing secrets, but its release steps fail closed until the three items are set. Compare the current installed app's certificate fingerprint against the private key before expecting an in-place update.

## One-time setup in this repository

Go to **Settings > Secrets and variables > Actions**.

If the private keystore used to sign the installed app exists, REUSE that key. If the key alias is not `app`, change the alias without changing the key/certificate (e.g. `keytool -changealias`). If its old signing key is lost, a new key cannot update that original installed APK: first back up app data and plan one controlled migration. The APK and its public certificate are insufficient to reconstruct the private key.

If no old key can be recovered, create a **new and then permanent** key on your trusted Windows computer with Java JDK keytool:
```powershell
keytool -genkeypair -keystore .\LabelSheet-signing.jks -storetype PKCS12 -alias app -keyalg RSA -keysize 3072 -validity 10950 -dname "CN=LabelSheet, O=Personal Apps, C=TT"
```
Use a strong unique password; a PKCS12 private key uses the same password as its keystore.

Configure all three items in GitHub:
1. **Secret** `ANDROID_SIGNING_KEYSTORE_B64`: one-line Base64 of that original binary keystore file, generated with PowerShell:
   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path '.\LabelSheet-signing.jks')))
   ```
2. **Secret** `ANDROID_SIGNING_STORE_PASSWORD`: the keystore password.
3. **Variable** `ANDROID_SIGNING_CERT_SHA256`: the SHA-256 fingerprint for alias `app`, shown by:
   ```powershell
   keytool -list -v -keystore .\LabelSheet-signing.jks -alias app
   ```

Back up the keystore securely in two locations you control and retain its password in a password manager. Do not commit or send a private signing key/password in chat. Encrypted GitHub secrets are **not readable back** by the assistant; GitHub makes them available to the workflow for each new build. The certificate fingerprint, unlike the private key, is public and can be retrieved again from any signed APK.

Until setup is complete, the workflow will **intentionally fail** instead of delivering another APK with a different certificate. Do not mistake a successful Gradle compilation for a deliverable signed update.

For every future release: preserve the package ID and user data, increment versionCode, reuse the exact signing key, and test an install-over-existing-app path with a data backup.


Repository: helpsomeoneonline-hub/LabelSheet. Compare any currently installed APK certificate to the retained key before claiming seamless upgrades.
