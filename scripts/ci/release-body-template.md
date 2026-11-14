{% if changelog %}{{changelog}}

{% endif %}Android — pick the APK for your device:
  neutrodyne-{{v}}-arm64-v8a.apk    most phones and tablets
  neutrodyne-{{v}}-x86_64.apk       x86_64 devices and emulators
  neutrodyne-{{v}}-armeabi-v7a.apk  older 32-bit phones (YouTube opens in the YouTube app)
These APKs are release builds signed with a key that is public in this repository
(signing/neutrodyne-public.keystore), so the signature does not show who built a file.
Download only from this page — any other copy is not Neutrodyne's.

{% if desktop %}Desktop — pick the file for your computer:
  Windows 10/11 x64, also Windows 11 on Arm:  neutrodyne-{{v}}-windows-x64.msi (or the portable .zip)
  Mac with Apple silicon, macOS 13 or later:  neutrodyne-{{v}}-macos-arm64.{{mac_ext}}
  Linux x64 or arm64:                         neutrodyne-{{v}}-linux-{x64|arm64}.{deb|rpm|tar.gz}
The desktop builds are not signed by a registered developer. macOS: after installing and after
every update, open System Settings › Privacy & Security and click "Open Anyway". Windows: choose
"More info", then "Run anyway"; Smart App Control must be off. Linux: install the .deb or .rpm,
or extract the tar.gz. They bundle an unmodified OpenJDK runtime (GPL-2.0 with the Classpath
Exception; source: openjdk-{{jdk}}-temurin-sources.tar.gz, RUNTIME-SOURCES.md).
{% if ffmpeg %}This software uses code of FFmpeg licensed under the LGPLv2.1 and its source can be
downloaded here: {{repoUrl}}/releases/download/{{tag}}/ffmpeg-{{ffmpeg}}-neutrodyne-src.tar.xz
{% endif %}{% if pbs %}The bundled CPython is python-build-standalone (MPL-2.0 build patches; source:
python-build-standalone-{{pbsTag}}-src.tar.gz).
{% endif %}{% if wix %}The MSI embeds unmodified WiX components (MS-RL; source: wix-{{wix}}-src.tar.gz).
{% endif %}{% endif %}{% if server %}Sync server (optional, self-hosted): neutrodyne-server-{{v}}.jar for Java 21 or later, or the image
ghcr.io/{{registry_owner}}/neutrodyne-server:{{v}} (digest {{digest}}). Run it with
`java -jar neutrodyne-server-{{v}}.jar serve` or with docker compose; the guide below has the
configuration. The image bundles an unmodified OpenJDK runtime and Debian base packages; sources:
neutrodyne-server-image-sources-{{v}}.tar.xz.
{% endif %}
Download only from this page and check the file:
  sha256sum --check --ignore-missing SHA256SUMS        (macOS: shasum -a 256 <file>; Windows: Get-FileHash -Algorithm SHA256 <file>)
  gh attestation verify <file> -R {{owner}}/Neutrodyne --signer-workflow {{owner}}/Neutrodyne/.github/workflows/release.yml --source-ref refs/tags/{{tag}} --source-digest <commit-sha>
{% if server %}  gh attestation verify oci://ghcr.io/{{registry_owner}}/neutrodyne-server@{{digest}} -R {{owner}}/Neutrodyne
{% endif %}
{% if android %}Obtainium (Android): add https://github.com/{{owner}}/Neutrodyne with the APK filter
`neutrodyne-.*-arm64-v8a\.apk$` (or Obtainium's architecture filter) to update through Obtainium
instead of the in-app check.

{% endif %}Guides: {{repoUrl}}#install-and-update · {{repoUrl}}#install-on-windows-macos-or-linux · {{repoUrl}}#run-the-server
