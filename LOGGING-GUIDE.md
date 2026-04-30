# 📝 日志系统配置指南

## 🎯 已实现的日志功能

### 1. **多文件分离记录**

```
logs/
├── p2p-server.log                      # 主日志文件（所有级别）
├── p2p-server-error.log                # 错误日志（仅ERROR级别）
├── p2p-auth.log                        # 认证相关日志
├── p2p-client-management.log           # 客户端管理日志
└── p2p-server.YYYY-MM-DD.N.log        # 滚动归档文件
```

---

### 2. **日志滚动策略**

#### 主日志文件
- **文件大小**: 超过 10MB 自动滚动
- **时间滚动**: 每天午夜自动滚动
- **保留天数**: 30天
- **总大小限制**: 1GB

#### 错误日志
- **文件大小**: 超过 10MB 自动滚动
- **保留天数**: 30天
- **总大小限制**: 500MB

#### 认证日志
- **文件大小**: 超过 5MB 自动滚动
- **保留天数**: 60天（更长，用于审计）
- **总大小限制**: 200MB

---

### 3. **日志级别配置**

| 模块 | 级别 | 说明 |
|------|------|------|
| **根日志** | INFO | 默认级别 |
| **com.tyin.zero** | DEBUG | P2P项目详细日志 |
| **认证模块** | INFO | 认证成功/失败记录 |
| **Netty** | WARN | 仅警告和错误 |
| **Spring** | INFO | Spring框架日志 |

---

## 📋 日志示例

### 控制台输出

```log
2026-04-28 17:30:00 [main] INFO  c.t.z.p.P2PServerApplication - Starting P2P Server...
2026-04-28 17:30:01 [main] INFO  c.t.z.p.c.ConfigurationReloadHandler - Configuration reload watcher started (file-based polling)
2026-04-28 17:30:01 [main] DEBUG c.t.z.pcommon.auth.ClientKeyStore - Loaded 5 clients from store
2026-04-28 17:30:02 [main] INFO  c.t.z.p.P2PServerApplication - Server started on port 8084
```

### 认证日志 (p2p-auth.log)

```log
2026-04-28 17:35:00 [nioEventLoopGroup-3-1] INFO  c.t.z.pcommon.auth.AuthManager - Authentication successful for client: client-home
2026-04-28 17:35:01 [nioEventLoopGroup-3-2] WARN  c.t.z.pcommon.auth.SharedKeyAuthenticator - Authentication failed: invalid key from client unknown-client
2026-04-28 17:36:00 [nioEventLoopGroup-3-1] INFO  c.t.z.pcommon.auth.RsaKeyAuthenticator - RSA authentication successful for client: client-office
```

### 客户端管理日志 (p2p-client-management.log)

```log
2026-04-28 17:40:00 [main] INFO  c.t.z.p.tools.ClientManager - Client added successfully: client-new
2026-04-28 17:40:01 [main] INFO  c.t.z.pcommon.auth.ClientKeyStore - Added client: client-new (no description)
2026-04-28 17:41:00 [main] INFO  c.t.z.p.tools.ClientManager - Client removed successfully: client-old
```

### 错误日志 (p2p-server-error.log)

```log
2026-04-28 17:45:00 [nioEventLoopGroup-3-1] ERROR c.t.z.p.handler.TunnelServerHandler - Failed to process message
java.io.IOException: Connection reset by peer
    at io.netty.channel.nio.AbstractNioByteChannel$NioByteUnsafe.read(...)
    ...
```

---

## 🔧 配置文件位置

### application.yaml

**服务端**: `p2p-server/src/main/resources/application.yaml`
**客户端**: `p2p-client/src/main/resources/application.yaml`

**关键配置**:
```yaml
logging:
  level:
    root: INFO
    com.tyin.zero: DEBUG
    
  file:
    name: logs/p2p-server.log
    
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n"
    file: "%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n"
```

---

### logback-spring.xml（高级配置）

**位置**: `p2p-server/src/main/resources/logback-spring.xml`

**功能**:
- ✅ 多文件分离（主日志、错误、认证、客户端管理）
- ✅ 滚动策略（按大小和时间）
- ✅ 日志过滤（错误单独记录）
- ✅ 自定义日志模式

---

## 💻 查看日志

### 实时查看

```bash
# Linux/Mac
tail -f p2p-server/logs/p2p-server.log

# Windows PowerShell
Get-Content p2p-server\logs\p2p-server.log -Wait -Tail 20
```

### 查看特定类型日志

```bash
# 认证日志
tail -f p2p-server/logs/p2p-auth.log

# 错误日志
tail -f p2p-server/logs/p2p-server-error.log

# 客户端管理日志
tail -f p2p-server/logs/p2p-client-management.log
```

### 搜索日志

```bash
# 查找认证失败
grep "Authentication failed" p2p-server/logs/p2p-auth.log

# 查找特定客户端
grep "client-home" p2p-server/logs/*.log

# 查找错误
grep "ERROR" p2p-server/logs/p2p-server.log
```

---

## 🎯 日志级别调整

### 临时调整（命令行）

```bash
# 启动时指定日志级别
java -jar p2p-server.jar --logging.level.com.tyin.zero=TRACE

# 使用脚本
./p2p-server.sh start --logging.level.root=DEBUG
```

### 永久调整（配置文件）

编辑 `application.yaml`:

```yaml
logging:
  level:
    com.tyin.zero: TRACE      # 最详细
    # com.tyin.zero: DEBUG    # 详细
    # com.tyin.zero: INFO     # 标准
    # com.tyin.zero: WARN     # 仅警告
    # com.tyin.zero: ERROR    # 仅错误
```

---

## 📊 日志格式说明

### 标准格式

```
%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n
```

**字段解释**:
- `%d{yyyy-MM-dd HH:mm:ss}` - 时间戳
- `[%thread]` - 线程名
- `%-5level` - 日志级别（左对齐，5字符宽）
- `%logger{36}` - 类名（最长36字符）
- `%msg` - 日志消息
- `%n` - 换行符

**示例**:
```
2026-04-28 17:30:00 [main] INFO  c.t.z.p.P2PServerApplication - Server started
```

---

## 🔍 日志分析技巧

### 1. 统计认证失败次数

```bash
grep "Authentication failed" p2p-server/logs/p2p-auth.log | wc -l
```

### 2. 查看今天的日志

```bash
grep "$(date +%Y-%m-%d)" p2p-server/logs/p2p-server.log
```

### 3. 提取所有客户端ID

```bash
grep -oP 'client: \K[a-zA-Z0-9_-]+' p2p-server/logs/p2p-auth.log | sort -u
```

### 4. 查看最近的重载操作

```bash
grep "reload" p2p-server/logs/p2p-server.log | tail -10
```

---

## 🛡️ 日志安全

### 1. 保护日志文件

```bash
# 设置严格权限
chmod 640 p2p-server/logs/*.log
chown p2puser:p2pgroup p2p-server/logs/*.log
```

### 2. 敏感信息脱敏

在代码中避免记录敏感信息：

```java
// ❌ 错误：记录完整密钥
log.info("Client auth key: {}", authKey);

// ✅ 正确：只记录部分信息
log.info("Client authenticated: {}", clientId);
```

### 3. 定期清理旧日志

```bash
# 添加到 crontab（每月1号清理90天前的日志）
0 2 1 * * find /opt/p2p-server/logs -name "*.log" -mtime +90 -delete
```

---

## 📈 性能优化

### 1. 异步日志（高并发场景）

修改 `logback-spring.xml`:

```xml
<appender name="ASYNC_FILE" class="ch.qos.logback.classic.AsyncAppender">
    <appender-ref ref="FILE"/>
    <queueSize>512</queueSize>
    <discardingThreshold>0</discardingThreshold>
</appender>
```

### 2. 减少日志量（生产环境）

```yaml
logging:
  level:
    com.tyin.zero: INFO  # 改为INFO而不是DEBUG
    io.netty: ERROR      # 仅记录错误
```

### 3. 日志压缩

Logback 自动支持 gzip 压缩：

```xml
<fileNamePattern>${LOG_PATH}/p2p-server.%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>
```

---

## ❓ 常见问题

### Q1: 日志文件没有生成？

**A**: 检查以下几点：
1. 确认 `logs/` 目录存在且有写权限
2. 检查 `application.yaml` 中的路径配置
3. 查看控制台是否有错误信息

```bash
mkdir -p p2p-server/logs
chmod 755 p2p-server/logs
```

---

### Q2: 如何禁用控制台日志？

**A**: 修改 `logback-spring.xml`，移除 CONSOLE appender：

```xml
<root level="INFO">
    <!-- <appender-ref ref="CONSOLE"/> -->  <!-- 注释掉 -->
    <appender-ref ref="FILE"/>
    <appender-ref ref="ERROR_FILE"/>
</root>
```

---

### Q3: 日志文件太大怎么办？

**A**: 
1. 调整滚动策略（减小 maxFileSize）
2. 减少保留天数（减小 maxHistory）
3. 启用日志压缩（添加 .gz 后缀）

---

### Q4: 如何查看历史日志？

**A**: 

```bash
# 列出所有归档文件
ls -lh p2p-server/logs/p2p-server.*.log

# 查看特定日期的日志
cat p2p-server/logs/p2p-server.2026-04-28.0.log

# 解压查看（如果启用了压缩）
zcat p2p-server/logs/p2p-server.2026-04-28.0.log.gz
```

---

## 🎯 最佳实践

### 1. 开发环境

```yaml
logging:
  level:
    com.tyin.zero: DEBUG   # 详细日志
    io.netty: DEBUG
```

### 2. 测试环境

```yaml
logging:
  level:
    com.tyin.zero: INFO
    io.netty: WARN
```

### 3. 生产环境

```yaml
logging:
  level:
    com.tyin.zero: INFO    # 标准日志
    io.netty: ERROR        # 仅错误
  file:
    name: /var/log/p2p-server/p2p-server.log  # 系统日志目录
```

---

## 📝 总结

### ✅ 已实现

- ✅ 多文件分离记录（主日志、错误、认证、客户端管理）
- ✅ 自动滚动策略（按大小和时间）
- ✅ 日志级别控制（DEBUG/INFO/WARN/ERROR）
- ✅ 自定义日志格式
- ✅ 控制台 + 文件双输出
- ✅ 长期归档（30-60天）

### 📁 配置文件

- `p2p-server/src/main/resources/application.yaml` - 基础配置
- `p2p-server/src/main/resources/logback-spring.xml` - 高级配置
- `p2p-client/src/main/resources/application.yaml` - 客户端配置

### 💡 常用命令

```bash
# 实时查看日志
tail -f p2p-server/logs/p2p-server.log

# 查看错误
tail -f p2p-server/logs/p2p-server-error.log

# 查看认证日志
tail -f p2p-server/logs/p2p-auth.log

# 搜索特定内容
grep "client-home" p2p-server/logs/*.log
```

---

**日志系统已完全配置完成！** 🎊
