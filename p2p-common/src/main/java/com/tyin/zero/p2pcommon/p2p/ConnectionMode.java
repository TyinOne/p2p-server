package com.tyin.zero.p2pcommon.p2p;

/**
 * P2P 连接模式
 */
public enum ConnectionMode {
    /**
     * 中继模式（服务器转发）
     */
    RELAY,

    /**
     * TCP 直连模式
     */
    TCP_DIRECT,

    /**
     * UDP 直连模式
     */
    UDP_DIRECT,

    /**
     * 通用直连模式（TCP 或 UDP）
     * 用于直连成功但具体协议未确定的情况
     */
    P2P_DIRECT
}