DPAD SMS Release v0.0.2
=======================

Summary
- D-pad usability improvements, draft persistence, archive UX polish, and working font size scaling.

Notable changes since v0.0.1
- Font size scale setting now actually applied: Small (0.85×) / Normal / Large (1.15×) / Extra Large (1.3×) — replaces the broken TV-style slider
- Draft persistence: unsent message drafts saved per-conversation, shown in thread list
- Archive UX: archive/unarchive from conversation list with D-pad-accessible undo snackbar
- D-pad usability overhaul: FAB reachable via D-pad down, send button gets initial focus (no more keyboard blocking screen), three-dot menu D-pad left/right navigation, MMS image height capped at 80dp
- Performance: batched unread count query, contact cache per load, spinner only on initial empty load
- Status bar fix: light theme uses surfaceVariant so bar is visually distinct from white background
- App renamed to DPAD SMS

How to install
- Release APK (signed): dpadsms.apk
- Install with: adb install -r dpadsms.apk

Notes
- Release APK is signed using keystore.properties if present in repo root; otherwise falls back to debug keystore.
- MMS delivery depends on carrier; test on real devices.
- Room DB is version 2; fresh installs and upgrades from v0.0.1 are both supported.
