package com.jtk.printtest;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST = 100;
    private static final UUID SPP_UUID =
        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private BluetoothDevice printer;
    private BluetoothAdapter adapter;
    private TextView status, logView;
    private Button findButton, smallButton, fullButton;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("JTK RP425 Printer Test");
        adapter = BluetoothAdapter.getDefaultAdapter();

        ScrollView scroll = new ScrollView(this);
        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        main.setPadding(pad, pad, pad, pad);
        scroll.addView(main);

        TextView heading = new TextView(this);
        heading.setText("JTK · RP425 Printer Test");
        heading.setTextSize(24);
        heading.setTypeface(null, 1);
        main.addView(heading);
        TextView details = new TextView(this);
        details.setText("\nDirect Bluetooth printing — no RT eLabel, print-service subscription, or Chrome serial port.\n\nPair RP425-3073 in Android Bluetooth settings first. Close other Rongta printing apps before testing.\n");
        details.setTextSize(15);
        main.addView(details);

        status = new TextView(this);
        status.setTextSize(17);
        status.setText("Status: Ready");
        main.addView(status);

        findButton = button(main, "1. Find paired RP425", () -> findPrinter());
        smallButton = button(main, "2. Print small ZPL test (4×6 label)", () -> print(false));
        fullButton = button(main, "3. Print full 4×6 sample label", () -> print(true));
        smallButton.setEnabled(false);
        fullButton.setEnabled(false);

        TextView diagnostic = new TextView(this);
        diagnostic.setText("\nDiagnostics");
        diagnostic.setTextSize(18);
        diagnostic.setTypeface(null, 1);
        main.addView(diagnostic);
        logView = new TextView(this);
        logView.setTextSize(13);
        logView.setTextIsSelectable(true);
        main.addView(logView);
        setContentView(scroll);

        if (adapter == null) {
            record("This Android device does not have a Bluetooth adapter.");
        } else {
            ensurePermissionThenFind();
        }
    }

    private Button button(LinearLayout parent, String title, Runnable action) {
        Button b = new Button(this);
        b.setText(title);
        parent.addView(b);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private int dp(int n) {
        return (int)(n * getResources().getDisplayMetrics().density);
    }

    private void record(String text) {
        runOnUiThread(() -> logView.append(text + "\n"));
    }

    private void displayStatus(String text) {
        runOnUiThread(() -> status.setText("Status: " + text));
    }

    private boolean hasPermission() {
        return Build.VERSION.SDK_INT < 31 ||
               checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                  == PackageManager.PERMISSION_GRANTED;
    }

    private void ensurePermissionThenFind() {
        if (!hasPermission()) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                               PERMISSION_REQUEST);
            return;
        }
        findPrinter();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == PERMISSION_REQUEST) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                findPrinter();
            } else {
                displayStatus("Bluetooth permission denied");
                record("Grant Nearby devices / Bluetooth permission in Android app settings.");
            }
        }
    }

    @SuppressWarnings("MissingPermission")
    private void findPrinter() {
        if (!hasPermission()) { ensurePermissionThenFind(); return; }
        if (adapter == null || !adapter.isEnabled()) {
            displayStatus("Bluetooth is off");
            record("Turn on Bluetooth in Android Settings.");
            return;
        }
        printer = null;
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded == null || bonded.isEmpty()) {
            displayStatus("No paired devices");
            record("Pair RP425-3073 in Android Bluetooth settings.");
        } else {
            record("Paired Bluetooth devices:");
            for (BluetoothDevice device : bonded) {
                String name = device.getName();
                record("• " + (name == null ? "(unnamed)" : name));
                ParcelUuid[] services = device.getUuids();
                if (services != null) {
                    for (ParcelUuid uuid : services) record("    service: " + uuid);
                }
                if (name != null && name.toUpperCase().contains("RP425")) {
                    printer = device;
                }
            }
        }
        boolean found = printer != null;
        smallButton.setEnabled(found);
        fullButton.setEnabled(found);
        displayStatus(found ? "Found " + printer.getName() : "RP425 not found");
        if (found) record("Selected printer: " + printer.getName());
    }

    private void print(boolean full) {
        if (!hasPermission()) { ensurePermissionThenFind(); return; }
        if (printer == null) { findPrinter(); return; }
        final BluetoothDevice target = printer;
        final String label = full ? fullLabel() : shortLabel();
        smallButton.setEnabled(false);
        fullButton.setEnabled(false);
        displayStatus("Connecting to printer…");

        executor.execute(() -> {
            BluetoothSocket socket = null;
            try {
                // Authenticated Bluetooth Classic Serial Port Profile (SPP).
                socket = target.createRfcommSocketToServiceRecord(SPP_UUID);
                socket.connect();
                displayStatus("Sending ZPL...");
                OutputStream output = socket.getOutputStream();
                byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
                output.write(bytes);
                output.flush();
                record("Sent " + bytes.length + " bytes using Bluetooth SPP.");
                record("A successful send does not confirm the label actually printed.");
                displayStatus("Data sent — check for physical label");
                Thread.sleep(350);
            } catch (Exception e) {
                displayStatus("Print connection failed");
                record("ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                record("If another Rongta app is open, close it completely and power-cycle the printer.");
                record("If a paired RP425 still fails, the unit may require a BLE interface or a different service UUID.");
            } finally {
                if (socket != null) {
                    try { socket.close(); } catch (Exception ignored) { }
                }
                runOnUiThread(() -> {
                    smallButton.setEnabled(true);
                    fullButton.setEnabled(true);
                });
            }
        });
    }

    private static String shortLabel() {
        return "^XA\n^CI28\n^PW812\n^LL1218\n" +
               "^FO38,65^A0N,55,55^FDJTK RP425 TEST^FS\n" +
               "^FO38,148^A0N,34,34^FDBluetooth SPP is working^FS\n" +
               "^FO38,225^GB733,3,3^FS\n^XZ\n";
    }

    private static String fullLabel() {
        String packageId = "JTK-" + UUID.randomUUID().toString().toUpperCase();
        return "^XA\n^CI28\n^PW812\n^LL1218\n" +
               "^FO40,40^A0N,49,49^FDJTK PACKING LABEL^FS\n" +
               "^FO40,112^A0N,26,26^FDSAMPLE - NOT FOR SHIPPING^FS\n" +
               "^FO40,160^GB730,3,3^FS\n" +
               "^FO40,198^A0N,25,25^FDCUSTOMER^FS\n" +
               "^FO40,236^A0N,44,44^FDJane Smith^FS\n" +
               "^FO40,307^A0N,25,25^FDINVOICE^FS\n" +
               "^FO40,343^A0N,42,42^FD10542^FS\n" +
               "^FO40,415^A0N,25,25^FDAUCTION^FS\n" +
               "^FO40,452^A0N,40,40^FDAuction E - 10/18/2026^FS\n" +
               "^FO40,522^GB730,3,3^FS\n" +
               "^FO40,546^A0N,30,30^FDITEM QUANTITIES^FS\n" +
               "^FO40,604^A0N,26,26^FDSMALL^FS\n" +
               "^FO290,604^A0N,26,26^FDMEDIUM^FS\n" +
               "^FO540,604^A0N,26,26^FDLARGE^FS\n" +
               "^FO40,651^A0N,61,61^FD2^FS\n" +
               "^FO290,651^A0N,61,61^FD1^FS\n" +
               "^FO540,651^A0N,61,61^FD0^FS\n" +
               "^FO40,750^GB730,3,3^FS\n" +
               "^FO40,775^A0N,25,25^FDGROSS PACKAGE WEIGHT^FS\n" +
               "^FO40,816^A0N,47,47^FD6 lb 7.2 oz^FS\n" +
               "^FO40,899^A0N,25,25^FDBOX DIMENSIONS^FS\n" +
               "^FO40,938^A0N,42,42^FD12 x 12 x 8 in^FS\n" +
               "^FO40,1022^GB730,3,3^FS\n" +
               "^FO40,1050^A0N,21,21^FDUNIQUE PACKAGE ID^FS\n" +
               "^FO40,1085^A0N,22,22^FD" + packageId + "^FS\n" +
               "^XZ\n";
    }

    @Override protected void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }
}
