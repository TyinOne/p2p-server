# Tyin P2P Tunnel - P2P 内网穿透系统

## 项目简介

Tyin P2P Tunnel 是一个基于 **Spring Boot 4.0** + **Netty 4.1** 的 P2P 内网穿透系统。客户端之间优先通过 UDP/TCP 打洞建立直连通道，打洞失败时自动回退到服务端中继，确保连接始终可用。

### 核心特性

- **多模式穿透** - UDP 打洞 → TCP 打洞 → 服务端中继，三级自动回退
- **统一客户端** - 所有客户端配置结构完全相同，无角色区分
- **声明式配置** - `tunnels` 暴露本地服务，`connect` 连接对端服务
- **动态注册** - 客户端自注册隧道，服务器无需预配置
- **混合认证** - 支持共享密钥和 RSA 密钥对两种认证方式
- **UPnP 支持** - 自动端口映射，简化路由器配置
- **跨平台** - 提供 Linux/Mac (sh) 和 Windows (bat/ps1) 管理脚本

---

## 架构

```
客户端 A (用户端)                    公网服务器                       客户端 B (内网端)
┌──────────────────┐              ┌────────────────┐              ┌──────────────────┐
│ p2p-client       │              │ p2p-server     │              │ p2p-client       │
│                  │              │                │              │                  │
│ connect:         │   UDP/TCP    │ P2pRegistry    │   UDP/TCP    │ tunnels:         │
│  peer-id: B      │◄──打洞直连──►│ (信令+中继)    │◄──打洞直连──►│  expose: 3389    │
│  remote-port:3390│  直连通道    │                │  直连通道    │                  │
│                  │              │  打洞失败时    │              │                  │
│ 本地 TCP 监听    │              │  转发数据流量  │              │ TCP→内网服务     │
│ :13390          │              └────────────────┘              │ → 127.0.0.1:3389│
└──────────────────┘                                              └──────────────────┘
         │                                                                 │
    mstsc /v:localhost:13390                                          RDP 服务:3389

连接模式优先级: UDP 打洞 → TCP 打洞 → 服务端中继（兜底）
```

---

## 项目结构

```
tyin-p2p/
├── p2p-common/              # 公共模块
│   └── src/main/java/com.tyin.zero.p2pcommon/
│       ├── protocol/        # 消息协议
│       │   ├── TunnelMessage.java
│       │   ├── TunnelMessageCodec.java
│       │   ├── TunnelChannelInitializer.java
│       │   └── JacksonConfig.java
│       ├── p2p/             # P2P 通用类
│       │   ├── TunnelExposeConfig.java
│       │   ├── P2pSession.java
│       │   └── P2pUdpCodec.java
│       ├── auth/            # 认证系统
│       │   ├── AuthManager.java
│       │   ├── SharedKeyAuthenticator.java
│       │   └── RsaKeyAuthenticator.java
│       └── util/
│           └── FilePermissionUtil.java
│
├── p2p-server/              # 服务端模块（信令 + 中继）
│   └── src/main/java/com.tyin.zero.p2pserver/
│       ├── config/
│       │   └── ServerConfig.java
│       ├── server/
│       │   ├── TunnelServer.java
│       │   ├── TunnelServerHandler.java
│       │   ├── P2pSignalingHandler.java
│       │   └── P2pRegistry.java
│       └── tools/
│           └── ClientManager.java
│
├── p2p-client/              # 客户端模块（P2P 数据通道）
│   └── src/main/java/com.tyin.zero.p2pclient/
│       ├── config/
│       │   └── ClientConfig.java
│       └── client/
│           ├── TunnelClient.java
│           ├── TunnelClientHandler.java
│           └── p2p/
│               ├── P2pManager.java
│               ├── P2pUdpChannel.java
│               ├── P2pHolePuncher.java
│               ├── P2pTcpListener.java
│               ├── P2pTcpBridgeHandler.java
│               └── UpnpPortMapper.java
│
├── config-examples/         # 配置示例
├── docs/                    # 文档
│   └── RDP-CONNECTION-GUIDE.md
└── pom.xml
```

---

## 快速开始

### 环境要求

- **JDK**: 21+
- **Maven**: 3.8+

### 编译

```bash
cd tyin-p2p
mvn clean package -DskipTests
```

### 部署

将 jar、脚本、配置文件放到同一目录：

```
部署目录/
├── p2p-server.sh (或 p2p-server.bat)
├── p2p-server-0.0.1-SNAPSHOT.jar
└── config/
    └── server.yaml
```

### 服务端配置 `config/server.yaml`

```yaml
p2p:
  server:
    auth-mode: SHARED_KEY
    shared-key: "my-secret-key-123"
    port: 8084
```

### 客户端配置（统一格式）

暴露本地服务：
```yaml
p2p:
  client:
    auth: "my-secret-key-123"
    server-host: "1.2.3.4"
    port: 8084
    client-id: "home-pc"
    p2p:
      udp-port: 0
    tunnels:
      - remote-port: 3389
        local-port: 3389
        local-address: 127.0.0.1
        description: "RDP"
    connect: []
```

连接对端服务：
```yaml
p2p:
  client:
    auth: "my-secret-key-123"
    server-host: "1.2.3.4"
    port: 8084
    client-id: "my-laptop"
    p2p:
      udp-port: 0
    tunnels: []
    connect:
      - peer-id: "home-pc"
        remote-port: 3389
        local-bind-port: 13389
        description: "家里电脑 RDP"
```

### 启动

```bash
# 服务端
./p2p-server.sh start

# 客户端（自动检测 config/client.yaml）
./p2p-client.sh start

# 或指定配置文件
./p2p-client.sh start -c config/my-config.yaml
```

详见 [RDP 连接指南](docs/RDP-CONNECTION-GUIDE.md)。

---

## 配置说明

### 服务端

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `p2p.server.auth-mode` | SHARED_KEY | 认证模式：SHARED_KEY / RSA_KEYPAIR |
| `p2p.server.shared-key` | - | 共享密钥 |
| `p2p.server.port` | 8084 | 信令端口 |

### 客户端

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `p2p.client.auth` | - | 认证密钥（与服务端一致） |
| `p2p.client.server-host` | - | 服务端地址 |
| `p2p.client.port` | 8084 | 服务端端口 |
| `p2p.client.client-id` | 自动生成 | 客户端标识 |
| `p2p.client.p2p.udp-port` | 0 | UDP 端口（0=系统分配） |
| `p2p.client.p2p.upnp-enabled` | false | 自动 UPnP 端口映射 |
| `p2p.client.p2p.hole-punch-timeout-ms` | 10000 | 打洞超时 |
| `p2p.client.p2p.heartbeat-interval-ms` | 15000 | 心跳间隔 |

---

## 认证系统

### SHARED_KEY 模式

```yaml
# 服务端
p2p.server.auth-mode: SHARED_KEY
p2p.server.shared-key: "my-secret-key"

# 客户端
p2p.client.auth: "my-secret-key"
```

### RSA_KEYPAIR 模式

```bash
# 生成密钥对
java -cp p2p-server.jar com.tyin.zero.p2pserver.tools.ClientManager generate-keys client-home

# 注册公钥
java -cp p2p-server.jar com.tyin.zero.p2pserver.tools.ClientManager add client-home $(cat keys/client-home-public.key)

# 将私钥复制到客户端
```

---

## 管理命令

```bash
./p2p-server.sh start [-c config.yaml]   # 启动
./p2p-server.sh stop                      # 停止
./p2p-server.sh restart [-c config.yaml]  # 重启
./p2p-server.sh reload                    # 零停机重载
./p2p-server.sh status                    # 查看状态
./p2p-server.sh list                      # 列出客户端
./p2p-server.sh add <id> <key>            # 添加客户端
./p2p-server.sh remove <id>               # 移除客户端
./p2p-client.sh start [-c config.yaml]    # 启动客户端
./p2p-client.sh stop                      # 停止客户端
```

---

## 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Java | 21 | 虚拟线程、records、switch 表达式 |
| Spring Boot | 4.0.6 | 应用框架 |
| Netty | 4.1.118 | 网络框架（TCP 信令 + UDP 数据） |
| Jackson | 2.18.2 | JSON 序列化 |
| Lombok | 1.18.36 | 代码简化 |

---

## 许可证

MIT License
