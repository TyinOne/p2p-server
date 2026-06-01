# P2P 并行连接与自动切换设计

**日期：** 2026-05-29
**目标：** 缩短连接时间 + 提升传输性能

---

## 背景

当前 P2P 连接流程：
1. TCP 打洞 → 超时（5s）
2. UDP 打洞 → 超时（10s）
3. RELAY → 启用

**问题：** 连接耗时 15-20 秒，用户体验差

---

## 设计方案

### 核心思路

**并行启动 + 自动切换 + RELAY 保底备份**

```
启动
  ↓
┌─────────────────────────────────────┐
│  1. RELAY 立即启动 → 用户秒连可用    │
│  2. 并行启动 TCP/UDP 直连尝试       │
│  3. 直连成功 → 自动切换到直连模式    │
│  4. RELAY 保持连接（备份）           │
│  5. 直连断开 → 自动回切到 RELAY     │
└─────────────────────────────────────┘
```

---

### 详细流程

#### Phase 1: 快速启动
```
收到 TCP_PUNCH_START 或 P2P_REQUEST
    ↓
启动 RELAY 通道 → 用户立即可用的保底连接
同时启动 TCP 打洞 + UDP 打洞（后台并行）
```

#### Phase 2: 直连检测
```
TCP 打洞成功 → 立即切换到 TCP 直连
UDP 打洞成功 → 立即切换到 UDP 直连
RELAY 保持连接不断（作为备份）
```

#### Phase 3: 后台持续尝试
```
即使直连失败，也持续在后台尝试 TCP/UDP 打洞
每 30 秒重试一次（可配置）
```

#### Phase 4: 自动切换
```
直连成功 → 自动从 RELAY 切换到直连
RELAY 保持连接备份
直连断开 → 自动回切到 RELAY
```

---

### 数据结构

#### 连接模式枚举
```java
public enum ConnectionMode {
    RELAY,           // 仅 RELAY
    TCP_DIRECT,      // TCP 直连
    UDP_DIRECT,      // UDP 直连
    P2P_DIRECT       // 通用直连（TCP 或 UDP）
}
```

#### P2pSession 扩展
```java
public class P2pSession {
    ConnectionMode mode;        // 当前使用的模式
    ConnectionMode preferredMode; // 偏好的模式（直连优先）
}
```

#### 连接状态（概念说明，非实际类）

利用现有 `tcpPeerChannels` 和 `relayPeers` 跟踪连接状态：
- `tcpPeerChannels`: 存储 TCP 直连 Channel
- `relayPeers`: 标记当前使用 RELAY 模式的 peer
- `hasActiveConnection()`: 检查 P2pTcpListener 是否有活跃连接

---

### 配置文件新增

```yaml
p2p:
  p2p:
    # 并行连接配置
    parallelConnectEnabled: true     # 默认 true，启用并行连接（秒连）
    directConnectRetryIntervalMs: 30000  # 直连重试间隔
    autoSwitchToDirect: true         # 直连成功后自动切换
    keepRelayAsBackup: true          # RELAY 保持备份
    switchDuringActiveConnection: false  # 活跃连接时不切换
```

**默认行为：** `parallelConnectEnabled: true` 即默认启用并行连接

---

### 接口设计

#### P2pManager 核心方法

```java
/**
 * 并行启动所有通道（RELAY + TCP + UDP）
 */
public void startParallelConnection(String peerId, InetSocketAddress peerAddr) {
    // 1. 立即启动 RELAY（保底）
    startRelayMode(peerId);
    
    // 2. 并行启动 TCP 打洞
    startTcpPunchInBackground(peerId, peerAddr);
    
    // 3. 并行启动 UDP 打洞
    startUdpPunchInBackground(peerId, peerAddr);
}

/**
 * 切换到直连模式
 */
public void switchToDirectMode(String peerId, ConnectionMode mode) {
    // 1. 更新 session 模式
    // 2. 发送 P2P_SUCCESS 通知服务端
    // 3. RELAY 保持备份不断
}

/**
 * 切换回 RELAY 模式
 */
public void switchToRelayMode(String peerId) {
    // 1. 更新 session 模式
    // 2. 通知服务端
}
```

---

### 切换策略（保守方案）

**核心原则：活跃连接时不自动切换**

```yaml
p2p:
  p2p:
    parallelConnectEnabled: true
    directConnectRetryIntervalMs: 30000
    autoSwitchToDirect: true          # 直连成功后自动切换
    keepRelayAsBackup: true            # RELAY 保持备份
    switchDuringActiveConnection: false  # 活跃连接时不切换
```

**切换判断逻辑：**

```java
private boolean canSwitchToDirect(String peerId) {
    // 1. 检查当前是否为 RELAY 模式
    if (!relayPeers.contains(peerId)) return false;

    // 2. 检查是否有活跃的连接
    if (hasActiveConnection(peerId)) {
        log.info("Cannot switch to direct mode: active connection exists");
        return false;
    }

    return true;
}

private boolean hasActiveConnection(String peerId) {
    // 检查本地 TCP 监听端口是否有活跃连接
    P2pTcpListener listener = tcpListeners.get(peerId);
    return listener != null && listener.hasActiveConnection();
}

// P2pTcpListener.java 需新增方法：
// public boolean hasActiveConnection() {
//     return activeChannel != null && activeChannel.isActive();
// }
```

**实际行为：**
- RELAY 立即可用，用户可正常使用任何服务（RDP、SSH 等）
- 后台持续尝试直连
- 只有当**无活跃连接**时才切换到直连
- 断开连接后，下次连接将直接使用直连

```java
private void scheduleDirectConnectRetry(String peerId) {
    heartbeatScheduler.scheduleWithFixedDelay(() -> {
        if (relayPeers.contains(peerId)) {
            // 当前在 RELAY 模式，尝试直连
            P2pSession session = sessions.get(peerId);
            if (session != null && session.getPeerAddress() != null) {
                log.debug("Background retry direct connect to {}", peerId);
                holePuncher.startPunching(
                    session.getPeerAddress(),
                    intervalMs,
                    timeoutMs,
                    () -> switchToDirectMode(peerId, ConnectionMode.UDP_DIRECT),
                    () -> {} // 失败静默，继续 RELAY
                );
            }
        }
    }, retryIntervalMs, retryIntervalMs, TimeUnit.MILLISECONDS);
}
```

---

## 改动文件

| 文件 | 改动 |
|------|------|
| `P2pManager.java` | 并行连接、模式切换、重试逻辑 |
| `P2pSession.java` | 添加 mode 字段 |
| `ClientConfig.java` | 新增配置项 |
| `config-examples/*.yaml` | 更新配置示例 |

---

## 兼容性

- 旧配置可继续使用（parallelConnectEnabled 默认为 true）
- 服务端无需修改
- RELAY 模式对服务端透明

---

## 风险评估

| 风险 | 缓解措施 |
|------|----------|
| 直连和 RELAY 同时发送数据导致乱序 | 在切换前 flush 所有数据，切换后重新建立会话 |
| 后台重试消耗资源 | 使用指数退避或限制最大并发数 |
| 对称型 NAT 环境下直连持续失败 | 持续尝试但不影响 RELAY使用 |