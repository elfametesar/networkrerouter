# IP Rerouter

A rooted Android utility for managing network interfaces directly: list every
interface on the device (`wlan0`, `rmnet0`, `tun0`, ...), create and remove
virtual interfaces, route traffic from one interface out through another,
exclude specific apps from a route, and reset everything back to system
default in one action.

This requires root (Magisk, KernelSU, or equivalent). It works by shelling
out to `ip` (iproute2) and `iptables` — there is no non-root fallback, by
design, since per-interface routing tables and arbitrary tun/dummy interface
creation aren't available to unprivileged apps on Android.

## How it works

- **Interface listing** — `ip -j addr show` for structured interface data,
  cross-referenced with `/proc/net/dev` for RX/TX byte counters.
- **Interface creation** — `ip tuntap add` for tun interfaces, `ip link add
  type dummy` for dummy/stub interfaces. Only app-created interfaces can be
  removed from the UI; system interfaces are listed but protected.
- **Routing** — each rule gets its own routing table (Linux's user-defined
  range, 100–252) and fwmark. Packets from the source interface are marked
  in an `iptables` mangle chain, then routed via `ip rule` + `ip route` into
  the target interface's table. Optional MASQUERADE handles NAT on the way
  out.
- **App exclusion** — implemented via `iptables -m owner --uid-owner <uid>`,
  which `RETURN`s matching traffic before it's marked, so excluded apps fall
  through to normal system routing untouched.
- **Reset all** — tears down every rule and app-created interface the app
  knows about (tracked in local JSON state), plus a defensive sweep for any
  leftover `IPRR_*` iptables chains in case state was lost.

## Project layout

```
app/src/main/kotlin/net/ip/rerouter/
  root/       RootShell.kt          libsu wrapper — all privileged commands go through here
  model/      Models.kt             NetInterface, RouteRule, AppInfo, AppState
  net/        InterfaceRepository   list/create/remove interfaces
              RoutingEngine         apply/remove routing rules, reset all
              AppRepository         installed app listing (exclusion picker)
              StateStore            JSON persistence of app-created state
  ui/         MainViewModel         orchestration + UI state
              MainScreen            screen composition
              components/           InterfaceCard, RuleCard, dialogs
              theme/                colors, type
```

## Building locally

Requires Android Studio (or the command-line SDK) with SDK 35 and JDK 17.

This repo does not commit `gradlew` / `gradle-wrapper.jar` (binary artifacts
generated in an environment without network access to Gradle's distribution
servers). Generate them once with a local Gradle install before building:

```
gradle wrapper --gradle-version 8.9
./gradlew assembleDebug
```

Opening the project in Android Studio will also offer to regenerate the
wrapper automatically. CI regenerates it on every run, so this only matters
for local builds.

The debug build installs alongside the release build (`.debug` app-id
suffix) so both can be on-device at once.

## CI

`.github/workflows/build.yml` builds both debug and release APKs on every
push/PR, uploads them as a workflow artifact, and — on pushes to
`main`/`master`, or a manual run with the `publish_release` input checked —
tags the commit and publishes a GitHub Release with the APKs attached.

**Signing (optional):** add these repo secrets to get a signed release APK;
without them, CI still builds and publishes an unsigned APK (marked as a
pre-release):

- `RELEASE_KEYSTORE_BASE64` — your keystore file, base64-encoded
  (`base64 -w0 your.keystore`)
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

## Status

Scaffolded and manually reviewed, but not yet compiled — this repo's own CI
run will be the first real build. Expect to iterate on the first couple of
Actions runs.
