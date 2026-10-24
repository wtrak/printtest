# JTK RP425 Native Android Printer Test

A tiny **Android app** that prints ZPL directly to the paired Rongta RP425 using Android Bluetooth Classic Serial Port Profile (SPP).

This avoids Chrome Web Serial and paid Android print services. The APK has **no Internet permission** and does not store or upload customer data.

## Build

The GitHub Actions workflow `Build Android Printer Test APK` assembles the debug APK and uploads it as a build artifact.

## On the tablet

1. Pair **RP425-3073** in Android Bluetooth settings.
2. Fully close RT eLabel and RTPrinter System so they are not holding the Bluetooth connection.
3. Install the debug APK built by GitHub Actions (you may need to allow installing apps from your browser/file manager).
4. Launch **JTK RP425 Test**, grant **Nearby devices** permission, and use **Find paired RP425**.
5. Tap **Print small ZPL test**, then **Print full 4×6 sample label** if successful.
6. If printing fails, send us the exact diagnostics shown in the app.

**Important:** This first version tests the standard Bluetooth SPP UUID only. If your RP425's firmware exposes only a BLE printing characteristic or a custom RFCOMM service, we'll adjust to its actual interface. A paired Bluetooth device is not proof that it supports SPP.

**Security:** Debug APK is for testing, not a signed production deployment. It sends only hard-coded fictional label data.
