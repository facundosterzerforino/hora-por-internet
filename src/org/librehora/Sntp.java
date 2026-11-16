package org.librehora;

import android.os.SystemClock;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/** Minimal SNTP client (RFC 4330), one request per call. */
final class Sntp {
    private static final int PORT = 123;
    private static final long SECONDS_1900_TO_1970 = 2208988800L;

    /** Result of one query: how far the phone clock is from the server. */
    static final class Answer {
        final String server;
        final long offsetMs;    // server time - phone time
        final long roundTripMs;
        Answer(String server, long offsetMs, long roundTripMs) {
            this.server = server;
            this.offsetMs = offsetMs;
            this.roundTripMs = roundTripMs;
        }
    }

    private Sntp() {
    }

    static Answer query(String host, int timeoutMs) throws Exception {
        InetAddress address = InetAddress.getByName(host);
        DatagramSocket socket = new DatagramSocket();
        try {
            socket.setSoTimeout(timeoutMs);
            byte[] buf = new byte[48];
            buf[0] = 0x23; // LI 0, version 4, mode 3 (client)
            long sendWall = System.currentTimeMillis();
            long sendTick = SystemClock.elapsedRealtime();
            writeTime(buf, 40, sendWall); // transmit timestamp, echoed as originate
            socket.send(new DatagramPacket(buf, buf.length, address, PORT));

            DatagramPacket reply = new DatagramPacket(new byte[48], 48);
            socket.receive(reply);
            long recvTick = SystemClock.elapsedRealtime();
            long recvWall = sendWall + (recvTick - sendTick); // immune to clock jumps
            byte[] r = reply.getData();
            if (reply.getLength() < 48) throw new Exception("short reply");

            int leap = (r[0] >> 6) & 3, mode = r[0] & 7, stratum = r[1] & 0xff;
            if (leap == 3 || mode != 4 || stratum < 1 || stratum > 15)
                throw new Exception("bad reply (leap " + leap + ", mode " + mode + ", stratum " + stratum + ")");
            for (int i = 24; i < 32; i++)
                if (r[i] != buf[i + 16]) throw new Exception("reply does not match the request");

            long serverRecv = readTime(r, 32), serverSend = readTime(r, 40);
            if (serverSend == 0) throw new Exception("empty server time");
            long roundTrip = (recvTick - sendTick) - (serverSend - serverRecv);
            long offset = ((serverRecv - sendWall) + (serverSend - recvWall)) / 2;
            return new Answer(host, offset, roundTrip);
        } finally {
            socket.close();
        }
    }

    private static long readTime(byte[] b, int off) {
        long seconds = read32(b, off), fraction = read32(b, off + 4);
        return (seconds - SECONDS_1900_TO_1970) * 1000 + (fraction * 1000L) / 0x100000000L;
    }

    private static long read32(byte[] b, int off) {
        return ((long) (b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16)
                | ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
    }

    private static void writeTime(byte[] b, int off, long ms) {
        long seconds = ms / 1000 + SECONDS_1900_TO_1970;
        long fraction = (ms % 1000) * 0x100000000L / 1000;
        for (int i = 0; i < 4; i++) {
            b[off + i] = (byte) (seconds >> (24 - 8 * i));
            b[off + 4 + i] = (byte) (fraction >> (24 - 8 * i));
        }
    }
}
