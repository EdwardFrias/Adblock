package com.adblock.ldplayer;

/** Lectura de consultas DNS y construcción de respuestas "bloqueado". Java puro. */
final class DnsPacket {

    static final class Query {
        String name;      // en minúsculas, sin punto final
        int type;         // 1 = A, 28 = AAAA...
        int qclass;       // 1 = IN
        int questionEnd;  // índice justo después de la sección de pregunta
    }

    private DnsPacket() {
    }

    /** Devuelve null si no es una consulta estándar con una única pregunta. */
    static Query parse(byte[] d, int len) {
        if (len < 17) {
            return null;
        }
        int flags = ((d[2] & 0xff) << 8) | (d[3] & 0xff);
        if ((flags & 0x8000) != 0) {
            return null; // es una respuesta
        }
        if (((flags >> 11) & 0xf) != 0) {
            return null; // no es QUERY estándar
        }
        int qd = ((d[4] & 0xff) << 8) | (d[5] & 0xff);
        if (qd != 1) {
            return null;
        }
        int p = 12;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (p >= len) {
                return null;
            }
            int l = d[p++] & 0xff;
            if (l == 0) {
                break;
            }
            if ((l & 0xc0) != 0 || p + l > len) {
                return null; // compresión o etiqueta fuera de rango
            }
            if (sb.length() > 0) {
                sb.append('.');
            }
            for (int i = 0; i < l; i++) {
                char c = (char) (d[p + i] & 0xff);
                if (c >= 'A' && c <= 'Z') {
                    c += 32;
                }
                sb.append(c);
            }
            p += l;
            if (sb.length() > 253) {
                return null;
            }
        }
        if (p + 4 > len || sb.length() == 0) {
            return null;
        }
        Query q = new Query();
        q.name = sb.toString();
        q.type = ((d[p] & 0xff) << 8) | (d[p + 1] & 0xff);
        q.qclass = ((d[p + 2] & 0xff) << 8) | (d[p + 3] & 0xff);
        q.questionEnd = p + 4;
        return q;
    }

    /**
     * Respuesta NOERROR: A -> 0.0.0.0, AAAA -> ::, cualquier otro tipo -> sin respuestas.
     * Se descartan las secciones adicionales (EDNS) para que el paquete sea mínimo y válido.
     */
    static byte[] blockedResponse(byte[] query, Query q) {
        boolean a = q.qclass == 1 && q.type == 1;
        boolean aaaa = q.qclass == 1 && q.type == 28;
        int rdLen = a ? 4 : (aaaa ? 16 : 0);
        int ansLen = rdLen == 0 ? 0 : 12 + rdLen;
        byte[] r = new byte[q.questionEnd + ansLen];
        System.arraycopy(query, 0, r, 0, q.questionEnd);

        int qflags = ((query[2] & 0xff) << 8) | (query[3] & 0xff);
        int flags = 0x8000 | (qflags & 0x0100) | 0x0080; // QR, copia de RD, RA, RCODE=0
        r[2] = (byte) (flags >> 8);
        r[3] = (byte) flags;
        r[6] = 0;
        r[7] = (byte) (rdLen == 0 ? 0 : 1); // ANCOUNT
        r[8] = 0;
        r[9] = 0;                           // NSCOUNT
        r[10] = 0;
        r[11] = 0;                          // ARCOUNT

        if (rdLen != 0) {
            int o = q.questionEnd;
            r[o] = (byte) 0xC0;
            r[o + 1] = 0x0C;                // puntero al nombre de la pregunta
            r[o + 2] = (byte) (q.type >> 8);
            r[o + 3] = (byte) q.type;
            r[o + 4] = 0;
            r[o + 5] = 1;                   // clase IN
            r[o + 6] = 0;
            r[o + 7] = 0;
            r[o + 8] = 0x01;
            r[o + 9] = 0x2C;                // TTL 300 s
            r[o + 10] = 0;
            r[o + 11] = (byte) rdLen;
            // rdata = ceros (0.0.0.0 / ::)
        }
        return r;
    }
}
