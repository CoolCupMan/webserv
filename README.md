# WebServ — Android app for device & server web GUIs

WebServ opens the web management interface of routers, access points, firewalls, switches,
hypervisors and server tools (Proxmox, ESXi, Grafana, mongo-express, Portainer, Cockpit, …)
by typing an **IP address or DNS name**. It is a WebView tuned for admin interfaces, which
often trip up regular mobile browsers.

## Features

- **Type anything**: `192.168.1.1`, `router.lan`, `nas:5001`, `fe80::1`, `[fe80::1]:8006`,
  or a full `http(s)://` URL. Scheme can be forced (HTTPS/HTTP) or left on **Auto**.
- **Auto scheme with fallback**: Auto picks HTTPS (or HTTP for typical HTTP ports such as 80,
  3000, 8080, 8081) and falls back to HTTP once if HTTPS does not answer.
- **Self-signed certificates**: shows subject, issuer, validity and SHA-256 fingerprint and lets
  you accept *this time* or *always*. "Always" pins that exact certificate for that host:port;
  a changed certificate prompts again. User-installed CAs are trusted too.
- **HTTP Basic/Digest login** dialog with optional saving; passwords are encrypted with an
  AES key held in the Android Keystore.
- **Port scan** of ~28 well-known management ports (443, 80, 8443, 8080, 8006 Proxmox,
  3000 Grafana, 8081 mongo-express, 9443 Portainer, 9090 Cockpit, 5000/5001 Synology,
  10443 FortiGate, 4444 Sophos, …), detecting HTTP vs HTTPS per port.
- **Gateway** button fills in the current network's default gateway (usually the router).
- **Desktop site** toggle (remembered per host) for UIs that break with a mobile user agent.
- **Uploads** (firmware, config restore, certificates) through the system file picker.
- **Downloads** (config backups, exports) saved to `Downloads/WebServ`, including `blob:` and
  `data:` downloads, using the session cookies and your pinned certificates.
- Pop-ups (`window.open`) stay in the same session; renderer crashes reload instead of killing the app.
- Favourites and recent hosts; long-press to rename, favourite, edit or delete.

> MongoDB itself has no web GUI — point WebServ at a web front end such as **mongo-express**
> (default port 8081).

## App ID, version and parallel installation

| Build | Application ID | Label |
|---|---|---|
| release | `com.coolcupman.webserv` | WebServ |
| debug | `com.coolcupman.webserv.debug` | WebServ Debug |
| extra copy | `com.coolcupman.webserv<suffix>` (e.g. `.lab`) | WebServ (lab) |

Release and debug install side by side. Any number of further copies can be built with
`-PappIdSuffix=.name`, each with its own cookies, saved hosts, logins and trusted certificates
(handy for keeping e.g. home and work/lab environments separate):

```sh
./gradlew assembleRelease -PappIdSuffix=.lab
```

The version lives in [`version.properties`](version.properties).
`versionCode = MAJOR*10000 + MINOR*100 + PATCH`; CI appends the run number to the version name
(`1.0.0+57`). Version and app ID are shown at the bottom of the start screen and in **About**.

APK names include all of it: `webserv-<appId>-<version>-<buildType>.apk`.

## Getting the APK

Every push builds debug and release APKs in GitHub Actions
(**Actions → Build APK → Artifacts**). Pushing a tag `v*` (e.g. `v1.0.0`) also publishes them on a
GitHub Release. Run the workflow manually to build a copy with a custom app ID suffix.

Install: copy the APK to the phone and open it (allow "install unknown apps" for your file
manager/browser), or `adb install webserv-....apk`.

### Stable signing (recommended)

Without secrets the release APK is signed with the CI runner's throwaway debug key, so each build
has a different signature and updating requires uninstalling first. For in-place updates, add these
repository secrets:

| Secret | Value |
|---|---|
| `WEBSERV_KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `WEBSERV_KEYSTORE_PASSWORD` | keystore password |
| `WEBSERV_KEY_ALIAS` | key alias |
| `WEBSERV_KEY_PASSWORD` | key password |

Create a keystore with
`keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias webserv`.

## Building locally

Requires JDK 17+ and the Android SDK (platform 35). Then:

```sh
./gradlew assembleDebug assembleRelease
```

Minimum Android version: 8.0 (API 26). Target: Android 15 (API 35).
