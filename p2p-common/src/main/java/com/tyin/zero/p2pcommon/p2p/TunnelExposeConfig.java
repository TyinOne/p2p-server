package com.tyin.zero.p2pcommon.p2p;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 客户端暴露的隧道配置
 * 每个客户端声明自己提供的隧道（本地服务 + 公开端口）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TunnelExposeConfig {

    /**
     * 公开端口（信令服务器上注册的端口，对端通过此端口连接）
     */
    private int remotePort;

    /**
     * 本地服务端口
     */
    private int localPort;

    /**
     * 本地服务地址
     */
    private String localAddress = "127.0.0.1";

    /**
     * 描述
     */
    private String description;
}
