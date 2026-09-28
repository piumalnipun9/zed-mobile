# Zed Mobile (Android)

Zed Mobile is a focused Android companion for the local `Zed-Desktop` fork. On a private Tailscale network it lets you choose an open or recently used local project, continue a native Zed Agent thread, send prompts, see retained agent activity, make one-time tool decisions, adjust a thread's model/thinking configuration, review recorded diffs, and receive direct live task-completion notifications.

It intentionally does **not** expose a general filesystem, terminal, shell, or remote desktop interface.

## Pairing and security

- Pairing is QR-first. In Zed-Desktop open **Settings → Mobile Gateway → Connect mobile device**, then scan the time-limited QR code from this app.
- The QR contains a short-lived, one-time pairing ticket—not a long-lived gateway credential or legacy shared token.
- After a successful claim, Android stores the device credential as AES-GCM ciphertext in DataStore. The key is non-exportable and held in Android Keystore.
- Zed-Desktop persists only the device ID, label, timestamp, and SHA-256 credential hash. It lists paired devices under **Settings → Mobile Gateway**, where removing a device immediately revokes access.
- The app prevents screenshots with `FLAG_SECURE` and disables Android backups. Credentials are never added to `SavedStateHandle`, logs, or UI state.
- HTTPS uses Android certificate validation. Direct HTTP is accepted only for loopback, a literal Tailscale IPv4 address (`100.64.0.0/10`), or debug-only emulator `10.0.2.2`. Hostnames, LAN/public HTTP addresses, URL paths, URL credentials, queries, fragments, redirects, and non-default HTTP encodings are rejected.
- Google Code Scanner runs in Google Play services, so the app intentionally declares **no** `CAMERA` permission.

For v2 QR pairings, **Stay connected for Agent notifications** is enabled by default. While it is enabled, Android runs a low-priority foreground service that holds a direct authenticated SSE connection to the paired desktop and posts generic Agent completion notifications. Android 13+ asks for notification permission when the connection is enabled. SSE events are ephemeral: the app neither saves event IDs nor asks the desktop to replay anything missed while the phone was offline.

The latest authenticated v2 project catalog is cached locally only to make launch responsive. It is bound to the saved desktop origin and device ID, never grants access, and is discarded when that credential binding is removed or changes. If the desktop is unavailable, the app labels the catalog as last-known data and disables project actions until a fresh desktop request succeeds.

## Desktop setup

1. Build/run the `Zed-Desktop` fork from `../zed-desktop` and open the local project(s) you intend to use.
2. Configure an authenticated built-in Agent model/provider.
3. Put the desktop and Android device on the same Tailscale tailnet.
4. In **Settings → Mobile Gateway**, configure:
   - **Bind Address** as the desktop's Tailscale IP and port, for example `100.99.221.90:7331`;
   - **Enabled**. Every currently open local project becomes available to paired phones, recently used local projects can be reopened through the catalog, and phone-based one-time Agent approvals are always enabled.
5. Choose **Connect mobile device**, open Zed Mobile, and scan the QR code.

The gateway must never be made public through a proxy, port forwarding, or reverse tunnel. The desktop-maintained project catalog is an API-access boundary—not a sandbox for Agent tools—so review tool permissions, model data sharing, and potential provider costs.

## Android flow

```text
Scan desktop QR → Projects → Threads → Agent chat → Changes
```

The warm Material 3 UI is optimized for phone-sized screens. The chat renders user/assistant messages, collapsible thinking blocks, tool output, plans, compaction information, pending approval cards, and a model/thinking configuration sheet. Diff review is read-only.

A phone decision always uses the desktop's current server-side one-time Allow/Deny option. It cannot create permanent permissions, alter command input, or approve a stale tool call. The QR ticket expires after two minutes, but the successfully paired device credential persists until you remove the device in Zed-Desktop settings or forget the pairing on the phone.

## Build and test

Open `zed-mobile` in Android Studio with Android SDK Platform 35 installed, or run from a terminal with a configured Android SDK:

```sh
./gradlew test assembleDebug
```

The debug APK is created at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install it with Android Studio, or with ADB:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For a real-device test, ensure the phone and desktop are connected to the same tailnet before scanning the desktop QR code. Keep **Stay connected for Agent notifications** enabled in Settings, start or open a native Agent thread from Zed Mobile, background the app, then let that Agent turn finish to verify the generic completion notification. Events are intentionally dropped if the stream is offline; reopening the app later still lets you fetch the completed thread normally.
