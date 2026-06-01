# P2P 并行连接实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 P2P 并行连接：RELAY 立即可用 + TCP/UDP 后台打洞 + 自动切换直连

**Architecture:** 修改 P2pManager 的连接流程，在收到 TCP_PUNCH_START 或 P2P_REQUEST 时**直接**设置 RELAY 模式并启动本地监听，同时后台并行打洞；添加直连检测和自动切换逻辑；保守策略（活跃连接不切换）。

**Tech Stack:** Java 21, Spring Boot, Netty, Maven

---

## 文件结构

| 文件 | 改动 |
|------|------|
| `p2p-common/src/main/java/com/tyin/zero/p2pcommon/p2p/ConnectionMode.java` | 新增：连接模式枚举 |
| `p2p-common/src/main/java/com/tyin/zero/p2pcommon/p2p/P2pSession.java` | 修改：添加 mode 字段 |
| `p2p-client/src/main/java/com/tyin/zero/p2pclient/config/ClientConfig.java` | 修改：添加并行连接配置项 |
| `p2p-client/src/main/java/com/tyin/zero/p2pclient/client/p2p/P2pTcpListener.java` | 修改：添加 hasActiveConnection() 方法 |
| `p2p-client/src/main/java/com/tyin/zero/p2pclient/client/p2p/P2pManager.java` | 修改：并行连接、模式切换逻辑、直连断开回切、后台重试 |
| `config-examples/test/test-client1.yaml` | 修改：添加并行连接配置 |
| `config-examples/test/test-client2.yaml` | 修改：添加并行连接配置 |

---

## Task 1: 添加 ConnectionMode 枚举

**Files:**
- Create: `p2p-common/src/main/java/com/tyin/zero/p2pcommon/p2p/ConnectionMode.java`

- [ ] **Step 1: 创建 ConnectionMode 枚举**

```java
package com.tyin.zero.p2pcommon.p2p;

/**
 * P2P 连接模式
 */
public enum ConnectionMode {
    /**
     * 中继模式（服务器转发）
     */
    RELAY,

    /**
     * TCP 直连模式
     */
    TCP_DIRECT,

    /**
     * UDP 直连模式
     */
    UDP_DIRECT,

    /**
     * 通用直连模式（TCP 或 UDP）
     * 用于直连成功但具体协议未确定的情况
     */
    P2P_DIRECT
}
```

- [ ] **Step 2: 提交**

```bash
git add p2p-common/src/main/java/com/tyin/zero/p2pcommon/p2p/ConnectionMode.java
git commit -m "feat(p2p): add ConnectionMode enum for tracking connection type"
```

---

## Task 2: 修改 P2pSession 添加 mode 字段

**Files:**
- Modify: `p2p-common/src/main/java/com/tyin/zero/p2pcommon/p2p/P2pSession.java:22-26`

- [ ] **Step 1: 添加 mode 字段**

在 `P2pSession.java` 中，`State state` 字段后添加：

```java
private volatile ConnectionMode mode = ConnectionMode.RELAY;
```

在 `isExpired()` 方法后添加：

```java
public ConnectionMode getMode() {
    return mode;
}

public void setMode(ConnectionMode mode) {
    this.mode = mode;
}
```

- [ ] **Step 2: 提交**

```bash
git add p2p-common/src/main/java/com/tyin/zero/p2pcommon/p2p/P2pSession.java
git commit -m "feat(p2p): add mode field to P2pSession for tracking connection mode"
```

---

## Task 3: 修改 ClientConfig 添加并行连接配置

**Files:**
- Modify: `p2p-client/src/main/java/com/tyin/zero/p2pclient/config/ClientConfig.java:52-87`

- [ ] **Step 1: 在 P2pConfig 类中添加配置字段**

在 `P2pConfig` 类 `sessionTimeoutMs` 字段后添加：

```java
/**
 * 是否启用并行连接（RELAY 立即启动 + TCP/UDP 后台打洞）
 */
private boolean parallelConnectEnabled = true;

/**
 * 直连重试间隔（毫秒）
 */
private long directConnectRetryIntervalMs = 30000;

/**
 * 直连成功后是否自动切换
 */
private boolean autoSwitchToDirect = true;

/**
 * RELAY 是否保持备份
 */
private boolean keepRelayAsBackup = true;

/**
 * 活跃连接时是否切换到直连（保守策略）
 */
private boolean switchDuringActiveConnection = false;
```

- [ ] **Step 2: 提交**

```bash
git add p2p-client/src/main/java/com/tyin/zero/p2pclient/config/ClientConfig.java
git commit -m "feat(config): add parallel connection settings to P2pConfig"
```

---

## Task 4: P2pTcpListener 添加 hasActiveConnection() 方法

**Files:**
- Modify: `p2p-client/src/main/java/com/tyin/zero/p2pclient/client/p2p/P2pTcpListener.java:86-99`

- [ ] **Step 1: 在 P2pTcpListener 中添加 hasActiveConnection() 方法**

在 `stop()` 方法后添加：

```java
/**
 * 检查是否有活跃的连接
 */
public boolean hasActiveConnection() {
    if (serverChannel == null || !serverChannel.isActive()) {
        return false;
    }
    // 检查是否有子 Channel 活跃
    return serverChannel.pipeline().channels().stream()
            .filter(ch -> ch instanceof io.netty.channel.socket.SocketChannel && ch.isActive())
            .findFirst()
            .isPresent();
}
```

- [ ] **Step 2: 提交**

```bash
git add p2p-client/src/main/java/com/tyin/zero/p2pclient/client/p2p/P2pTcpListener.java
git commit -m "feat(p2p): add hasActiveConnection() method to P2pTcpListener"
```

---

## Task 5: 修改 P2pManager - handleTcpPunchStart 并行启动 RELAY

**Files:**
- Modify: `p2p-client/src/main/java/com/tyin/zero/p2pclient/client/p2p/P2pManager.java:334-379`

**核心改动：并行模式下直接设置 RELAY 状态，不通知服务器，不等待 RELAY_READY**

- [ ] **Step 1: 修改 handleTcpPunchStart 方法实现并行连接**

将现有 `handleTcpPunchStart` 方法替换为：

```java
/**
 * 处理 TCP 打洞开始指令
 * 并行模式：立即启用 RELAY + 后台并行 TCP/UDP 打洞
 */
public void handleTcpPunchStart(TunnelMessage msg) {
    String peerId = msg.getPeerId();

    // 如果已在 relay 模式，忽略 TCP 打洞指令
    if (relayPeers.contains(peerId)) {
        log.info("TCP_PUNCH_START ignored for {}: already in relay mode", peerId);
        return;
    }

    String peerTcpAddr = msg.getCandidateAddr();

    if (peerTcpAddr == null) {
        log.warn("TCP_PUNCH_START without peer TCP address for {}", peerId);
        return;
    }

    String[] parts = peerTcpAddr.split(":");
    if (parts.length != 2) {
        log.warn("Invalid peer TCP address: {}", peerTcpAddr);
        return;
    }

    String host = parts[0];
    int port = Integer.parseInt(parts[1]);

    log.info("TCP punch start: myId={}, peerId={}, peerTcpAddr={}",
            clientConfig.getClientId(), peerId, peerTcpAddr);

    // 检查是否启用并行连接
    boolean parallelEnabled = clientConfig.getP2p().isParallelConnectEnabled();

    if (parallelEnabled) {
        // 并行模式：直接设置 RELAY 状态（不通知服务器）
        log.info("Parallel connect: starting RELAY immediately for {}", peerId);

        // 创建/更新会话
        P2pSession session = sessions.computeIfAbsent(peerId, P2pSession::new);
        session.setState(P2pSession.State.ESTABLISHED);
        session.setMode(ConnectionMode.RELAY);

        // 添加到 RELAY 集合
        relayPeers.add(peerId);

        // 启动本地 TCP 监听（用户可立即连接）
        for (ClientConfig.PeerTunnel peerTunnel : clientConfig.getConnect()) {
            if (peerId.equals(peerTunnel.getPeerId())) {
                startLocalTcpListener(peerTunnel, session);
                break;
            }
        }

        log.info("Parallel connect: RELAY mode active for {}, background TCP punching continues...", peerId);

        // 存储预期的对端地址（用于匹配入站连接）
        tcpPendingPeers.put(host + ":" + port, peerId);

        // 发起 TCP 连接尝试
        for (int i = 0; i < 3; i++) {
            connectTcpPunch(peerId, host, port, () -> onTcpPunchSuccess(peerId));
        }

        // 等待 TCP 结果，超时后降级 UDP
        scheduleTcpTimeoutFallback(peerId, host, port);
    } else {
        // 非并行模式：保持原有行为
        tcpPendingPeers.put(host + ":" + port, peerId);
        for (int i = 0; i < 3; i++) {
            connectTcpPunch(peerId, host, port, () -> onTcpPunchSuccess(peerId));
        }
        scheduleTcpTimeoutFallback(peerId, host, port);
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add p2p-client/src/main/java/com/tyin/zero/p2pclient/client/p2p/P2pManager.java
git commit -m "feat(p2p): implement parallel connect in handleTcpPunchStart"
```

---

## Task 6: 修改 P2pManager - sendP2pRequest 支持并行连接

**Files:**
- Modify: `p2p-client/src/main/java/com/tyin/zero/p2pclient/client/p2p/P2pManager.java:240-254`

- [ ] **Step 1: 修改 sendP2pRequest 方法**

将现有 `sendP2pRequest` 方法替换为：

```java
/**
 * 发起 P2P 连接请求
 */
public void sendP2pRequest(String peerId, int remotePort) {
    if (serverCtx == null) return;

    P2pSession session = new P2pSession(peerId);
    sessions.put(peerId, session);

    // 并行模式：直接设置 RELAY 状态并启动本地监听
    if (clientConfig.getP2p().isParallelConnectEnabled()) {
        log.info("Parallel connect: marking {} as RELAY mode before P2P_REQUEST response", peerId);
        session.setMode(ConnectionMode.RELAY);
        // 注意：本地监听和 relayPeers 在收到 candidate 或 TCP_PUNCH_START 后启动
    }

    TunnelMessage msg = new TunnelMessage();
    msg.setType(TunnelMessage.MessageType.P2P_REQUEST);
    msg.setClientId(clientConfig.getClientId());
    msg.setPeerId(peerId);
    msg.setRemotePort(remotePort);
    serverCtx.writeAndFlush(msg);

    log.info("Sent P2P request: myId={} → peerId={} (port {})", clientConfig.getClientId(), peerId, remotePort);
}
```

- [ ] **Step 2: 提交**

```bash
git add p2p-client/src/main/java/com/tyin/zero/p2pclient/client/p2p/P2pManager.java
git commit -m "feat(p2p): update sendP2pRequest for parallel connect"
```

---

## Task 7: 修改 P2pManager - handleCandidate 支持并行连接

**Files:**
- Modify: `p2p-client/src/main/java/com/tyin/zero/p2pclient/client/p2p/P2pManager.java:259-278`

- [ ] **Step 1: 修改 handleCandidate 方法**

将现有 `handleCandidate` 方法替换为：

```java
/**
 * 处理对端候选地址
 */
public void handleCandidate(TunnelMessage msg) {
    String peerId = msg.getPeerId();
    String candidateAddr = msg.getCandidateAddr();

    log.info("Received candidate: myId={}, msgClientId={}, peerId={}, addr={}",
            clientConfig.getClientId(), msg.getClientId(), peerId, candidateAddr);

    P2pSession session = sessions.get(peerId);
    if (session == null) {
        session = new P2pSession(peerId);
        sessions.put(peerId, session);
    }

    String[] parts = candidateAddr.split(":");
    if (parts.length == 2) {
        String host = parts[0];
        int port = Integer.parseInt(parts[1]);
        session.setPeerAddress(new InetSocketAddress(host, port));
    }

    // 并行模式：收到 candidate 后立即启用 RELAY
    if (clientConfig.getP2p().isParallelConnectEnabled()
            && session.getMode() == ConnectionMode.RELAY
            && !relayPeers.contains(peerId)) {
        log.info("Parallel connect: enabling RELAY for {} upon receiving candidate", peerId);
        relayPeers.add(peerId);

        // 启动本地 TCP 监听
        if (clientConfig.getConnect() != null) {
            for (ClientConfig.PeerTunnel peerTunnel : clientConfig.getConnect()) {
                if (peerId.equals(peerTunnel.getPeerId())) {
                    startLocalTcpListener(peerTunnel, session);
                    break;
                }
            }
        }
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add p2p-client/src/main/java/com/tyin/zero/p2pclient/client/p2p/P2pManager.java
git commit -m "feat(p2p): update handleCandidate for parallel connect"
```

---

## Task 8: 修改 P2pManager - 添加自动切换到直连逻辑

**Files:**
- Modify: `p2p-client/src/main/java/com/tyin/zero/p2pclient/client/p2p/P2pManager.java:426-436`

- [ ] **Step 1: 修改 onTcpPunchSuccess 方法实现自动切换**

替换 `onTcpPunchSuccess` 方法：

```java
private void onTcpPunchSuccess(String peerId) {
    tcpWaitingFallback.remove(peerId);

    // 检查是否启用了自动切换且当前是 RELAY 模式
    if (clientConfig.getP2p().isAutoSwitchToDirect() && relayPeers.contains(peerId)) {
        // 检查是否有活跃连接
        P2pTcpListener listener = tcpListeners.get(peerId);
        boolean hasActive = listener != null && listener.hasActiveConnection();

        if (hasActive && !clientConfig.getP2p().isSwitchDuringActiveConnection()) {
            log.info("TCP punch succeeded for {} but active connection exists, keeping RELAY", peerId);
            // 保持 RELAY 模式，但更新会话状态
            P2pSession session = sessions.get(peerId);
            if (session != null) {
                session.setState(P2pSession.State.ESTABLISHED);
                session.updateSeen();
                session.setMode(ConnectionMode.TCP_DIRECT);
            }
            sendP2pSuccess(peerId);
            return;
        }

        // 执行切换
        log.info("Switching from RELAY to TCP_DIRECT for peer {}", peerId);
        relayPeers.remove(peerId);

        P2pSession session = sessions.get(peerId);
        if (session != null) {
            session.setState(P2pSession.State.ESTABLISHED);
            session.updateSeen();
            session.setMode(ConnectionMode.TCP_DIRECT);
        }

        sendP2pSuccess(peerId);
        onP2pEstablished(peerId, session);
    } else {
        P2pSession session = sessions.get(peerId);
        if (session != null && session.getState() != P2pSession.State.ESTABLISHED) {
            session.setState(P2pSession.State.ESTABLISHED);
            session.updateSeen();
            session.setMode(ConnectionMode.TCP_DIRECT);
            sendP2pSuccess(peerId);
            onP2pEstablished(peerId, session);
        }
    }

    log.info("P2P channel established via TCP punch with {}", peerId);
}
```

- [ ] **Step 2: 修改 scheduleTcpTimeoutFallback 中的 UDP 成功回调**

找到 `scheduleTcpTimeoutFallback` 方法中的 UDP 成功回调（约在 406-411 行），替换为：

```java
() -> {
    session.setState(P2pSession.State.ESTABLISHED);
    session.updateSeen();
    session.setMode(ConnectionMode.UDP_DIRECT);

    // 自动切换逻辑（与 TCP 类似）
    if (clientConfig.getP2p().isAutoSwitchToDirect() && relayPeers.contains(peerId)) {
        P2pTcpListener listener = tcpListeners.get(peerId);
        boolean hasActive = listener != null && listener.hasActiveConnection();

        if (!hasActive || clientConfig.getP2p().isSwitchDuringActiveConnection()) {
            log.info("Switching from RELAY to UDP_DIRECT for peer {}", peerId);
            relayPeers.remove(peerId);
        } else {
            log.info("UDP punch succeeded for {} but active connection exists, keeping RELAY", peerId);
        }
    }

    sendP2pSuccess(peerId);
    onP2pEstablished(peerId, session);
    log.info("P2P channel established via UDP fallback with {}", peerId);
}
```

- [ ] **Step 3: 提交**

```bash
git add p2p-client/src/main/java/com/tyin/zero/p2pclient/client/p2p/P2pManager.java
git commit -m "feat(p2p): implement auto-switch to direct mode"
```

---

## Task 9: 修改 P2pManager - handleHolePunch 支持并行连接

**Files:**
- Modify: `p2p-client/src/main/java/com/tyin/zero/p2pclient/client/p2p/P2pManager.java:280-332`

- [ ] **Step 1: 修改 handleHolePunch 方法实现并行连接**

将现有 `handleHolePunch` 方法替换为：

```java
/**
 * 处理打洞指令（UDP）
 * 并行模式：直接启用 RELAY，同时后台 UDP 打洞
 */
public void handleHolePunch(TunnelMessage msg) {
    String peerId = msg.getPeerId();
    log.info("Handle hole punch: myId={}, msgClientId={}, peerId={}, sessions={}",
            clientConfig.getClientId(), msg.getClientId(), peerId, sessions.keySet());

    // 如果已在 relay 模式，忽略 hole punch 指令
    if (relayPeers.contains(peerId)) {
        log.info("Hole punch ignored for {}: already in relay mode", peerId);
        return;
    }

    P2pSession session = sessions.get(peerId);

    if (session == null) {
        log.warn("Cannot start hole punch: no session for peer {} (myId={})", peerId, clientConfig.getClientId());
        sendP2pFailed(peerId);
        return;
    }

    if (session.getPeerAddress() == null) {
        log.warn("Cannot start hole punch: no peer address for {} (session state={})", peerId, session.getState());
        sendP2pFailed(peerId);
        return;
    }

    session.setState(P2pSession.State.PUNCHING);

    // 检查是否启用并行连接
    boolean parallelEnabled = clientConfig.getP2p().isParallelConnectEnabled();

    if (parallelEnabled) {
        // 并行模式：直接启用 RELAY（不通知服务器）
        log.info("Parallel connect: starting RELAY immediately for {}", peerId);

        session.setMode(ConnectionMode.RELAY);
        relayPeers.add(peerId);

        // 启动本地 TCP 监听
        for (ClientConfig.PeerTunnel peerTunnel : clientConfig.getConnect()) {
            if (peerId.equals(peerTunnel.getPeerId())) {
                startLocalTcpListener(peerTunnel, session);
                break;
            }
        }

        log.info("Parallel connect: RELAY mode active for {}, background UDP punching continues...", peerId);
    }

    // 开始 UDP 打洞
    holePuncher.startPunching(
            session.getPeerAddress(),
            clientConfig.getP2p().getHolePunchIntervalMs(),
            clientConfig.getP2p().getHolePunchTimeoutMs(),
            () -> {
                // 成功
                session.setState(P2pSession.State.ESTABLISHED);
                session.updateSeen();
                session.setMode(ConnectionMode.UDP_DIRECT);

                // 自动切换（保守策略）
                if (clientConfig.getP2p().isAutoSwitchToDirect() && relayPeers.contains(peerId)) {
                    P2pTcpListener listener = tcpListeners.get(peerId);
                    boolean hasActive = listener != null && listener.hasActiveConnection();

                    if (!hasActive || clientConfig.getP2p().isSwitchDuringActiveConnection()) {
                        log.info("Switching from RELAY to UDP_DIRECT for peer {}", peerId);
                        relayPeers.remove(peerId);
                    } else {
                        log.info("UDP punch succeeded for {} but active connection exists, keeping RELAY", peerId);
                    }
                }

                sendP2pSuccess(peerId);
                log.info("P2P channel established with {}", peerId);
                onP2pEstablished(peerId, session);
            },
            () -> {
                // 超时
                session.setState(P2pSession.State.FAILED);
                log.warn("P2P hole punch timeout: myId={}, peerId={}, peerAddr={}",
                        clientConfig.getClientId(), peerId, session.getPeerAddress());

                // 并行模式下已经启动了 RELAY，不需要通知服务器
                if (!parallelEnabled) {
                    sendP2pFailed(peerId);
                } else {
                    log.info("Hole punch failed for {} but RELAY is already active", peerId);
                }
            }
    );
}
```

- [ ] **Step 2: 提交**

```bash
git add p2p-client/src/main/java/com/tyin/zero/p2pclient/client/p2p/P2pManager.java
git commit -m "feat(p2p): update handleHolePunch for parallel connect"
```

---

## Task 10: 修改 P2pManager - 直连断开后回切 RELAY

**Files:**
- Modify: `p2p-client/src/main/java/com/tyin/zero/p2pclient/client/p2p/P2pManager.java:500-545` (TcpPunchDataHandler) 和 `550-626` (TcpPunchServerHandler)

- [ ] **Step 1: 修改 TcpPunchDataHandler.channelInactive**

在 `TcpPunchDataHandler` 的 `channelInactive` 方法中添加回切逻辑：

```java
@Override
public void channelInactive(ChannelHandlerContext ctx) {
    if (peerId != null) {
        tcpPeerChannels.remove(peerId, ctx.channel());
        log.info("TCP punch channel inactive for peer {}", peerId);

        // 直连断开后回切到 RELAY（如果 keepRelayAsBackup 启用）
        P2pSession session = sessions.get(peerId);
        if (session != null && session.getMode() != ConnectionMode.RELAY
                && clientConfig.getP2p().isKeepRelayAsBackup()
                && relayPeers.contains(peerId)) {
            log.info("Direct connection lost for {}, switching back to RELAY", peerId);
            session.setMode(ConnectionMode.RELAY);
        }
    }
}
```

- [ ] **Step 2: 修改 TcpPunchServerHandler.channelInactive**

在 `TcpPunchServerHandler` 的 `channelInactive` 方法中添加相同的回切逻辑：

```java
@Override
public void channelInactive(ChannelHandlerContext ctx) {
    if (peerId != null) {
        tcpPeerChannels.remove(peerId, ctx.channel());
        log.info("TCP punch server channel inactive for peer {}", peerId);

        // 直连断开后回切到 RELAY（如果 keepRelayAsBackup 启用）
        P2pSession session = sessions.get(peerId);
        if (session != null && session.getMode() != ConnectionMode.RELAY
                && clientConfig.getP2p().isKeepRelayAsBackup()
                && relayPeers.contains(peerId)) {
            log.info("Direct connection lost for {}, switching back to RELAY", peerId);
            session.setMode(ConnectionMode.RELAY);
        }
    }
}
```

- [ ] **Step 3: 提交**

```bash
git add p2p-client/src/main/java/com/tyin/zero/p2pclient/client/p2p/P2pManager.java
git commit -m "feat(p2p): implement direct-to-relay fallback on disconnect"
```

---

## Task 11: 修改 P2pManager - 更新 onP2pEstablished 日志

**Files:**
- Modify: `p2p-client/src/main/java/com/tyin/zero/p2pclient/client/p2p/P2pManager.java:646-662`

- [ ] **Step 1: 更新 onP2pEstablished 方法的日志输出**

将 `onP2pEstablished` 方法中的日志替换为：

```java
private void onP2pEstablished(String peerId, P2pSession session) {
    String mode = relayPeers.contains(peerId) ? "RELAY" :
            (session.getMode() == ConnectionMode.TCP_DIRECT ? "TCP_DIRECT" :
             session.getMode() == ConnectionMode.UDP_DIRECT ? "UDP_DIRECT" : "P2P_DIRECT");
    log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    log.info("P2P connection established with {}", peerId);
    log.info("Connection mode: {}", mode);
    log.info("Peer address: {}", session.getPeerAddress());
    log.info("Local bind port: {}", getLocalBindPort(peerId));
    log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

    // 找到对应的 connect 配置，启动本地 TCP 监听
    if (clientConfig.getConnect() != null) {
        for (ClientConfig.PeerTunnel peerTunnel : clientConfig.getConnect()) {
            if (peerId.equals(peerTunnel.getPeerId())) {
                // 如果并行模式下已启动监听，跳过
                if (!tcpListeners.containsKey(peerId)) {
                    startLocalTcpListener(peerTunnel, session);
                }
            }
        }
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add p2p-client/src/main/java/com/tyin/zero/p2pclient/client/p2p/P2pManager.java
git commit -m "feat(p2p): update onP2pEstablished logging"
```

---

## Task 12: 修改 P2pManager - 后台直连重试调度

**Files:**
- Modify: `p2p-client/src/main/java/com/tyin/zero/p2pclient/client/p2p/P2pManager.java` (在心跳调度附近添加)

**背景：Phase 3 要求即使直连失败，也要持续在后台尝试 TCP/UDP 打洞，每 30 秒重试一次**

- [ ] **Step 1: 在 heartbeatScheduler 初始化附近添加后台重试方法**

在心跳调度初始化后添加：

```java
/**
 * 启动后台直连重试调度
 * 当并行模式下直连失败时，定期重试打洞
 */
private void scheduleDirectConnectRetry() {
    long retryIntervalMs = clientConfig.getP2p().getDirectConnectRetryIntervalMs();

    heartbeatScheduler.scheduleWithFixedDelay(() -> {
        for (Map.Entry<String, P2pSession> entry : sessions.entrySet()) {
            String peerId = entry.getKey();
            P2pSession session = entry.getValue();

            // 只对 RELAY 模式且启用了并行连接的 peer 重试
            if (!relayPeers.contains(peerId)) continue;
            if (!clientConfig.getP2p().isParallelConnectEnabled()) continue;
            if (session.getPeerAddress() == null) continue;

            log.debug("Background retry: checking direct connect for {}", peerId);

            // 发起 TCP 连接重试
            String host = session.getPeerAddress().getHostString();
            int port = session.getPeerAddress().getPort();

            tcpPendingPeers.put(host + ":" + port, peerId);
            for (int i = 0; i < 3; i++) {
                connectTcpPunch(peerId, host, port, () -> onTcpPunchSuccess(peerId));
            }

            // 同时尝试 UDP 打洞
            holePuncher.startPunching(
                    session.getPeerAddress(),
                    clientConfig.getP2p().getHolePunchIntervalMs(),
                    clientConfig.getP2p().getHolePunchTimeoutMs(),
                    () -> {
                        session.setState(P2pSession.State.ESTABLISHED);
                        session.updateSeen();
                        session.setMode(ConnectionMode.UDP_DIRECT);

                        // 自动切换
                        if (clientConfig.getP2p().isAutoSwitchToDirect()) {
                            P2pTcpListener listener = tcpListeners.get(peerId);
                            boolean hasActive = listener != null && listener.hasActiveConnection();
                            if (!hasActive || clientConfig.getP2p().isSwitchDuringActiveConnection()) {
                                log.info("Background retry succeeded: switching from RELAY to UDP_DIRECT for {}", peerId);
                                relayPeers.remove(peerId);
                            }
                        }
                        sendP2pSuccess(peerId);
                        onP2pEstablished(peerId, session);
                    },
                    () -> log.debug("Background UDP retry failed for {}", peerId)
            );
        }
    }, retryIntervalMs, retryIntervalMs, TimeUnit.MILLISECONDS);
}
```

- [ ] **Step 2: 在 init() 方法末尾调用**

在心跳调度初始化后调用 `scheduleDirectConnectRetry()`：

```java
// 心跳定时任务
long heartbeatMs = clientConfig.getP2p().getHeartbeatIntervalMs();
heartbeatScheduler.scheduleWithFixedDelay(this::sendHeartbeats,
        heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS);

// 后台直连重试调度
scheduleDirectConnectRetry();
```

- [ ] **Step 3: 提交**

```bash
git add p2p-client/src/main/java/com/tyin/zero/p2pclient/client/p2p/P2pManager.java
git commit -m "feat(p2p): add background direct connect retry scheduler"
```

---

## Task 13: 更新配置示例文件

**Files:**
- Modify: `config-examples/test/test-client1.yaml`
- Modify: `config-examples/test/test-client2.yaml`

- [ ] **Step 1: 更新 test-client1.yaml**

在 `p2p:` 配置块中添加：

```yaml
p2p:
  p2p:
    p2pPort: 5001
    upnpEnabled: false
    tcpHolePunchTimeoutMs: 8000
    holePunchTimeoutMs: 10000
    holePunchIntervalMs: 500
    heartbeatIntervalMs: 15000
    sessionTimeoutMs: 60000
    # 并行连接配置
    parallelConnectEnabled: true
    directConnectRetryIntervalMs: 30000
    autoSwitchToDirect: true
    keepRelayAsBackup: true
    switchDuringActiveConnection: false
```

- [ ] **Step 2: 更新 test-client2.yaml**

在 `p2p:` 配置块中添加相同的并行连接配置。

- [ ] **Step 3: 提交**

```bash
git add config-examples/test/test-client1.yaml config-examples/test/test-client2.yaml
git commit -m "docs(config): add parallel connect settings to test configs"
```

---

## Task 14: 构建验证

**Files:**
- (无)

- [ ] **Step 1: 运行完整构建**

```bash
mvn clean package -DskipTests
```

预期：BUILD SUCCESS

- [ ] **Step 2: 提交**

```bash
git add -A
git commit -m "feat(p2p): implement parallel connect with auto-switch to direct"
```

---

## 自检清单

**1. Spec 覆盖检查：**
- [x] 并行连接配置项 → Task 3
- [x] RELAY 立即启动 → Task 5, Task 7, Task 9（**直接**设置，不等服务器）
- [x] TCP/UDP 后台打洞 → Task 5, Task 7, Task 9
- [x] 自动切换到直连 → Task 8
- [x] 保守策略（活跃连接不切换）→ Task 8
- [x] RELAY 保持备份 → Task 8, Task 10
- [x] 直连断开后回切 RELAY → Task 10
- [x] P2P_REQUEST 并行连接 → Task 6, Task 7
- [x] 后台重试调度 → Task 12 (每 30 秒重试)
- [x] hasActiveConnection() 方法 → Task 4
- [x] 日志更新 → Task 11
- [x] 配置示例更新 → Task 13
- [x] ConnectionMode.P2P_DIRECT → Task 1

**2. 占位符扫描：**
- 无 TBD/TODO/placeholder

**3. 类型一致性检查：**
- `ConnectionMode` 枚举在 Task 1 创建，包含 RELAY, TCP_DIRECT, UDP_DIRECT, P2P_DIRECT
- `P2pSession.setMode()` 在 Task 2 添加
- `P2pTcpListener.hasActiveConnection()` 在 Task 4 添加
- `clientConfig.getP2p().isParallelConnectEnabled()` 在 Task 3 添加
- `clientConfig.getP2p().isKeepRelayAsBackup()` 在 Task 3 添加
- `clientConfig.getP2p().isAutoSwitchToDirect()` 在 Task 3 添加
- `clientConfig.getP2p().getDirectConnectRetryIntervalMs()` 在 Task 3 添加

**4. 设计一致性检查：**

| 设计文档要求 | 实现计划 |
|--------------|----------|
| Phase 1: 收到 TCP_PUNCH_START 或 P2P_REQUEST → 启动 RELAY 通道 | Task 5, 6, 7 |
| Phase 1: **RELAY 立即启动**（不等服务器响应） | Task 5, 7, 9（直接 add relayPeers，不调用 sendP2pFailed）|
| Phase 2: 直连成功 → 自动切换 | Task 8 |
| Phase 2: RELAY 保持连接备份 | Task 10 (channelInactive 回切) |
| Phase 3: 后台持续尝试，**每 30 秒重试** | Task 12 (scheduleDirectConnectRetry) |
| Phase 4: 直连断开 → **自动回切**到 RELAY | Task 10 (channelInactive 回切) |
| 保守策略：活跃连接不切换 | Task 8 (switchDuringActiveConnection=false) |

---

## 执行选项

**1. Subagent-Driven (recommended)** - 每个 Task 由独立 subagent 执行，任务间审查

**2. Inline Execution** - 在当前 session 中使用 executing-plans 技能批量执行

**Which approach?**