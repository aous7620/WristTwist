## critical

Do not change these unless you are intentionally reworking accessibility integration:

1. `watch/src/main/AndroidManifest.xml` service metadata must point to `@xml/accessibility_config`.
2. `watch/src/main/res/xml/accessibility_config.xml` must exist and stay conservative for OEM compatibility.
3. Keep service declaration as:
   `android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"` with accessibility intent action.
4. If the service disappears from watch Accessibility settings, first restore the three points above, rebuild, uninstall watch app, then reinstall watch APK.

This project has previously regressed here; treat this as a release blocker check.
