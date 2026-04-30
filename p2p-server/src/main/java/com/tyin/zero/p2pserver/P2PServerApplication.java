package com.tyin.zero.p2pserver;

import com.tyin.zero.p2pserver.config.ServerConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;

/**
 * P2P Tunnel Server Application
 * 
 * 启动方式:
 * 1. 使用默认配置: java -jar p2p-server.jar
 * 2. 使用外部配置: java -jar p2p-server.jar --spring.config.location=file:./config.yaml
 * 3. 指定配置文件: java -jar p2p-server.jar --spring.config.additional-location=file:./server-config.yaml
 */
@SpringBootApplication
@EnableConfigurationProperties(ServerConfig.class)
@ComponentScan(basePackages = {"com.tyin.zero.p2pserver", "com.tyin.zero.p2pcommon"})
public class P2PServerApplication {
    
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(P2PServerApplication.class);
        
        // 支持外部配置文件
        // 优先级: 命令行参数 > 外部配置文件 > application.yaml
        app.run(args);
    }
}
