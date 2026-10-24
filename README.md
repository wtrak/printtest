# JTK RP425 printer test

This is a **test only**, not the shipping production app.

## On your Android tablet

1. Pair the Rongta RP425 in Android **Bluetooth** settings.
2. Open the deployed HTTPS URL in **Chrome for Android** (not an embedded browser).
3. Tap **Connect Bluetooth printer** and select the RP425 if offered.
4. Tap **Print small ZPL test**. This should consume one 4×6 label.
5. If it works, tap **Print full 4×6 label**. It uses fictional customer fields and a random QR package ID.
6. Share the exact diagnostic message if the printer cannot connect or print.

No subscription, PDF print service, or Google Sheets connection is used in this test.

### Requirements / limitations

- Android Chrome Web Serial supports **Bluetooth RFCOMM / Serial Port Profile**, but not every Bluetooth printer exposes a compatible serial port.
- The RP425 model's manufacturer lists **ZPL** commands, but actual firmware configurations may vary.
- A Web Serial write means bytes were sent, **not** that the printer successfully printed.
- Site must use **HTTPS**; permission to connect is requested only after you press Connect.
- This is a public static test page containing only fictional information. Don't enter real customer data.
- If direct Chrome printing fails, the next route is a small Android companion APK for Bluetooth printing.
