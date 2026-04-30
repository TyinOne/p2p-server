package com.tyin.zero.p2pserver;

import com.tyin.zero.p2pserver.tools.ClientManager;

import java.util.Set;

/**
 * 统一入口：根据参数决定启动服务器还是工具命令
 *
 * 服务器模式: java -jar p2p-server.jar [spring-args...]
 * 工具模式:   java -jar p2p-server.jar list|add|remove|generate-keys [args...]
 */
public class Launcher {

    private static final Set<String> TOOL_COMMANDS = Set.of("list", "add", "remove", "generate-keys");

    public static void main(String[] args) {
        if (args.length > 0 && TOOL_COMMANDS.contains(args[0].toLowerCase())) {
            ClientManager.main(args);
        } else {
            P2PServerApplication.main(args);
        }
    }
}
