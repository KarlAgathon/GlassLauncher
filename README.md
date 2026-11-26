# LEAD Launcher v10

An open-source launcher for Android TV and Google TV. The Modern Taupe & Charcoal theme pairs smoky glass cards with the built-in living-room wallpaper. Home starts with four shortcuts in a single row. Add more from **Apps → Add to Home** and use Left or Right to scroll and wrap.

| Remote button | Action |
| --- | --- |
| OK | Open the focused app or menu item |
| Hold OK | Open options for the focused Home shortcut |
| Left / Right | Move along the Home row or top navigation |
| Back | Close the current menu or return one level |
| Home | Return to the launcher Home when LEAD handles the system Home action |

The main sections are **Apps**, **Personalize**, **System**, **Diagnostics**, and **About**, with separate Power access. Manage Home labels under **Personalize → Home Layout**. Home shortcuts support custom pictures, names, and manual icon positioning.

**Apps → Files** opens File Manager with Internal Storage, USB Drive, Downloads, Network & Connect, Recycle Bin, and Recent Files. Phone Connect lives under **Network & Connect**. It uses unencrypted local HTTP with TV approval. Use it on a trusted network.

Create or restore launcher settings under **System → Backup & Restore**. Full backups also contain the APKs they successfully copied. Android handles installation and its confirmations. Application account data and passwords belong to the installed apps.

**Personalize → Screensaver** controls Ambient 2.0, including Minimal, Balanced, and Full Dashboard modes. **Diagnostics → Speed Test** runs manual route-specific tests. **About → Check for Updates** checks the installed version against the update channel.

## Build and test

Use JDK 17, Gradle 8.9, and Android SDK 34. Both APKs target API 34 and support API 24 and later.

```sh
gradle :app:assembleDebug :vpntest:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest --no-daemon
gradle :app:connectedDebugAndroidTest --no-daemon
```

The second command requires an attached Android device or emulator. For the VPN helper to communicate with the launcher, both APKs need the same signer. Stable APKs and update metadata are published through the repository's signed release workflow.

## Android TV regression checks

Use a test box or emulator with a disposable profile and files. Repeat navigation checks at 720p and 1080p, on Android 7 and a current Google TV version when available.

1. On a fresh install without permissions, check that Home opens directly. Use OK and hold OK on an installed app and a missing shortcut. Cover an unavailable Play Store and cancel an Android install prompt.
2. On an upgrade, verify custom wallpaper, logos, pins, and saved label settings. Toggle both Home label switches independently, restart, and recheck. Scroll far enough to recycle cards and inspect YouTube, Silo, Media Center, Disney+, and padded custom icons where available.
3. Exercise Home wrap-around, Back from category pages, menus and dialogs, Home return, and focus after canceling. Repeat using English, Bosnian, German, and Russian.
4. Verify the six File Manager tiles. Copy, move, replace, rename, recycle, restore, and permanently delete disposable items. Cancel a large copy and unplug a test USB drive during a move. Inspect both paths. Test the SAF picker and SMB, FTP, FTPS, and SFTP separately.
5. Restore valid and malformed backups. Check that a failed restore leaves the original pins, preferences, and artwork in place. On a disposable profile, repeat with low storage or a forced interruption.
6. In Phone Connect, approve and deny pairing, interrupt an upload, test a large upload, and leave the screen with the browser still open. Confirm that the connection closes.
7. Check low-memory and offline startup, VPN on and off, Ambient preview and wake, updater, notifications, and Diagnostics.

License: MIT. Icons are from Lucide (ISC). Inter is licensed under the SIL Open Font License.
