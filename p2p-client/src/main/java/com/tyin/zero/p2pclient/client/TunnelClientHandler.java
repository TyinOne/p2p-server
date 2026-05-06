package com.tyin.zero.p2pclient.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tyin.zero.p2pclient.client.p2p.P2pManager;
import com.tyin.zero.p2pclient.config.ClientConfig;
import com.tyin.zero.p2pcommon.p2p.TunnelExposeConfig;
import com.tyin.zero.p2pcommon.protocol.JacksonConfig;
import com.tyin.zero.p2pcommon.protocol.TunnelMessage;
import io.netty.channel.*;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class TunnelClientHandler extends SimpleChannelInboundHandler<TunnelMessage> {

    private final ClientConfig clientConfig;
    private final P2pManager p2pManager;
    private static final ObjectMapper objectMapper = JacksonConfig.objectMapper();

    public TunnelClientHandler(ClientConfig clientConfig, P2pManager p2pManager) {
        this.clientConfig = clientConfig;
        this.p2pManager = p2pManager;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        p2pManager.setServerContext(ctx);
        log.info("Channel active, sending authentication...");

        TunnelMessage authMessage = new TunnelMessage();
        authMessage.setType(TunnelMessage.MessageType.REGISTER);
        authMessage.setClientId(clientConfig.getClientId());
        authMessage.setAuth(clientConfig.getAuth());
        authMessage.setTimestamp(System.currentTimeMillis());

        // 携带暴露的隧道列表
        if (clientConfig.getTunnels() != null && !clientConfig.getTunnels().isEmpty()) {
            List<TunnelExposeConfig> exposeList = new ArrayList<>();
            for (ClientConfig.TunnelExpose t : clientConfig.getTunnels()) {
                exposeList.add(new TunnelExposeConfig(
                        t.getRemotePort(), t.getLocalPort(),
                        t.getLocalAddress(), t.getDescription()));
            }
            try {
                authMessage.setTunnelsJson(objectMapper.writeValueAsString(exposeList));
            } catch (Exception e) {
                log.warn("Failed to serialize tunnels: {}", e.getMessage());
            }
        }

        ctx.writeAndFlush(authMessage);
        log.info("Authentication request sent");
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TunnelMessage msg) {
        switch (msg.getType()) {
            case REGISTER_RESPONSE -> handleAuthResponse(ctx, msg);
            case HEARTBEAT -> handleHeartbeat(ctx);
            case HEARTBEAT_RESPONSE -> log.debug("Heartbeat ack received");
            case ERROR -> handleError(msg);
            // P2P 信令
            case P2P_BINDING_RESPONSE -> p2pManager.handleBindingResponse(msg);
            case P2P_CANDIDATE -> p2pManager.handleCandidate(msg);
            case P2P_HOLE_PUNCH -> p2pManager.handleHolePunch(msg);
            case TCP_PUNCH_START -> p2pManager.handleTcpPunchStart(msg);
            case P2P_FAILED -> log.warn("P2P failed: {}", msg.getPayload() != null
                    ? new String(msg.getPayload(), java.nio.charset.StandardCharsets.UTF_8) : "unknown");
            // 中继
            case RELAY_READY -> p2pManager.handleRelayReady(msg);
            case RELAY_DATA -> p2pManager.handleRelayData(msg);
            case PEER_OFFLINE -> p2pManager.handlePeerOffline(msg);
            default -> log.debug("Ignored message type: {}", msg.getType());
        }
    }

    private void handleAuthResponse(ChannelHandlerContext ctx, TunnelMessage msg) {
        byte[] payload = msg.getPayload();
        String response = payload != null ? new String(payload, StandardCharsets.UTF_8) : "";

        if ("OK".equals(response)) {
            log.info("Authentication successful, client ID: {}", msg.getClientId());
            // 认证成功后，触发 P2P NAT 发现
            if (p2pManager.isP2pEnabled()) {
                p2pManager.sendBindingRequest();
            }
        } else {
            log.error("Authentication failed: {}", response);
            ctx.close();
        }
    }

    private void handleHeartbeat(ChannelHandlerContext ctx) {
        log.debug("Received heartbeat, sending response...");
        TunnelMessage response = new TunnelMessage();
        response.setType(TunnelMessage.MessageType.HEARTBEAT);
        response.setClientId(clientConfig.getClientId());
        response.setTimestamp(System.currentTimeMillis());
        ctx.writeAndFlush(response);
    }

    private void handleError(TunnelMessage msg) {
        log.error("Received error from server: {}", msg.getPayload() != null
                ? new String(msg.getPayload(), StandardCharsets.UTF_8) : "unknown");
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.warn("Server connection lost");
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent event && event.state() == IdleState.WRITER_IDLE) {
            log.debug("Writer idle, sending heartbeat...");
            TunnelMessage heartbeat = new TunnelMessage();
            heartbeat.setType(TunnelMessage.MessageType.HEARTBEAT);
            heartbeat.setClientId(clientConfig.getClientId());
            heartbeat.setTimestamp(System.currentTimeMillis());
            ctx.writeAndFlush(heartbeat);
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Exception caught: {}", cause.getMessage(), cause);
        ctx.close();
    }
}
