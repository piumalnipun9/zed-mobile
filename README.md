# Zed Mobile (Android)

Zed Mobile is a focused Android companion for the local `Zed-Desktop` fork. On a private Tailscale network it lets you choose an open project, continue a native Zed Agent thread, send prompts, see retained agent activity, make one-time tool decisions, adjust a thread's model/thinking configuration, and review recorded diffs.

It intentionally does **not** expose a general filesystem, terminal, shell, or remote desktop interface.

## Pairing and security

- Pairing is QR-first. In Zed-Desktop open **Settings → Mobile Gateway → Connect mobile device**, then scan the time-limited QR code from this app.
- The QR contains a short-lived, one-time pairing ticket—not a long-lived gateway credential or legacy shared token.
- After a successful claim, Android stores the device credential as AES-GCM ciphertext in DataStore. The key is non-exportable and held in Android Keystore.
- Zed-Desktop persists only the device ID, label, timestamp, and SHA-256 credential hash. It lists paired devices under **Settings → Mobile Gateway**, where removing a device immediately revokes access.
- The app prevents screenshots with `FLAG_SECURE` and disables Android backups. Credentials are never added to `SavedStateHandle`, logs, or UI state.
- HTTPS uses Android certificate validation. Direct HTTP is accepted only for loopback, a literal Tailscale IPv4 address (`100.64.0.0/10`), or debug-only emulator `10.0.2.2`. Hostnames, LAN/public HTTP addresses, URL paths, URL credentials, queries, fragments, redirects, and non-default HTTP encodings are rejected.
- Google Code Scanner runs in Google Play services, so the app intentionally declares **no** `CAMERA` permission.

Pairing persists across app restarts, but the app has no permanent socket or background notification channel in this milestone. It polls only while the Agent chat is visible.

## Desktop setup

1. Build/run the `Zed-Desktop` fork from `../zed-desktop` and open the local project(s) you intend to use.
2. Configure an authenticated built-in Agent model/provider.
3. Put the desktop and Android device on the same Tailscale tailnet.
4. In **Settings → Mobile Gateway**, configure:
   - **Bind Address** as the desktop's Tailscale IP and port, for example `100.99.221.90:7331`;
   - **Enabled**. Every currently open local project becomes available to paired phones, and phone-based one-time Agent approvals are always enabled.
   - **Enabled**.
5. Choose **Connect mobile device**, open Zed Mobile, and scan the QR code.

The gateway must never be made public through a proxy, port forwarding, or reverse tunnel. The project allowlist is an API-access boundary—not a sandbox for Agent tools—so review tool permissions, model data sharing, and potential provider costs.

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

For a real-device test, ensure the phone and desktop are connected to the same tailnet before scanning the desktop QR code. There is no background notification/push flow yet, so leave the app's Agent chat visible while expecting a tool approval.
