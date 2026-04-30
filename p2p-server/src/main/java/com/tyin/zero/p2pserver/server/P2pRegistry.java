package com.tyin.zero.p2pserver.server;

import com.tyin.zero.p2pcommon.p2p.TunnelExposeConfig;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P2P 注册表
 * 维护端口→客户端映射和客户端→公网地址映射
 */
@Slf4j
@Component
public class P2pRegistry {

    /**
     * 端口注册表：remotePort → clientId
     * 客户端注册时动态填充
     */
    private final Map<Integer, String> portRegistry = new ConcurrentHashMap<>();

    /**
     * 客户端注册信息
     */
    private final Map<String, ClientInfo> clientRegistry = new ConcurrentHashMap<>();

    public record ClientInfo(
            String clientId,
            Channel channel,
            String publicAddress,
            String localAddress,
            List<TunnelExposeConfig> tunnels,
            int tcpPort
    ) {}

    /**
     * 注册客户端
     */
    public void registerClient(String clientId, Channel channel, List<TunnelExposeConfig> tunnels) {
        InetSocketAddress addr = (InetSocketAddress) channel.remoteAddress();
        String publicAddr = addr.getHostString() + ":" + addr.getPort();

        ClientInfo info = new ClientInfo(clientId, channel, publicAddr, null, tunnels, 0);
        clientRegistry.put(clientId, info);

        if (tunnels != null) {
            for (TunnelExposeConfig tunnel : tunnels) {
                portRegistry.put(tunnel.getRemotePort(), clientId);
                log.info("Registered tunnel: port {} → client {} ({}:{})",
                        tunnel.getRemotePort(), clientId,
                        tunnel.getLocalAddress(), tunnel.getLocalPort());
            }
        }

        log.info("Client {} registered, public address: {}", clientId, publicAddr);
    }

    /**
     * 注销客户端
     */
    public void unregisterClient(String clientId) {
        ClientInfo info = clientRegistry.remove(clientId);
        if (info != null && info.tunnels() != null) {
            for (TunnelExposeConfig tunnel : info.tunnels()) {
                portRegistry.remove(tunnel.getRemotePort());
            }
        }
        log.info("Client {} unregistered", clientId);
    }

    /**
     * 更新客户端公网地址和局域网地址
     */
    public void updatePublicAddress(String clientId, String publicAddress, String localAddress, int tcpPort) {
        ClientInfo old = clientRegistry.get(clientId);
        if (old != null) {
            clientRegistry.put(clientId, new ClientInfo(
                    clientId, old.channel(), publicAddress, localAddress, old.tunnels(), tcpPort));
            log.info("Updated public address for {}: {} (local: {}, tcpPort: {})",
                    clientId, publicAddress, localAddress, tcpPort);
        }
    }

    /**
     * 根据端口查找客户端 ID
     */
    public String findClientByPort(int remotePort) {
        return portRegistry.get(remotePort);
    }

    /**
     * 获取客户端信息
     */
    public ClientInfo getClient(String clientId) {
        return clientRegistry.get(clientId);
    }

    /**
     * 获取客户端公网地址
     */
    public String getPublicAddress(String clientId) {
        ClientInfo info = clientRegistry.get(clientId);
        return info != null ? info.publicAddress() : null;
    }

    /**
     * 获取客户端 TCP 端口
     */
    public int getTcpPort(String clientId) {
        ClientInfo info = clientRegistry.get(clientId);
        return info != null ? info.tcpPort() : 0;
    }

    /**
     * 获取客户端的 Channel
     */
    public Channel getChannel(String clientId) {
        ClientInfo info = clientRegistry.get(clientId);
        return info != null ? info.channel() : null;
    }

    /**
     * 获取所有已注册的客户端 ID
     */
    public java.util.Set<String> getAllClientIds() {
        return clientRegistry.keySet();
    }
}
