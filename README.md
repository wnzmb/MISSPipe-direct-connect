# MISSPipe

## I miss you...

**An open-source Android app for comfortably browsing MISSAV, PornHub, KISSJAV, 85po, and more.**

This project is a **PipePipe-based fork** dedicated to **R18 video platforms**.
Its current focus is to enable **direct access to `missav.one` without a proxy/VPN**
by fixing DNS pollution, SNI blocking, and IP blacklisting.

> **Status:** This repository is currently a **work-in-progress / semi-finished prototype**.
> Building from scratch may fail because the required JDK/Android SDK environment,
> signing configs, and/or the `PipePipeExtractor` linkage are not fully resolved yet.
> Use at your own risk, and feel free to open issues/PRs to help stabilize it.

## Why this fork

The original MISSPipe focuses on R18 platform scraping and playback.
This direct-connect edition adds a network-layer strategy so users in restricted
networks can reach the site without a traditional proxy:

- **Custom DNS / DoH fallback** to bypass DNS pollution
- **Built-in IP table** for known-good Cloudflare / CDN endpoints
- **Domain rotation** across `missav.one / .ai / .ws`
- **Optional SNI manipulation** to defeat SNI-based DPI
- **Cloudflare challenge handling** via WebView fallback

## Features

* Optimized for R18 platforms (web scraping + PipePipe's powerful engine)
* Built-in ad blocking
* Background playback support
* Open source (GPL-3.0)
* Manage videos from multiple services in a single unified playlist
* **Direct-connect network layer** (DNS/DoH, IP rotation, SNI bypass, CF challenge)

## Project structure

```
MISSPipe-direct-connect/
├── PipePipeClient/      # Android application (UI, player, app logic, network layer)
└── PipePipeExtractor/   # NewPipe-style extractor library used by the client
```

## Implementation roadmap

### Phase 1 — Basic direct connect ✅ Completed
- `MissAvDns` custom DNS with hard-coded IP + DoH/system fallback
- `DownloaderImpl` wired to `MissAvDns`
- UA upgrade in `MissAvParsingHelper.browserHeaders()`
- Settings toggle `use_built_in_hosts` in `NewPipeSettings`

### Phase 2 — Stability ✅ Completed
- `MissAvIpProvider` (IP rotation + failure marking)
- `MissAvDomainManager` (multi-domain failover)
- Dynamic domain switching in `MissAvParsingHelper.localizeUrl()`
- User-defined IP input UI
- `MissAvFailoverInterceptor` (network interceptor for IO failure detection)
- `MissAvDirectConnectConfig` (settings sync at startup and on change)

### Phase 3 — SNI bypass ✅ Completed
- `MissAvSniConfig` (mode configuration: plain/replace/empty)
- `MissAvSniSocketFactory` (SNI replacement, default target: `missav.one`)
- `MissAvEmptySniSocketFactory` (empty SNI mode)
- SNI mode switching in `DownloaderImpl` with per-domain HostnameVerifier
- Settings UI (`sni_mode` ListPreference, requires app restart)

### Phase 4 — Cloudflare challenge handling ✅ Completed
- `MissAvCloudflareInterceptor` (detects 403 + `cf-mitigated: challenge`)
- `MissAvCloudflareActivity` (WebView-based challenge solver)
- `cf_clearance` cookie persistence and automatic retry

## Default domain

The default MissAV domain has been switched from `missav.ws` to `missav.one`
because `missav.ws` is DNS-polluted in restricted networks while `missav.one`
resolves normally to Cloudflare endpoints.

## Building

### Prerequisites

- JDK 21
- Android SDK with `compileSdk 37` and matching build-tools / platform-tools
- Gradle wrapper included in the repo

### Command line

```bash
cd PipePipeClient
./gradlew assembleDebug
```

Common failure: if `JAVA_HOME` is not set, the build stops with

```
ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
```

### Release signing

`assembleRelease` / `bundleRelease` require these environment variables:

- `KEY_PATH`
- `KEY_STORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

Without them the build intentionally fails to keep update signatures stable.

## Disclaimer

* This app is **unofficial**. Please use it responsibly and ensure your usage complies with the terms of service of the platforms you access.
* This application is intended for adults only. Users must be at least 18 years old.

## Credits

* [PipePipe](https://github.com/InfinityLoop1308/PipePipe) — the YouTube/NicoNico client this project is based on
* [unofficial-api-for-missav](https://github.com/EchterAlsFake/unofficial-api-for-missav) — MISSAV integration

## Contributing

Pull requests are always welcome!
