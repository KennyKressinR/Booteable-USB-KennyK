package app.isousb.booteable;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.Window;
import android.view.WindowManager;

import androidx.activity.result.ActivityResult;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;

import rikka.shizuku.Shizuku;

/**
 * Plugin "IsoUsb".
 * - Acceso directo al USB con la API USB Host (protocolo Bulk-Only + comandos SCSI), sin root.
 * - Grabación raw de ISO híbridas, verificación SHA-256, formato MBR/GPT + FAT32 propio.
 * - Shizuku: formato del sistema (sm partition) y desmontaje limpio (sm unmount).
 * - Permisos, ajustes del sistema, selector de ISO y copia de archivos a volúmenes montados.
 */
@CapacitorPlugin(name = "IsoUsb")
public class IsoUsbPlugin extends Plugin {

    private static final String ACTION_USB_PERM = "app.isousb.booteable.USB_PERMISSION";
    private static final String SHIZUKU_PKG = "moe.shizuku.privileged.api";
    private static final int CHUNK = 64 * 1024;

    private UsbManager usb;
    private Uri isoUri;
    private String isoName = "imagen.iso";
    private long isoSize = 0;
    private volatile boolean cancel = false;
    private volatile boolean busy = false;
    private PowerManager.WakeLock wake;

    interface Job {
        JSObject run() throws Exception;
    }

    @Override
    public void load() {
        usb = (UsbManager) getContext().getSystemService(Context.USB_SERVICE);

        BroadcastReceiver rx = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                JSObject o = new JSObject();
                o.put("action", i.getAction());
                notifyListeners("usb", o);
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        ContextCompat.registerReceiver(getContext(), rx, f, ContextCompat.RECEIVER_NOT_EXPORTED);

        try {
            Shizuku.addRequestPermissionResultListener(new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    notifyListeners("shizuku", shizukuStatus());
                }
            });
        } catch (Throwable ignored) {
        }

        // Barras del sistema con los colores de la app
        final Activity a = getActivity();
        if (a != null) {
            a.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Window w = a.getWindow();
                        w.setStatusBarColor(0xFF0F2A43);
                        w.setNavigationBarColor(0xFF0F2A43);
                        WindowInsetsControllerCompat wc = WindowCompat.getInsetsController(w, w.getDecorView());
                        wc.setAppearanceLightStatusBars(false);
                        wc.setAppearanceLightNavigationBars(false);
                    } catch (Throwable ignored) {
                    }
                }
            });
        }
    }

    // ===================================================================
    //  Estado, permisos y ajustes
    // ===================================================================

    private boolean hasAllFiles() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return ContextCompat.checkSelfPermission(getContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private JSObject shizukuStatus() {
        boolean installed = false, running = false, granted = false;
        int uid = -1;
        try {
            getContext().getPackageManager().getPackageInfo(SHIZUKU_PKG, 0);
            installed = true;
        } catch (Exception ignored) {
        }
        try {
            running = Shizuku.pingBinder();
            if (running) {
                granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
                uid = Shizuku.getUid();
            }
        } catch (Throwable ignored) {
        }
        JSObject o = new JSObject();
        o.put("installed", installed);
        o.put("running", running);
        o.put("granted", granted);
        o.put("uid", uid);
        return o;
    }

    @PluginMethod
    public void status(PluginCall call) {
        JSObject o = new JSObject();
        o.put("sdk", Build.VERSION.SDK_INT);
        o.put("allFiles", hasAllFiles());
        o.put("usbHost", usb != null
                && getContext().getPackageManager().hasSystemFeature(PackageManager.FEATURE_USB_HOST));
        o.put("shizuku", shizukuStatus());
        call.resolve(o);
    }

    @PluginMethod
    public void requestAllFiles(PluginCall call) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                Uri pkg = Uri.parse("package:" + getContext().getPackageName());
                try {
                    getActivity().startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg));
                } catch (Exception e) {
                    getActivity().startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                }
            } else {
                ActivityCompat.requestPermissions(getActivity(), new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE}, 7001);
            }
            call.resolve();
        } catch (Exception e) {
            call.reject("No se pudo abrir el ajuste: " + msg(e));
        }
    }

    @PluginMethod
    public void openSettings(PluginCall call) {
        String kind = call.getString("kind", "app");
        Uri pkg = Uri.parse("package:" + getContext().getPackageName());
        Intent i;
        if ("dev".equals(kind)) {
            i = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
        } else if ("files".equals(kind) && Build.VERSION.SDK_INT >= 30) {
            i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg);
        } else {
            i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg);
        }
        try {
            getActivity().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject("No se pudo abrir ajustes: " + msg(e));
        }
    }

    @PluginMethod
    public void shizukuRequest(PluginCall call) {
        try {
            if (!Shizuku.pingBinder()) {
                call.reject("Shizuku no está en ejecución");
                return;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                call.resolve(shizukuStatus());
                return;
            }
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                call.reject("Permiso denegado. Concédelo desde la app Shizuku (Apps autorizadas).");
                return;
            }
            Shizuku.requestPermission(4242);
            call.resolve(shizukuStatus());
        } catch (Throwable t) {
            call.reject("Shizuku: " + msg(t));
        }
    }

    @PluginMethod
    public void shizukuOpen(PluginCall call) {
        Intent i = getContext().getPackageManager().getLaunchIntentForPackage(SHIZUKU_PKG);
        String how = "app";
        if (i == null) {
            i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"));
            how = "web";
        }
        try {
            getActivity().startActivity(i);
            JSObject o = new JSObject();
            o.put("opened", how);
            call.resolve(o);
        } catch (Exception e) {
            call.reject("No se pudo abrir Shizuku: " + msg(e));
        }
    }

    // ===================================================================
    //  Dispositivos USB
    // ===================================================================

    private UsbDevice findDevice(String id) {
        if (usb == null || id == null) return null;
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (d.getDeviceName().equals(id)) return d;
        }
        return null;
    }

    private boolean isMassStorage(UsbDevice d) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            if (d.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_MASS_STORAGE) return true;
        }
        return false;
    }

    @PluginMethod
    public void listDevices(PluginCall call) {
        JSArray arr = new JSArray();
        if (usb != null) {
            for (UsbDevice d : usb.getDeviceList().values()) {
                if (!isMassStorage(d)) continue;
                String man = null, prod = null;
                try {
                    man = d.getManufacturerName();
                    prod = d.getProductName();
                } catch (Exception ignored) {
                }
                JSObject o = new JSObject();
                o.put("id", d.getDeviceName());
                o.put("vid", String.format(Locale.US, "%04x", d.getVendorId()));
                o.put("pid", String.format(Locale.US, "%04x", d.getProductId()));
                o.put("manufacturer", man == null ? "" : man);
                o.put("product", prod == null ? "" : prod);
                o.put("perm", usb.hasPermission(d));
                arr.put(o);
            }
        }
        JSObject r = new JSObject();
        r.put("devices", arr);
        call.resolve(r);
    }

    @PluginMethod
    public void requestUsb(final PluginCall call) {
        final UsbDevice d = findDevice(call.getString("id", ""));
        if (d == null) {
            call.reject("Dispositivo no encontrado");
            return;
        }
        if (usb.hasPermission(d)) {
            JSObject o = new JSObject();
            o.put("granted", true);
            call.resolve(o);
            return;
        }
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                try {
                    getContext().unregisterReceiver(this);
                } catch (Exception ignored) {
                }
                JSObject o = new JSObject();
                o.put("granted", usb.hasPermission(d));
                call.resolve(o);
            }
        };
        ContextCompat.registerReceiver(getContext(), r, new IntentFilter(ACTION_USB_PERM),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        Intent in = new Intent(ACTION_USB_PERM).setPackage(getContext().getPackageName());
        usb.requestPermission(d, PendingIntent.getBroadcast(getContext(), 0, in, flags));
    }

    /** Abre el USB, reclama la interfaz y lee la capacidad. */
    private Msc open(String id) throws IOException {
        UsbDevice d = findDevice(id);
        if (d == null) throw new IOException("Dispositivo no encontrado. Pulsa Actualizar.");
        if (!usb.hasPermission(d)) throw new IOException("Falta el permiso USB. Pulsa «Dar permiso».");
        Msc m = new Msc(usb, d);
        try {
            m.init();
        } catch (IOException e) {
            m.close();
            throw e;
        }
        return m;
    }

    @PluginMethod
    public void deviceInfo(PluginCall call) {
        final String id = call.getString("id", "");
        async(call, new Job() {
            @Override
            public JSObject run() throws Exception {
                Msc m = open(id);
                try {
                    String vendor = "", product = "", rev = "";
                    try {
                        byte[] q = new byte[36];
                        m.cmd(new byte[]{0x12, 0, 0, 0, 36, 0}, 36, true, q, 0);
                        vendor = new String(q, 8, 8, StandardCharsets.US_ASCII).trim();
                        product = new String(q, 16, 16, StandardCharsets.US_ASCII).trim();
                        rev = new String(q, 32, 4, StandardCharsets.US_ASCII).trim();
                    } catch (IOException ignored) {
                    }
                    JSObject o = new JSObject();
                    o.put("vendor", vendor);
                    o.put("product", product);
                    o.put("rev", rev);
                    o.put("blocks", m.blocks);
                    o.put("blockSize", m.bs);
                    o.put("bytes", m.blocks * m.bs);
                    return o;
                } finally {
                    m.close();
                }
            }
        });
    }

    // ===================================================================
    //  Selector de ISO
    // ===================================================================

    @PluginMethod
    public void pickIso(PluginCall call) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(call, i, "isoPicked");
    }

    @ActivityCallback
    private void isoPicked(PluginCall call, ActivityResult res) {
        if (call == null) return;
        if (res.getResultCode() != Activity.RESULT_OK || res.getData() == null || res.getData().getData() == null) {
            call.reject("Selección cancelada");
            return;
        }
        Uri u = res.getData().getData();
        String name = "imagen.iso";
        long size = -1;
        Cursor c = null;
        try {
            c = getContext().getContentResolver().query(u, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni);
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        if (size <= 0) {
            try (ParcelFileDescriptor p = getContext().getContentResolver().openFileDescriptor(u, "r")) {
                if (p != null) size = p.getStatSize();
            } catch (Exception ignored) {
            }
        }
        if (size <= 0) {
            call.reject("No se pudo determinar el tamaño del archivo");
            return;
        }
        isoUri = u;
        isoName = name;
        isoSize = size;
        JSObject o = new JSObject();
        o.put("name", name);
        o.put("size", size);
        o.put("hybrid", looksHybrid(u));
        WinIso.Info info = analyzeIso(u);
        o.put("udf", info.udf);
        o.put("efi", info.efi);
        o.put("windows", info.windows);
        o.put("wimBytes", info.wim);
        call.resolve(o);
    }

    /** Una ISO híbrida (Linux) trae sector de arranque MBR con firma 55 AA; las de Windows no. */
    private boolean looksHybrid(Uri u) {
        InputStream is = null;
        try {
            is = getContext().getContentResolver().openInputStream(u);
            if (is == null) return false;
            byte[] b = new byte[512];
            return readFully(is, b, 512) == 512 && (b[510] & 0xFF) == 0x55 && (b[511] & 0xFF) == 0xAA;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                if (is != null) is.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ===================================================================
    //  Grabar ISO (raw, estilo dd) con verificación opcional
    // ===================================================================

    @PluginMethod
    public void writeIso(PluginCall call) {
        final String id = call.getString("id", "");
        final boolean verify = call.getBoolean("verify", true);
        if (isoUri == null) {
            call.reject("Primero elige una ISO");
            return;
        }
        final Uri uri = isoUri;
        final long size = isoSize;
        async(call, new Job() {
            @Override
            public JSObject run() throws Exception {
                Msc m = open(id);
                InputStream is = null;
                try {
                    long cap = m.blocks * m.bs;
                    if (size > cap) {
                        throw new IOException("La imagen (" + fmt(size) + ") no cabe en el USB (" + fmt(cap) + ")");
                    }
                    int chunk = Math.max(m.bs, (CHUNK / m.bs) * m.bs);
                    byte[] buf = new byte[chunk];
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    is = getContext().getContentResolver().openInputStream(uri);
                    if (is == null) throw new IOException("No se pudo abrir la ISO");

                    long done = 0, lba = 0, last = 0, t0 = System.currentTimeMillis();
                    while (done < size) {
                        if (cancel) throw new IOException("Cancelado por el usuario");
                        int want = (int) Math.min(chunk, size - done);
                        int got = readFully(is, buf, want);
                        if (got < want) throw new IOException("La imagen terminó antes de lo esperado");
                        md.update(buf, 0, got);
                        int pad = ((got + m.bs - 1) / m.bs) * m.bs;
                        if (pad > got) Arrays.fill(buf, got, pad, (byte) 0);
                        m.write(lba, buf, 0, pad / m.bs);
                        lba += pad / m.bs;
                        done += got;
                        long now = System.currentTimeMillis();
                        if (now - last > 300) {
                            last = now;
                            progress("write", done, size, t0);
                        }
                    }
                    m.sync();
                    progress("write", size, size, t0);
                    byte[] h1 = md.digest();

                    boolean verified = false;
                    if (verify) {
                        MessageDigest md2 = MessageDigest.getInstance("SHA-256");
                        long left = size, vl = 0, lastV = 0, t1 = System.currentTimeMillis();
                        while (left > 0) {
                            if (cancel) throw new IOException("Cancelado por el usuario");
                            int want = (int) Math.min(chunk, left);
                            int nb = (want + m.bs - 1) / m.bs;
                            m.read(vl, buf, 0, nb);
                            md2.update(buf, 0, want);
                            vl += nb;
                            left -= want;
                            long now = System.currentTimeMillis();
                            if (now - lastV > 300) {
                                lastV = now;
                                progress("verify", size - left, size, t1);
                            }
                        }
                        progress("verify", size, size, t1);
                        verified = MessageDigest.isEqual(h1, md2.digest());
                        if (!verified) {
                            throw new IOException("La verificación falló: lo leído del USB no coincide con la ISO "
                                    + "(¿USB defectuoso o con capacidad falsa?)");
                        }
                    }
                    JSObject r = new JSObject();
                    r.put("bytes", size);
                    r.put("sha256", hex(h1));
                    r.put("verified", verified);
                    return r;
                } finally {
                    try {
                        if (is != null) is.close();
                    } catch (IOException ignored) {
                    }
                    m.close();
                }
            }
        });
    }

    @PluginMethod
    public void cancelJob(PluginCall call) {
        cancel = true;
        call.resolve();
    }

    // ===================================================================
    //  Formato directo por USB: MBR o GPT + una partición FAT32
    // ===================================================================

    @PluginMethod
    public void format(PluginCall call) {
        final String id = call.getString("id", "");
        final boolean gpt = "gpt".equals(call.getString("scheme", "mbr"));
        String l = call.getString("label", "USB");
        l = l == null ? "" : l.replaceAll("[^A-Za-z0-9 _-]", "").toUpperCase(Locale.US);
        if (l.length() > 11) l = l.substring(0, 11);
        if (l.trim().isEmpty()) l = "NO NAME";
        final String label = l;
        async(call, new Job() {
            @Override
            public JSObject run() throws Exception {
                Msc m = open(id);
                try {
                    WinIso.formatFat32(m, gpt, label, ctl("format"));
                    JSObject o = new JSObject();
                    o.put("scheme", gpt ? "GPT" : "MBR");
                    o.put("fs", "FAT32");
                    o.put("label", label);
                    return o;
                } finally {
                    m.close();
                }
            }
        });
    }

    // ===================================================================
    //  Shizuku: comandos del sistema (sm)
    // ===================================================================

    /** Ejecuta un comando con privilegios de shell vía Shizuku. Devuelve {código, salida}. */
    private String[] sh(String cmd) throws Exception {
        if (!Shizuku.pingBinder()) throw new IOException("Shizuku no está en ejecución");
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            throw new IOException("Shizuku no ha dado permiso a esta app");
        }
        Method nm = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
        nm.setAccessible(true);
        Process p = (Process) nm.invoke(null, new Object[]{new String[]{"sh", "-c", cmd + " 2>&1"}, null, null});
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        InputStream in = p.getInputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        int code = p.waitFor();
        return new String[]{String.valueOf(code), bo.toString("UTF-8")};
    }

    @PluginMethod
    public void shizukuDisks(PluginCall call) {
        async(call, new Job() {
            @Override
            public JSObject run() throws Exception {
                String[] d = sh("sm list-disks");
                String[] v = sh("sm list-volumes public");
                StringBuilder disks = new StringBuilder(), vols = new StringBuilder();
                for (String line : d[1].split("\n")) {
                    String s = line.trim();
                    if (s.startsWith("disk:") && !s.startsWith("disk:179,")) disks.append(s).append('\n');
                }
                for (String line : v[1].split("\n")) {
                    String s = line.trim();
                    if (s.startsWith("public:") && !s.startsWith("public:179,")) vols.append(s).append('\n');
                }
                JSObject o = new JSObject();
                o.put("disks", disks.toString());
                o.put("volumes", vols.toString());
                return o;
            }
        });
    }

    @PluginMethod
    public void shizukuFormat(PluginCall call) {
        final String disk = call.getString("disk", "");
        if (disk == null || !disk.matches("disk:\\d+,\\d+")) {
            call.reject("Elige un disco válido");
            return;
        }
        async(call, new Job() {
            @Override
            public JSObject run() throws Exception {
                String[] r = sh("sm partition " + disk + " public");
                if (!"0".equals(r[0])) throw new IOException("sm partition falló: " + r[1].trim());
                JSObject o = new JSObject();
                o.put("out", r[1].trim());
                return o;
            }
        });
    }

    private String unmountAll() throws Exception {
        StringBuilder sb = new StringBuilder();
        String[] v = sh("sm list-volumes public");
        for (String line : v[1].split("\n")) {
            String[] t = line.trim().split("\\s+");
            if (t.length >= 2 && t[0].matches("public:\\d+,\\d+") && !t[0].startsWith("public:179,")
                    && "mounted".equals(t[1])) {
                String[] u = sh("sm unmount " + t[0]);
                sb.append(t[0]).append(": ").append("0".equals(u[0]) ? "desmontado" : u[1].trim()).append('\n');
            }
        }
        if (sb.length() == 0) sb.append("Shizuku: no hay volúmenes USB montados\n");
        return sb.toString();
    }

    // ===================================================================
    //  Expulsión segura
    // ===================================================================

    @PluginMethod
    public void eject(PluginCall call) {
        final String id = call.getString("id", "");
        async(call, new Job() {
            @Override
            public JSObject run() throws Exception {
                StringBuilder log = new StringBuilder();
                boolean viaShizuku = false;
                try {
                    if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                        log.append(unmountAll());
                        viaShizuku = true;
                    }
                } catch (Throwable t) {
                    log.append("Shizuku: ").append(msg(t)).append('\n');
                }
                UsbDevice d = findDevice(id);
                if (d != null && usb.hasPermission(d)) {
                    Msc m = null;
                    try {
                        m = open(id);
                        m.sync();
                        m.eject();
                        log.append("USB: caché vaciada y expulsión enviada\n");
                    } catch (IOException e) {
                        log.append("USB: ").append(msg(e)).append('\n');
                    } finally {
                        if (m != null) m.close();
                    }
                } else if (!viaShizuku) {
                    log.append("Sin permiso USB ni Shizuku: usa Ajustes > Almacenamiento > Expulsar\n");
                }
                JSObject o = new JSObject();
                o.put("log", log.toString());
                o.put("shizuku", viaShizuku);
                return o;
            }
        });
    }

    // ===================================================================
    //  Volúmenes montados por Android + copia de la ISO como archivo
    // ===================================================================

    private String volumePath(StorageVolume v) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                File d = v.getDirectory();
                return d == null ? null : d.getAbsolutePath();
            }
            Method gp = StorageVolume.class.getMethod("getPath");
            return (String) gp.invoke(v);
        } catch (Exception e) {
            return null;
        }
    }

    @PluginMethod
    public void listVolumes(PluginCall call) {
        JSArray arr = new JSArray();
        StorageManager sm = (StorageManager) getContext().getSystemService(Context.STORAGE_SERVICE);
        if (sm != null) {
            for (StorageVolume v : sm.getStorageVolumes()) {
                if (!v.isRemovable()) continue;
                String path = volumePath(v);
                String state = v.getState();
                long free = -1;
                if (path != null && Environment.MEDIA_MOUNTED.equals(state)) {
                    try {
                        free = new StatFs(path).getAvailableBytes();
                    } catch (Exception ignored) {
                    }
                }
                String desc = v.getDescription(getContext());
                String uuid = v.getUuid();
                JSObject o = new JSObject();
                o.put("name", desc == null ? "" : desc);
                o.put("state", state == null ? "" : state);
                o.put("path", path == null ? "" : path);
                o.put("uuid", uuid == null ? "" : uuid);
                o.put("free", free);
                if (path != null && Environment.MEDIA_MOUNTED.equals(state)) arr.put(o);
            }
        }
        JSObject r = new JSObject();
        r.put("volumes", arr);
        call.resolve(r);
    }

    @PluginMethod
    public void copyIso(PluginCall call) {
        final String dir = call.getString("path", "");
        if (isoUri == null) {
            call.reject("Primero elige una ISO");
            return;
        }
        if (dir == null || dir.isEmpty()) {
            call.reject("Elige un volumen");
            return;
        }
        if (!hasAllFiles()) {
            call.reject("Falta el permiso «Acceso a todos los archivos»");
            return;
        }
        final Uri uri = isoUri;
        final long size = isoSize;
        final String name = isoName.replaceAll("[\\\\/:*?\"<>|]", "_");
        async(call, new Job() {
            @Override
            public JSObject run() throws Exception {
                File folder = new File(dir);
                if (!folder.isDirectory()) throw new IOException("El volumen ya no está montado");
                if (new StatFs(dir).getAvailableBytes() < size) {
                    throw new IOException("No hay espacio suficiente en el volumen");
                }
                File out = new File(folder, name);
                InputStream is = getContext().getContentResolver().openInputStream(uri);
                if (is == null) throw new IOException("No se pudo abrir la ISO");
                FileOutputStream os = new FileOutputStream(out);
                boolean ok = false;
                try {
                    byte[] buf = new byte[1 << 20];
                    long done = 0, last = 0, t0 = System.currentTimeMillis();
                    int n;
                    while ((n = is.read(buf)) > 0) {
                        if (cancel) throw new IOException("Cancelado por el usuario");
                        os.write(buf, 0, n);
                        done += n;
                        long now = System.currentTimeMillis();
                        if (now - last > 300) {
                            last = now;
                            progress("copy", done, size, t0);
                        }
                    }
                    os.flush();
                    os.getFD().sync();
                    progress("copy", size, size, t0);
                    ok = true;
                } finally {
                    try {
                        is.close();
                    } catch (IOException ignored) {
                    }
                    try {
                        os.close();
                    } catch (IOException ignored) {
                    }
                    if (!ok) out.delete();
                }
                JSObject o = new JSObject();
                o.put("file", out.getAbsolutePath());
                return o;
            }
        });
    }

    // ===================================================================
    //  ISOs no híbridas (Windows): formatear en FAT32 y extraer los archivos
    // ===================================================================

    private WinIso.Info analyzeIso(Uri u) {
        ParcelFileDescriptor pfd = null;
        FileInputStream fis = null;
        try {
            pfd = getContext().getContentResolver().openFileDescriptor(u, "r");
            if (pfd == null) return new WinIso.Info();
            fis = new FileInputStream(pfd.getFileDescriptor());
            return WinIso.analyze(fis.getChannel());
        } catch (Exception e) {
            return new WinIso.Info();
        } finally {
            try {
                if (fis != null) fis.close();
            } catch (IOException ignored) {
            }
            try {
                if (pfd != null) pfd.close();
            } catch (IOException ignored) {
            }
        }
    }

    private WinIso.Ctl ctl(final String phase) {
        final long t0 = System.currentTimeMillis();
        return new WinIso.Ctl() {
            private long last = 0;

            @Override
            public boolean cancelled() {
                return cancel;
            }

            @Override
            public void progress(long done, long total) {
                long now = System.currentTimeMillis();
                if (now - last > 300 || done >= total) {
                    last = now;
                    IsoUsbPlugin.this.progress(phase, done, total, t0);
                }
            }
        };
    }

    @PluginMethod
    public void extractIso(PluginCall call) {
        final String id = call.getString("id", "");
        final boolean gpt = "gpt".equals(call.getString("scheme", "mbr"));
        String l = call.getString("label", "WINDOWS");
        l = l == null ? "" : l.replaceAll("[^A-Za-z0-9 _-]", "").toUpperCase(Locale.US);
        if (l.length() > 11) l = l.substring(0, 11);
        if (l.trim().isEmpty()) l = "WINDOWS";
        final String label = l;
        if (isoUri == null) {
            call.reject("Primero elige una ISO");
            return;
        }
        final Uri uri = isoUri;
        async(call, new Job() {
            @Override
            public JSObject run() throws Exception {
                ParcelFileDescriptor pfd = null;
                FileInputStream fis = null;
                try {
                    pfd = getContext().getContentResolver().openFileDescriptor(uri, "r");
                    if (pfd == null) throw new IOException("No se pudo abrir la ISO");
                    fis = new FileInputStream(pfd.getFileDescriptor());
                    FileChannel ch = fis.getChannel();
                    // primero se lee la ISO: si falla, el USB no se toca
                    WinIso.FNode root = WinIso.buildTree(new WinIso.Udf(ch));
                    Msc m = open(id);
                    try {
                        WinIso.Result r = WinIso.extract(m, root, gpt, label, ctl("extract"));
                        JSObject o = new JSObject();
                        o.put("files", r.files);
                        o.put("bytes", r.bytes);
                        o.put("parts", r.parts);
                        return o;
                    } finally {
                        m.close();
                    }
                } finally {
                    try {
                        if (fis != null) fis.close();
                    } catch (IOException ignored) {
                    }
                    try {
                        if (pfd != null) pfd.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        });
    }

    // ===================================================================
    //  Utilidades
    // ===================================================================

    private void async(final PluginCall call, final Job job) {
        if (busy) {
            call.reject("Hay otra operación en curso");
            return;
        }
        busy = true;
        cancel = false;
        acquireWake();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSObject r = job.run();
                    call.resolve(r == null ? new JSObject() : r);
                } catch (Throwable t) {
                    call.reject(msg(t));
                } finally {
                    busy = false;
                    releaseWake();
                }
            }
        }, "isousb-job").start();
    }

    private void acquireWake() {
        try {
            PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "isousb:job");
            wake.acquire(4 * 60 * 60 * 1000L);
        } catch (Exception ignored) {
        }
        keepScreen(true);
    }

    private void releaseWake() {
        try {
            if (wake != null && wake.isHeld()) wake.release();
        } catch (Exception ignored) {
        }
        keepScreen(false);
    }

    private void keepScreen(final boolean on) {
        final Activity a = getActivity();
        if (a == null) return;
        a.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (on) a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        });
    }

    private void progress(String phase, long done, long total, long t0) {
        JSObject o = new JSObject();
        o.put("phase", phase);
        o.put("done", done);
        o.put("total", total);
        double secs = Math.max(0.001, (System.currentTimeMillis() - t0) / 1000.0);
        o.put("mbps", done / 1048576.0 / secs);
        notifyListeners("progress", o);
    }

    private static int readFully(InputStream is, byte[] b, int len) throws IOException {
        int n = 0;
        while (n < len) {
            int r = is.read(b, n, len - n);
            if (r < 0) break;
            n += r;
        }
        return n;
    }

    private static String msg(Throwable t) {
        String m = t.getMessage();
        return m != null ? m : t.getClass().getSimpleName();
    }

    private static String fmt(long b) {
        if (b >= (1L << 30)) return String.format(Locale.US, "%.2f GB", b / 1073741824.0);
        if (b >= (1L << 20)) return String.format(Locale.US, "%.1f MB", b / 1048576.0);
        return b + " B";
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(Locale.US, "%02x", x));
        return s.toString();
    }

    // ===================================================================
    //  USB Mass Storage: protocolo Bulk-Only (BOT) + comandos SCSI
    // ===================================================================

    static class ScsiError extends IOException {
        ScsiError(String m) {
            super(m);
        }
    }

    static class Msc {
        final UsbDeviceConnection c;
        final UsbInterface itf;
        final UsbEndpoint epIn, epOut;
        int tag = 0x1000;
        int bs = 512;
        long blocks = 0;

        Msc(UsbManager mgr, UsbDevice d) throws IOException {
            UsbInterface fi = null;
            UsbEndpoint ei = null, eo = null;
            for (int i = 0; i < d.getInterfaceCount() && fi == null; i++) {
                UsbInterface it = d.getInterface(i);
                if (it.getInterfaceClass() != UsbConstants.USB_CLASS_MASS_STORAGE) continue;
                if (it.getInterfaceSubclass() != 6 || it.getInterfaceProtocol() != 0x50) continue;
                UsbEndpoint a = null, b = null;
                for (int k = 0; k < it.getEndpointCount(); k++) {
                    UsbEndpoint e = it.getEndpoint(k);
                    if (e.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                    if (e.getDirection() == UsbConstants.USB_DIR_IN) a = e;
                    else b = e;
                }
                if (a != null && b != null) {
                    fi = it;
                    ei = a;
                    eo = b;
                }
            }
            if (fi == null) throw new IOException("Este USB no usa el protocolo Bulk-Only (BOT)");
            UsbDeviceConnection cc = mgr.openDevice(d);
            if (cc == null) throw new IOException("No se pudo abrir el USB (¿falta el permiso?)");
            if (!cc.claimInterface(fi, true)) {
                cc.close();
                throw new IOException("No se pudo reclamar la interfaz USB");
            }
            c = cc;
            itf = fi;
            epIn = ei;
            epOut = eo;
        }

        void init() throws IOException {
            boolean ready = false;
            for (int i = 0; i < 15 && !ready; i++) {
                try {
                    cmd(new byte[6], 0, false, null, 0);       // TEST UNIT READY
                    ready = true;
                } catch (IOException e) {
                    try {
                        Thread.sleep(400);
                    } catch (InterruptedException ie) {
                        throw new IOException("Interrumpido");
                    }
                }
            }
            byte[] cap = new byte[8];
            cmd(new byte[]{0x25, 0, 0, 0, 0, 0, 0, 0, 0, 0}, 8, true, cap, 0);   // READ CAPACITY(10)
            long last = ((cap[0] & 0xFFL) << 24) | ((cap[1] & 0xFFL) << 16) | ((cap[2] & 0xFFL) << 8) | (cap[3] & 0xFFL);
            long size = ((cap[4] & 0xFFL) << 24) | ((cap[5] & 0xFFL) << 16) | ((cap[6] & 0xFFL) << 8) | (cap[7] & 0xFFL);
            if (last == 0xFFFFFFFFL) throw new IOException("USB de más de 2 TB: no soportado");
            if (size < 512 || size > 65536) throw new IOException("Tamaño de bloque inválido: " + size);
            bs = (int) size;
            blocks = last + 1;
        }

        void write(long lba, byte[] buf, int off, int n) throws IOException {
            byte[] cdb = {0x2A, 0, (byte) (lba >> 24), (byte) (lba >> 16), (byte) (lba >> 8), (byte) lba,
                    0, (byte) (n >> 8), (byte) n, 0};
            cmd(cdb, n * bs, false, buf, off);
        }

        void read(long lba, byte[] buf, int off, int n) throws IOException {
            byte[] cdb = {0x28, 0, (byte) (lba >> 24), (byte) (lba >> 16), (byte) (lba >> 8), (byte) lba,
                    0, (byte) (n >> 8), (byte) n, 0};
            cmd(cdb, n * bs, true, buf, off);
        }

        void sync() {
            try {
                cmd(new byte[]{0x35, 0, 0, 0, 0, 0, 0, 0, 0, 0}, 0, false, null, 0);   // SYNCHRONIZE CACHE
            } catch (IOException ignored) {
            }
        }

        void eject() {
            try {
                cmd(new byte[]{0x1B, 0, 0, 0, 0x02, 0}, 0, false, null, 0);            // START STOP UNIT (LoEj)
            } catch (IOException ignored) {
            }
        }

        void close() {
            try {
                c.releaseInterface(itf);
            } catch (Exception ignored) {
            }
            c.close();
        }

        int cmd(byte[] cdb, int len, boolean dirIn, byte[] buf, int off) throws IOException {
            return cmd0(cdb, len, dirIn, buf, off, true);
        }

        private int cmd0(byte[] cdb, int len, boolean dirIn, byte[] buf, int off, boolean wantSense) throws IOException {
            byte[] w = new byte[31];
            ByteBuffer b = ByteBuffer.wrap(w).order(ByteOrder.LITTLE_ENDIAN);
            int t = ++tag;
            b.putInt(0x43425355).putInt(t).putInt(len);                                   // 'USBC', tag, longitud
            b.put((byte) (dirIn ? 0x80 : 0x00)).put((byte) 0).put((byte) cdb.length).put(cdb);
            xfer(epOut, w, 0, 31);

            IOException dataErr = null;
            if (len > 0) {
                UsbEndpoint ep = dirIn ? epIn : epOut;
                try {
                    xfer(ep, buf, off, len);
                } catch (IOException e) {
                    dataErr = e;
                    clearHalt(ep);
                }
            }
            byte[] s = new byte[13];
            try {
                xfer(epIn, s, 0, 13);
            } catch (IOException e) {
                clearHalt(epIn);
                xfer(epIn, s, 0, 13);
            }
            ByteBuffer sb = ByteBuffer.wrap(s).order(ByteOrder.LITTLE_ENDIAN);
            if (sb.getInt(0) != 0x53425355 || sb.getInt(4) != t) {                          // 'USBS'
                recover();
                throw new IOException("Respuesta USB inválida (CSW)");
            }
            int status = s[12] & 0xFF;
            if (status == 0) {
                if (dataErr != null) throw dataErr;
                return len - sb.getInt(8);
            }
            if (status == 2) {
                recover();
                throw new IOException("Error de fase USB");
            }
            throw new ScsiError("SCSI 0x" + Integer.toHexString(cdb[0] & 0xFF) + " falló"
                    + (wantSense ? " (" + sense() + ")" : ""));
        }

        private String sense() {
            try {
                byte[] r = new byte[18];
                cmd0(new byte[]{0x03, 0, 0, 0, 18, 0}, 18, true, r, 0, false);              // REQUEST SENSE
                int key = r[2] & 0x0F, asc = r[12] & 0xFF, ascq = r[13] & 0xFF;
                String extra = key == 7 ? ", protegido contra escritura" : key == 2 ? ", sin medio o no listo" : "";
                return String.format(Locale.US, "sense %02X/%02X/%02X%s", key, asc, ascq, extra);
            } catch (IOException e) {
                return "sin sense";
            }
        }

        private void xfer(UsbEndpoint ep, byte[] buf, int off, int len) throws IOException {
            int done = 0, zeros = 0;
            while (done < len) {
                int n = c.bulkTransfer(ep, buf, off + done, Math.min(len - done, 16384), 30000);
                if (n < 0) throw new IOException("Falló la transferencia USB");
                if (n == 0) {
                    if (++zeros > 5) throw new IOException("El USB no responde");
                } else {
                    done += n;
                    zeros = 0;
                }
            }
        }

        private void clearHalt(UsbEndpoint ep) {
            c.controlTransfer(0x02, 0x01, 0, ep.getAddress(), null, 0, 2000);
        }

        private void recover() {
            c.controlTransfer(0x21, 0xFF, 0, itf.getId(), null, 0, 2000);                    // Bulk-Only Reset
            clearHalt(epIn);
            clearHalt(epOut);
        }
    }
}
