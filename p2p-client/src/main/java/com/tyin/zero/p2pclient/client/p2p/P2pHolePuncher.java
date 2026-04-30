package com.tyin.zero.p2pclient.client.p2p;

import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * UDP 打洞器
 * 定时向对端公网地址发送 HolePunch 包，直到收到响应或超时
 */
@Slf4j
public class P2pHolePuncher {

    private final P2pUdpChannel udpChannel;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean punching = new AtomicBoolean(false);
    private ScheduledFuture<?> punchTask;

    public P2pHolePuncher(P2pUdpChannel udpChannel) {
        this.udpChannel = udpChannel;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("hole-punch-scheduler").factory());
    }

    /**
     * 开始打洞
     *
     * @param peerAddr           对端公网地址
     * @param intervalMs         发送间隔（毫秒）
     * @param timeoutMs          超时时间（毫秒）
     * @param onSuccess          成功回调
     * @param onTimeout          超时回调
     */
    public void startPunching(InetSocketAddress peerAddr, long intervalMs, long timeoutMs,
                               Runnable onSuccess, Runnable onTimeout) {
        if (!punching.compareAndSet(false, true)) {
            log.warn("Already punching, ignoring duplicate request");
            return;
        }

        log.info("Starting hole punch to {}", peerAddr);

        long startTime = System.currentTimeMillis();

        punchTask = scheduler.scheduleWithFixedDelay(() -> {
            try {
                if (!punching.get()) return;

                if (System.currentTimeMillis() - startTime > timeoutMs) {
                    log.warn("Hole punch to {} timed out after {}ms", peerAddr, timeoutMs);
                    stop();
                    onTimeout.run();
                    return;
                }

                // 发送 HolePunch 包
                udpChannel.sendHolePunch(peerAddr);
                log.debug("Sent hole punch packet to {}", peerAddr);

            } catch (Exception e) {
                log.error("Error during hole punch: {}", e.getMessage());
            }
        }, 0, intervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 收到对端的 HolePunch 包，打洞成功
     */
    public void onHolePunchReceived(InetSocketAddress peerAddr) {
        if (punching.compareAndSet(true, false)) {
            log.info("Hole punch successful with {}", peerAddr);
            if (punchTask != null) {
                punchTask.cancel(false);
            }
        }
    }

    /**
     * 停止打洞
     */
    public void stop() {
        punching.set(false);
        if (punchTask != null) {
            punchTask.cancel(false);
        }
    }

    public boolean isPunching() {
        return punching.get();
    }

    public void shutdown() {
        stop();
        scheduler.shutdownNow();
    }
}
