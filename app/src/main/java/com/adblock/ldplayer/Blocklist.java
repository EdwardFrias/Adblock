package com.adblock.ldplayer;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.HashSet;

/**
 * Lista de dominios bloqueados + lista de excepciones. Java puro (sin Android) para poder probarlo.
 *
 * Un dominio se bloquea si él o cualquiera de sus dominios padre está en la lista
 * (ads.example.com se bloquea si "example.com" está en la lista), sin llegar nunca al TLD solo.
 */
final class Blocklist {

    private static volatile HashSet<String> blocked = new HashSet<String>();
    private static volatile HashSet<String> allowed = new HashSet<String>();

    private Blocklist() {
    }

    static int size() {
        return blocked.size();
    }

    static void replace(HashSet<String> set) {
        blocked = set;
    }

    private static volatile HashSet<String> custom = new HashSet<String>();

    /** Dominios separados por espacios, comas, punto y coma o saltos de línea. */
    static HashSet<String> toSet(String text) {
        HashSet<String> s = new HashSet<String>();
        if (text != null) {
            for (String t : text.split("[\\s,;]+")) {
                String d = normalize(t);
                if (d != null) {
                    s.add(d);
                }
            }
        }
        return s;
    }

    /** Excepciones: nunca se bloquean (ni ellas ni sus subdominios). */
    static void setAllow(String text) {
        allowed = toSet(text);
    }

    /** Bloqueos propios del usuario, además de la lista descargada. */
    static void setCustom(String text) {
        custom = toSet(text);
    }

    /** Las excepciones tienen prioridad sobre los bloqueos propios y sobre la lista. */
    static boolean isBlocked(String name) {
        HashSet<String> a = allowed;
        if (!a.isEmpty() && matchesChain(a, name)) {
            return false;
        }
        HashSet<String> c = custom;
        if (!c.isEmpty() && matchesChain(c, name)) {
            return true;
        }
        return matchesChain(blocked, name);
    }

    private static boolean matchesChain(HashSet<String> set, String name) {
        String n = name;
        while (true) {
            if (set.contains(n)) {
                return true;
            }
            int dot = n.indexOf('.');
            if (dot < 0) {
                return false;
            }
            n = n.substring(dot + 1);
            if (n.indexOf('.') < 0) {
                return false; // solo queda el TLD
            }
        }
    }

    private static boolean isBlockIp(String s) {
        return s.equals("0.0.0.0") || s.equals("127.0.0.1") || s.equals("::") || s.equals("::1");
    }

    /** Devuelve el dominio en minúsculas, o null si no es un nombre de host válido. */
    static String normalize(String t) {
        if (t == null) {
            return null;
        }
        String d = t.trim().toLowerCase(java.util.Locale.US);
        if (d.endsWith(".")) {
            d = d.substring(0, d.length() - 1);
        }
        if (d.isEmpty() || d.indexOf('.') < 0 || d.length() > 253) {
            return null;
        }
        boolean allDigitsDots = true;
        for (int i = 0; i < d.length(); i++) {
            char c = d.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_';
            if (!ok) {
                return null; // sintaxis de otras listas (||dominio^, rutas, IPv6...)
            }
            if (!((c >= '0' && c <= '9') || c == '.')) {
                allDigitsDots = false;
            }
        }
        return allDigitsDots ? null : d; // descarta direcciones IP
    }

    /**
     * Lee un fichero en formato hosts ("0.0.0.0 dominio [dominio2...]") o una lista simple con un
     * dominio por línea. Ignora comentarios (#) y líneas que no apuntan a 0.0.0.0/127.0.0.1.
     */
    static HashSet<String> parse(BufferedReader r) throws IOException {
        HashSet<String> out = new HashSet<String>(1 << 16);
        String line;
        while ((line = r.readLine()) != null) {
            int h = line.indexOf('#');
            if (h >= 0) {
                line = line.substring(0, h);
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] tok = line.split("\\s+");
            int from;
            if (tok.length == 1) {
                from = 0;
            } else if (isBlockIp(tok[0])) {
                from = 1;
            } else {
                continue;
            }
            for (int i = from; i < tok.length; i++) {
                String d = normalize(tok[i]);
                if (d != null && !d.equals("localhost.localdomain")) { // cabecera típica de hosts
                    out.add(d);
                }
            }
        }
        return out;
    }
}
