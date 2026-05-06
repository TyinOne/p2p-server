package com.tyin.zero.p2pclient.client.p2p;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * P2P TCP↔UDP 桥接处理器
 * 负责将 TCP 字节流封装为 UDP 数据报，或将 UDP 数据报解封写回 TCP
 *
 * 共用于两端：
 * - connect 端：本地 TCP → UDP → 对端
 * - tunnels 端：对端 UDP → TCP → 本地服务
 */
@Slf4j
public class P2pTcpBridgeHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private static final AtomicInteger SESSION_ID_GEN = new AtomicInteger(1);

    private final int sessionId;
    private final String peerId;
    private final P2pManager p2pManager;
    private final boolean isSourceSide; // true=数据来源端(connect端), false=服务端(tunnels端)
    private ChannelHandlerContext tcpCtx;

    public P2pTcpBridgeHandler(int sessionId, String peerId, P2pManager p2pManager, boolean isSourceSide) {
        this.sessionId = sessionId;
        this.peerId = peerId;
        this.p2pManager = p2pManager;
        this.isSourceSide = isSourceSide;
    }

    /**
     * 生成唯一的 sessionId
     */
    public static int nextSessionId() {
        return SESSION_ID_GEN.incrementAndGet();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        this.tcpCtx = ctx;
        super.channelActive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
        byte[] data = new byte[msg.readableBytes()];
        msg.readBytes(data);

        log.debug("TCP→UDP: {} bytes for session {} (peer {})", data.length, sessionId, peerId);

        // 通过 P2P 发送
        p2pManager.sendData(peerId, sessionId, data);
    }

    /**
     * 收到 P2P 数据时调用（由 P2pManager 分发）
     */
    public void onData(byte[] payload) {
        if (tcpCtx != null && tcpCtx.channel().isActive()) {
            tcpCtx.writeAndFlush(tcpCtx.alloc().buffer().writeBytes(payload));
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.info("TCP bridge session {} closed (peer {})", sessionId, peerId);
        p2pManager.unregisterTunnelHandler(sessionId);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("TCP bridge session {} error: {}", sessionId, cause.getMessage());
        ctx.close();
    }
}
