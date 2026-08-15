# IP Rerouter

Root Android utility for creating virtual interfaces and applying interface-to-interface policy routing.

## Fixed in this revision

- Live interface discovery is based on both `ip -j link` and `ip -j addr`.
- Interfaces such as hotspot `wlan1` and root-created `tun1` are treated as real kernel interfaces and can be selected as route endpoints.
- The interface list refreshes automatically every 2 seconds while the app is open.
- A manual refresh button is also available.
- Route creation performs one final live-interface check before touching routing state.
- The UI uses higher-contrast text and explicit Material 3 surface/text colors.
- Dialogs show interface name, kind, IPv4 address, and UP/DOWN state.
- Release builds are wired to an explicit signing configuration instead of silently producing an unsigned release APK.
- GitHub Actions can sign release builds using repository secrets.

## tun2socks sessions

- The app bundles the [xjasonlyu/tun2socks](https://github.com/xjasonlyu/tun2socks) binary (gVisor-based, MIT licensed) as `libtun2socks.so` under `jniLibs/{arm64-v8a,armeabi-v7a}` so AGP extracts it to a real, execute-permitted file on disk at install time.
- A tun2socks session reads raw IP packets off an app-created TUN interface and forwards each TCP/UDP flow through a SOCKS5/HTTP/Shadowsocks proxy, run as a root-owned detached process (`setsid nohup ... &`) so it keeps running independently of this app's process.
- The real egress interface tun2socks dials the proxy through is detected live (same `detectRealRoutingTable()` used elsewhere), never hardcoded.
- Session state (proxy address, protocol, MTU, etc.) persists across app restarts; PID files under the app's private files dir track whether a session is actually still running.

## Release signing

For local builds, copy `signing.properties.example` to `signing.properties` and point it at your release keystore.

For GitHub Actions, configure these repository secrets:

- `RELEASE_KEYSTORE_BASE64`
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

The workflow decodes the keystore only on non-PR builds and runs `assembleRelease`.

Do not commit a release keystore or `signing.properties`.

## Build

```text
./gradlew assembleDebug
./gradlew assembleRelease
```

The release build intentionally requires valid signing properties. This prevents a CI job from claiming to have produced a signed release when it actually produced an unsigned APK.
