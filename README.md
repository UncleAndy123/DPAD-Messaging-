# DPAD SMS

A messaging app designed for dumbphones with D-pad navigation.

## Features

- **SMS/MMS Support** - Send and receive text messages and multimedia messages
- **Group Messaging** - Support for group SMS and MMS conversations
- **D-Pad Navigation** - Optimized for devices with physical directional buttons
- **Dark/Light Theme** - System theme following or manual light/dark mode
- **Accent Colors** - Customizable accent color (Blue, Green, Orange, Rose)
- **Conversation Management** - Pin, archive, mute, and delete conversations
- **Recycle Bin** - Soft-delete messages with recovery option
- **Blocked Keywords** - Filter messages containing specific keywords
- **Delivery Reports** - Optional delivery status notifications

## Requirements

- Android 6.0+ (API 23+)
- Device with D-pad navigation support

## Installation

1. Download the latest release APK from the [Releases](https://github.com/jbriones95/DPAD-Messaging/releases) page
2. Transfer to your device
3. Enable "Install from unknown sources" in settings
4. Install the APK
5. Set as default SMS app

## Building from Source

### Prerequisites

- Android Studio (2023+)
- Gradle 8.5+
- Java 17

### Build Commands

```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease
```

The APK will be generated at `app/build/outputs/apk/debug/app-debug.apk` or `app/build/outputs/apk/release/app-release.apk`

## Project Structure

```
app/src/main/
├── kotlin/com/dpad/messaging/
│   ├── activities/       # Activity classes
│   ├── adapters/         # RecyclerView adapters
│   ├── databases/        # Room database
│   ├── helpers/          # Utility classes (ThemeManager, Prefs, MmsSender, etc.)
│   ├── models/           # Data models
│   ├── receivers/        # Broadcast receivers
│   └── services/         # Background services
└── res/
    ├── layout/           # XML layouts
    ├── values/           # Colors, strings, themes
    └── drawable/         # Icons and drawables
```

## Key Components

### ThemeManager
Handles theme mode (system/light/dark) and accent color selection.

### Prefs
SharedPreferences wrapper for app settings storage.

### MmsSender
Handles MMS message composition and sending via system MmsService.

### SmsSender
Handles SMS message sending via SmsManager.

## License

Proprietary - All rights reserved