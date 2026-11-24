package com.adblock.ldplayer;

import android.app.Notification;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * VPN local que SOLO captura el tráfico hacia un DNS virtual (10.111.222.2). El resto del tráfico
 * de Android no entra en el túnel. Cada consulta DNS se contesta aquí (si el dominio está en la
 * lista) con 0.0.0.0, o se reenvía al DNS real por un socket protegido (fuera del túnel).
 */
public class BlockerVpnService extends VpnService {

    public static final String ACTION_START = "com.adblock.ldplayer.START";
    public static final String ACTION_STOP = "com.adblock.ldplayer.STOP";

    private static final String VPN_ADDR = "10.111.222.1";
    private static final String DNS_IP = "10.111.222.2";
    private static final byte[] DNS_ADDR = {10, 111, (byte) 222, 2};
    private static final String[] UPSTREAMS = {"1.1.1.1", "8.8.8.8"};
    private static final int NOTIF_ID = 1;
    static final String PREFS = "adblock";

    // Estado visible desde la actividad (mismo proceso). Los contadores solo los escribe un hilo.
    public static volatile boolean running = false;
    public static volatile String status = "Detenido";
    public static volatile int total = 0;
    public static volatile int blocked = 0;

    private volatile boolean stopping = false;
    private volatile ParcelFileDescriptor tun;
    private volatile ThreadPoolExecutor pool;
    private Thread loop;
    private InetAddress[] upstream;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopVpn("Detenido");
            return START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, notification("Iniciando…"));
        if (loop != null) {
            return START_STICKY;
        }
        stopping = false;
        total = 0;
        blocked = 0;
        status = "Cargando lista…";
        loop = new Thread(this::run, "adblock-loop");
        loop.start();
        return START_STICKY;
    }

    private Notification notification(String text) {
        return Notif.build(this, "AdBlock", text);
    }

    private void run() {
        try {
            upstream = new InetAddress[UPSTREAMS.length];
            for (int i = 0; i < UPSTREAMS.length; i++) {
                upstream[i] = InetAddress.getByName(UPSTREAMS[i]); // son IPs literales: sin DNS
            }
            loadList();

            Builder b = new Builder()
                    .setSession("AdBlock")
                    .setMtu(1500)
                    .setBlocking(true)
                    .addAddress(VPN_ADDR, 32)
                    .addRoute(DNS_IP, 32)       // solo el DNS virtual entra en el túnel
                    .addDnsServer(DNS_IP);
            ParcelFileDescriptor t = b.establish();
            if (t == null) {
                status = "No se pudo crear la VPN (permiso no concedido o revocado)";
                finish();
                return;
            }
            tun = t;

            ThreadPoolExecutor p = new ThreadPoolExecutor(8, 8, 20, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<Runnable>(512),
                    new ThreadPoolExecutor.DiscardPolicy());
            p.allowCoreThreadTimeOut(true);
            pool = p;

            running = true;
            status = "Activo · " + Blocklist.size() + " dominios en la lista";
            notifyText(status);

            final FileInputStream in = new FileInputStream(t.getFileDescriptor());
            final FileOutputStream out = new FileOutputStream(t.getFileDescriptor());
            byte[] buf = new byte[32767];
            while (!stopping) {
                int n = in.read(buf);
                if (n < 0) {
                    break;
                }
                if (n > 0) {
                    handle(buf, n, out);
                }
            }
        } catch (IOException e) {
            if (!stopping) {
                status = "Error: " + e.getMessage();
            }
        } catch (Throwable t) {
            status = "Error: " + t;
        } finally {
            finish();
        }
    }

    private void loadList() throws IOException {
        File f = new File(getFilesDir(), "hosts.txt");
        InputStream is = f.exists() ? new FileInputStream(f) : getAssets().open("hosts_builtin.txt");
        BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        try {
            HashSet<String> set = Blocklist.parse(r);
            Blocklist.replace(set);
        } finally {
            r.close();
        }
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        Blocklist.setAllow(sp.getString("allow", ""));
    }

    private void handle(byte[] buf, int n, FileOutputStream out) {
        IpUdp.Udp4 u = IpUdp.parseUdp4(buf, n);
        if (u == null || u.dstPort != 53 || !IpUdp.addrEquals(buf, 16, DNS_ADDR)) {
            return; // el túnel solo recibe DNS; cualquier otra cosa se ignora
        }
        final byte[] dns = Arrays.copyOfRange(buf, u.payloadOff, u.payloadOff + u.payloadLen);
        final byte[] src = Arrays.copyOfRange(buf, 12, 16);
        final int srcPort = u.srcPort;

        total++;
        DnsPacket.Query q = DnsPacket.parse(dns, dns.length);
        if (q != null && Blocklist.isBlocked(q.name)) {
            blocked++;
            byte[] resp = DnsPacket.blockedResponse(dns, q);
            write(out, IpUdp.buildUdp4(DNS_ADDR, 53, src, srcPort, resp, resp.length));
            return;
        }
        final ThreadPoolExecutor p = pool;
        if (p != null) {
            p.execute(() -> forward(dns, src, srcPort, out));
        }
    }

    /** Reenvía la consulta al DNS real. El socket se protege para que NO entre en nuestra VPN. */
    private void forward(byte[] dns, byte[] src, int srcPort, FileOutputStream out) {
        for (InetAddress up : upstream) {
            if (stopping) {
                return;
            }
            DatagramSocket s = null;
            try {
                s = new DatagramSocket();
                if (!protect(s)) {
                    continue;
                }
                s.setSoTimeout(3000);
                s.send(new DatagramPacket(dns, dns.length, up, 53));
                byte[] rb = new byte[1472]; // MTU 1500 - IP 20 - UDP 8
                DatagramPacket rp = new DatagramPacket(rb, rb.length);
                s.receive(rp);
                write(out, IpUdp.buildUdp4(DNS_ADDR, 53, src, srcPort, rb, rp.getLength()));
                return;
            } catch (IOException ignored) {
                // tiempo agotado o red caída: prueba el siguiente servidor
            } finally {
                if (s != null) {
                    s.close();
                }
            }
        }
    }

    private void write(FileOutputStream out, byte[] pkt) {
        try {
            synchronized (out) {
                out.write(pkt);
            }
        } catch (IOException ignored) {
            // túnel cerrado
        }
    }

    private void notifyText(String text) {
        android.app.NotificationManager nm =
                (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID, notification(text));
        }
    }

    /** Se llama desde el hilo principal (parar) o desde el hilo de trabajo (fin/fallo). */
    private synchronized void finish() {
        running = false;
        stopping = true;
        ThreadPoolExecutor p = pool;
        pool = null;
        if (p != null) {
            p.shutdownNow();
        }
        ParcelFileDescriptor t = tun;
        tun = null;
        if (t != null) {
            try {
                t.close();
            } catch (IOException ignored) {
                // ya cerrado
            }
        }
        loop = null;
        stopForeground(true);
        stopSelf();
    }

    private void stopVpn(String why) {
        stopping = true;
        status = why;
        finish();
    }

    @Override
    public void onRevoke() {
        stopVpn("Detenido: otra VPN o el sistema revocó el permiso");
    }

    @Override
    public void onDestroy() {
        stopping = true;
        running = false;
        ParcelFileDescriptor t = tun;
        tun = null;
        if (t != null) {
            try {
                t.close();
            } catch (IOException ignored) {
                // ya cerrado
            }
        }
        ThreadPoolExecutor p = pool;
        if (p != null) {
            p.shutdownNow();
        }
        super.onDestroy();
    }
}
