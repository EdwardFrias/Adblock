package com.adblock.ldplayer;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;

/** Descarga la lista de dominios publicitarios (formato hosts) y la activa. */
final class ListUpdater {

    /** Lista pública "Unified hosts" de StevenBlack (anuncios y malware). */
    static final String URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts";

    private ListUpdater() {
    }

    /** Bloqueante: llamar desde un hilo secundario. Devuelve el número de dominios. */
    static int update(Context c) throws IOException {
        File tmp = new File(c.getFilesDir(), "hosts.tmp");
        File dst = new File(c.getFilesDir(), "hosts.txt");

        HttpURLConnection h = (HttpURLConnection) new URL(URL).openConnection();
        h.setConnectTimeout(15000);
        h.setReadTimeout(30000);
        try {
            if (h.getResponseCode() != 200) {
                throw new IOException("HTTP " + h.getResponseCode());
            }
            InputStream in = h.getInputStream();
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                byte[] b = new byte[16384];
                int n;
                while ((n = in.read(b)) > 0) {
                    out.write(b, 0, n);
                }
            } finally {
                out.close();
                in.close();
            }
        } finally {
            h.disconnect();
        }

        HashSet<String> set;
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(tmp), "UTF-8"));
        try {
            set = Blocklist.parse(r);
        } finally {
            r.close();
        }
        if (set.size() < 1000) {
            tmp.delete();
            throw new IOException("La lista descargada no es válida (" + set.size() + " dominios)");
        }
        dst.delete();
        if (!tmp.renameTo(dst)) {
            throw new IOException("No se pudo guardar la lista");
        }
        Blocklist.replace(set);
        return set.size();
    }
}
