#!/bin/bash
# P2P Tunnel Server Management Script
# Usage: ./p2p-server.sh [command] [options]

set -e

CWD="$(pwd)"
SERVER_JAR="$CWD/p2p-server-0.0.1-SNAPSHOT.jar"
PID_FILE="$CWD/p2p-server.pid"
CONFIG_RECORD="$CWD/.last-config"
LOG_FILE="$CWD/logs/p2p-server.log"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Parse arguments
COMMAND=""
CONFIG_FILE=""
CLIENT_ID=""
PUBLIC_KEY=""

parse_args() {
    while [[ $# -gt 0 ]]; do
        case $1 in
            start|stop|restart|reload|status|list|add|remove|generate-keys)
                COMMAND="$1"
                shift
                ;;
            --config|-c)
                CONFIG_FILE="$2"
                shift 2
                ;;
            --list|-l)
                COMMAND="list"
                shift
                ;;
            --help|-h)
                COMMAND="help"
                shift
                ;;
            *)
                if [ -z "$CLIENT_ID" ]; then
                    CLIENT_ID="$1"
                elif [ -z "$PUBLIC_KEY" ]; then
                    PUBLIC_KEY="$1"
                fi
                shift
                ;;
        esac
    done
}

print_header() {
    echo -e "${BLUE}========================================${NC}"
    echo -e "${BLUE}  P2P Tunnel Server Manager${NC}"
    echo -e "${BLUE}========================================${NC}"
    echo
}

print_usage() {
    echo "Usage: $0 <command> [options]"
    echo
    echo "Commands:"
    echo "  Server Management:"
    echo "    start [--config|-c <file>]   Start the server"
    echo "    stop                         Stop the server"
    echo "    restart [--config|-c <file>] Restart the server"
    echo "    reload                       Reload configuration and keys"
    echo "    status                       Check server status"
    echo
    echo "  Client Management:"
    echo "    list, -l                     List all registered clients"
    echo "    add <client-id> <public-key> Add a new client"
    echo "    remove <client-id>           Remove a client"
    echo "    generate-keys <client-id>    Generate key pair for new client"
    echo
    echo "Options:"
    echo "  --config, -c <file>   Specify configuration file"
    echo "  --list, -l            List clients (shortcut)"
    echo "  --help, -h            Show this help message"
    echo
    echo "Examples:"
    echo "  $0 start"
    echo "  $0 start -c config/server.yaml"
    echo "  $0 stop"
    echo "  $0 restart -c config/server.yaml"
    echo "  $0 reload"
    echo "  $0 status"
    echo "  $0 list"
    echo "  $0 -l"
    echo "  $0 add client-home MIIBIjANBg..."
    echo "  $0 remove client-home"
    echo "  $0 generate-keys client-office"
    echo
}

check_jar() {
    if [ ! -f "$SERVER_JAR" ]; then
        echo -e "${RED}Error: Server JAR not found: $SERVER_JAR${NC}"
        echo "Please build the project first: mvn clean package"
        exit 1
    fi
}

get_pid() {
    if [ -f "$PID_FILE" ]; then
        local pid=$(cat "$PID_FILE")
        if ps -p "$pid" > /dev/null 2>&1; then
            echo "$pid"
            return 0
        else
            rm -f "$PID_FILE"
            return 1
        fi
    fi
    return 1
}

cmd_start() {
    check_jar

    local existing_pid
    existing_pid=$(get_pid) || true
    if [ -n "$existing_pid" ]; then
        echo -e "${YELLOW}Server is already running (PID: $existing_pid)${NC}"
        return 1
    fi
    
    local config_arg=""
    if [ -n "$CONFIG_FILE" ]; then
        config_arg="--spring.config.additional-location=file:$CONFIG_FILE"
        echo -e "${BLUE}Starting with config: $CONFIG_FILE${NC}"
    elif [ -f "config/server.yaml" ]; then
        config_arg="--spring.config.additional-location=file:config/server.yaml"
        echo -e "${BLUE}Starting with config: config/server.yaml${NC}"
    else
        echo -e "${BLUE}Starting with default config${NC}"
    fi
    
    # Create logs directory
    mkdir -p "$(dirname "$LOG_FILE")"
    
    echo -e "${GREEN}Starting P2P Server...${NC}"
    
    nohup java -jar "$SERVER_JAR" $config_arg > "$LOG_FILE" 2>&1 &
    local pid=$!
    echo $pid > "$PID_FILE"
    
    # 记录使用的配置文件
    if [ -n "$CONFIG_FILE" ]; then
        echo "$CONFIG_FILE" > "$CONFIG_RECORD"
    else
        echo "default" > "$CONFIG_RECORD"
    fi
    
    sleep 2
    
    if ps -p "$pid" > /dev/null 2>&1; then
        echo -e "${GREEN}✓ Server started successfully (PID: $pid)${NC}"
        echo -e "${BLUE}Log file: $LOG_FILE${NC}"
    else
        echo -e "${RED}✗ Failed to start server${NC}"
        echo "Check log file: $LOG_FILE"
        rm -f "$PID_FILE"
        exit 1
    fi
}

cmd_stop() {
    local pid
    pid=$(get_pid) || true

    if [ -z "$pid" ]; then
        echo -e "${YELLOW}Server is not running${NC}"
        return 0
    fi

    # Verify the process is actually a Java process
    local cmd=$(ps -p "$pid" -o comm= 2>/dev/null || ps -p "$pid" -o command= 2>/dev/null)
    if [[ "$cmd" != *"java"* ]]; then
        echo -e "${YELLOW}PID $pid is not a Java process, removing stale PID file${NC}"
        rm -f "$PID_FILE"
        return 1
    fi

    echo -e "${YELLOW}Stopping server (PID: $pid)...${NC}"

    kill "$pid"

    # Wait for graceful shutdown
    local count=0
    while ps -p "$pid" > /dev/null 2>&1 && [ $count -lt 10 ]; do
        sleep 1
        count=$((count + 1))
    done

    if ps -p "$pid" > /dev/null 2>&1; then
        echo -e "${YELLOW}Force killing...${NC}"
        kill -9 "$pid"
    fi

    rm -f "$PID_FILE"
    echo -e "${GREEN}✓ Server stopped${NC}"
}

cmd_restart() {
    cmd_stop
    sleep 2
    cmd_start
}

cmd_reload() {
    local pid
    pid=$(get_pid) || true

    if [ -z "$pid" ]; then
        echo -e "${RED}Server is not running${NC}"
        return 1
    fi

    # 读取上次启动时使用的配置文件
    local last_config="default"
    if [ -f "$CONFIG_RECORD" ]; then
        last_config=$(cat "$CONFIG_RECORD")
    fi

    echo -e "${YELLOW}Reloading configuration...${NC}"
    if [ "$last_config" != "default" ]; then
        echo -e "${BLUE}Using config file: $last_config${NC}"
    else
        echo -e "${BLUE}Using default configuration${NC}"
    fi

    # 通过文件标志通知 Java 进程重载（跨平台兼容）
    local reload_flag="$CWD/keys/.reload-request"
    mkdir -p "$(dirname "$reload_flag")"
    touch "$reload_flag"

    echo -e "${GREEN}✓ Reload request created${NC}"

    # 等待 Java 进程处理（最多 10 秒）
    local count=0
    while [ -f "$reload_flag" ] && [ $count -lt 10 ]; do
        sleep 1
        count=$((count + 1))
    done

    if [ -f "$reload_flag" ]; then
        echo -e "${YELLOW}Warning: Reload request not yet processed (flag file still exists)${NC}"
        echo -e "${BLUE}Server will reload on next poll cycle${NC}"
    else
        echo -e "${GREEN}✓ Configuration reloaded successfully${NC}"
    fi
}

cmd_status() {
    local pid
    pid=$(get_pid) || true
    
    if [ -z "$pid" ]; then
        echo -e "${YELLOW}Server is not running${NC}"
        return 1
    fi
    
    echo -e "${GREEN}Server is running${NC}"
    echo -e "${BLUE}PID: $pid${NC}"
    
    # Cross-platform uptime
    if [[ "$OSTYPE" == "darwin"* ]]; then
        local uptime=$(ps -p "$pid" -o etime= | xargs)
    else
        local uptime=$(ps -o etime= -p "$pid" | xargs)
    fi
    echo -e "${BLUE}Uptime: $uptime${NC}"

    # Memory
    local mem=$(ps -o rss= -p "$pid" | xargs)
    local mem_mb=$((mem / 1024))
    echo -e "${BLUE}Memory: ${mem_mb} MB${NC}"
    
    # Show log tail
    if [ -f "$LOG_FILE" ]; then
        echo
        echo -e "${BLUE}Recent logs:${NC}"
        tail -5 "$LOG_FILE"
    fi
}

cmd_list() {
    check_jar
    
    echo -e "${GREEN}Registered Clients:${NC}"
    echo "----------------------------------------"
    
    java -jar "$SERVER_JAR" list
    
    echo "----------------------------------------"
}

cmd_add() {
    check_jar
    
    if [ -z "$CLIENT_ID" ] || [ -z "$PUBLIC_KEY" ]; then
        echo -e "${RED}Error: Client ID and Public Key are required${NC}"
        echo "Usage: $0 add <client-id> <public-key>"
        exit 1
    fi
    
    echo -e "${YELLOW}Adding client: ${CLIENT_ID}${NC}"

    local exit_code=0
    java -jar "$SERVER_JAR" add "$CLIENT_ID" "$PUBLIC_KEY" || exit_code=$?

    if [ $exit_code -eq 0 ]; then
        echo -e "${GREEN}✓ Client added successfully${NC}"
        
        # If server is running, trigger reload
        if get_pid > /dev/null 2>&1; then
            echo -e "${BLUE}Reloading server configuration...${NC}"
            cmd_reload
        fi
    else
        echo -e "${RED}✗ Failed to add client${NC}"
        exit 1
    fi
}

cmd_remove() {
    check_jar
    
    if [ -z "$CLIENT_ID" ]; then
        echo -e "${RED}Error: Client ID is required${NC}"
        echo "Usage: $0 remove <client-id>"
        exit 1
    fi
    
    echo -e "${YELLOW}Removing client: ${CLIENT_ID}${NC}"
    if [ -t 0 ]; then
        read -p "Are you sure? (y/N): " confirm
    else
        confirm="y"
    fi

    if [[ $confirm =~ ^[Yy]$ ]]; then
        local exit_code=0
        java -jar "$SERVER_JAR" remove "$CLIENT_ID" || exit_code=$?

        if [ $exit_code -eq 0 ]; then
            echo -e "${GREEN}✓ Client removed successfully${NC}"
            
            # If server is running, trigger reload
            if get_pid > /dev/null 2>&1; then
                echo -e "${BLUE}Reloading server configuration...${NC}"
                cmd_reload
            fi
        else
            echo -e "${RED}✗ Failed to remove client${NC}"
            exit 1
        fi
    else
        echo "Cancelled."
    fi
}

cmd_generate_keys() {
    check_jar
    
    if [ -z "$CLIENT_ID" ]; then
        echo -e "${RED}Error: Client ID is required${NC}"
        echo "Usage: $0 generate-keys <client-id>"
        exit 1
    fi
    
    echo -e "${YELLOW}Generating key pair for: ${CLIENT_ID}${NC}"

    local exit_code=0
    java -jar "$SERVER_JAR" generate-keys "$CLIENT_ID" || exit_code=$?

    if [ $exit_code -eq 0 ]; then
        echo
        echo -e "${GREEN}✓ Keys generated successfully${NC}"
        echo -e "${BLUE}Private key: keys/${CLIENT_ID}-private.key${NC}"
        echo -e "${BLUE}Public key: keys/${CLIENT_ID}-public.key${NC}"
        echo
        echo -e "${YELLOW}Next steps:${NC}"
        echo "1. Send private key to client machine (secure channel)"
        echo "2. Run: $0 add $CLIENT_ID \$(cat keys/${CLIENT_ID}-public.key)"
    else
        echo -e "${RED}✗ Failed to generate keys${NC}"
        exit 1
    fi
}

# Main
parse_args "$@"
print_header

case "${COMMAND:-help}" in
    start)
        cmd_start
        ;;
    stop)
        cmd_stop
        ;;
    restart)
        cmd_restart
        ;;
    reload)
        cmd_reload
        ;;
    status)
        cmd_status
        ;;
    list)
        cmd_list
        ;;
    add)
        cmd_add
        ;;
    remove)
        cmd_remove
        ;;
    generate-keys)
        cmd_generate_keys
        ;;
    help|*)
        print_usage
        ;;
esac
