# graalvm-windows-arm64

> [!WARNING]
> **Experimental.** A feasibility study: nothing here is usable yet.

GraalVM CE native images for Windows on ARM64, which GraalVM CE doesn't ship. The goal, as
[graalvm-mobile](https://github.com/NucleusFramework/graalvm-mobile) does for iOS and Android: a kit per GraalVM CE
release, built by the CI, so that GraalVM CE's own native-image can build and link images for `windows-aarch64`.

The findings are in [FINDINGS.md](FINDINGS.md).
