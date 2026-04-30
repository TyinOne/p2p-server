package com.tyin.zero.p2pserver.tools;

import com.tyin.zero.p2pcommon.auth.ClientKeyStore;
import com.tyin.zero.p2pcommon.auth.KeyPairGenerator;
import com.tyin.zero.p2pcommon.util.FilePermissionUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Collection;

/**
 * 客户端管理工具类
 * 供命令行脚本调用
 */
public class ClientManager {

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            System.exit(1);
        }

        String command = args[0];

        try {
            switch (command.toLowerCase()) {
                case "list" -> listClients();
                case "add" -> {
                    if (args.length < 3) {
                        System.err.println("Error: Client ID and Public Key are required");
                        System.err.println("Usage: ClientManager add <client-id> <public-key>");
                        System.exit(1);
                    }
                    addClient(args[1], args[2]);
                }
                case "remove" -> {
                    if (args.length < 2) {
                        System.err.println("Error: Client ID is required");
                        System.err.println("Usage: ClientManager remove <client-id>");
                        System.exit(1);
                    }
                    removeClient(args[1]);
                }
                case "generate-keys" -> {
                    if (args.length < 2) {
                        System.err.println("Error: Client ID is required");
                        System.err.println("Usage: ClientManager generate-keys <client-id>");
                        System.exit(1);
                    }
                    generateKeys(args[1]);
                }
                default -> {
                    System.err.println("Unknown command: " + command);
                    printUsage();
                    System.exit(1);
                }
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void listClients() {
        ClientKeyStore keyStore = new ClientKeyStore();
        Collection<ClientKeyStore.ClientEntry> clients = keyStore.getAllClients();

        if (clients.isEmpty()) {
            System.out.println("No clients registered.");
            return;
        }

        System.out.printf("%-20s %-40s %-25s %s%n",
            "ID", "Public Key", "Added Time", "Description");
        System.out.println("-".repeat(100));

        for (ClientKeyStore.ClientEntry client : clients) {
            String publicKeyShort = ClientKeyStore.formatPublicKey(client.getPublicKey());
            String description = client.getDescription() != null ? client.getDescription() : "";

            System.out.printf("%-20s %-40s %-25s %s%n",
                client.getClientId(),
                publicKeyShort,
                client.getAddedTime().toString(),
                description);
        }

        System.out.println("-".repeat(100));
        System.out.println("Total: " + clients.size() + " clients");
    }

    private static void addClient(String clientId, String publicKey) {
        ClientKeyStore keyStore = new ClientKeyStore();

        if (keyStore.hasClient(clientId)) {
            throw new IllegalArgumentException("Client already exists: " + clientId);
        }

        keyStore.addClient(clientId, publicKey, null);
        System.out.println("Client added successfully: " + clientId);
    }

    private static void removeClient(String clientId) {
        ClientKeyStore keyStore = new ClientKeyStore();

        if (!keyStore.hasClient(clientId)) {
            throw new IllegalArgumentException("Client not found: " + clientId);
        }

        keyStore.removeClient(clientId);
        System.out.println("Client removed successfully: " + clientId);
    }

    private static void generateKeys(String clientId) throws Exception {
        KeyPair keyPair = KeyPairGenerator.generateKeyPair();
        String publicKey = KeyPairGenerator.publicKeyToBase64(keyPair.getPublic());
        String privateKey = KeyPairGenerator.privateKeyToBase64(keyPair.getPrivate());

        Files.createDirectories(Path.of("keys"));

        Path privateKeyFile = Path.of("keys", clientId + "-private.key");
        Files.writeString(privateKeyFile, privateKey);
        FilePermissionUtil.setOwnerReadWrite(privateKeyFile);

        Path publicKeyFile = Path.of("keys", clientId + "-public.key");
        Files.writeString(publicKeyFile, publicKey);

        System.out.println("Keys generated for: " + clientId);
        System.out.println("Private key saved to: keys/" + clientId + "-private.key");
        System.out.println("Public key saved to: keys/" + clientId + "-public.key");
        System.out.println();
        System.out.println("Public Key Content:");
        System.out.println(publicKey);
    }

    private static void printUsage() {
        System.out.println("Usage: ClientManager <command> [options]");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  list                     List all registered clients");
        System.out.println("  add <id> <public-key>    Add a new client");
        System.out.println("  remove <id>              Remove a client");
        System.out.println("  generate-keys <id>       Generate key pair for new client");
    }
}
