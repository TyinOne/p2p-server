package com.tyin.zero.p2pcommon.p2p;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

/**
 * P2P UDP 数据报编解码
 * 极简二进制格式：1B type + 4B sessionId + N bytes payload
 */
public final class P2pUdpCodec {

    public static final byte TYPE_DATA = 0x01;
    public static final byte TYPE_HEARTBEAT = 0x02;
    public static final byte TYPE_HOLE_PUNCH = 0x03;

    private P2pUdpCodec() {}

    /**
     * 编码数据包
     */
    public static ByteBuf encodeData(ByteBufAllocator alloc, int sessionId, byte[] payload) {
        ByteBuf buf = alloc.buffer(5 + payload.length);
        buf.writeByte(TYPE_DATA);
        buf.writeInt(sessionId);
        buf.writeBytes(payload);
        return buf;
    }

    /**
     * 编码心跳包
     */
    public static ByteBuf encodeHeartbeat(ByteBufAllocator alloc, int sessionId) {
        ByteBuf buf = alloc.buffer(5);
        buf.writeByte(TYPE_HEARTBEAT);
        buf.writeInt(sessionId);
        return buf;
    }

    /**
     * 编码打洞包
     */
    public static ByteBuf encodeHolePunch(ByteBufAllocator alloc) {
        ByteBuf buf = alloc.buffer(5);
        buf.writeByte(TYPE_HOLE_PUNCH);
        buf.writeInt(0); // sessionId unused for hole punch
        return buf;
    }

    /**
     * 解码结果
     */
    public record Decoded(byte type, int sessionId, byte[] payload) {}

    /**
     * 解码数据报
     */
    public static Decoded decode(ByteBuf buf) {
        if (buf.readableBytes() < 5) {
            throw new IllegalArgumentException("UDP packet too short: " + buf.readableBytes());
        }
        byte type = buf.readByte();
        int sessionId = buf.readInt();
        byte[] payload = new byte[buf.readableBytes()];
        buf.readBytes(payload);
        return new Decoded(type, sessionId, payload);
    }
}
