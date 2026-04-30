package com.tyin.zero.p2pcommon.p2p;

import lombok.Data;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * P2P 会话状态
 * 表示与一个对端的 P2P 连接
 */
@Data
public class P2pSession {

    public enum State {
        DISCOVERING,
        PUNCHING,
        ESTABLISHED,
        FAILED
    }

    private final String peerId;
    private volatile State state = State.DISCOVERING;
    private volatile InetSocketAddress peerAddress;
    private volatile long lastSeen = System.currentTimeMillis();

    /**
     * 隧道会话映射：sessionId → 本地 TCP Channel
     * 每个活跃的 TCP 连接对应一个会话
     */
    private final ConcurrentMap<Integer, Object> tunnelSessions = new ConcurrentHashMap<>();

    public P2pSession(String peerId) {
        this.peerId = peerId;
    }

    public void updateSeen() {
        this.lastSeen = System.currentTimeMillis();
    }

    public boolean isExpired(long timeoutMs) {
        return System.currentTimeMillis() - lastSeen > timeoutMs;
    }
}
