# JTK Packing Station — Google Sheets version

**This is the Google Sheets-connected packing workflow. It replaces the prior browser-local storage design.** The current master workbook is in Google Drive. Existing customer/order data is NOT put into this public GitHub repository.

## Architecture

- **Master Google Sheet**: `JTK Packing Master — All Auctions`
  - [Open master Sheet](https://docs.google.com/spreadsheets/d/1h9SpQUDBdbpqQV7bgQeFCqSgdTkSPPz_meiD6HV6MhI/edit)
  - **Auctions**: 16 imported auctions
  - **Customers**: 523 imported customers
  - **Packages**: one row per physical box, with permanent JTK package ID
  - **Shipping Export**: 19-column shipping CSV-formatted, dynamically calculated from Packages + Customers
- **Packing screen**: `packing/index.html`, hosted under the same Vercel origin as the server API.
- **Backend**: `api/packing.js`; only this server ever authenticates with the Google Sheets API.
- **Android**: existing Packing Station APK loads the hosted packing screen and sends ZPL commands through Bluetooth SPP to the Rongta RP425. It does not save a separate auction database.
- **Desktop**: same URL in a computer browser for importing new auctions and reviewing/exporting packages.

## One-time secure connection setup (must be completed by the owner)

ChatGPT's own connected Google Drive account is **not available to apps running independently on Vercel**. This separate server needs its own least-privilege Google Sheets authorization.

1. In [Google Cloud Console](https://console.cloud.google.com/), choose/create a project for JTK Packing Station.
2. In **APIs & Services → Library**, enable **Google Sheets API**.
3. In **IAM & Admin → Service Accounts**, create a service account such as `jtk-packing-sheets`.
4. In that service account's **Keys** tab, create and download one **JSON private key**. Keep the JSON private. Never commit it to GitHub or send it in chat.
5. Open the [master Google Sheet](https://docs.google.com/spreadsheets/d/1h9SpQUDBdbpqQV7bgQeFCqSgdTkSPPz_meiD6HV6MhI/edit). Select **Share**, and add the service account's `client_email` address as **Editor**. Only share this packing workbook, not your whole Drive.
6. In the Vercel dashboard, open the existing project `jtk-rp425-printer-test` → **Settings → Environment Variables** and add these for **Production**:
   - `GOOGLE_SERVICE_ACCOUNT_EMAIL`: `client_email` from the JSON
   - `GOOGLE_PRIVATE_KEY`: `private_key` from the JSON, including its BEGIN/END PRIVATE KEY text. You can store it with literal `\n` escapes; the backend converts them to newlines.
   - `PACKING_APP_PASSWORD`: a unique strong station password of your choice (enter on Android and desktop).
   - `PACKING_SESSION_SECRET`: a different random secret at least 24 characters long. On Mac, `openssl rand -hex 32` will generate one. Do not reuse the station password.
   - `JTK_SHEET_ID` (optional): `1h9SpQUDBdbpqQV7bgQeFCqSgdTkSPPz_meiD6HV6MhI`
7. Redeploy `jtk-rp425-printer-test` so the production server loads those variables.
8. Open the [packing dashboard](https://jtk-rp425-printer-test-taylors-projects-41e735e8.vercel.app/packing/index.html); enter the station password.
9. Test **one sample package** using an actual customer selection with a small, identifiable box (or a test-only auction), confirm the new row appears in **Packages** and its corresponding line appears in **Shipping Export**, then print and scan the QR. Make sure not to create a dummy package in a live invoice export unless you plan to delete it.

## Workflow

- On **desktop**: new auction → select A–F and date → upload existing 19-column CSV; the authenticated server appends Auctions and Customers rows to the Google Sheet.
- On **tablet**: choose auction dropdown → choose customer dropdown → enter box, pounds/ounces, small/medium/large counts → **Save to Google Sheet & Print**.
- On save, the backend checks that the customer belongs to the chosen auction, checks whether package ID is already present, then adds a package row. The Android app prints **after** save succeeds.
- On reprint, the same permanent package ID is used; no new package row.
- On **desktop**: select auction → **Download 19-column shipping CSV**. The export format follows the user's existing Shipping Organizer headers. For a live overview, inspect the master Sheet's Shipping Export tab.

## Security & current limitations

- This public repository intentionally has **no real customer CSVs, Google private key, or station password**.
- Server endpoints require a station password and a signed, secure, HttpOnly session cookie. Browser session token is stored only as a cookie; customer data is not persistently saved on the tablet.
- On an unconfigured backend, the packing UI intentionally blocks saving and shows a setup notice. This is safer than silently falling back to local storage.
- Current station password is shared across stations. Before broad external access, consider individual worker logins and auditing.
- New-auction imports reject duplicate auction IDs rather than overwriting existing customers or payment status.
- A package is one physical box. For customers with multiple packages, the Shipping Export generates one CSV row per box; **the existing Shipping Organizer's importer may need a matching update to preserve multiple boxes rather than treating bidcards as unique customers.** Do not use its “replace auction rows” option on a live auction.
- Google Sheets API writes are not a fully transactional database. Server checks repeated package IDs, but two stations saving the same ID at precisely the same time could race. Move to transactional storage if volume or station concurrency justifies it.
- The Android printer confirms bytes sent, **not** that the printer mechanically printed; physically verify label and scanned QR.
- This is a test rollout, not yet hardened production software.
