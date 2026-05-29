# CLAUDE.md

本文档为 Claude Code (claude.ai/code) 在本代码仓库中工作时提供指导。

## 项目规则

本项目所有回答使用中文回复

## 构建命令

```bash
# 完整构建（跳过测试）
mvn clean package -DskipTests

# 构建指定模块
mvn clean package -DskipTests -pl p2p-server -am

# 运行测试
mvn test

# 运行单个测试类
mvn test -Dtest=SharedKeyAuthenticatorTest

# 运行模块测试
mvn test -pl p2p-common
```

## 架构

### 三模块结构

- **p2p-common** - 共享协议、认证和 P2P 核心类
- **p2p-server** - 信令服务器 + 中继（Spring Boot + Netty）
- **p2p-client** - P2P 数据通道客户端（Spring Boot + Netty）

### 连接流程

1. 两个客户端通过 TCP（端口 8084）向服务器注册
2. 服务器作为信令中介，协助客户端进行 NAT 打洞
3. 打洞成功后，客户端之间通过 UDP 或 TCP 直接通信
4. 打洞失败时，服务器中继数据（兜底方案）

### 核心组件

**p2p-common/protocol/TunnelMessage** - 所有协议消息使用同一类，通过 `MessageType` 枚举区分类型

**p2p-server/server/TunnelServer** - Netty TCP 服务器，接收客户端连接

**p2p-server/server/P2pSignalingHandler** - 通过服务器处理客户端之间的 P2P 信令

**p2p-client/client/P2pManager** - 客户端 P2P 编排：UDP 通道、TCP 打洞、会话管理

**p2p-client/client/p2p/P2pUdpChannel** - UDP 套接字，用于直接 P2P 数据传输

**p2p-client/client/p2p/P2pHolePuncher** - 执行 UDP 打洞，包含重试和超时逻辑

### 认证方式

- **SHARED_KEY** - 共享密钥认证
- **RSA_KEYPAIR** - RSA 公私钥对认证
- **HYBRID** - RSA 优先，共享密钥兜底

密钥生成：`java -cp p2p-server.jar com.tyin.zero.p2pserver.tools.ClientManager generate-keys <client-id>`

## 配置

服务器：`config/server.yaml`，使用 `p2p.server.*` 属性
客户端：`config/client.yaml`，使用 `p2p.client.*` 属性

客户端两种模式：
- **tunnels[]** - 暴露本地服务（在 P2P 关系中作为"服务端"）
- **connect[]** - 连接对端暴露的远程服务

## 服务器工具

`java -jar p2p-server.jar list|add|remove|generate-keys [args...]`