package com.tyin.zero.p2pserver.config;

import com.tyin.zero.p2pcommon.auth.AuthManager;
import com.tyin.zero.p2pcommon.auth.ClientKeyStore;
import com.tyin.zero.p2pcommon.auth.RsaKeyAuthenticator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class AuthConfig {

    @Bean
    public AuthManager authManager(ServerConfig serverConfig) {
        String authMode = serverConfig.getAuthMode();
        String sharedKey = serverConfig.getSharedKey();

        if ("RSA_KEYPAIR".equalsIgnoreCase(authMode)) {
            log.info("Initializing AuthManager in RSA_KEYPAIR mode");
            ClientKeyStore keyStore = new ClientKeyStore();
            return new AuthManager(sharedKey, keyStore);

        } else if ("HYBRID".equalsIgnoreCase(authMode)) {
            log.info("Initializing AuthManager in HYBRID mode (RSA + SharedKey fallback)");
            ClientKeyStore keyStore = new ClientKeyStore();
            RsaKeyAuthenticator rsaAuthenticator = new RsaKeyAuthenticator();
            return new AuthManager(sharedKey, rsaAuthenticator);

        } else {
            log.info("Initializing AuthManager in SHARED_KEY mode");
            if (sharedKey == null || sharedKey.isEmpty()) {
                sharedKey = "default-key-change-this";
                log.warn("No shared key configured, using default. Change this in production!");
            }
            return new AuthManager(sharedKey);
        }
    }
}
