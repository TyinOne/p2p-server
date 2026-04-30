package com.tyin.zero.p2pcommon.auth;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RSA密钥对认证器
 */
@Slf4j
public class RsaKeyAuthenticator {

    /**
     * 客户端ID -> 公钥映射
     */
    private final Map<String, PublicKey> clientPublicKeys = new ConcurrentHashMap<>();

    /**
     * 签名时间戳有效期（毫秒），默认1分钟
     */
    private final long timestampValidityMs;

    public RsaKeyAuthenticator() {
        this(60000);
    }

    public RsaKeyAuthenticator(long timestampValidityMs) {
        this.timestampValidityMs = timestampValidityMs;
    }

    /**
     * 注册客户端公钥
     */
    public void registerClientPublicKey(String clientId, String publicKeyBase64) throws Exception {
        PublicKey publicKey = KeyPairGenerator.base64ToPublicKey(publicKeyBase64);
        clientPublicKeys.put(clientId, publicKey);
        log.info("Registered public key for client: {}", clientId);
    }

    /**
     * 验证客户端签名
     */
    public AuthResult authenticate(String clientId, String signature, long timestamp) {
        try {
            PublicKey publicKey = clientPublicKeys.get(clientId);
            if (publicKey == null) {
                log.warn("Authentication failed: unregistered client {}", clientId);
                return AuthResult.failure("Client not registered");
            }

            long currentTime = System.currentTimeMillis();
            if (Math.abs(currentTime - timestamp) > timestampValidityMs) {
                log.warn("Authentication failed: timestamp expired for client {}", clientId);
                return AuthResult.failure("Signature timestamp expired");
            }

            if (signature == null || signature.isEmpty()) {
                return AuthResult.failure("Empty signature");
            }

            String signData = clientId + ":" + timestamp;
            boolean valid = verifySignature(signData, signature, publicKey);

            if (valid) {
                log.debug("RSA authentication successful for client: {}", clientId);
                return AuthResult.success(clientId);
            } else {
                log.warn("RSA authentication failed: invalid signature from client {}", clientId);
                return AuthResult.failure("Invalid signature");
            }

        } catch (Exception e) {
            log.error("Authentication error for client {}: {}", clientId, e.getMessage());
            return AuthResult.failure("Authentication error: " + e.getMessage());
        }
    }

    private boolean verifySignature(String data, String signatureBase64, PublicKey publicKey)
            throws Exception {
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(publicKey);
        signature.update(data.getBytes(StandardCharsets.UTF_8));

        byte[] signatureBytes = Base64.getDecoder().decode(signatureBase64);
        return signature.verify(signatureBytes);
    }

    public int getRegisteredClientCount() {
        return clientPublicKeys.size();
    }

    public void removeClientPublicKey(String clientId) {
        clientPublicKeys.remove(clientId);
        log.info("Removed public key for client: {}", clientId);
    }
}
