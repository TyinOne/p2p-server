package com.tyin.zero.p2pserver.server;

import com.tyin.zero.p2pcommon.protocol.TunnelMessage;
import com.tyin.zero.p2pcommon.p2p.TunnelExposeConfig;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * P2P 信令处理器
 * 处理 NAT 发现、P2P 连接请求、打洞协调
 */
@Slf4j
@Component
public class P2pSignalingHandler {

    private final P2pRegistry registry;

    public P2pSignalingHandler(P2pRegistry registry) {
        this.registry = registry;
    }

    /**
     * 处理客户端注册（提取暴露的隧道信息）
     */
    public void handleRegister(String clientId, Channel channel, List<TunnelExposeConfig> tunnels) {
        registry.registerClient(clientId, channel, tunnels);
    }

    /**
     * 处理客户端注销
     */
    public void handleUnregister(String clientId) {
        registry.unregisterClient(clientId);
    }

    /**
     * 处理 NAT binding 请求
     * 客户端通过此消息获取自己的公网地址
     */
    public void handleBinding(ChannelHandlerContext ctx, TunnelMessage msg) {
        String clientId = msg.getClientId();
        InetSocketAddress addr = (InetSocketAddress) ctx.channel().remoteAddress();

        // 优先使用客户端上报的 UDP 端口，回退到 TCP 源端口
        int port = (msg.getUdpPort() != null && msg.getUdpPort() > 0)
                ? msg.getUdpPort() : addr.getPort();
        String publicAddr = addr.getHostString() + ":" + port;
        String localAddr = msg.getLocalAddr();
        if (localAddr != null && port > 0) {
            localAddr = localAddr + ":" + port;
        }

        registry.updatePublicAddress(clientId, publicAddr, localAddr);

        TunnelMessage response = new TunnelMessage();
        response.setType(TunnelMessage.MessageType.P2P_BINDING_RESPONSE);
        response.setClientId(clientId);
        response.setCandidateAddr(publicAddr);
        ctx.writeAndFlush(response);

        log.info("P2P binding for {}: public={}, local={}, udpPort={}",
                clientId, publicAddr, localAddr, msg.getUdpPort());
    }

    /**
     * 处理 P2P 连接请求
     * 客户端 A 请求连接客户端 B 的某个隧道端口
     */
    public void handleRequest(ChannelHandlerContext ctx, TunnelMessage msg) {
        String requesterId = msg.getClientId();
        String peerId = msg.getPeerId();
        int remotePort = msg.getRemotePort() != null ? msg.getRemotePort() : 0;

        log.info("P2P request from {} to peer {} (port {}), registered clients: {}",
                requesterId, peerId, remotePort, registry.getAllClientIds());

        // 查找对端
        P2pRegistry.ClientInfo peerInfo = registry.getClient(peerId);
        if (peerInfo == null) {
            sendError(ctx, requesterId, "Peer not found: " + peerId);
            return;
        }

        Channel peerChannel = peerInfo.channel();
        if (peerChannel == null || !peerChannel.isActive()) {
            sendError(ctx, requesterId, "Peer not connected: " + peerId);
            return;
        }

        String requesterAddr = registry.getPublicAddress(requesterId);
        String peerAddr = registry.getPublicAddress(peerId);

        if (requesterAddr == null || peerAddr == null) {
            sendError(ctx, requesterId, "Public address not available, send P2P_BINDING first");
            return;
        }

        // 向双方发送对方的公网候选地址
        sendCandidate(ctx.channel(), requesterId, peerId, peerAddr);
        sendCandidate(peerChannel, peerId, requesterId, requesterAddr);

        // 通知双方开始打洞
        sendHolePunch(ctx.channel(), requesterId, peerId);
        sendHolePunch(peerChannel, peerId, requesterId);

        log.info("P2P hole punch initiated between {} ({}) and {} ({})",
                requesterId, requesterAddr, peerId, peerAddr);
    }

    /**
     * 处理 P2P 成功通知
     */
    public void handleSuccess(ChannelHandlerContext ctx, TunnelMessage msg) {
        String clientId = msg.getClientId();
        String peerId = msg.getPeerId();
        log.info("P2P channel established: {} ↔ {}", clientId, peerId);
    }

    /**
     * 处理 P2P 失败通知
     */
    public void handleFailed(ChannelHandlerContext ctx, TunnelMessage msg) {
        String clientId = msg.getClientId();
        String peerId = msg.getPeerId();
        log.warn("P2P channel failed: {} ↔ {}, falling back to relay", clientId, peerId);
    }

    private void sendCandidate(Channel channel, String targetId, String peerId, String candidateAddr) {
        TunnelMessage msg = new TunnelMessage();
        msg.setType(TunnelMessage.MessageType.P2P_CANDIDATE);
        msg.setClientId(targetId);
        msg.setPeerId(peerId);
        msg.setCandidateAddr(candidateAddr);
        channel.writeAndFlush(msg);
    }

    private void sendHolePunch(Channel channel, String targetId, String peerId) {
        TunnelMessage msg = new TunnelMessage();
        msg.setType(TunnelMessage.MessageType.P2P_HOLE_PUNCH);
        msg.setClientId(targetId);
        msg.setPeerId(peerId);
        channel.writeAndFlush(msg);
    }

    private void sendError(ChannelHandlerContext ctx, String clientId, String errorMessage) {
        TunnelMessage msg = new TunnelMessage();
        msg.setType(TunnelMessage.MessageType.P2P_FAILED);
        msg.setClientId(clientId);
        msg.setPayload(errorMessage.getBytes(StandardCharsets.UTF_8));
        ctx.writeAndFlush(msg);
        log.warn("P2P error for {}: {}", clientId, errorMessage);
    }
}
