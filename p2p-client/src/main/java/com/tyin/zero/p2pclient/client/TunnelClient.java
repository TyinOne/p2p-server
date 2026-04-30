package com.tyin.zero.p2pclient.client;

import com.tyin.zero.p2pclient.client.p2p.P2pManager;
import com.tyin.zero.p2pclient.config.ClientConfig;
import com.tyin.zero.p2pcommon.protocol.TunnelChannelInitializer;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioSocketChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class TunnelClient {

    private final ClientConfig clientConfig;
    private final P2pManager p2pManager;

    private EventLoopGroup workerGroup;
    private Channel channel;
    private final ScheduledExecutorService reconnectExecutor =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofVirtual().name("reconnect-scheduler").factory()
            );
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private static final int MAX_RECONNECT_ATTEMPTS = 50;
    private volatile boolean stopped = false;

    public TunnelClient(ClientConfig clientConfig, P2pManager p2pManager) {
        this.clientConfig = clientConfig;
        this.p2pManager = p2pManager;
    }

    @PostConstruct
    public void start() {
        log.info("Starting P2P Tunnel Client...");
        log.info("Server: {}:{}", clientConfig.getServerHost(), clientConfig.getPort());
        log.info("Client ID: {}", clientConfig.getClientId());

        workerGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
        connectToServer();
    }

    private void connectToServer() {
        if (stopped) return;

        try {
            TunnelClientHandler handler = new TunnelClientHandler(clientConfig, p2pManager);

            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(workerGroup)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                    .handler(new TunnelChannelInitializer(() -> handler, 30, 60));

            ChannelFuture future = bootstrap.connect(
                    clientConfig.getServerHost(),
                    clientConfig.getPort()
            ).addListener((ChannelFutureListener) f -> {
                if (f.isSuccess()) {
                    reconnectAttempts.set(0);
                    log.info("Connected to server successfully");
                }
            }).sync();

            this.channel = future.channel();

            this.channel.closeFuture().addListener(f -> {
                if (!stopped) {
                    scheduleReconnect();
                }
            });

        } catch (Exception e) {
            log.error("Failed to connect to server: {}", e.getMessage());
            if (!stopped) {
                scheduleReconnect();
            }
        }
    }

    private void scheduleReconnect() {
        int attempt = reconnectAttempts.incrementAndGet();
        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            log.error("Max reconnect attempts ({}) reached, giving up", MAX_RECONNECT_ATTEMPTS);
            return;
        }

        long delay = Math.min(5 * (long) Math.pow(1.5, Math.min(attempt - 1, 8)), 60);
        log.info("Reconnecting in {} seconds (attempt {}/{})", delay, attempt, MAX_RECONNECT_ATTEMPTS);

        reconnectExecutor.schedule(this::connectToServer, delay, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop() {
        log.info("Stopping P2P Tunnel Client...");
        stopped = true;

        if (channel != null && channel.isActive()) {
            channel.close();
        }

        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }

        reconnectExecutor.shutdownNow();

        log.info("P2P Tunnel Client stopped");
    }
}
