package com.tyin.zero.p2pserver.server;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tyin.zero.p2pcommon.auth.AuthManager;
import com.tyin.zero.p2pcommon.auth.AuthResult;
import com.tyin.zero.p2pcommon.p2p.TunnelExposeConfig;
import com.tyin.zero.p2pcommon.protocol.JacksonConfig;
import com.tyin.zero.p2pcommon.protocol.TunnelMessage;
import io.netty.channel.*;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class TunnelServerHandler extends SimpleChannelInboundHandler<TunnelMessage> {

    private final AuthManager authManager;
    private final P2pSignalingHandler p2pSignalingHandler;
    private static final ObjectMapper objectMapper = JacksonConfig.objectMapper();

    private String clientId;
    private boolean authenticated = false;

    private static final Map<String, Channel> clientChannels = new ConcurrentHashMap<>();

    public TunnelServerHandler(AuthManager authManager, P2pSignalingHandler p2pSignalingHandler) {
        this.authManager = authManager;
        this.p2pSignalingHandler = p2pSignalingHandler;
    }

    public static Channel getClientChannel(String clientId) {
        return clientChannels.get(clientId);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        if (ctx.channel().remoteAddress() instanceof InetSocketAddress addr) {
            log.info("New connection from {}:{}", addr.getHostString(), addr.getPort());
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TunnelMessage msg) {
        switch (msg.getType()) {
            case REGISTER -> handleRegister(ctx, msg);
            case HEARTBEAT -> handleHeartbeat(ctx, msg);
            // P2P 信令
            case P2P_BINDING -> p2pSignalingHandler.handleBinding(ctx, msg);
            case P2P_REQUEST -> p2pSignalingHandler.handleRequest(ctx, msg);
            case P2P_SUCCESS -> p2pSignalingHandler.handleSuccess(ctx, msg);
            case P2P_FAILED -> p2pSignalingHandler.handleFailed(ctx, msg);
            case TCP_PUNCH -> {} // TCP port reported in binding, no separate handling needed
            // 中继数据
            case RELAY_DATA -> handleRelayData(ctx, msg);
            default -> log.warn("Unknown message type from client {}: {}", clientId, msg.getType());
        }
    }

    private void handleRegister(ChannelHandlerContext ctx, TunnelMessage msg) {
        String reqClientId = msg.getClientId();
        String authData = msg.getAuth();
        Long timestamp = msg.getTimestamp();

        log.info("Register request from client: {}", reqClientId);

        AuthResult result = authManager.authenticate(reqClientId, authData, timestamp);

        TunnelMessage response = new TunnelMessage();
        response.setType(TunnelMessage.MessageType.REGISTER_RESPONSE);
        response.setClientId(reqClientId);

        if (result.success()) {
            this.clientId = reqClientId;
            this.authenticated = true;
            clientChannels.put(reqClientId, ctx.channel());

            // 解析暴露的隧道列表并注册到 P2P 注册表
            List<TunnelExposeConfig> tunnels = parseTunnels(msg.getTunnelsJson());
            p2pSignalingHandler.handleRegister(reqClientId, ctx.channel(), tunnels);

            log.info("Client {} authenticated successfully", reqClientId);

            response.setPayload("OK".getBytes(StandardCharsets.UTF_8));
            ctx.writeAndFlush(response);
        } else {
            log.warn("Authentication failed for client {}: {}", reqClientId, result.errorMessage());
            response.setPayload(result.errorMessage().getBytes(StandardCharsets.UTF_8));
            ctx.writeAndFlush(response);
            ctx.close();
        }
    }

    private List<TunnelExposeConfig> parseTunnels(String tunnelsJson) {
        if (tunnelsJson == null || tunnelsJson.isEmpty()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(tunnelsJson, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse tunnels JSON: {}", e.getMessage());
            return List.of();
        }
    }

    private void handleHeartbeat(ChannelHandlerContext ctx, TunnelMessage msg) {
        TunnelMessage response = new TunnelMessage();
        response.setType(TunnelMessage.MessageType.HEARTBEAT_RESPONSE);
        response.setClientId(clientId);
        response.setTimestamp(System.currentTimeMillis());
        ctx.writeAndFlush(response);
    }

    private void handleRelayData(ChannelHandlerContext ctx, TunnelMessage msg) {
        String peerId = msg.getPeerId();
        if (peerId == null) {
            log.warn("RELAY_DATA without peerId from {}", clientId);
            return;
        }

        Channel peerChannel = clientChannels.get(peerId);
        if (peerChannel == null || !peerChannel.isActive()) {
            log.warn("RELAY_DATA: peer {} not connected", peerId);
            return;
        }

        // 转发给对端，clientId 设为发送方（让接收方知道是谁发的）
        TunnelMessage relay = new TunnelMessage();
        relay.setType(TunnelMessage.MessageType.RELAY_DATA);
        relay.setClientId(clientId);
        relay.setPeerId(clientId);
        relay.setSessionId(msg.getSessionId());
        relay.setPayload(msg.getPayload());
        peerChannel.writeAndFlush(relay);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (clientId != null) {
            clientChannels.remove(clientId);
            p2pSignalingHandler.handleUnregister(clientId);
            log.info("Client {} disconnected", clientId);
        } else {
            log.info("Unauthenticated client disconnected");
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent event && event.state() == IdleState.READER_IDLE) {
            log.warn("Reader idle timeout for client {}, closing", clientId);
            ctx.close();
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Exception in client handler {}: {}", clientId, cause.getMessage(), cause);
        ctx.close();
    }
}
