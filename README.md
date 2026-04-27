# DPAD Messenger — Setup Guide

## Prerequisites

- Android Studio Hedgehog or newer
- A Firebase project

## Firebase Setup

1. Go to [Firebase Console](https://console.firebase.google.com/) and create a project.
2. Add an **Android app** with package name `com.dpad.messaging`.
3. Download `google-services.json` and place it at `app/google-services.json`.
4. Enable the following Firebase services:
   - **Authentication** → Email/Password
   - **Firestore Database** → Start in production mode
   - **Storage** → Default bucket
   - **Cloud Messaging** (no extra config needed)

## Firestore Security Rules

```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /users/{uid} {
      allow read: if request.auth != null;
      allow write: if request.auth.uid == uid;
    }
    match /conversations/{convId} {
      allow read, write: if request.auth.uid in resource.data.participantIds
                         || request.auth.uid in request.resource.data.participantIds;
      match /messages/{msgId} {
        allow read, write: if request.auth.uid in
          get(/databases/$(database)/documents/conversations/$(convId)).data.participantIds;
      }
    }
  }
}
```

## Firestore Indexes

Create a **composite index** on the `users` collection:
- Field: `displayName` (Ascending)

## Support

If you find this app helpful and want to support its development, you can buy me a coffee!

<a href="https://www.buymeacoffee.com/jbriones95" target="_blank"><img src="https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png" alt="Buy Me A Coffee" style="height: 60px !important;width: 217px !important;" ></a>

## Building

```bash
./gradlew assembleDebug
```

## D-pad Navigation Notes

- All list items are `focusable()` and respond to **D-pad Center / Enter** via `dpadClickable`.
- Focus automatically moves to the first item in each list when the screen loads.
- The chat input bar items (attach, text field, send) are all focusable and navigable with the D-pad left/right.
- Works on **Android TV** (remote D-pad) and **phone/tablet + gamepad**.

## Project Structure

```
app/src/main/java/com/dpad/messaging/
├── data/
│   ├── model/          Conversation, Message, User
│   └── firebase/       AuthRepository, MessagingRepository, UserRepository
├── navigation/         Screen, AppNavGraph
├── service/            DpadFirebaseMessagingService (FCM)
├── ui/
│   ├── auth/           LoginScreen, RegisterScreen, AuthViewModel
│   ├── chat/           ChatScreen, ChatViewModel
│   ├── components/     Avatar
│   ├── conversations/  ConversationsScreen, ConversationsViewModel
│   ├── search/         SearchScreen, SearchViewModel
│   └── theme/          Theme.kt
├── util/               DpadUtils (dpadClickable modifier)
└── MainActivity.kt
```
