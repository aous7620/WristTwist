# WristTwist

WristTwist is a two module Android project for hand/wrist rotation gesture control across Wear OS and phone.

## Modules

1. `watch`
Wear OS app that detects wrist twist gestures and triggers configured actions.

2. `phone`
Companion app that manages behavior settings and executes media commands routed from the watch.

## Features

1. Gesture based action triggering on watch
2. Media control routing to phone playback sessions
3. Sync of behavior settings between phone and watch
4. Accessibility and notification listener based integration

## Build

```powershell
.\gradlew :watch:assembleDebug :phone:assembleDebug
```

## Install

```powershell
adb -s <phone_serial> install -r phone\build\outputs\apk\debug\phone-debug.apk
adb -s <watch_serial_or_ip_port> install -r watch\build\outputs\apk\debug\watch-debug.apk
```

## Setup

1. Open WristTwist Companion on phone and grant notification access.
2. Open WristTwist Watch and enable the accessibility service.
3. Configure the primary action and behavior in the companion app.

