package com.tyin.zero.p2pcommon.auth;

import lombok.extern.slf4j.Slf4j;

/**
 * 统一认证管理器 - 支持多种认证模式
 */
@Slf4j
public class AuthManager {

    private final AuthMode authMode;
    private final SharedKeyAuthenticator sharedKeyAuthenticator;
    private final RsaKeyAuthenticator rsaKeyAuthenticator;
    private final ClientKeyStore keyStore;

    /**
     * 构造函数 - 共享密钥模式
     */
    public AuthManager(String sharedKey) {
        this.authMode = AuthMode.SHARED_KEY;
        this.sharedKeyAuthenticator = new SharedKeyAuthenticator(sharedKey);
        this.rsaKeyAuthenticator = null;
        this.keyStore = null;
        log.info("AuthManager initialized with SHARED_KEY mode");
    }

    /**
     * 混合模式构造函数（优先使用RSA，降级到共享密钥）
     */
    public AuthManager(String sharedKey, RsaKeyAuthenticator rsaAuthenticator) {
        this.authMode = AuthMode.RSA_KEYPAIR;
        this.sharedKeyAuthenticator = new SharedKeyAuthenticator(sharedKey);
        this.rsaKeyAuthenticator = rsaAuthenticator;
        this.keyStore = null;
        log.info("AuthManager initialized with HYBRID mode (RSA + Shared Key fallback)");
    }

    /**
     * 使用密钥存储的构造函数（推荐）
     */
    public AuthManager(String sharedKey, ClientKeyStore keyStore) {
        this.authMode = AuthMode.RSA_KEYPAIR;
        this.sharedKeyAuthenticator = new SharedKeyAuthenticator(sharedKey);
        this.rsaKeyAuthenticator = new RsaKeyAuthenticator();
        this.keyStore = keyStore;

        try {
            for (ClientKeyStore.ClientEntry entry : keyStore.getAllClients()) {
                rsaKeyAuthenticator.registerClientPublicKey(
                    entry.getClientId(),
                    entry.getPublicKey()
                );
            }
            log.info("Loaded {} clients from key store", keyStore.getClientCount());
        } catch (Exception e) {
            log.error("Failed to load clients from key store: {}", e.getMessage());
        }

        log.info("AuthManager initialized with ClientKeyStore");
    }

    /**
     * 认证客户端
     */
    public AuthResult authenticate(String clientId, String authData, Long timestamp) {
        return switch (authMode) {
            case SHARED_KEY -> authenticateWithSharedKey(clientId, authData);
            case RSA_KEYPAIR -> {
                if (timestamp == null) {
                    yield AuthResult.failure("Timestamp required for RSA authentication");
                }
                yield authenticateWithRsa(clientId, authData, timestamp);
            }
        };
    }

    private AuthResult authenticateWithSharedKey(String clientId, String providedKey) {
        if (sharedKeyAuthenticator == null) {
            return AuthResult.failure("Shared key authenticator not configured");
        }
        return sharedKeyAuthenticator.authenticate(clientId, providedKey);
    }

    private AuthResult authenticateWithRsa(String clientId, String signature, long timestamp) {
        if (rsaKeyAuthenticator == null) {
            return AuthResult.failure("RSA authenticator not configured");
        }

        AuthResult result = rsaKeyAuthenticator.authenticate(clientId, signature, timestamp);

        if (!result.success() && sharedKeyAuthenticator != null) {
            log.debug("RSA failed, trying shared key authentication for client: {}", clientId);
            return sharedKeyAuthenticator.authenticate(clientId, signature);
        }

        return result;
    }

    /**
     * 注册客户端公钥（仅RSA模式）
     */
    public void registerClientPublicKey(String clientId, String publicKeyBase64) throws Exception {
        if (rsaKeyAuthenticator == null) {
            throw new IllegalStateException("RSA authenticator not configured");
        }

        rsaKeyAuthenticator.registerClientPublicKey(clientId, publicKeyBase64);

        if (keyStore != null) {
            keyStore.addClient(clientId, publicKeyBase64, null);
        }
    }

    /**
     * 注册客户端公钥（带描述）
     */
    public void registerClientPublicKey(String clientId, String publicKeyBase64, String description) throws Exception {
        if (rsaKeyAuthenticator == null) {
            throw new IllegalStateException("RSA authenticator not configured");
        }

        rsaKeyAuthenticator.registerClientPublicKey(clientId, publicKeyBase64);

        if (keyStore != null) {
            keyStore.addClient(clientId, publicKeyBase64, description);
        }
    }

    /**
     * 获取已注册的客户端数量（仅RSA模式）
     */
    public int getRegisteredClientCount() {
        if (rsaKeyAuthenticator == null) {
            return 0;
        }
        return rsaKeyAuthenticator.getRegisteredClientCount();
    }
}
