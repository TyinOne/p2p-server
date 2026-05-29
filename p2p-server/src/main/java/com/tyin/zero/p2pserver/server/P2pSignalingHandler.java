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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * P2P 信令处理器
 * 处理 NAT 发现、P2P 连接请求、打洞协调
 */
@Slf4j
@Component
public class P2pSignalingHandler {

    private final P2pRegistry registry;

    /**
     * 已尝试 TCP 打洞的对端组合（"peerA:peerB" 格式，排序后存储）
     * 用于在 TCP 打洞也失败后回退到中继模式
     */
    private final Set<String> tcpPunchAttempted = ConcurrentHashMap.newKeySet();

    /**
     * 待处理的 P2P 请求（peerId → 等待列表）
     * 当 peer 不存在时，request 请求会被存入此处
     * 当 peer 上线并发送 binding 时，触发这些请求
     */
    private final Map<String, ConcurrentLinkedQueue<PendingRequest>> pendingRequests = new ConcurrentHashMap<>();

    public record PendingRequest(String requesterId, int remotePort, ChannelHandlerContext requesterCtx, long timestamp) {}

    public P2pSignalingHandler(P2pRegistry registry) {
        this.registry = registry;
    }

    private static final long PENDING_REQUEST_TIMEOUT_MS = 300_000; // 5 分钟超时
    private static final int MAX_PENDING_REQUESTS_PER_PEER = 10; // 每个 peer 最大 pending 请求数

    private static String pairKey(String a, String b) {
        return a.compareTo(b) < 0 ? a + ":" + b : b + ":" + a;
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
        // 清理与该客户端相关的 TCP 打洞记录
        tcpPunchAttempted.removeIf(key -> key.contains(clientId));
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

        int tcpPort = (msg.getTcpPort() != null && msg.getTcpPort() > 0)
                ? msg.getTcpPort() : 0;

        registry.updatePublicAddress(clientId, publicAddr, localAddr, tcpPort);

        TunnelMessage response = new TunnelMessage();
        response.setType(TunnelMessage.MessageType.P2P_BINDING_RESPONSE);
        response.setClientId(clientId);
        response.setCandidateAddr(publicAddr);
        ctx.writeAndFlush(response);

        log.info("P2P binding for {}: public={}, local={}, udpPort={}, tcpPort={}",
                clientId, publicAddr, localAddr, msg.getUdpPort(), tcpPort);

        // 检查是否有等待此 client 的 P2P 请求，如有则触发
        ConcurrentLinkedQueue<PendingRequest> waiting = pendingRequests.remove(clientId);
        if (waiting != null && !waiting.isEmpty()) {
            log.info("Found {} pending P2P requests for {}, triggering...", waiting.size(), clientId);
            long now = System.currentTimeMillis();
            for (PendingRequest req; (req = waiting.poll()) != null; ) {
                // 检查是否超时
                if (now - req.timestamp() > PENDING_REQUEST_TIMEOUT_MS) {
                    log.debug("Skipping expired pending request from {}", req.requesterId());
                    continue;
                }
                // 检查 requester 是否仍然在线
                if (req.requesterCtx() != null && req.requesterCtx().channel().isActive()) {
                    // 重新构造 P2P_REQUEST 并处理
                    TunnelMessage requestMsg = new TunnelMessage();
                    requestMsg.setType(TunnelMessage.MessageType.P2P_REQUEST);
                    requestMsg.setClientId(req.requesterId());
                    requestMsg.setPeerId(clientId);
                    requestMsg.setRemotePort(req.remotePort());
                    handleRequest(req.requesterCtx(), requestMsg);
                } else {
                    log.debug("Skipping stale pending request from {}", req.requesterId());
                }
            }
        }
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
            // peer 不在线，将请求加入等待队列（限制数量防止攻击）
            ConcurrentLinkedQueue<PendingRequest> queue = pendingRequests.computeIfAbsent(peerId, k -> new ConcurrentLinkedQueue<>());
            if (queue.size() >= MAX_PENDING_REQUESTS_PER_PEER) {
                log.warn("Too many pending requests for peer {}, rejecting request from {}", peerId, requesterId);
                sendError(ctx, requesterId, "Too many pending requests for peer: " + peerId);
                return;
            }
            queue.add(new PendingRequest(requesterId, remotePort, ctx, System.currentTimeMillis()));
            log.info("P2P request queued: {} waiting for peer {} to come online", requesterId, peerId);
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

        // TCP 打洞优先尝试
        String requesterTcpAddr = getTcpAddress(requesterId);
        String peerTcpAddr = getTcpAddress(peerId);

        if (requesterTcpAddr != null && peerTcpAddr != null) {
            log.info("P2P TCP punch initiated between {} ({}) and {} ({})",
                    requesterId, requesterTcpAddr, peerId, peerTcpAddr);
            // 标记 TCP 已尝试（后续 P2P_FAILED 直接回退中继）
            tcpPunchAttempted.add(requesterId.compareTo(peerId) < 0
                    ? requesterId + ":" + peerId : peerId + ":" + requesterId);
            // 通知双方开始 TCP 打洞
            sendTcpPunchStart(ctx.channel(), requesterId, peerId, peerTcpAddr);
            sendTcpPunchStart(peerChannel, peerId, requesterId, requesterTcpAddr);
        } else {
            log.warn("TCP addresses not available for {} ↔ {}, falling back to UDP punch",
                    requesterId, peerId);
            // 回退到 UDP 打洞
            sendHolePunch(ctx.channel(), requesterId, peerId);
            sendHolePunch(peerChannel, peerId, requesterId);
        }
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
     * 先尝试 TCP 打洞，TCP 打洞也失败后回退到中继模式
     */
    public void handleFailed(ChannelHandlerContext ctx, TunnelMessage msg) {
        String clientId = msg.getClientId();
        String peerId = msg.getPeerId();
        String pairKey = pairKey(clientId, peerId);

        // 如果还没尝试过 TCP 打洞，先尝试
        if (tcpPunchAttempted.add(pairKey)) {
            String requesterTcpAddr = getTcpAddress(clientId);
            String peerTcpAddr = getTcpAddress(peerId);

            if (requesterTcpAddr != null && peerTcpAddr != null) {
                log.warn("P2P UDP failed: {} ↔ {}, trying TCP punch", clientId, peerId);
                Channel requesterChannel = ctx.channel();
                Channel peerChannel = registry.getChannel(peerId);

                if (requesterChannel != null && requesterChannel.isActive()) {
                    sendTcpPunchStart(requesterChannel, clientId, peerId, peerTcpAddr);
                }
                if (peerChannel != null && peerChannel.isActive()) {
                    sendTcpPunchStart(peerChannel, peerId, clientId, requesterTcpAddr);
                }
                return;
            }
            log.warn("TCP addresses not available for {} ↔ {}, falling back to relay", clientId, peerId);
        }

        // TCP 打洞也失败了（或不可用），回退到中继模式
        log.warn("P2P all direct methods failed: {} ↔ {}, falling back to relay", clientId, peerId);
        tcpPunchAttempted.remove(pairKey);

        Channel requesterChannel = ctx.channel();
        Channel peerChannel = registry.getChannel(peerId);

        if (requesterChannel != null && requesterChannel.isActive()) {
            sendRelayReady(requesterChannel, clientId, peerId);
        }
        if (peerChannel != null && peerChannel.isActive()) {
            sendRelayReady(peerChannel, peerId, clientId);
        }
    }

    /**
     * 获取客户端的 TCP 打洞地址（公网IP:TCP监听端口）
     */
    private String getTcpAddress(String clientId) {
        String publicAddr = registry.getPublicAddress(clientId);
        int tcpPort = registry.getTcpPort(clientId);
        if (publicAddr == null || tcpPort <= 0) return null;

        int colonIdx = publicAddr.lastIndexOf(':');
        if (colonIdx <= 0) return null;
        return publicAddr.substring(0, colonIdx) + ":" + tcpPort;
    }

    private void sendRelayReady(Channel channel, String targetId, String peerId) {
        TunnelMessage msg = new TunnelMessage();
        msg.setType(TunnelMessage.MessageType.RELAY_READY);
        msg.setClientId(targetId);
        msg.setPeerId(peerId);
        channel.writeAndFlush(msg);
        log.info("Sent RELAY_READY to {} for peer {}", targetId, peerId);
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

    private void sendTcpPunchStart(Channel channel, String targetId, String peerId, String peerTcpAddr) {
        TunnelMessage msg = new TunnelMessage();
        msg.setType(TunnelMessage.MessageType.TCP_PUNCH_START);
        msg.setClientId(targetId);
        msg.setPeerId(peerId);
        msg.setCandidateAddr(peerTcpAddr);
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
