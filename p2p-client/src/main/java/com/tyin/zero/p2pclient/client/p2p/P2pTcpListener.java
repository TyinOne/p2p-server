package com.tyin.zero.p2pclient.client.p2p;

import com.tyin.zero.p2pclient.config.ClientConfig;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import lombok.extern.slf4j.Slf4j;

/**
 * P2P 本地 TCP 监听器
 * 在本地端口监听 TCP 连接，将数据桥接到 P2P UDP 通道
 *
 * 用户通过 mstsc /v:localhost:13390 连接，此监听器接受连接后
 * 将数据通过 P2P UDP 发送给对端
 */
@Slf4j
public class P2pTcpListener {

    private final ClientConfig.PeerTunnel peerTunnel;
    private final P2pManager p2pManager;
    private final P2pSessionHolder sessionHolder;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public P2pTcpListener(ClientConfig.PeerTunnel peerTunnel, P2pManager p2pManager,
                           P2pSessionHolder sessionHolder) {
        this.peerTunnel = peerTunnel;
        this.p2pManager = p2pManager;
        this.sessionHolder = sessionHolder;
    }

    /**
     * 启动本地 TCP 监听
     */
    public void start() {
        int localPort = peerTunnel.getLocalBindPort();
        String peerId = peerTunnel.getPeerId();

        bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        workerGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        int sessionId = P2pTcpBridgeHandler.nextSessionId();
                        P2pTcpBridgeHandler bridgeHandler =
                                new P2pTcpBridgeHandler(sessionId, peerId, p2pManager, true);

                        ch.pipeline().addLast(bridgeHandler);

                        // 注册隧道处理器：P2P 数据 → TCP
                        p2pManager.registerTunnelHandler(sessionId, payload -> {
                            if (ch.isActive()) {
                                ByteBuf buf = ch.alloc().buffer(payload.length);
                                buf.writeBytes(payload);
                                ch.writeAndFlush(buf);
                            }
                        });

                        log.info("New TCP connection for P2P tunnel session {} → peer {}",
                                sessionId, peerId);
                    }
                });

        bootstrap.bind(localPort).addListener((ChannelFutureListener) f -> {
            if (f.isSuccess()) {
                serverChannel = f.channel();
                log.info("P2P TCP listener started on port {} → peer {} (port {})",
                        localPort, peerId, peerTunnel.getRemotePort());
            } else {
                log.error("Failed to start P2P TCP listener on port {}", localPort, f.cause());
            }
        });
    }

    /**
     * 停止监听
     */
    public void stop() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
    }

    /**
     * 检查是否有活跃的连接
     */
    public boolean hasActiveConnection() {
        if (serverChannel == null || !serverChannel.isActive()) {
            return false;
        }
        // 检查是否有子 Channel 活跃
        return serverChannel.pipeline().channels().stream()
                .filter(ch -> ch instanceof io.netty.channel.socket.SocketChannel && ch.isActive())
                .findFirst()
                .isPresent();
    }

    /**
     * 会话持有者接口（用于检查 P2P 会话是否可用）
     */
    public interface P2pSessionHolder {
        boolean isP2pAvailable(String peerId);
    }
}
