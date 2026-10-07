# Public signing key

`neutrodyne-public.keystore` signs every Android build of Neutrodyne: the published release APKs and local debug builds alike ([D61](../docs/PLAN.md#3-key-decisions), [09 Committed keystore](../docs/design/09-quality-and-release.md#committed-keystore)).

- **It is public on purpose.** Store and key password are `neutrodyne`, alias `neutrodyne`. There is no private release key, no key ceremony and no backup.
- **The signature proves nothing about who built an APK.** Anyone can sign an APK with this key that installs over Neutrodyne as an update. Download Neutrodyne only from the project's [GitHub release page](https://github.com/L-K-M/Neutrodyne/releases).
- **Forks must change the application ID or the key**, so their builds never install over Neutrodyne or the other way round.

The keystore was created once with JDK 21:

```
keytool -genkeypair -v -storetype PKCS12 -keystore signing/neutrodyne-public.keystore -storepass neutrodyne -keypass neutrodyne -alias neutrodyne -keyalg RSA -keysize 4096 -validity 12000 -dname "CN=Neutrodyne (public key), O=Neutrodyne"
```
