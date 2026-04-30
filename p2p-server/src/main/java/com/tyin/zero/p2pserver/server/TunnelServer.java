package com.tyin.zero.p2pserver.server;

import com.tyin.zero.p2pcommon.auth.AuthManager;
import com.tyin.zero.p2pcommon.protocol.TunnelChannelInitializer;
import com.tyin.zero.p2pserver.config.ServerConfig;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class TunnelServer {

    private final ServerConfig serverConfig;
    private final AuthManager authManager;
    private final P2pSignalingHandler p2pSignalingHandler;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public TunnelServer(ServerConfig serverConfig, AuthManager authManager,
                        P2pSignalingHandler p2pSignalingHandler) {
        this.serverConfig = serverConfig;
        this.authManager = authManager;
        this.p2pSignalingHandler = p2pSignalingHandler;
    }

    @PostConstruct
    public void start() {
        log.info("Starting Tunnel Server on port {}...", serverConfig.getPort());

        bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        workerGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new TunnelChannelInitializer(
                            () -> new TunnelServerHandler(authManager, p2pSignalingHandler), 60, 30));

            ChannelFuture future = bootstrap.bind(serverConfig.getPort()).sync();
            this.serverChannel = future.channel();
            log.info("Tunnel Server started on port {}", serverConfig.getPort());

        } catch (Exception e) {
            log.error("Failed to start Tunnel Server", e);
            shutdown();
            throw new RuntimeException("Server startup failed", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down Tunnel Server...");
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        log.info("Tunnel Server stopped");
    }
}
