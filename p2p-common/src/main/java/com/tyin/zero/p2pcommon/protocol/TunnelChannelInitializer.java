package com.tyin.zero.p2pcommon.protocol;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 共享的 Netty Channel 初始化器
 * 统一客户端/服务端的 Pipeline 配置
 */
public class TunnelChannelInitializer extends ChannelInitializer<SocketChannel> {

    private static final int MAX_FRAME_LENGTH = 1048576; // 1MB
    private static final int LENGTH_FIELD_LENGTH = 4;

    private final Supplier<ChannelHandler> handlerFactory;
    private final int readerIdleSeconds;
    private final int writerIdleSeconds;

    public TunnelChannelInitializer(Supplier<ChannelHandler> handlerFactory,
                                     int readerIdleSeconds,
                                     int writerIdleSeconds) {
        this.handlerFactory = handlerFactory;
        this.readerIdleSeconds = readerIdleSeconds;
        this.writerIdleSeconds = writerIdleSeconds;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ch.pipeline()
                .addLast("frameDecoder", new LengthFieldBasedFrameDecoder(
                        MAX_FRAME_LENGTH, 0, LENGTH_FIELD_LENGTH, 0, LENGTH_FIELD_LENGTH))
                .addLast("frameEncoder", new LengthFieldPrepender(LENGTH_FIELD_LENGTH))
                .addLast("codec", new TunnelMessageCodec())
                .addLast("idleState", new IdleStateHandler(
                        readerIdleSeconds, writerIdleSeconds, 0, TimeUnit.SECONDS))
                .addLast("handler", handlerFactory.get());
    }
}
