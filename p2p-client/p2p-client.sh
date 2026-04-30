#!/bin/bash
# P2P Tunnel Client Management Script
# Usage: ./p2p-client.sh [command] [options]

set -e

CWD="$(pwd)"
CLIENT_JAR="$CWD/p2p-client-0.0.1-SNAPSHOT.jar"
PID_FILE="$CWD/p2p-client.pid"
LOG_FILE="$CWD/logs/p2p-client.log"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

COMMAND=""
CONFIG_FILE=""

parse_args() {
    while [[ $# -gt 0 ]]; do
        case $1 in
            start|stop|restart|status)
                COMMAND="$1"
                shift
                ;;
            --config|-c)
                CONFIG_FILE="$2"
                shift 2
                ;;
            --help|-h)
                COMMAND="help"
                shift
                ;;
            *)
                shift
                ;;
        esac
    done
}

print_header() {
    echo -e "${BLUE}========================================${NC}"
    echo -e "${BLUE}  P2P Tunnel Client Manager${NC}"
    echo -e "${BLUE}========================================${NC}"
    echo
}

print_usage() {
    echo "Usage: $0 <command> [options]"
    echo
    echo "Commands:"
    echo "  start [--config|-c <file>]   Start the client"
    echo "  stop                         Stop the client"
    echo "  restart [--config|-c <file>] Restart the client"
    echo "  status                       Check client status"
    echo
    echo "Options:"
    echo "  --config, -c <file>   Specify configuration file"
    echo "  --help, -h            Show this help message"
    echo
    echo "Examples:"
    echo "  $0 start"
    echo "  $0 start -c config/client.yaml"
    echo "  $0 stop"
    echo "  $0 status"
    echo
}

check_jar() {
    if [ ! -f "$CLIENT_JAR" ]; then
        echo -e "${RED}Error: Client JAR not found: $CLIENT_JAR${NC}"
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
        echo -e "${YELLOW}Client is already running (PID: $existing_pid)${NC}"
        return 1
    fi

    local config_arg=""
    if [ -n "$CONFIG_FILE" ]; then
        config_arg="--spring.config.additional-location=file:$CONFIG_FILE"
        echo -e "${BLUE}Starting with config: $CONFIG_FILE${NC}"
    elif [ -f "config/client.yaml" ]; then
        config_arg="--spring.config.additional-location=file:config/client.yaml"
        echo -e "${BLUE}Starting with config: config/client.yaml${NC}"
    else
        echo -e "${BLUE}Starting with default config${NC}"
    fi

    mkdir -p "$(dirname "$LOG_FILE")"

    echo -e "${GREEN}Starting P2P Client...${NC}"

    nohup java -jar "$CLIENT_JAR" $config_arg > "$LOG_FILE" 2>&1 &
    local pid=$!
    echo $pid > "$PID_FILE"

    sleep 2

    if ps -p "$pid" > /dev/null 2>&1; then
        echo -e "${GREEN}✓ Client started successfully (PID: $pid)${NC}"
        echo -e "${BLUE}Log file: $LOG_FILE${NC}"
    else
        echo -e "${RED}✗ Failed to start client${NC}"
        echo "Check log file: $LOG_FILE"
        rm -f "$PID_FILE"
        exit 1
    fi
}

cmd_stop() {
    local pid
    pid=$(get_pid) || true

    if [ -z "$pid" ]; then
        echo -e "${YELLOW}Client is not running${NC}"
        return 0
    fi

    # Verify the process is actually a Java process
    local cmd=$(ps -p "$pid" -o comm= 2>/dev/null || ps -p "$pid" -o command= 2>/dev/null)
    if [[ "$cmd" != *"java"* ]]; then
        echo -e "${YELLOW}PID $pid is not a Java process, removing stale PID file${NC}"
        rm -f "$PID_FILE"
        return 1
    fi

    echo -e "${YELLOW}Stopping client (PID: $pid)...${NC}"

    kill "$pid"

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
    echo -e "${GREEN}✓ Client stopped${NC}"
}

cmd_restart() {
    cmd_stop
    sleep 2
    cmd_start
}

cmd_status() {
    local pid
    pid=$(get_pid) || true

    if [ -z "$pid" ]; then
        echo -e "${YELLOW}Client is not running${NC}"
        return 1
    fi

    echo -e "${GREEN}Client is running${NC}"
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

    if [ -f "$LOG_FILE" ]; then
        echo
        echo -e "${BLUE}Recent logs:${NC}"
        tail -5 "$LOG_FILE"
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
    status)
        cmd_status
        ;;
    help|*)
        print_usage
        ;;
esac
