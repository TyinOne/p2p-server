package com.tyin.zero.p2pcommon.protocol;

import lombok.Data;

/**
 * P2P 隧道协议消息
 */
@Data
public class TunnelMessage {

    /**
     * 消息类型
     */
    private MessageType type;

    /**
     * 客户端ID
     */
    private String clientId;

    /**
     * 认证密钥
     */
    private String auth;

    /**
     * 数据负载
     */
    private byte[] payload;

    /**
     * 时间戳
     */
    private Long timestamp;

    /**
     * P2P 对端的 clientId
     */
    private String peerId;

    /**
     * P2P 候选地址（公网地址 "ip:port"）
     */
    private String candidateAddr;

    /**
     * 隧道的公开端口
     */
    private Integer remotePort;

    /**
     * 本地服务端口
     */
    private Integer localPort;

    /**
     * 本地服务地址
     */
    private String localAddress;

    /**
     * P2P UDP 端口（客户端 binding 时上报）
     */
    private Integer udpPort;

    /**
     * P2P TCP 端口（客户端 binding 时上报，用于 TCP 打洞）
     */
    private Integer tcpPort;

    /**
     * 客户端局域网 IP（binding 时上报，用于同 NAT 直连）
     */
    private String localAddr;

    /**
     * 暴露的隧道列表（JSON 序列化，注册时携带）
     */
    private String tunnelsJson;

    /**
     * 隧道会话 ID（中继数据时使用）
     */
    private Integer sessionId;

    /**
     * 消息类型枚举
     */
    public enum MessageType {
        // 客户端注册
        REGISTER,
        // 注册响应
        REGISTER_RESPONSE,
        // 心跳
        HEARTBEAT,
        // 心跳响应
        HEARTBEAT_RESPONSE,
        // 错误
        ERROR,
        // P2P NAT 发现
        P2P_BINDING,
        P2P_BINDING_RESPONSE,
        // P2P 连接建立
        P2P_REQUEST,
        P2P_CANDIDATE,
        P2P_HOLE_PUNCH,
        P2P_SUCCESS,
        P2P_FAILED,
        // TCP 打洞
        TCP_PUNCH,
        TCP_PUNCH_START,
        // 服务端中继
        RELAY_READY,
        RELAY_DATA,
        // 对端离线通知
        PEER_OFFLINE
    }
}
