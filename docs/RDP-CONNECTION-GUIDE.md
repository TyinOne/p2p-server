# RDP 远程桌面 P2P 穿透使用指南

## 场景

你在外面（笔记本），想远程访问家里/公司的 Windows 电脑（开了 RDP 3389 端口）。
你有一台公网服务器（如云服务器，公网 IP: 1.2.3.4）。

```
你的笔记本 (外网)              公网服务器                家里电脑 (内网)
┌──────────────────┐        ┌────────────┐          ┌──────────────────┐
│  RDP 客户端      │        │ p2p-server │          │ Windows + RDP    │
│  mstsc           │        │ (信令+中继) │          │ p2p-client       │
│  p2p-client      │        │            │          │                  │
│  连接 localhost  │◄─UDP──►│ 打洞成功:  │◄───UDP──►│ 监听 3389        │
│  :13389          │  直连   │ 零数据流量  │   直连   │                  │
│                  │        │ 打洞失败:  │          │                  │
│                  │◄───────►│ 服务端中继 │◄────────►│                  │
└──────────────────┘        └────────────┘          └──────────────────┘

连接模式: UDP 打洞 → TCP 打洞 → 服务端中继（自动回退）
```

---

## 第一步：编译项目

```bash
cd tyin-p2p
mvn clean package -DskipTests
```

产物：
- `p2p-server/target/p2p-server-0.0.1-SNAPSHOT.jar`
- `p2p-client/target/p2p-client-0.0.1-SNAPSHOT.jar`

---

## 第二步：部署并启动公网服务器

### 部署文件

将以下文件放到公网服务器的同一目录下：

```
server/
├── p2p-server.sh
├── p2p-server-0.0.1-SNAPSHOT.jar
└── config/
    └── server.yaml
```

### 配置文件 `config/server.yaml`

```yaml
p2p:
  server:
    auth-mode: SHARED_KEY
    shared-key: "my-secret-key-123"
    port: 8084
```

### 启动

```bash
cd server
./p2p-server.sh start
```

### 防火墙

放行 TCP 8084（信令通道）：

```bash
sudo ufw allow 8084/tcp
```

---

## 第三步：部署并启动家里电脑（被控端）

### 部署文件

```
home-pc/
├── p2p-client.sh
├── p2p-client-0.0.1-SNAPSHOT.jar
└── config/
    └── client.yaml
```

### 配置文件 `config/client.yaml`

```yaml
p2p:
  client:
    auth: "my-secret-key-123"
    server-host: "1.2.3.4"
    port: 8084
    client-id: "home-pc"

    p2p:
      udp-port: 5000                 # 固定 UDP 端口（防火墙需放行）

    tunnels:
      - remote-port: 3389
        local-port: 3389
        local-address: 127.0.0.1
        description: "家里电脑 RDP"

    connect: []
```

### 确保 Windows 开启了远程桌面

设置 → 系统 → 远程桌面 → 开启

### 启动

```bash
cd home-pc
./p2p-client.sh start
```

---

## 第四步：部署并启动笔记本（控制端）

### 部署文件

```
laptop/
├── p2p-client.sh
├── p2p-client-0.0.1-SNAPSHOT.jar
└── config/
    └── client.yaml
```

### 配置文件 `config/client.yaml`

```yaml
p2p:
  client:
    auth: "my-secret-key-123"
    server-host: "1.2.3.4"
    port: 8084
    client-id: "my-laptop"

    p2p:
      udp-port: 5001                 # 固定 UDP 端口（与被控端不同）

    tunnels: []

    connect:
      - peer-id: "home-pc"
        remote-port: 3389
        local-bind-port: 13389
        description: "家里电脑 RDP"
```

### 启动

```bash
cd laptop
./p2p-client.sh start
```

---

## 第五步：连接远程桌面

```bash
mstsc /v:localhost:13389
```

或打开 Windows 远程桌面连接工具，地址输入 `localhost:13389`。

---

## 部署总结

| 角色 | 目录结构 | 启动命令 |
|------|----------|----------|
| 公网服务器 | `server/{jar, script, config/server.yaml}` | `./p2p-server.sh start` |
| 家里电脑 | `home-pc/{jar, script, config/client.yaml}` | `./p2p-client.sh start` |
| 笔记本 | `laptop/{jar, script, config/client.yaml}` | `./p2p-client.sh start` |

每个目录结构完全相同：jar + 脚本 + config/。脚本自动检测 `config/` 下的配置文件。

---

## 故障排查

### 打洞超时（Hole punch timed out）

- 检查两端防火墙是否放行 UDP 端口
- 一端在对称 NAT (Symmetric NAT) 后面时 UDP 打洞会失败，系统会自动尝试 TCP 打洞
- TCP 打洞也失败时，自动回退到服务端中继模式（连接仍可用，但流量经过服务器）
- 查看服务端日志确认两端公网地址是否正确

### 认证失败

- 两端的 `auth` 是否与服务端的 `shared-key` 完全一致
- `server-host` 是否正确
- 测试连通性：`telnet 1.2.3.4 8084`

### P2P 成功但 RDP 连不上

- 家里电脑是否开启了远程桌面
- RDP 端口是否是 3389（默认）
- 本地防火墙是否放行 3389

### 如何确认连接模式？

- **UDP 直连**: 客户端日志有 `P2P channel established`，服务端无 `RELAY_DATA` 日志
- **TCP 直连**: 客户端日志有 `P2P channel established via TCP punch`
- **服务端中继**: 客户端日志有 `Relay mode ready for peer`，服务端有 `RELAY_DATA` 转发日志
- `netstat -an | grep 8084` 可观察服务端数据流量（中继模式下有明显流量）
