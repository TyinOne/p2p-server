package com.tyin.zero.p2pserver.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 服务端配置属性
 */
@Data
@ConfigurationProperties(prefix = "p2p.server")
public class ServerConfig {

    /**
     * 认证模式（SHARED_KEY / RSA_KEYPAIR）
     */
    private String authMode = "SHARED_KEY";

    /**
     * 共享密钥（SHARED_KEY 模式使用）
     */
    private String sharedKey;

    /**
     * 服务端口（客户端连接端口）
     */
    private int port = 8084;
}
