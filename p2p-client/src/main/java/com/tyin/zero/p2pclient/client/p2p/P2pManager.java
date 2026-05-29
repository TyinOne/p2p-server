package com.tyin.zero.p2pclient.client.p2p;

import com.tyin.zero.p2pclient.config.ClientConfig;
import com.tyin.zero.p2pcommon.p2p.P2pSession;
import com.tyin.zero.p2pcommon.p2p.P2pUdpCodec;
import com.tyin.zero.p2pcommon.protocol.TunnelMessage;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
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

    // TCP 打洞
    private int tcpListenPort = -1;
    private EventLoopGroup tcpGroup;
    private Channel tcpServerChannel;
    private final Map<String, Channel> tcpPeerChannels = new ConcurrentHashMap<>();
    // TCP 打洞预期对端地址：InetSocketAddress → peerId
    private final Map<String, String> tcpPendingPeers = new ConcurrentHashMap<>();

    /**
     * P2P 会话：peerId → P2pSession
     */
    private final Map<String, P2pSession> sessions = new ConcurrentHashMap<>();

    /**
     * 中继模式的对端集合
     */
    private final java.util.Set<String> relayPeers = ConcurrentHashMap.newKeySet();

    /**
     * 隧道会话：sessionId → 隧道处理器
     */
    private final Map<Integer, P2pTunnelHandler> tunnelHandlers = new ConcurrentHashMap<>();

    /**
     * 会话归属：sessionId → peerId
     */
    private final Map<Integer, String> sessionPeerMap = new ConcurrentHashMap<>();

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

        int p2pPort = clientConfig.getP2p().getP2pPort();
        udpChannel.bind(p2pPort).addListener(f -> {
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

                // 绑定 TCP 监听（用于 TCP 打洞），绑定完成后发送 binding
                bindTcpServer(() -> {
                    // 如果认证已完成，立即发送 binding 请求
                    if (serverCtx != null) {
                        sendBindingRequest();
                    }
                });
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
        return clientConfig.getP2p() != null && clientConfig.getP2p().getP2pPort() >= 0
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
        if (tcpListenPort > 0) {
            msg.setTcpPort(tcpListenPort);
        }
        serverCtx.writeAndFlush(msg);
        log.info("Sent P2P binding request, UDP port: {}, TCP port: {}, local IP: {}",
                externalPort, tcpListenPort, msg.getLocalAddr());
    }

    private String getLocalIp() {
        try {
            return java.net.InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    /**
     * 绑定 TCP 监听端口（用于 TCP 打洞）
     *
     * @param onBound 绑定成功后回调
     */
    private void bindTcpServer(Runnable onBound) {
        int p2pPort = clientConfig.getP2p().getP2pPort();
        tcpGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(tcpGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(
                                new LengthFieldBasedFrameDecoder(65535, 0, 4, 0, 4),
                                new LengthFieldPrepender(4),
                                new TcpPunchServerHandler()
                        );
                    }
                });

        bootstrap.bind(p2pPort).addListener((ChannelFutureListener) f -> {
            if (f.isSuccess()) {
                tcpServerChannel = f.channel();
                tcpListenPort = ((InetSocketAddress) tcpServerChannel.localAddress()).getPort();
                log.info("P2P TCP listener bound to port {}", tcpListenPort);
                if (onBound != null) {
                    onBound.run();
                }
            } else {
                log.error("Failed to bind P2P TCP listener on port {}", p2pPort, f.cause());
            }
        });
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
     * 处理 TCP 打洞开始指令
     * 收到后同时向对端的 TCP 端口发起连接
     */
    public void handleTcpPunchStart(TunnelMessage msg) {
        String peerId = msg.getPeerId();
        String peerTcpAddr = msg.getCandidateAddr();

        if (peerTcpAddr == null) {
            log.warn("TCP_PUNCH_START without peer TCP address for {}", peerId);
            return;
        }

        String[] parts = peerTcpAddr.split(":");
        if (parts.length != 2) {
            log.warn("Invalid peer TCP address: {}", peerTcpAddr);
            return;
        }

        String host = parts[0];
        int port = Integer.parseInt(parts[1]);

        log.info("TCP punch start: myId={}, peerId={}, peerTcpAddr={}",
                clientConfig.getClientId(), peerId, peerTcpAddr);

        // 存储预期的对端地址（用于匹配入站连接）
        tcpPendingPeers.put(host + ":" + port, peerId);

        // 同时发起 TCP 连接（双向打洞）
        for (int i = 0; i < 3; i++) {
            connectTcpPunch(peerId, host, port);
        }
    }

    /**
     * 向对端 TCP 端口发起打洞连接
     */
    private void connectTcpPunch(String peerId, String host, int port) {
        if (tcpGroup == null) {
            tcpGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        }

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(tcpGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(
                                new LengthFieldBasedFrameDecoder(65535, 0, 4, 0, 4),
                                new LengthFieldPrepender(4),
                                new TcpPunchDataHandler()
                        );
                    }
                });

        bootstrap.connect(host, port).addListener((ChannelFutureListener) f -> {
            if (f.isSuccess()) {
                Channel ch = f.channel();
                log.info("TCP punch connected to {}:{} for peer {}", host, port, peerId);

                // 如果已有连接，关闭旧的
                Channel old = tcpPeerChannels.put(peerId, ch);
                if (old != null && old.isActive() && old != ch) {
                    old.close();
                }

                // 如果会话还未建立，标记为已建立
                P2pSession session = sessions.get(peerId);
                if (session != null && session.getState() != P2pSession.State.ESTABLISHED) {
                    session.setState(P2pSession.State.ESTABLISHED);
                    session.updateSeen();
                    sendP2pSuccess(peerId);
                    onP2pEstablished(peerId, session);
                    log.info("P2P channel established via TCP punch with {}", peerId);
                }

                // 添加关闭监听
                ch.closeFuture().addListener((ChannelFutureListener) cf -> {
                    tcpPeerChannels.remove(peerId, ch);
                    log.info("TCP punch channel closed for peer {}", peerId);
                });
            } else {
                log.debug("TCP punch connect failed to {}:{} for peer {}: {}",
                        host, port, peerId,
                        f.cause() != null ? f.cause().getMessage() : "unknown");
            }
        });
    }

    /**
     * TCP 打洞数据处理器（客户端主动连接时使用）
     */
    private class TcpPunchDataHandler extends SimpleChannelInboundHandler<ByteBuf> {
        private String peerId;

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
            if (msg.readableBytes() < 5) return;

            byte type = msg.readByte();
            int sessionId = msg.readInt();
            byte[] payload = new byte[msg.readableBytes()];
            msg.readBytes(payload);

            if (peerId == null) {
                for (Map.Entry<String, Channel> entry : tcpPeerChannels.entrySet()) {
                    if (entry.getValue() == ctx.channel()) {
                        peerId = entry.getKey();
                        break;
                    }
                }
            }

            if (type == P2pUdpCodec.TYPE_DATA && peerId != null) {
                handleTcpData(peerId, sessionId, payload);
            } else if (peerId == null) {
                log.warn("TCP punch data received but no matching peer found, discarding {} bytes", payload.length);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (peerId != null) {
                tcpPeerChannels.remove(peerId, ctx.channel());
                log.info("TCP punch channel inactive for peer {}", peerId);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.debug("TCP punch channel error: {}", cause.getMessage());
            ctx.close();
        }
    }

    /**
     * TCP 打洞服务端处理器（接受入站连接时使用）
     * 通过 remoteAddress 匹配预期的 peerId
     */
    private class TcpPunchServerHandler extends SimpleChannelInboundHandler<ByteBuf> {
        private String peerId;

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            // 通过 remoteAddress 查找匹配的 peerId
            InetSocketAddress remoteAddr = (InetSocketAddress) ctx.channel().remoteAddress();
            String addrKey = remoteAddr.getHostString() + ":" + remoteAddr.getPort();

            // 尝试精确匹配
            peerId = tcpPendingPeers.remove(addrKey);

            // 如果精确匹配失败，通过 IP 段匹配（NAT 可能改变端口）
            if (peerId == null) {
                for (Map.Entry<String, String> entry : tcpPendingPeers.entrySet()) {
                    if (entry.getKey().startsWith(remoteAddr.getHostString() + ":")) {
                        peerId = entry.getValue();
                        tcpPendingPeers.remove(entry.getKey());
                        break;
                    }
                }
            }

            if (peerId != null) {
                Channel old = tcpPeerChannels.put(peerId, ctx.channel());
                if (old != null && old.isActive() && old != ctx.channel()) {
                    old.close();
                }

                log.info("TCP punch accepted connection from {} for peer {}", addrKey, peerId);

                // 如果会话还未建立，标记为已建立
                P2pSession session = sessions.get(peerId);
                if (session != null && session.getState() != P2pSession.State.ESTABLISHED) {
                    session.setState(P2pSession.State.ESTABLISHED);
                    session.updateSeen();
                    sendP2pSuccess(peerId);
                    onP2pEstablished(peerId, session);
                    log.info("P2P channel established via TCP punch (inbound) with {}", peerId);
                }
            } else {
                log.warn("TCP punch accepted connection from {} but no matching peer found", addrKey);
            }

            super.channelActive(ctx);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
            if (msg.readableBytes() < 5) return;

            byte type = msg.readByte();
            int sessionId = msg.readInt();
            byte[] payload = new byte[msg.readableBytes()];
            msg.readBytes(payload);

            if (type == P2pUdpCodec.TYPE_DATA && peerId != null) {
                handleTcpData(peerId, sessionId, payload);
            } else if (peerId == null) {
                log.warn("TCP punch server received data but no matching peer found, discarding {} bytes", payload.length);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (peerId != null) {
                tcpPeerChannels.remove(peerId, ctx.channel());
                log.info("TCP punch server channel inactive for peer {}", peerId);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.debug("TCP punch server channel error: {}", cause.getMessage());
            ctx.close();
        }
    }

    /**
     * 处理 TCP 打洞收到的数据
     */
    private void handleTcpData(String peerId, int sessionId, byte[] payload) {
        P2pSession session = sessions.get(peerId);
        if (session != null) session.updateSeen();

        P2pTunnelHandler handler = tunnelHandlers.get(sessionId);
        if (handler != null) {
            handler.onData(payload);
        } else if (!clientConfig.getTunnels().isEmpty()) {
            connectToLocalService(sessionId, payload, peerId);
        }
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
     * 检查是否有到对端的 P2P 通道（UDP 或 TCP）
     */
    public boolean hasP2pChannel(String peerId) {
        if (relayPeers.contains(peerId)) return true;
        Channel tcpCh = tcpPeerChannels.get(peerId);
        if (tcpCh != null && tcpCh.isActive()) return true;
        P2pSession session = sessions.get(peerId);
        return session != null && session.getState() == P2pSession.State.ESTABLISHED;
    }

    /**
     * 通过 P2P 或中继发送数据
     */
    public void sendData(String peerId, int sessionId, byte[] payload) {
        // 中继模式
        if (relayPeers.contains(peerId)) {
            sendRelayData(peerId, sessionId, payload);
            return;
        }

        // TCP 打洞模式
        Channel tcpChannel = tcpPeerChannels.get(peerId);
        if (tcpChannel != null && tcpChannel.isActive()) {
            if (log.isTraceEnabled()) {
                log.trace("TCP punch data to {}: session={}, {} bytes", peerId, sessionId, payload.length);
            }
            ByteBuf buf = tcpChannel.alloc().buffer(5 + payload.length);
            buf.writeByte(P2pUdpCodec.TYPE_DATA);
            buf.writeInt(sessionId);
            buf.writeBytes(payload);
            tcpChannel.writeAndFlush(buf);
            P2pSession session = sessions.get(peerId);
            if (session != null) session.updateSeen();
            return;
        }

        // UDP P2P 模式
        P2pSession session = sessions.get(peerId);
        if (session == null || session.getState() != P2pSession.State.ESTABLISHED) {
            log.warn("No P2P channel to {}, cannot send data", peerId);
            return;
        }
        if (log.isTraceEnabled()) {
            log.trace("P2P data to {}: session={}, {} bytes", peerId, sessionId, payload.length);
        }
        udpChannel.sendData(session.getPeerAddress(), sessionId, payload);
        session.updateSeen();
    }

    /**
     * 处理 RELAY_READY（服务端通知进入中继模式）
     */
    public void handleRelayReady(TunnelMessage msg) {
        String peerId = msg.getPeerId();
        log.info("Relay mode ready for peer {}", peerId);

        relayPeers.add(peerId);

        // 创建会话（如果不存在）
        P2pSession session = sessions.get(peerId);
        if (session == null) {
            session = new P2pSession(peerId);
            sessions.put(peerId, session);
        }
        session.setState(P2pSession.State.ESTABLISHED);

        // 启动本地 TCP 监听
        onP2pEstablished(peerId, session);
    }

    /**
     * 处理 RELAY_DATA（服务端转发的数据）
     */
    public void handleRelayData(TunnelMessage msg) {
        String peerId = msg.getPeerId();
        Integer sessionId = msg.getSessionId();
        byte[] payload = msg.getPayload();

        if (sessionId == null || payload == null) return;

        // 更新会话状态
        P2pSession session = sessions.get(peerId);
        if (session != null) {
            session.updateSeen();
        }

        // 分发到隧道处理器
        P2pTunnelHandler handler = tunnelHandlers.get(sessionId);
        if (handler != null) {
            handler.onData(payload);
        } else if (!clientConfig.getTunnels().isEmpty()) {
            // tunnels 端：收到新 sessionId，连接本地服务
            connectToLocalService(sessionId, payload, peerId);
        }
    }

    /**
     * 通过服务端中继发送数据
     */
    private void sendRelayData(String peerId, int sessionId, byte[] payload) {
        if (serverCtx == null || !serverCtx.channel().isActive()) {
            log.warn("Server connection lost, cannot relay data to {}", peerId);
            return;
        }

        if (log.isTraceEnabled()) {
            log.trace("Relay data to {}: session={}, {} bytes", peerId, sessionId, payload.length);
        }

        TunnelMessage msg = new TunnelMessage();
        msg.setType(TunnelMessage.MessageType.RELAY_DATA);
        msg.setClientId(clientConfig.getClientId());
        msg.setPeerId(peerId);
        msg.setSessionId(sessionId);
        msg.setPayload(payload);
        serverCtx.writeAndFlush(msg);
    }

    /**
     * 处理对端离线通知（服务端推送）
     */
    public void handlePeerOffline(TunnelMessage msg) {
        String peerId = msg.getPeerId();
        if (peerId == null) return;

        log.info("Peer {} went offline, cleaning up session", peerId);

        // 从中继模式移除
        relayPeers.remove(peerId);

        // 关闭 TCP 打洞通道
        Channel tcpChannel = tcpPeerChannels.remove(peerId);
        if (tcpChannel != null) {
            tcpChannel.close();
        }

        // 清理 TCP 待连接列表
        tcpPendingPeers.entrySet().removeIf(e -> e.getValue().equals(peerId));

        // 清理会话
        P2pSession session = sessions.remove(peerId);
        if (session != null) {
            log.info("Session with {} cleaned up (was {})", peerId, session.getState());
        }

        // 关闭并清理 TCP 监听器
        P2pTcpListener listener = tcpListeners.remove(peerId);
        if (listener != null) {
            listener.stop();
        }

        // 清理与此对端相关的隧道处理器
        sessionPeerMap.entrySet().removeIf(e -> peerId.equals(e.getValue()));
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
            // 查找对端 peerId
            String peerId = findPeerIdByAddress(sender);
            connectToLocalService(sessionId, payload, peerId);
        } else {
            log.debug("No handler for P2P session {}", sessionId);
        }
    }

    private String findPeerIdByAddress(InetSocketAddress addr) {
        for (Map.Entry<String, P2pSession> entry : sessions.entrySet()) {
            P2pSession s = entry.getValue();
            if (s.getPeerAddress() != null
                    && s.getPeerAddress().getAddress().equals(addr.getAddress())
                    && s.getPeerAddress().getPort() == addr.getPort()) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * tunnels 端：收到新 sessionId 时，连接本地服务并设置桥接
     * 使用第一个隧道配置的 localAddress:localPort
     */
    private void connectToLocalService(int sessionId, byte[] firstPayload, String peerId) {
        if (clientConfig.getTunnels().isEmpty()) {
            log.warn("No tunnel config, cannot connect to local service");
            return;
        }

        // 使用第一个隧道配置
        ClientConfig.TunnelExpose tunnel = clientConfig.getTunnels().get(0);
        String host = tunnel.getLocalAddress();
        int port = tunnel.getLocalPort();

        log.info("P2P: new session {}, connecting to local service {}:{}", sessionId, host, port);

        // 记录会话归属
        if (peerId != null) {
            sessionPeerMap.put(sessionId, peerId);
        }

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
     * 本地服务响应 → P2P UDP 或中继发送给对端
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

            // 通过 P2pManager 发送（自动选择 UDP 或中继）
            String peerId = sessionPeerMap.get(sessionId);
            if (peerId != null) {
                sendData(peerId, sessionId, data);
            } else {
                // 回退：查找任意已建立的会话
                for (Map.Entry<String, P2pSession> entry : sessions.entrySet()) {
                    if (entry.getValue().getState() == P2pSession.State.ESTABLISHED) {
                        sendData(entry.getKey(), sessionId, data);
                        break;
                    }
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
        // 使用快照避免 ConcurrentModificationException
        for (P2pSession session : new java.util.ArrayList<>(sessions.values())) {
            if (session.getState() == P2pSession.State.ESTABLISHED
                    && session.getPeerAddress() != null) {
                udpChannel.sendHeartbeat(session.getPeerAddress(), 0);

                // 检查会话超时并清理
                if (session.isExpired(clientConfig.getP2p().getSessionTimeoutMs())) {
                    log.warn("P2P session with {} expired, removing", session.getPeerId());
                    sessions.remove(session.getPeerId(), session);
                    relayPeers.remove(session.getPeerId());
                    Channel ch = tcpPeerChannels.remove(session.getPeerId());
                    if (ch != null) ch.close();
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
        tcpPendingPeers.clear();
        for (Channel ch : tcpPeerChannels.values()) {
            ch.close();
        }
        if (tcpServerChannel != null) tcpServerChannel.close();
        if (tcpGroup != null) tcpGroup.shutdownGracefully();
        if (udpChannel != null) udpChannel.shutdown();
    }

    /**
     * 隧道数据处理器接口
     */
    public interface P2pTunnelHandler {
        void onData(byte[] payload);
    }
}
