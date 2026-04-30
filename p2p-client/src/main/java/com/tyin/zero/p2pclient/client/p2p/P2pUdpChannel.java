package com.tyin.zero.p2pclient.client.p2p;

import com.tyin.zero.p2pcommon.p2p.P2pUdpCodec;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;

/**
 * P2P UDP 通道管理
 * 管理 DatagramChannel 的绑定、发送和接收
 */
@Slf4j
public class P2pUdpChannel {

    private final EventLoopGroup group;
    private DatagramChannel channel;
    private final P2pDataHandler dataHandler;

    public P2pUdpChannel(P2pDataHandler dataHandler) {
        this.group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        this.dataHandler = dataHandler;
    }

    /**
     * 绑定本地 UDP 端口
     */
    public ChannelFuture bind(int localPort) {
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_BROADCAST, false)
                .handler(new ChannelInitializer<DatagramChannel>() {
                    @Override
                    protected void initChannel(DatagramChannel ch) {
                        ch.pipeline().addLast(new UdpReceiverHandler());
                    }
                });

        return bootstrap.bind(localPort).addListener((ChannelFutureListener) f -> {
            if (f.isSuccess()) {
                this.channel = (DatagramChannel) f.channel();
                int boundPort = ((InetSocketAddress) channel.localAddress()).getPort();
                log.info("P2P UDP channel bound to port {}", boundPort);
            } else {
                log.error("Failed to bind P2P UDP channel", f.cause());
            }
        });
    }

    /**
     * 发送数据到对端
     */
    public void sendData(InetSocketAddress peerAddr, int sessionId, byte[] payload) {
        if (channel == null || !channel.isActive()) {
            log.warn("P2P UDP channel not active, cannot send data");
            return;
        }
        ByteBuf buf = P2pUdpCodec.encodeData(channel.alloc(), sessionId, payload);
        channel.writeAndFlush(new DatagramPacket(buf, peerAddr));
    }

    /**
     * 发送心跳到对端
     */
    public void sendHeartbeat(InetSocketAddress peerAddr, int sessionId) {
        if (channel == null || !channel.isActive()) {
            return;
        }
        ByteBuf buf = P2pUdpCodec.encodeHeartbeat(channel.alloc(), sessionId);
        channel.writeAndFlush(new DatagramPacket(buf, peerAddr));
    }

    /**
     * 发送打洞包
     */
    public void sendHolePunch(InetSocketAddress addr) {
        if (channel == null || !channel.isActive()) {
            return;
        }
        ByteBuf buf = P2pUdpCodec.encodeHolePunch(channel.alloc());
        channel.writeAndFlush(new DatagramPacket(buf, addr));
    }

    public InetSocketAddress getLocalAddress() {
        if (channel == null) return null;
        return (InetSocketAddress) channel.localAddress();
    }

    public boolean isActive() {
        return channel != null && channel.isActive();
    }

    @PreDestroy
    public void shutdown() {
        if (channel != null) {
            channel.close();
        }
        group.shutdownGracefully();
    }

    /**
     * 数据回调接口
     */
    public interface P2pDataHandler {
        void onDataReceived(InetSocketAddress sender, int sessionId, byte[] payload);
        void onHeartbeatReceived(InetSocketAddress sender, int sessionId);
        void onHolePunchReceived(InetSocketAddress sender);
    }

    /**
     * UDP 接收处理器
     */
    private class UdpReceiverHandler extends SimpleChannelInboundHandler<DatagramPacket> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
            InetSocketAddress sender = packet.sender();
            ByteBuf content = packet.content();

            try {
                if (content.readableBytes() < 5) {
                    log.debug("Ignoring short UDP packet from {}", sender);
                    return;
                }

                P2pUdpCodec.Decoded decoded = P2pUdpCodec.decode(content);

                switch (decoded.type()) {
                    case P2pUdpCodec.TYPE_DATA ->
                        dataHandler.onDataReceived(sender, decoded.sessionId(), decoded.payload());
                    case P2pUdpCodec.TYPE_HEARTBEAT ->
                        dataHandler.onHeartbeatReceived(sender, decoded.sessionId());
                    case P2pUdpCodec.TYPE_HOLE_PUNCH ->
                        dataHandler.onHolePunchReceived(sender);
                    default -> log.debug("Unknown P2P packet type {} from {}", decoded.type(), sender);
                }
            } catch (Exception e) {
                log.error("Error processing UDP packet from {}: {}", sender, e.getMessage());
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("P2P UDP channel error: {}", cause.getMessage(), cause);
        }
    }
}
