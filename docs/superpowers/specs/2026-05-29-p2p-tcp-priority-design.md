# P2P 穿透改进设计

**日期：** 2026-05-29
**目标：** 优化 TCP 打洞优先级，提升对称型 NAT 环境下的穿透成功率

---

## 背景

当前系统 UDP 打洞失败后降级到 TCP 打洞，但存在以下问题：

1. **TCP 端口动态分配** - 无法在路由器/防火墙预先配置
2. **UPnP 仅映射 UDP** - TCP 端口无 UPnP 映射
3. **打洞顺序不合理** - 对称型 NAT 下，TCP 穿透比 UDP 更稳定

---

## 改进方案

### 1. 配置统一

**改动：** `udpPort` → `p2pPort`，TCP/UDP 共用一个端口

**ClientConfig.P2pConfig：**
```java
// 旧
private int udpPort = 0;

// 新
private int p2pPort = 0;          // TCP/UDP 共用端口
private boolean upnpEnabled = false;
```

**配置文件：**
```yaml
p2p:
  client:
    p2p:
      p2pPort: 5000           # TCP/UDP 共用
      upnpEnabled: true
```

---

### 2. UPnP 双协议映射

**UpnpPortMapper.addMapping() 扩展：**

```java
public int addMapping(int localPort) {
    // 绑定后同时映射 TCP 和 UDP
    addPortMapping(localPort, localPort, "UDP", "tyin-p2p");
    addPortMapping(localPort, localPort, "TCP", "tyin-p2p");  // 新增
    addFirewallRule(localPort, "UDP");
    addFirewallRule(localPort, "TCP");                          // 新增
    return localPort;
}
```

**删除映射时也需同时清理：**
```java
public void deleteMapping() {
    deletePortMapping(mappedPort, "UDP");
    deletePortMapping(mappedPort, "TCP");  // 新增
    removeFirewallRule(mappedPort, "UDP");
    removeFirewallRule(mappedPort, "TCP");  // 新增
}
```

---

### 3. 防火墙规则适配

**Windows netsh 命令扩展：**
```java
// UDP 规则
netsh advfirewall firewall add rule ... protocol=UDP ...

// TCP 规则
netsh advfirewall firewall add rule ... protocol=TCP ...
```

---

### 4. 打洞顺序调整

**新流程：**
```
启动 → TCP打洞(首选) → 失败 → UDP打洞 → 失败 → 中继
```

**实现位置：** `P2pManager`

**状态机扩展：**
```java
public enum HolePunchSequence {
    TCP_ONLY,      // 仅尝试 TCP
    UDP_FALLBACK,  // TCP 失败，降级 UDP
    RELAY_FINAL    // 最终兜底
}
```

**超时配置：**
```yaml
p2p:
  p2p:
    tcpHolePunchTimeoutMs: 8000   # TCP 打洞超时
    udpHolePunchTimeoutMs: 5000   # UDP 打洞超时（保持现有）
```

---

### 5. P2pManager 改动

**binding 阶段：**
```java
// 同时上报 TCP 和 UDP 端口（现在共用同一端口）
msg.setUdpPort(externalPort);   // 保持兼容
msg.setTcpPort(externalPort);   // 现在是同一个端口
```

**打洞发起：**
```java
// 1. 先尝试 TCP 打洞
holePuncher.startTcpPunching(peerAddress, peerTcpPort, timeoutMs, successCallback, failCallback);

// 2. TCP 失败后，降级 UDP
holePuncher.startUdpPunching(peerAddress, timeoutMs, successCallback, failCallback);
```

---

## 改动文件清单

| 文件 | 改动 |
|------|------|
| `ClientConfig.java` | `udpPort` → `p2pPort`，新增 `tcpHolePunchTimeoutMs` |
| `UpnpPortMapper.java` | 同时映射 TCP + UDP |
| `P2pManager.java` | TCP 优先打洞，状态机调整 |
| `P2pHolePuncher.java` | 支持 TCP 打洞作为首选 |
| `config-examples/*.yaml` | 配置示例更新 |

---

## 兼容性

- **配置文件：** 旧配置 `udpPort` 需迁移为 `p2pPort`
- **协议：** 与服务端的 `TunnelMessage.setTcpPort()` 已有接口，无需修改
- **中继模式：** 不受影响

---

## 风险评估

| 风险 | 缓解措施 |
|------|----------|
| 老路由器 UPnP 不支持 TCP | 配置项可单独关闭 TCP UPnP |
| TCP 打洞超时导致整体延迟 | 合理设置 `tcpHolePunchTimeoutMs`，避免过长 |
| 端口冲突 | 建议用户使用 5000+ 范围端口 |