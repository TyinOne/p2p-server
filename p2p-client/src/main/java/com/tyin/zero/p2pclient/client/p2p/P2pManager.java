package com.tyin.zero.p2pclient.client.p2p;

import com.tyin.zero.p2pclient.config.ClientConfig;
import com.tyin.zero.p2pcommon.p2p.P2pSession;
import com.tyin.zero.p2pcommon.p2p.P2pUdpCodec;
import com.tyin.zero.p2pcommon.protocol.TunnelMessage;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * P2P 管理器
 * 客户端 P2P 的主控制器，协调信令、打洞和数据通道
 */
@Slf4j
@Component
public class P2pManager implements P2pUdpChannel.P2pDataHandler {

    private final ClientConfig clientConfig;

    private P2pUdpChannel udpChannel;
    private P2pHolePuncher holePuncher;
    private UpnpPortMapper upnpPortMapper;
    private int externalPort = -1;
    private ChannelHandlerContext serverCtx;

    /**
     * P2P 会话：peerId → P2pSession
     */
    private final Map<String, P2pSession> sessions = new ConcurrentHashMap<>();

    /**
     * 隧道会话：sessionId → 隧道处理器
     */
    private final Map<Integer, P2pTunnelHandler> tunnelHandlers = new ConcurrentHashMap<>();

    private final ScheduledExecutorService heartbeatScheduler =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofVirtual().name("p2p-heartbeat").factory());

    public P2pManager(ClientConfig clientConfig) {
        this.clientConfig = clientConfig;
    }

    @PostConstruct
    public void init() {
        if (!isP2pEnabled()) {
            log.info("P2P is disabled");
            return;
        }

        log.info("Initializing P2P manager...");
        udpChannel = new P2pUdpChannel(this);
        holePuncher = new P2pHolePuncher(udpChannel);

        int udpPort = clientConfig.getP2p().getUdpPort();
        udpChannel.bind(udpPort).addListener(f -> {
            if (f.isSuccess()) {
                int localPort = udpChannel.getLocalAddress().getPort();
                log.info("P2P UDP channel ready on port {}", localPort);

                // UPnP 端口映射
                if (clientConfig.getP2p().isUpnpEnabled()) {
                    upnpPortMapper = new UpnpPortMapper();
                    externalPort = upnpPortMapper.addMapping(localPort);
                    if (externalPort > 0) {
                        log.info("UPnP mapping active: external port {}", externalPort);
                    } else {
                        externalPort = localPort;
                        log.info("UPnP mapping not available, using local port {}", localPort);
                    }
                } else {
                    externalPort = localPort;
                }

                // 如果认证已完成，立即发送 binding 请求
                if (serverCtx != null) {
                    sendBindingRequest();
                }
            }
        });

        // 心跳定时任务
        long heartbeatMs = clientConfig.getP2p().getHeartbeatIntervalMs();
        heartbeatScheduler.scheduleWithFixedDelay(this::sendHeartbeats,
                heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 设置服务端信令 Channel
     */
    public void setServerContext(ChannelHandlerContext ctx) {
        this.serverCtx = ctx;
    }

    /**
     * 是否启用 P2P
     */
    public boolean isP2pEnabled() {
        return clientConfig.getP2p() != null && clientConfig.getP2p().getUdpPort() >= 0
                && (clientConfig.getTunnels() != null && !clientConfig.getTunnels().isEmpty()
                    || clientConfig.getConnect() != null && !clientConfig.getConnect().isEmpty());
    }

    /**
     * 发送 NAT binding 请求
     */
    public void sendBindingRequest() {
        if (serverCtx == null) return;

        // 等待 UDP 绑定和 UPnP 映射完成
        if (externalPort < 0) {
            log.warn("UDP channel not ready, delaying binding request");
            return;
        }

        TunnelMessage msg = new TunnelMessage();
        msg.setType(TunnelMessage.MessageType.P2P_BINDING);
        msg.setClientId(clientConfig.getClientId());
        msg.setUdpPort(externalPort);
        msg.setLocalAddr(getLocalIp());
        serverCtx.writeAndFlush(msg);
        log.info("Sent P2P binding request, UDP port: {}, local IP: {}",
                externalPort, msg.getLocalAddr());
    }

    private String getLocalIp() {
        try {
            return java.net.InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    /**
     * 处理 binding 响应（收到自己的公网地址）
     */
    public void handleBindingResponse(TunnelMessage msg) {
        String publicAddr = msg.getCandidateAddr();
        log.info("P2P binding response: public address = {}", publicAddr);

        // 为每个 connect 的对端发起 P2P 请求
        List<ClientConfig.PeerTunnel> connectList = clientConfig.getConnect();
        log.info("Connect list: size={}, isNull={}, connect={}",
                connectList == null ? "null" : connectList.size(),
                connectList == null,
                connectList);
        if (connectList != null) {
            for (ClientConfig.PeerTunnel peerTunnel : connectList) {
                log.info("Sending P2P request to peer: {}", peerTunnel.getPeerId());
                sendP2pRequest(peerTunnel.getPeerId(), peerTunnel.getRemotePort());
            }
        }
    }

    /**
     * 发起 P2P 连接请求
     */
    public void sendP2pRequest(String peerId, int remotePort) {
        if (serverCtx == null) return;

        P2pSession session = new P2pSession(peerId);
        sessions.put(peerId, session);

        TunnelMessage msg = new TunnelMessage();
        msg.setType(TunnelMessage.MessageType.P2P_REQUEST);
        msg.setClientId(clientConfig.getClientId());
        msg.setPeerId(peerId);
        msg.setRemotePort(remotePort);
        serverCtx.writeAndFlush(msg);

        log.info("Sent P2P request: myId={} → peerId={} (port {})", clientConfig.getClientId(), peerId, remotePort);
    }

    /**
     * 处理对端候选地址
     */
    public void handleCandidate(TunnelMessage msg) {
        String peerId = msg.getPeerId();
        String candidateAddr = msg.getCandidateAddr();

        log.info("Received candidate: myId={}, msgClientId={}, peerId={}, addr={}",
                clientConfig.getClientId(), msg.getClientId(), peerId, candidateAddr);

        P2pSession session = sessions.get(peerId);
        if (session == null) {
            session = new P2pSession(peerId);
            sessions.put(peerId, session);
        }

        String[] parts = candidateAddr.split(":");
        if (parts.length == 2) {
            String host = parts[0];
            int port = Integer.parseInt(parts[1]);
            session.setPeerAddress(new InetSocketAddress(host, port));
        }
    }

    /**
     * 处理打洞指令
     */
    public void handleHolePunch(TunnelMessage msg) {
        String peerId = msg.getPeerId();
        log.info("Handle hole punch: myId={}, msgClientId={}, peerId={}, sessions={}",
                clientConfig.getClientId(), msg.getClientId(), peerId, sessions.keySet());

        P2pSession session = sessions.get(peerId);

        if (session == null) {
            log.warn("Cannot start hole punch: no session for peer {} (myId={})", peerId, clientConfig.getClientId());
            sendP2pFailed(peerId);
            return;
        }

        if (session.getPeerAddress() == null) {
            log.warn("Cannot start hole punch: no peer address for {} (session state={})", peerId, session.getState());
            sendP2pFailed(peerId);
            return;
        }

        session.setState(P2pSession.State.PUNCHING);

        holePuncher.startPunching(
                session.getPeerAddress(),
                clientConfig.getP2p().getHolePunchIntervalMs(),
                clientConfig.getP2p().getHolePunchTimeoutMs(),
                () -> {
                    // 成功
                    session.setState(P2pSession.State.ESTABLISHED);
                    session.updateSeen();
                    sendP2pSuccess(peerId);
                    log.info("P2P channel established with {}", peerId);

                    // 启动对端隧道监听
                    onP2pEstablished(peerId, session);
                },
                () -> {
                    // 超时
                    session.setState(P2pSession.State.FAILED);
                    log.warn("P2P hole punch timeout: myId={}, peerId={}, peerAddr={}",
                            clientConfig.getClientId(), peerId, session.getPeerAddress());
                    sendP2pFailed(peerId);
                }
        );
    }

    /**
     * P2P 通道建立后的回调
     */
    private void onP2pEstablished(String peerId, P2pSession session) {
        // 找到对应的 connect 配置，启动本地 TCP 监听
        if (clientConfig.getConnect() != null) {
            for (ClientConfig.PeerTunnel peerTunnel : clientConfig.getConnect()) {
                if (peerId.equals(peerTunnel.getPeerId())) {
                    startLocalTcpListener(peerTunnel, session);
                }
            }
        }
    }

    private final Map<String, P2pTcpListener> tcpListeners = new ConcurrentHashMap<>();

    /**
     * 启动本地 TCP 监听（供用户连接）
     */
    private void startLocalTcpListener(ClientConfig.PeerTunnel peerTunnel, P2pSession session) {
        String peerId = peerTunnel.getPeerId();
        if (tcpListeners.containsKey(peerId)) {
            log.info("TCP listener for peer {} already started", peerId);
            return;
        }

        P2pTcpListener listener = new P2pTcpListener(peerTunnel, this,
                peerId2 -> hasP2pChannel(peerId2));
        listener.start();
        tcpListeners.put(peerId, listener);
    }

    /**
     * 发送 P2P 成功通知
     */
    private void sendP2pSuccess(String peerId) {
        if (serverCtx == null) return;

        TunnelMessage msg = new TunnelMessage();
        msg.setType(TunnelMessage.MessageType.P2P_SUCCESS);
        msg.setClientId(clientConfig.getClientId());
        msg.setPeerId(peerId);
        serverCtx.writeAndFlush(msg);
    }

    /**
     * 发送 P2P 失败通知
     */
    private void sendP2pFailed(String peerId) {
        if (serverCtx == null) return;

        log.error(">>> sendP2pFailed: myId={}, peerId={}, sessions={}, sessionStates={}",
                clientConfig.getClientId(), peerId, sessions.keySet(),
                sessions.entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                java.util.Map.Entry::getKey,
                                e -> e.getValue().getState().name())));

        TunnelMessage msg = new TunnelMessage();
        msg.setType(TunnelMessage.MessageType.P2P_FAILED);
        msg.setClientId(clientConfig.getClientId());
        msg.setPeerId(peerId);
        serverCtx.writeAndFlush(msg);
    }

    /**
     * 获取 P2P 会话
     */
    public P2pSession getSession(String peerId) {
        return sessions.get(peerId);
    }

    /**
     * 检查是否有到对端的 P2P 通道
     */
    public boolean hasP2pChannel(String peerId) {
        P2pSession session = sessions.get(peerId);
        return session != null && session.getState() == P2pSession.State.ESTABLISHED;
    }

    /**
     * 通过 P2P 发送数据
     */
    public void sendData(String peerId, int sessionId, byte[] payload) {
        P2pSession session = sessions.get(peerId);
        if (session == null || session.getState() != P2pSession.State.ESTABLISHED) {
            log.warn("No P2P channel to {}, cannot send data", peerId);
            return;
        }
        udpChannel.sendData(session.getPeerAddress(), sessionId, payload);
        session.updateSeen();
    }

    /**
     * 注册隧道处理器
     */
    public void registerTunnelHandler(int sessionId, P2pTunnelHandler handler) {
        tunnelHandlers.put(sessionId, handler);
    }

    /**
     * 注销隧道处理器
     */
    public void unregisterTunnelHandler(int sessionId) {
        tunnelHandlers.remove(sessionId);
    }

    // --- P2pDataHandler 实现 ---

    @Override
    public void onDataReceived(InetSocketAddress sender, int sessionId, byte[] payload) {
        // 查找对端会话并更新
        for (P2pSession session : sessions.values()) {
            if (session.getPeerAddress() != null
                    && session.getPeerAddress().getAddress().equals(sender.getAddress())
                    && session.getPeerAddress().getPort() == sender.getPort()) {
                session.updateSeen();
                break;
            }
        }

        // 分发到隧道处理器
        P2pTunnelHandler handler = tunnelHandlers.get(sessionId);
        if (handler != null) {
            handler.onData(payload);
        } else if (!clientConfig.getTunnels().isEmpty()) {
            // tunnels 端：收到新 sessionId，连接本地服务
            connectToLocalService(sessionId, payload);
        } else {
            log.debug("No handler for P2P session {}", sessionId);
        }
    }

    /**
     * tunnels 端：收到新 sessionId 时，连接本地服务并设置桥接
     * 使用第一个隧道配置的 localAddress:localPort
     */
    private void connectToLocalService(int sessionId, byte[] firstPayload) {
        if (clientConfig.getTunnels().isEmpty()) {
            log.warn("No tunnel config, cannot connect to local service");
            return;
        }

        // 使用第一个隧道配置
        ClientConfig.TunnelExpose tunnel = clientConfig.getTunnels().get(0);
        String host = tunnel.getLocalAddress();
        int port = tunnel.getLocalPort();

        log.info("P2P: new session {}, connecting to local service {}:{}", sessionId, host, port);

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory()))
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new LocalServiceP2pBridgeHandler(sessionId));
                    }
                });

        bootstrap.connect(host, port).addListener((ChannelFutureListener) f -> {
            if (f.isSuccess()) {
                Channel localChannel = f.channel();
                log.info("P2P: connected to local service {}:{} for session {}", host, port, sessionId);

                // 注册处理器：P2P 数据 → 本地服务
                registerTunnelHandler(sessionId, payload -> {
                    if (localChannel.isActive()) {
                        ByteBuf buf = localChannel.alloc().buffer(payload.length);
                        buf.writeBytes(payload);
                        localChannel.writeAndFlush(buf);
                    }
                });

                // 写入第一个包（已经在缓冲区）
                if (firstPayload != null && firstPayload.length > 0 && localChannel.isActive()) {
                    ByteBuf buf = localChannel.alloc().buffer(firstPayload.length);
                    buf.writeBytes(firstPayload);
                    localChannel.writeAndFlush(buf);
                }
            } else {
                log.error("P2P: failed to connect to local service {}:{} for session {}",
                        host, port, sessionId, f.cause());
            }
        });
    }

    /**
     * 本地服务桥接处理器（tunnels 端）
     * 本地服务响应 → P2P UDP 发送给对端
     */
    private class LocalServiceP2pBridgeHandler extends io.netty.channel.SimpleChannelInboundHandler<ByteBuf> {
        private final int sessionId;

        LocalServiceP2pBridgeHandler(int sessionId) {
            this.sessionId = sessionId;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
            byte[] data = new byte[msg.readableBytes()];
            msg.readBytes(data);

            // 找到对端并通过 P2P 发送
            for (P2pSession session : sessions.values()) {
                if (session.getState() == P2pSession.State.ESTABLISHED
                        && session.getPeerAddress() != null) {
                    udpChannel.sendData(session.getPeerAddress(), sessionId, data);
                    break;
                }
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            log.info("Local service connection closed for P2P session {}", sessionId);
            unregisterTunnelHandler(sessionId);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("Local service bridge error for session {}: {}", sessionId, cause.getMessage());
            ctx.close();
        }
    }

    @Override
    public void onHeartbeatReceived(InetSocketAddress sender, int sessionId) {
        for (P2pSession session : sessions.values()) {
            if (session.getPeerAddress() != null
                    && session.getPeerAddress().getAddress().equals(sender.getAddress())
                    && session.getPeerAddress().getPort() == sender.getPort()) {
                session.updateSeen();
                // 回复心跳
                udpChannel.sendHeartbeat(sender, sessionId);
                break;
            }
        }
    }

    @Override
    public void onHolePunchReceived(InetSocketAddress sender) {
        log.info("Hole punch packet received from {}", sender);
        // 收到打洞包，回复一个打洞包（让对端也知道打洞成功）
        udpChannel.sendHolePunch(sender);
        // 通知打洞器
        holePuncher.onHolePunchReceived(sender);
    }

    /**
     * 发送心跳到所有活跃会话
     */
    private void sendHeartbeats() {
        for (P2pSession session : sessions.values()) {
            if (session.getState() == P2pSession.State.ESTABLISHED
                    && session.getPeerAddress() != null) {
                udpChannel.sendHeartbeat(session.getPeerAddress(), 0);

                // 检查会话超时
                if (session.isExpired(clientConfig.getP2p().getSessionTimeoutMs())) {
                    log.warn("P2P session with {} expired", session.getPeerId());
                    session.setState(P2pSession.State.FAILED);
                }
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down P2P manager...");
        heartbeatScheduler.shutdownNow();
        if (upnpPortMapper != null) upnpPortMapper.deleteMapping();
        if (holePuncher != null) holePuncher.shutdown();
        if (udpChannel != null) udpChannel.shutdown();
    }

    /**
     * 隧道数据处理器接口
     */
    public interface P2pTunnelHandler {
        void onData(byte[] payload);
    }
}
