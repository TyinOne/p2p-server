package com.tyin.zero.p2pserver.config;

import com.tyin.zero.p2pcommon.auth.AuthManager;
import com.tyin.zero.p2pcommon.auth.ClientKeyStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class ConfigurationReloadHandler {

    private static final String RELOAD_FLAG_FILE = "keys/.reload-request";

    private final AuthManager authManager;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofVirtual().name("config-reload-watcher").factory()
            );

    public ConfigurationReloadHandler(AuthManager authManager) {
        this.authManager = authManager;
    }

    @PostConstruct
    public void init() {
        log.info("Initializing configuration reload handler (file-based polling)...");
        startFileBasedReloadWatcher();
        log.info("Using file-based reload mechanism (create {} to trigger reload)", RELOAD_FLAG_FILE);
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    private void startFileBasedReloadWatcher() {
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                Path flagPath = Path.of(RELOAD_FLAG_FILE);
                if (Files.exists(flagPath)) {
                    log.info("Reload flag file detected, triggering configuration reload...");
                    Files.delete(flagPath);
                    handleReload();
                }
            } catch (Exception e) {
                log.error("Error checking reload flag file", e);
            }
        }, 5, 5, TimeUnit.SECONDS);

        log.info("File-based reload watcher started (checking every 5 seconds)");
    }

    private synchronized void handleReload() {
        log.info("=== Starting configuration reload ===");

        try {
            reloadClientKeys();
            log.info("=== Configuration reload completed successfully ===");
        } catch (Exception e) {
            log.error("=== Configuration reload failed ===", e);
        }
    }

    private void reloadClientKeys() {
        log.info("Reloading client keys from store...");

        try {
            ClientKeyStore keyStore = new ClientKeyStore();

            for (ClientKeyStore.ClientEntry client : keyStore.getAllClients()) {
                try {
                    authManager.registerClientPublicKey(
                            client.getClientId(), client.getPublicKey());
                } catch (Exception e) {
                    log.warn("Failed to register client key {}: {}",
                            client.getClientId(), e.getMessage());
                }
            }

            log.info("Reloaded {} clients from key store", keyStore.getClientCount());
        } catch (Exception e) {
            log.error("Failed to reload client keys", e);
        }
    }
}
