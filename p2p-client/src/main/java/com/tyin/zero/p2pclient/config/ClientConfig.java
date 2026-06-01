package com.tyin.zero.p2pclient.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端配置属性
 */
@Data
@ConfigurationProperties(prefix = "p2p.client")
public class ClientConfig {

    /**
     * 认证密钥（与服务端一致）
     */
    private String auth;

    /**
     * 服务端地址
     */
    private String serverHost = "127.0.0.1";

    /**
     * 服务端端口
     */
    private int port = 8084;

    /**
     * 客户端ID（可选，默认自动生成）
     */
    private String clientId;

    /**
     * P2P 配置
     */
    private P2pConfig p2p = new P2pConfig();

    /**
     * 暴露的隧道列表（本机提供的服务，供对端连接）
     */
    private List<TunnelExpose> tunnels = new ArrayList<>();

    /**
     * 要连接的对端隧道列表
     */
    private List<PeerTunnel> connect = new ArrayList<>();

    @Data
    public static class P2pConfig {
        /**
         * P2P 通道端口（TCP/UDP 共用）
         */
        private int p2pPort = 0;

        /**
         * 是否启用 UPnP 自动端口映射
         */
        private boolean upnpEnabled = false;

        /**
         * TCP 打洞超时（毫秒）
         */
        private long tcpHolePunchTimeoutMs = 8000;

        /**
         * UDP 打洞超时（毫秒）
         */
        private long holePunchTimeoutMs = 10000;

        /**
         * HolePunch 发送间隔（毫秒）
         */
        private long holePunchIntervalMs = 500;

        /**
         * UDP 心跳间隔（毫秒）
         */
        private long heartbeatIntervalMs = 15000;

        /**
         * 会话超时（毫秒）
         */
        private long sessionTimeoutMs = 60000;

        /**
         * 是否启用并行连接（RELAY 立即启动 + TCP/UDP 后台打洞）
         */
        private boolean parallelConnectEnabled = true;

        /**
         * 直连重试间隔（毫秒）
         */
        private long directConnectRetryIntervalMs = 30000;

        /**
         * 直连成功后是否自动切换
         */
        private boolean autoSwitchToDirect = true;

        /**
         * RELAY 是否保持备份
         */
        private boolean keepRelayAsBackup = true;

        /**
         * 活跃连接时是否切换到直连（保守策略）
         */
        private boolean switchDuringActiveConnection = false;
    }

    @Data
    public static class TunnelExpose {
        /**
         * 公开端口（信令服务器上注册的端口）
         */
        private int remotePort;

        /**
         * 本地服务端口
         */
        private int localPort;

        /**
         * 本地服务地址
         */
        private String localAddress = "127.0.0.1";

        /**
         * 描述
         */
        private String description;
    }

    @Data
    public static class PeerTunnel {
        /**
         * 对端客户端 ID
         */
        private String peerId;

        /**
         * 对端暴露的公开端口
         */
        private int remotePort;

        /**
         * 本地监听端口（用户通过此端口访问对端服务）
         */
        private int localBindPort;

        /**
         * 描述
         */
        private String description;
    }
}
