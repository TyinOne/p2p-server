package com.tyin.zero.p2pclient.client.p2p;

import lombok.extern.slf4j.Slf4j;

import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;

/**
 * UPnP IGD 端口映射（纯 HTTP/SSDP 实现，无第三方依赖）
 */
@Slf4j
public class UpnpPortMapper {

    private static final String SSDP_ADDR = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    private static final int DISCOVER_TIMEOUT_MS = 3000;

    private String controlUrl;
    private String serviceType;
    private int mappedPort = -1;

    /**
     * 添加 UDP + TCP 端口映射
     *
     * @param localPort 本地绑定端口
     * @return 映射成功后的外部端口，失败返回 -1
     */
    public int addMapping(int localPort) {
        try {
            // 1. SSDP 发现网关
            String location = discoverGateway();
            if (location == null) {
                log.warn("No UPnP gateway found via SSDP");
                return -1;
            }
            log.info("UPnP gateway found: {}", location);

            // 2. 获取设备描述，找到控制 URL
            if (!parseDescription(location)) {
                log.warn("Failed to parse UPnP device description");
                return -1;
            }
            log.info("UPnP control URL: {}", controlUrl);

            // 3. 添加 UDP 端口映射
            if (!addPortMapping(localPort, localPort, "UDP", "tyin-p2p")) {
                log.warn("UPnP AddPortMapping failed for UDP");
                return -1;
            }
            log.info("UPnP port mapping created: UDP {}", localPort);

            // 4. 添加 TCP 端口映射
            if (!addPortMapping(localPort, localPort, "TCP", "tyin-p2p")) {
                log.warn("UPnP AddPortMapping failed for TCP, UDP mapping succeeded");
                // UDP 成功但 TCP 失败，仍视为成功
            } else {
                log.info("UPnP port mapping created: TCP {}", localPort);
            }

            // 5. 添加 Windows 防火墙规则
            addFirewallRule(localPort, "UDP");
            addFirewallRule(localPort, "TCP");

            mappedPort = localPort;
            return localPort;
        } catch (Exception e) {
            log.warn("UPnP port mapping failed: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * 删除端口映射（UDP + TCP）
     */
    public void deleteMapping() {
        if (controlUrl != null && mappedPort > 0) {
            try {
                deletePortMapping(mappedPort, "UDP");
                log.info("UPnP port mapping removed: UDP {}", mappedPort);
            } catch (Exception e) {
                log.warn("Failed to remove UPnP UDP mapping: {}", e.getMessage());
            }
            try {
                deletePortMapping(mappedPort, "TCP");
                log.info("UPnP port mapping removed: TCP {}", mappedPort);
            } catch (Exception e) {
                log.warn("Failed to remove UPnP TCP mapping: {}", e.getMessage());
            }
            removeFirewallRule(mappedPort, "UDP");
            removeFirewallRule(mappedPort, "TCP");
            mappedPort = -1;
        }
    }

    /**
     * 添加 Windows 防火墙入站规则
     */
    private void addFirewallRule(int port, String protocol) {
        String ruleName = "tyin-p2p-" + protocol.toLowerCase() + "-" + port;
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (!os.contains("windows")) return;

            // 先删除旧规则（如果存在）
            removeFirewallRule(port, protocol);

            ProcessBuilder pb = new ProcessBuilder(
                    "netsh", "advfirewall", "firewall", "add", "rule",
                    "name=" + ruleName,
                    "dir=in", "action=allow", "protocol=" + protocol,
                    "localport=" + port, "profile=any"
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            int exitCode = p.waitFor();
            if (exitCode == 0) {
                log.info("Firewall rule added: allow inbound {} {}", protocol, port);
            } else {
                log.warn("Failed to add firewall rule (exit code {}), try running as administrator", exitCode);
            }
        } catch (Exception e) {
            log.warn("Failed to add firewall rule: {}", e.getMessage());
        }
    }

    /**
     * 删除 Windows 防火墙规则
     */
    private void removeFirewallRule(int port, String protocol) {
        String ruleName = "tyin-p2p-" + protocol.toLowerCase() + "-" + port;
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (!os.contains("windows")) return;

            ProcessBuilder pb = new ProcessBuilder(
                    "netsh", "advfirewall", "firewall", "delete", "rule",
                    "name=" + ruleName
            );
            pb.redirectErrorStream(true);
            pb.start().waitFor();
        } catch (Exception ignored) {
        }
    }

    private String discoverGateway() {
        // 尝试所有非回环网卡
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp() || !ni.supportsMulticast()) continue;

                for (Enumeration<InetAddress> addrs = ni.getInetAddresses(); addrs.hasMoreElements(); ) {
                    InetAddress localAddr = addrs.nextElement();
                    if (localAddr instanceof Inet6Address) continue; // 跳过 IPv6

                    String result = tryDiscoverOnInterface(ni, localAddr);
                    if (result != null) return result;
                }
            }
        } catch (Exception e) {
            log.warn("SSDP discovery error: {}", e.getMessage());
        }

        // 回退：用默认接口再试一次
        return tryDiscoverOnInterface(null, null);
    }

    private String tryDiscoverOnInterface(NetworkInterface ni, InetAddress localAddr) {
        String[] searchTypes = {
                "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
                "urn:schemas-upnp-org:service:WANIPConnection:1",
                "urn:schemas-upnp-org:service:WANPPPConnection:1"
        };

        for (String st : searchTypes) {
            String mSearch = "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: " + SSDP_ADDR + ":" + SSDP_PORT + "\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 3\r\n" +
                    "ST: " + st + "\r\n" +
                    "\r\n";

            try {
                DatagramSocket socket = new DatagramSocket(0, localAddr != null ? localAddr : InetAddress.getLocalHost());
                socket.setSoTimeout(DISCOVER_TIMEOUT_MS);

                try {
                    byte[] buf = mSearch.getBytes(StandardCharsets.UTF_8);
                    DatagramPacket packet = new DatagramPacket(buf, buf.length,
                            InetAddress.getByName(SSDP_ADDR), SSDP_PORT);
                    String ifaceName = ni != null ? ni.getDisplayName() : "default";
                    log.info("SSDP M-SEARCH on [{}] ST={}", ifaceName, st);
                    socket.send(packet);

                    // 接收响应（可能有多个）
                    byte[] recvBuf = new byte[4096];
                    DatagramPacket recvPacket = new DatagramPacket(recvBuf, recvBuf.length);
                    socket.receive(recvPacket);

                    String response = new String(recvPacket.getData(), 0, recvPacket.getLength(), StandardCharsets.UTF_8);

                    for (String line : response.split("\r\n")) {
                        if (line.toUpperCase().startsWith("LOCATION:")) {
                            String location = line.substring("LOCATION:".length()).trim();
                            log.info("SSDP LOCATION found: {}", location);
                            return location;
                        }
                    }
                } catch (SocketTimeoutException e) {
                    log.debug("SSDP timeout on [{}] ST={}", ni != null ? ni.getDisplayName() : "default", st);
                } finally {
                    socket.close();
                }
            } catch (Exception e) {
                log.debug("SSDP error on interface: {}", e.getMessage());
            }
        }
        return null;
    }

    private boolean parseDescription(String location) throws IOException {
        String xml = httpGet(location);
        if (xml == null) return false;

        // 查找 WANIPConnection 或 WANPPPConnection 的控制 URL
        // 简单 XML 解析：找 serviceType 和 controlURL
        String[] serviceTypes = {
                "urn:schemas-upnp-org:service:WANIPConnection:1",
                "urn:schemas-upnp-org:service:WANPPPConnection:1",
                "urn:schemas-upnp-org:service:WANIPConnection:2"
        };

        for (String st : serviceTypes) {
            int idx = xml.indexOf(st);
            if (idx >= 0) {
                serviceType = st;
                // 在这个 service 块中找 controlURL
                int blockStart = xml.lastIndexOf("<service>", idx);
                int blockEnd = xml.indexOf("</service>", idx);
                if (blockStart >= 0 && blockEnd >= 0) {
                    String block = xml.substring(blockStart, blockEnd);
                    int urlStart = block.indexOf("<controlURL>");
                    int urlEnd = block.indexOf("</controlURL>");
                    if (urlStart >= 0 && urlEnd >= 0) {
                        String path = block.substring(urlStart + "<controlURL>".length(), urlEnd).trim();
                        // 构建完整 URL
                        URL locUrl = new URL(location);
                        controlUrl = new URL(locUrl.getProtocol(), locUrl.getHost(), locUrl.getPort(), path).toString();
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean addPortMapping(int extPort, int intPort, String protocol, String description) throws IOException {
        String localIp = getLocalIp();
        String soapBody = "<?xml version=\"1.0\"?>\r\n" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n" +
                "<s:Body>\r\n" +
                "<u:AddPortMapping xmlns:u=\"" + serviceType + "\">\r\n" +
                "  <NewRemoteHost></NewRemoteHost>\r\n" +
                "  <NewExternalPort>" + extPort + "</NewExternalPort>\r\n" +
                "  <NewProtocol>" + protocol + "</NewProtocol>\r\n" +
                "  <NewInternalPort>" + intPort + "</NewInternalPort>\r\n" +
                "  <NewInternalClient>" + localIp + "</NewInternalClient>\r\n" +
                "  <NewEnabled>1</NewEnabled>\r\n" +
                "  <NewPortMappingDescription>" + description + "</NewPortMappingDescription>\r\n" +
                "  <NewLeaseDuration>3600</NewLeaseDuration>\r\n" +
                "</u:AddPortMapping>\r\n" +
                "</s:Body>\r\n" +
                "</s:Envelope>\r\n";

        return sendSoapAction("AddPortMapping", soapBody);
    }

    private void deletePortMapping(int extPort, String protocol) throws IOException {
        String soapBody = "<?xml version=\"1.0\"?>\r\n" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n" +
                "<s:Body>\r\n" +
                "<u:DeletePortMapping xmlns:u=\"" + serviceType + "\">\r\n" +
                "  <NewRemoteHost></NewRemoteHost>\r\n" +
                "  <NewExternalPort>" + extPort + "</NewExternalPort>\r\n" +
                "  <NewProtocol>" + protocol + "</NewProtocol>\r\n" +
                "</u:DeletePortMapping>\r\n" +
                "</s:Body>\r\n" +
                "</s:Envelope>\r\n";

        sendSoapAction("DeletePortMapping", soapBody);
    }

    private boolean sendSoapAction(String action, String body) throws IOException {
        URL url = new URL(controlUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "text/xml; charset=utf-8");
        conn.setRequestProperty("SOAPAction", "\"" + serviceType + "#" + action + "\"");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        conn.disconnect();
        return code == 200;
    }

    private String httpGet(String urlStr) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        try (InputStream is = conn.getInputStream();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) >= 0) {
                baos.write(buf, 0, n);
            }
            return baos.toString(StandardCharsets.UTF_8.name());
        } finally {
            conn.disconnect();
        }
    }

    private String getLocalIp() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }
}
