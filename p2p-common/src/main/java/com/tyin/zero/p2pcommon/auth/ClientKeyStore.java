package com.tyin.zero.p2pcommon.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tyin.zero.p2pcommon.protocol.JacksonConfig;
import com.tyin.zero.p2pcommon.util.FilePermissionUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 客户端密钥存储管理器
 * 使用AES-256-GCM加密存储客户端公钥
 */
@Slf4j
public class ClientKeyStore {

    private static final String ENCRYPTION_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final String KEY_STORE_FILE = "keys/clients.enc";
    private static final String MASTER_KEY_FILE = "keys/master.key";
    private static final ObjectMapper objectMapper = JacksonConfig.objectMapper();

    @Data
    public static class ClientEntry {
        private String clientId;
        private String publicKey;
        private LocalDateTime addedTime;
        private String description;

        public ClientEntry() {
            this.addedTime = LocalDateTime.now();
        }

        public ClientEntry(String clientId, String publicKey) {
            this.clientId = clientId;
            this.publicKey = publicKey;
            this.addedTime = LocalDateTime.now();
        }
    }

    private final Map<String, ClientEntry> clients = new ConcurrentHashMap<>();
    private SecretKey encryptionKey;

    public ClientKeyStore() {
        initializeKeyStore();
    }

    private void initializeKeyStore() {
        try {
            Files.createDirectories(Path.of("keys"));
            loadOrCreateMasterKey();
            loadClients();
            log.info("Client key store initialized with {} clients", clients.size());
        } catch (Exception e) {
            log.error("Failed to initialize key store: {}", e.getMessage(), e);
            throw new RuntimeException("Key store initialization failed", e);
        }
    }

    private void loadOrCreateMasterKey() throws Exception {
        Path keyPath = Path.of(MASTER_KEY_FILE);

        if (Files.exists(keyPath)) {
            byte[] keyBytes = Files.readAllBytes(keyPath);
            this.encryptionKey = new SecretKeySpec(keyBytes, "AES");
            log.debug("Loaded existing master key");
        } else {
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(256);
            this.encryptionKey = keyGen.generateKey();

            Files.write(keyPath, this.encryptionKey.getEncoded());
            FilePermissionUtil.setOwnerReadWrite(keyPath);

            log.info("Generated new master key");
        }
    }

    private void loadClients() throws Exception {
        Path storePath = Path.of(KEY_STORE_FILE);

        if (!Files.exists(storePath)) {
            log.info("No existing client store found, starting fresh");
            return;
        }

        try {
            byte[] encryptedData = Files.readAllBytes(storePath);
            String json = decrypt(encryptedData);

            if (json != null && !json.isEmpty()) {
                List<ClientEntry> entries = objectMapper.readValue(
                        json, new TypeReference<List<ClientEntry>>() {});
                for (ClientEntry entry : entries) {
                    clients.put(entry.getClientId(), entry);
                }
            }

            log.info("Loaded {} clients from store", clients.size());
        } catch (Exception e) {
            log.error("Failed to load clients: {}", e.getMessage());
            clients.clear();
        }
    }

    public synchronized void save() throws Exception {
        try {
            String json = objectMapper.writeValueAsString(new ArrayList<>(clients.values()));
            byte[] encrypted = encrypt(json);
            Files.write(Path.of(KEY_STORE_FILE), encrypted);

            log.info("Saved {} clients to store", clients.size());
        } catch (Exception e) {
            log.error("Failed to save clients: {}", e.getMessage(), e);
            throw e;
        }
    }

    public synchronized void addClient(String clientId, String publicKey, String description) {
        if (clients.containsKey(clientId)) {
            throw new IllegalArgumentException("Client already exists: " + clientId);
        }

        ClientEntry entry = new ClientEntry(clientId, publicKey);
        entry.setDescription(description);
        clients.put(clientId, entry);

        try {
            save();
            log.info("Added client: {} ({})", clientId, description != null ? description : "no description");
        } catch (Exception e) {
            log.error("Failed to save after adding client: {}", e.getMessage());
            clients.remove(clientId);
            throw new RuntimeException("Failed to persist client", e);
        }
    }

    public synchronized void removeClient(String clientId) {
        ClientEntry removed = clients.remove(clientId);
        if (removed == null) {
            throw new IllegalArgumentException("Client not found: " + clientId);
        }

        try {
            save();
            log.info("Removed client: {}", clientId);
        } catch (Exception e) {
            log.error("Failed to save after removing client: {}", e.getMessage());
            clients.put(clientId, removed);
            throw new RuntimeException("Failed to persist removal", e);
        }
    }

    public String getClientPublicKey(String clientId) {
        ClientEntry entry = clients.get(clientId);
        return entry != null ? entry.getPublicKey() : null;
    }

    public boolean hasClient(String clientId) {
        return clients.containsKey(clientId);
    }

    public Collection<ClientEntry> getAllClients() {
        return Collections.unmodifiableCollection(clients.values());
    }

    public int getClientCount() {
        return clients.size();
    }

    private byte[] encrypt(String plaintext) throws Exception {
        Cipher cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM);
        byte[] iv = new byte[GCM_IV_LENGTH];
        SecureRandom random = new SecureRandom();
        random.nextBytes(iv);

        GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, parameterSpec);

        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        byte[] result = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, result, 0, iv.length);
        System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);

        return result;
    }

    private String decrypt(byte[] ciphertext) throws Exception {
        byte[] iv = new byte[GCM_IV_LENGTH];
        System.arraycopy(ciphertext, 0, iv, 0, iv.length);

        byte[] actualCiphertext = new byte[ciphertext.length - iv.length];
        System.arraycopy(ciphertext, iv.length, actualCiphertext, 0, actualCiphertext.length);

        Cipher cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM);
        GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey, parameterSpec);

        byte[] plaintext = cipher.doFinal(actualCiphertext);
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    public static String formatPublicKey(String publicKey) {
        if (publicKey == null || publicKey.length() < 20) {
            return publicKey;
        }
        return publicKey.substring(0, 10) + "..." + publicKey.substring(publicKey.length() - 10);
    }
}
