package com.tyin.zero.p2pcommon.auth;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 共享密钥认证器
 */
@Slf4j
public class SharedKeyAuthenticator {

    private final String sharedKey;

    public SharedKeyAuthenticator(String sharedKey) {
        if (sharedKey == null || sharedKey.isEmpty()) {
            throw new IllegalArgumentException("Shared key cannot be null or empty");
        }
        this.sharedKey = sharedKey;
    }

    /**
     * 验证客户端提供的密钥（恒定时间比较，防止时序攻击）
     *
     * @param clientId 客户端ID
     * @param providedKey 客户端提供的密钥
     * @return 认证结果
     */
    public AuthResult authenticate(String clientId, String providedKey) {
        if (providedKey == null || providedKey.isEmpty()) {
            log.warn("Authentication failed: empty key from client {}", clientId);
            return AuthResult.failure("Empty authentication key");
        }

        boolean valid = MessageDigest.isEqual(
                sharedKey.getBytes(StandardCharsets.UTF_8),
                providedKey.getBytes(StandardCharsets.UTF_8)
        );

        if (valid) {
            log.debug("Authentication successful for client: {}", clientId);
            return AuthResult.success(clientId);
        } else {
            log.warn("Authentication failed: invalid key from client {}", clientId);
            return AuthResult.failure("Invalid authentication key");
        }
    }
}
