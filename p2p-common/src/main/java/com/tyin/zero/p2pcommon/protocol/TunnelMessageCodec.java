package com.tyin.zero.p2pcommon.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 隧道消息编解码器
 * 需要配合 LengthFieldBasedFrameDecoder 使用以处理消息帧
 */
@Slf4j
public class TunnelMessageCodec extends MessageToMessageCodec<ByteBuf, TunnelMessage> {

    private static final ObjectMapper objectMapper = JacksonConfig.objectMapper();

    @Override
    protected void encode(ChannelHandlerContext ctx, TunnelMessage msg, List<Object> out) throws Exception {
        String json = objectMapper.writeValueAsString(msg);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        // LengthFieldPrepender 在 pipeline 中负责添加长度前缀，这里只写 JSON 数据
        ByteBuf buf = ctx.alloc().buffer(bytes.length);
        buf.writeBytes(bytes);

        out.add(buf);
        log.debug("Encoded message: type={}, clientId={}", msg.getType(), msg.getClientId());
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) throws Exception {
        // LengthFieldBasedFrameDecoder 已经处理了帧边界，直接读取完整消息
        byte[] bytes = new byte[msg.readableBytes()];
        msg.readBytes(bytes);

        String json = new String(bytes, StandardCharsets.UTF_8);
        TunnelMessage message = objectMapper.readValue(json, TunnelMessage.class);

        out.add(message);
        log.debug("Decoded message: type={}, clientId={}", message.getType(), message.getClientId());
    }
}
