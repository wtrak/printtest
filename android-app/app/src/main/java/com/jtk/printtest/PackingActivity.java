package com.jtk.printtest;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PackingActivity extends Activity {
    private static final String PACKING_URL =
        "https://jtk-rp425-printer-test-taylors-projects-41e735e8.vercel.app/packing/index.html";
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int REQ_FILE = 771;
    private static final int REQ_SAVE = 772;
    private static final int REQ_BLUETOOTH = 773;

    private final ExecutorService queue = Executors.newSingleThreadExecutor();
    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private String pendingExport;
    private BluetoothAdapter adapter;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        setTitle("JTK Packing Station");
        adapter = BluetoothAdapter.getDefaultAdapter();
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(true);
        web.getSettings().setSupportZoom(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                return !("https".equals(u.getScheme())
                  && "jtk-rp425-printer-test-taylors-projects-41e735e8.vercel.app".equals(u.getHost())
                  && u.getPath().startsWith("/packing/"));
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(
                  WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.setType("*/*");
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/csv","text/plain","application/vnd.ms-excel","application/octet-stream"});
                startActivityForResult(Intent.createChooser(i, "Choose auction customer CSV"), REQ_FILE);
                return true;
            }
        });
        web.addJavascriptInterface(new PrinterBridge(), "AndroidBridge");
        setContentView(web);
        web.loadUrl(PACKING_URL);

        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BLUETOOTH);
        }
    }

    private void sendResult(String packageId, boolean ok, String message) {
        JSONObject j = new JSONObject();
        try {
            j.put("id", packageId);
            j.put("ok", ok);
            j.put("message", message);
        } catch (Exception ignored) { }
        final String arg = JSONObject.quote(j.toString());
        runOnUiThread(() -> web.evaluateJavascript(
            "if (window.jtkNativePrintResult) window.jtkNativePrintResult(" + arg + ");", null));
    }

    private static String safe(Object raw, int maxLen) {
        String value = raw == null ? "" : String.valueOf(raw);
        value = value.replaceAll("[\\\\^~\\r\\n\\p{Cntrl}]", " ");
        value = value.replaceAll("[^\\x20-\\x7E]", "");
        return value.length() > maxLen ? value.substring(0, maxLen) : value;
    }

    private static String zplText(int x, int y, int h, String text) {
        return "^FO" + x + "," + y + "^A0N," + h + "," + h + "^FD" + text + "^FS\n";
    }

    private static String zplLabel(JSONObject p) throws Exception {
        String pkgId = p.getString("id");
        if (!pkgId.matches("JTK-[A-Z0-9-]{10,80}")) throw new Exception("Invalid package ID");
        String customer = safe(p.optString("name"), 18);
        String auction = safe(p.optString("auctionName"), 22);
        String bidcard = safe(p.optString("bidcard"), 20);
        String date = safe(p.optString("auctionDate"), 13);
        String weight = safe(p.optString("pounds"), 10) + " lb " + safe(p.optString("ounces"), 10) + " oz";
        String dims = safe(p.optString("length"), 9) + " x " + safe(p.optString("width"), 9) +
                " x " + safe(p.optString("height"), 9) + " in";
        StringBuilder z = new StringBuilder(1500);
        z.append("^XA\n^CI28\n^PW812\n^LL1218\n");
        z.append(zplText(40, 35, 49, "JTK PACKING LABEL"));
        z.append(zplText(40, 107, 23, "INTERNAL PACKAGE IDENTIFICATION"));
        z.append("^FO40,160^GB730,3,3^FS\n");
        z.append(zplText(40, 190, 23, "CUSTOMER"));
        z.append(zplText(40, 225, 42, customer));
        z.append(zplText(40, 298, 22, "BIDCARD / INVOICE"));
        z.append(zplText(40, 336, 40, bidcard));
        z.append("^FO520,184^BQN,2,5^FDLA,").append(pkgId).append("^FS\n");
        z.append(zplText(528, 382, 20, "SCAN PACKAGE ID"));
        z.append(zplText(40, 415, 23, "AUCTION"));
        z.append(zplText(40, 452, 37, auction));
        z.append(zplText(40, 494, 25, date));
        z.append("^FO40,549^GB730,3,3^FS\n");
        z.append(zplText(40, 571, 27, "ITEM QUANTITIES"));
        z.append(zplText(40, 629, 24, "SMALL"));
        z.append(zplText(290, 629, 24, "MEDIUM"));
        z.append(zplText(540, 629, 24, "LARGE"));
        z.append(zplText(40, 666, 60, safe(p.optString("small"), 3)));
        z.append(zplText(290, 666, 60, safe(p.optString("medium"), 3)));
        z.append(zplText(540, 666, 60, safe(p.optString("large"), 3)));
        z.append("^FO40,755^GB730,3,3^FS\n");
        z.append(zplText(40, 777, 24, "PACKAGE WEIGHT"));
        z.append(zplText(40, 810, 44, weight));
        z.append(zplText(40, 898, 24, "BOX DIMENSIONS"));
        z.append(zplText(40, 940, 38, dims));
        z.append("^FO40,1020^GB730,3,3^FS\n");
        z.append(zplText(40, 1043, 22, "UNIQUE PACKAGE ID"));
        z.append(zplText(40, 1091, 23, pkgId));
        z.append("^XZ\n");
        return z.toString();
    }

    public class PrinterBridge {
        @JavascriptInterface public void printPackage(String data) {
            String id = "";
            JSONObject p;
            try {
                p = new JSONObject(data);
                id = p.optString("id");
            } catch (Exception e) {
                sendResult(id, false, "Invalid print data");
                return;
            }
            final String idForCallback = id;
            final JSONObject pkg = p;
            queue.execute(() -> {
                BluetoothSocket socket = null;
                try {
                    if (adapter == null || !adapter.isEnabled()) throw new Exception("Bluetooth is switched off");
                    if (Build.VERSION.SDK_INT >= 31 &&
                        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                        throw new Exception("Nearby devices permission not granted");
                    Set<BluetoothDevice> paired = adapter.getBondedDevices();
                    BluetoothDevice printer = null;
                    if (paired != null) for (BluetoothDevice dev : paired) {
                        String name = dev.getName();
                        if (name != null && name.toUpperCase().contains("RP425")) {
                            printer = dev; break;
                        }
                    }
                    if (printer == null) throw new Exception("RP425 not paired");
                    String zpl = zplLabel(pkg);
                    socket = printer.createRfcommSocketToServiceRecord(SPP);
                    socket.connect();
                    OutputStream stream = socket.getOutputStream();
                    stream.write(zpl.getBytes(StandardCharsets.US_ASCII));
                    stream.flush();
                    Thread.sleep(350);
                    sendResult(idForCallback, true, "Data sent; verify physical label");
                } catch (Exception e) {
                    sendResult(idForCallback, false, safe(e.getMessage(), 170));
                } finally {
                    if (socket != null) try { socket.close(); } catch (Exception ignored) { }
                }
            });
        }

        @JavascriptInterface public void saveFile(String filename, String contents, String mime) {
            runOnUiThread(() -> {
                pendingExport = contents;
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType(mime == null || mime.isEmpty() ? "text/csv" : mime);
                intent.putExtra(Intent.EXTRA_TITLE, filename);
                startActivityForResult(intent, REQ_SAVE);
            });
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQ_FILE && fileCallback != null) {
            Uri[] uris = result == RESULT_OK && data != null && data.getData() != null ?
                new Uri[]{data.getData()} : null;
            fileCallback.onReceiveValue(uris);
            fileCallback = null;
        }
        if (request == REQ_SAVE && pendingExport != null) {
            if (result == RESULT_OK && data != null && data.getData() != null) {
                try (OutputStream stream = getContentResolver().openOutputStream(data.getData())) {
                    if (stream != null) {
                        stream.write(pendingExport.getBytes(StandardCharsets.UTF_8));
                        stream.flush();
                    }
                } catch (Exception e) {
                    sendResult("", false, "Export failed: " + e.getMessage());
                }
            }
            pendingExport = null;
        }
    }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (web != null) web.destroy();
        queue.shutdown();
        super.onDestroy();
    }
}
