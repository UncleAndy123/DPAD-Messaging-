DPAD Messaging
===============

[![Buy Me A Coffee](https://img.shields.io/badge/Buy%20me%20a%20coffee-Donate-yellow?logo=buy-me-a-coffee)](https://buymeacoffee.com/jbriones95)

A D-pad friendly SMS/MMS messaging app built with Kotlin + Jetpack Compose. Designed for low-RAM devices and navigation via D-pad (TV or hardware keypad).

Key features
- D-pad-first navigation with clear focus highlights
- SMS and MMS support (group messaging, image attachments)
- Compact UI optimized for small viewports
- Thread metadata (pin, archive, mute, block) persisted locally
- Configurable theme and accent color

Screenshots
- screenshots/conversations.png — Main conversation list
- screenshots/3dot_focus.png — D-pad focus on the three-dot menu
- screenshots/options_dialog.png — Thread options dialog
- screenshots/chat.png — Chat view with compact input bar
- screenshots/newmessage.png — New message screen
- screenshots/settings.png — Settings / theme and accent

Getting started
1. Build: ./gradlew :app:assembleDebug
2. Install: adb install -r app/build/outputs/apk/debug/app-debug.apk
3. Grant runtime permissions when the app launches and set as default SMS app when prompted.

Notes before publishing
- This repo intentionally does not include keystore credentials. Create a root-level keystore.properties for release signing if needed. See app/build.gradle.kts for the signing config.
- Ensure you understand carrier and platform constraints when using Telephony providers (MMS delivery may vary by carrier).

Support
If you find this project useful, you can support ongoing development at:

https://buymeacoffee.com/jbriones95

License
MIT (add your preferred license file)
