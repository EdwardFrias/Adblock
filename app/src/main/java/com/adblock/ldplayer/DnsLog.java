package com.adblock.ldplayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registro de los últimos dominios consultados (sin repetidos, el más reciente primero). Sirve para
 * descubrir qué dominio sirve un anuncio y bloquearlo a mano. Java puro.
 */
final class DnsLog {

    static final class Entry {
        final String name;
        final int count;
        final boolean blocked;

        Entry(String name, int count, boolean blocked) {
            this.name = name;
            this.count = count;
            this.blocked = blocked;
        }
    }

    private static final int MAX = 400;

    private static final class Val {
        int count;
        boolean blocked;
    }

    // orden de acceso: al consultar de nuevo un dominio pasa a ser el más reciente
    private static final LinkedHashMap<String, Val> map =
            new LinkedHashMap<String, Val>(512, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Val> eldest) {
                    return size() > MAX;
                }
            };

    private DnsLog() {
    }

    static synchronized void record(String name, boolean blocked) {
        Val v = map.get(name);
        if (v == null) {
            v = new Val();
            map.put(name, v);
        }
        v.count++;
        v.blocked = blocked;
    }

    /** Copia ordenada del más reciente al más antiguo. */
    static synchronized List<Entry> snapshot() {
        ArrayList<Entry> out = new ArrayList<Entry>(map.size());
        for (Map.Entry<String, Val> e : map.entrySet()) {
            out.add(new Entry(e.getKey(), e.getValue().count, e.getValue().blocked));
        }
        java.util.Collections.reverse(out); // el LinkedHashMap lista del más antiguo al más nuevo
        return out;
    }

    static synchronized void clear() {
        map.clear();
    }
}
