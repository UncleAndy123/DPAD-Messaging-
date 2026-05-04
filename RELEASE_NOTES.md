DPAD SMS Release v0.0.3
=======================

Summary
- Bug fixes for archive view and MMS sending.

Notable changes since v0.0.2
- Fixed archive view: now shows only archived conversations (previously showed all threads mixed together)
- MMS send errors now surface as a snackbar instead of silently failing
- After MMS send, a follow-up reload is scheduled so the sent bubble resolves correctly
- Fixed inbox: non-archived threads no longer appear in archive view and vice versa

How to install
- Release APK (signed): dpadsms.apk
- Install with: adb install -r dpadsms.apk

Notes
- MMS delivery still depends on carrier APN configuration. If you see a "Failed to send" snackbar, share the error text for further diagnosis.
- Room DB version 2; upgrades from v0.0.1 and v0.0.2 are supported.
