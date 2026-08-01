package com.labjudge.client.net;

import java.lang.reflect.Type;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.labjudge.client.app.ServerConfig;
import com.labjudge.common.api.StompDestinations;
import com.labjudge.common.dto.LeaderboardDto;
import com.labjudge.common.dto.VerdictEventDto;

/**
 * STOMP over raw WebSocket. The JWT travels as an Authorization handshake
 * header — the server's JwtHandshakeInterceptor validates it and binds the
 * session Principal, which is what routes /user/queue/verdicts to us (FR-15).
 *
 * Connecting is separate from subscribing so verdicts (FR-15) and the
 * leaderboard broadcast (FR-17) share a single socket.
 *
 * Exponential-backoff reconnect (NFR-R03, FMEA-03) is chunk S3-C7 and will be
 * layered on top of this class, not woven into it.
 */
public class StompClientAdapter implements AutoCloseable {

    private final ServerConfig config;
    private final WebSocketStompClient client;

    private volatile StompSession session;

    public StompClientAdapter(ServerConfig config) {
        this.config = config;
        this.client = new WebSocketStompClient(new StandardWebSocketClient());
        this.client.setMessageConverter(new MappingJackson2MessageConverter());

        // Without heartbeats isConnected() keeps returning true long after the
        // server has gone, so a drop is never noticed and pushes silently stop
        // (NFR-R03, FMEA-03). Must match the server's broker heartbeat.
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("stomp-heartbeat-");
        scheduler.setDaemon(true);
        scheduler.initialize();
        this.client.setTaskScheduler(scheduler);
        this.client.setDefaultHeartbeat(new long[] { 10_000, 10_000 });
    }

    /** Establishes the session if it isn't already up. Safe to call repeatedly. */
    public synchronized void connect(String jwt) throws JudgeApi.ApiException {
        if (isConnected()) {
            return;
        }
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add("Authorization", "Bearer " + jwt);

        var handler = new StompSessionHandlerAdapter() {
            @Override
            public void handleException(StompSession s, StompCommand command,
                                        StompHeaders h, byte[] payload, Throwable ex) {
                // Surfaced through the connection banner in S3-C7; log for now.
                System.err.println("STOMP error: " + ex.getMessage());
            }
        };

        try {
            session = client.connectAsync(config.wsUrl(), headers, handler)
                    .get(10, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            throw new JudgeApi.ApiException("WebSocket connection failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JudgeApi.ApiException("WebSocket connection interrupted", e);
        }
    }

    /** FR-15: private verdict queue for this user. */
    public void subscribeVerdicts(Consumer<VerdictEventDto> onVerdict)
            throws JudgeApi.ApiException {
        subscribe(StompDestinations.USER_QUEUE_VERDICTS, VerdictEventDto.class, onVerdict);
    }

    /** FR-17: contest-wide standings broadcast. */
    public void subscribeLeaderboard(long contestId, Consumer<LeaderboardDto> onUpdate)
            throws JudgeApi.ApiException {
        subscribe(StompDestinations.contestLeaderboard(contestId),
                LeaderboardDto.class, onUpdate);
    }

    private <T> void subscribe(String destination, Class<T> payloadType, Consumer<T> consumer)
            throws JudgeApi.ApiException {
        StompSession s = session;
        if (s == null || !s.isConnected()) {
            throw new JudgeApi.ApiException(-1, "Not connected to the server");
        }
        s.subscribe(destination, new StompSessionHandlerAdapter() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return payloadType;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                consumer.accept((T) payload);
            }
        });
    }

    public boolean isConnected() {
        StompSession s = session;
        return s != null && s.isConnected();
    }

    @Override
    public void close() {
        StompSession s = session;
        if (s != null && s.isConnected()) {
            s.disconnect();
        }
        client.stop();
    }
}
