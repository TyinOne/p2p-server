package com.tyin.zero.p2pclient;

import com.tyin.zero.p2pclient.config.ClientConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;

/**
 * P2P Tunnel Client Application
 * 
 * 启动方式:
 * 1. 使用默认配置: java -jar p2p-client.jar
 * 2. 使用外部配置: java -jar p2p-client.jar --spring.config.location=file:./config.yaml
 * 3. 指定配置文件: java -jar p2p-client.jar --spring.config.additional-location=file:./client-config.yaml
 */
@SpringBootApplication
@EnableConfigurationProperties(ClientConfig.class)
@ComponentScan(basePackages = {"com.tyin.zero.p2pclient", "com.tyin.zero.p2pcommon"})
public class P2PClientApplication {
    
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(P2PClientApplication.class);
        
        // 支持外部配置文件
        // 优先级: 命令行参数 > 外部配置文件 > application.yaml
        app.run(args);
    }
}
