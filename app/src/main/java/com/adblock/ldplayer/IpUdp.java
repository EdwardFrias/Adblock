package com.adblock.ldplayer;

/** Lectura y construcción de paquetes IPv4/UDP crudos (lo que llega por el túnel). Java puro. */
final class IpUdp {

    static final class Udp4 {
        int srcPort;
        int dstPort;
        int payloadOff;
        int payloadLen;
    }

    private IpUdp() {
    }

    /** Devuelve null si no es un datagrama UDP IPv4 completo (sin fragmentar). */
    static Udp4 parseUdp4(byte[] p, int len) {
        if (len < 28 || ((p[0] & 0xff) >> 4) != 4) {
            return null;
        }
        int ihl = (p[0] & 0x0f) * 4;
        if (ihl < 20 || len < ihl + 8 || (p[9] & 0xff) != 17) {
            return null;
        }
        int total = ((p[2] & 0xff) << 8) | (p[3] & 0xff);
        if (total > len || total < ihl + 8) {
            return null;
        }
        if ((((p[6] & 0x3f) << 8) | (p[7] & 0xff)) != 0) {
            return null; // fragmento
        }
        int udpLen = ((p[ihl + 4] & 0xff) << 8) | (p[ihl + 5] & 0xff);
        if (udpLen < 8 || ihl + udpLen > total) {
            return null;
        }
        Udp4 u = new Udp4();
        u.srcPort = ((p[ihl] & 0xff) << 8) | (p[ihl + 1] & 0xff);
        u.dstPort = ((p[ihl + 2] & 0xff) << 8) | (p[ihl + 3] & 0xff);
        u.payloadOff = ihl + 8;
        u.payloadLen = udpLen - 8;
        return u;
    }

    static boolean addrEquals(byte[] pkt, int off, byte[] addr) {
        for (int i = 0; i < 4; i++) {
            if (pkt[off + i] != addr[i]) {
                return false;
            }
        }
        return true;
    }

    /** Suma de comprobación de 16 bits en complemento a uno (cabecera IPv4). */
    static int checksum(byte[] b, int off, int len) {
        long sum = 0;
        int i = 0;
        for (; i + 1 < len; i += 2) {
            sum += ((b[off + i] & 0xff) << 8) | (b[off + i + 1] & 0xff);
        }
        if (i < len) {
            sum += (b[off + i] & 0xff) << 8;
        }
        while ((sum >> 16) != 0) {
            sum = (sum & 0xffff) + (sum >> 16);
        }
        return (int) (~sum & 0xffff);
    }

    /** Paquete IPv4/UDP completo. La suma UDP puede ser 0 en IPv4 (campo opcional). */
    static byte[] buildUdp4(byte[] src, int srcPort, byte[] dst, int dstPort,
                            byte[] payload, int payloadLen) {
        int total = 20 + 8 + payloadLen;
        byte[] o = new byte[total];
        o[0] = 0x45;
        o[2] = (byte) (total >> 8);
        o[3] = (byte) total;
        o[6] = 0x40;            // DF
        o[8] = 64;              // TTL
        o[9] = 17;              // UDP
        System.arraycopy(src, 0, o, 12, 4);
        System.arraycopy(dst, 0, o, 16, 4);
        int c = checksum(o, 0, 20);
        o[10] = (byte) (c >> 8);
        o[11] = (byte) c;

        o[20] = (byte) (srcPort >> 8);
        o[21] = (byte) srcPort;
        o[22] = (byte) (dstPort >> 8);
        o[23] = (byte) dstPort;
        int udpLen = 8 + payloadLen;
        o[24] = (byte) (udpLen >> 8);
        o[25] = (byte) udpLen;
        System.arraycopy(payload, 0, o, 28, payloadLen);
        return o;
    }
}
