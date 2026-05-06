# 配置文件说明

## 配置文件结构

```
部署目录/
├── p2p-server.sh                    # 管理脚本
├── p2p-server-0.0.1-SNAPSHOT.jar    # 服务端 jar
└── config/
    └── server.yaml                  # 服务端配置

部署目录/
├── p2p-client.sh                    # 管理脚本
├── p2p-client-0.0.1-SNAPSHOT.jar    # 客户端 jar
└── config/
    └── client.yaml                  # 客户端配置
```

---

## 服务端配置

服务端负责信令中转和数据中继（兜底），配置非常简单：

```yaml
# config/server.yaml
p2p:
  server:
    auth-mode: SHARED_KEY         # SHARED_KEY 或 RSA_KEYPAIR
    shared-key: "my-secret-key"   # 共享密钥
    port: 8084                    # 信令端口
```

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `auth-mode` | SHARED_KEY | 认证模式 |
| `shared-key` | - | 共享密钥 |
| `port` | 8084 | 客户端连接端口 |

---

## 客户端配置

所有客户端配置结构完全相同。每个客户端既可暴露本地服务，也可连接对端服务。

```yaml
# config/client.yaml
p2p:
  client:
    auth: "my-secret-key"           # 必须与服务端一致
    server-host: "1.2.3.4"          # 服务端地址
    port: 8084                      # 服务端端口
    client-id: "my-client"          # 客户端标识

    p2p:                            # P2P 通道配置（可选）
      udp-port: 0                   # UDP 端口，0=系统分配
      upnp-enabled: false           # 自动 UPnP 端口映射
      hole-punch-timeout-ms: 10000  # 打洞超时
      hole-punch-interval-ms: 500   # 打洞间隔
      heartbeat-interval-ms: 15000  # 心跳间隔
      session-timeout-ms: 60000     # 会话超时

    tunnels:                        # 暴露的本地服务
      - remote-port: 3390           # 公开端口（对端通过此端口访问）
        local-port: 3389            # 本地服务端口
        local-address: 127.0.0.1    # 本地服务地址
        description: "RDP"

    connect:                        # 连接的对端服务
      - peer-id: "other-client"     # 对端客户端 ID
        remote-port: 3390           # 对端暴露的端口
        local-bind-port: 13390      # 本地监听端口
        description: "对端 RDP"
```

### tunnels vs connect

| 字段 | 含义 | 示例 |
|------|------|------|
| `tunnels` | 我提供什么服务 | 本机 RDP 3389 → 暴露为 3390 |
| `connect` | 我需要什么服务 | 连接对端 3390 → 本地监听 13390 |

---

## 连接模式

客户端建立 P2P 连接时，按以下顺序自动尝试：

| 优先级 | 模式 | 说明 | 服务端数据流量 |
|--------|------|------|----------------|
| 1 | UDP 打洞 | 双方向对方公网地址发 UDP 包打通 NAT | 无 |
| 2 | TCP 打洞 | 双方向对方 TCP 监听端口同时发起连接 | 无 |
| 3 | 服务端中继 | 数据通过服务端转发（兜底） | 有 |

- 模式 1、2 成功后客户端直连，服务端零数据流量
- 模式 3 作为兜底，确保在严格 NAT 环境下也能连通
- 整个过程自动完成，无需手动配置

---

## 配置优先级

```
1. 命令行参数（最高）
   --p2p.client.auth=temp-key

2. 外部配置文件（-c 指定）
   ./p2p-client.sh start -c config/custom.yaml

3. application.yaml（jar 内部）

4. 代码默认值（最低）
```

---

## 使用示例

### 示例 1：暴露 RDP 服务

```yaml
# config/client.yaml
p2p:
  client:
    auth: "my-secret-key"
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

### 示例 2：连接对端 RDP

```yaml
# config/client.yaml
p2p:
  client:
    auth: "my-secret-key"
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

然后：`mstsc /v:localhost:13389`

### 示例 3：同时暴露和连接

```yaml
p2p:
  client:
    auth: "my-secret-key"
    server-host: "1.2.3.4"
    port: 8084
    client-id: "client-a"
    p2p:
      udp-port: 0
    tunnels:
      - remote-port: 3390
        local-port: 3389
        local-address: 127.0.0.1
        description: "本机 RDP"
    connect:
      - peer-id: "client-b"
        remote-port: 3390
        local-bind-port: 13390
        description: "client-b 的 RDP"
```

---

## 安全建议

```bash
# 保护配置文件
chmod 600 config/*.yaml
echo "config/*.yaml" >> .gitignore

# 使用环境变量
export P2P_SHARED_KEY="secret-key"
```

```yaml
p2p:
  server:
    shared-key: ${P2P_SHARED_KEY}
```
