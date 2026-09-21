/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.client.net;

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

import com.arbitrator.client.app.ServerConfig;
import com.arbitrator.common.api.StompDestinations;
import com.arbitrator.common.dto.AnnouncementDto;
import com.arbitrator.common.dto.ContestStateDto;
import com.arbitrator.common.dto.LeaderboardDto;
import com.arbitrator.common.dto.VerdictEventDto;

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

            /**
             * The gap STATUS.md's known issue #8 describes: this callback is the
             * ONLY notification a dropped WebSocket sends — a heartbeat timeout,
             * a killed server, a network blip. StompSessionHandlerAdapter's
             * default implementation is a no-op, so without this override
             * nothing ever learned the transport had died: {@code session} kept
             * pointing at a dead object, isConnected() kept answering from
             * whatever Spring's internal flag happened to hold, and every
             * subscription on it — verdicts, announcements, clarifications,
             * leaderboard, contest state — went silently dark with no visible
             * symptom until the participant restarted the app. Proven live: a
             * transport error was reproduced and the announcement published
             * afterward was never delivered while nothing here reacted.
             *
             * Clearing the field is the whole fix: isConnected() (below) starts
             * answering false immediately, and MainController's existing 3 s
             * watchdog already calls connectLive() again the moment isLive()
             * goes false — which redials and resubscribes everything at once.
             */
            @Override
            public void handleTransportError(StompSession s, Throwable ex) {
                System.err.println("STOMP transport lost: " + ex.getMessage());
                synchronized (StompClientAdapter.this) {
                    if (session == s) {
                        session = null;
                    }
                }
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

    /** FR-06: contest lifecycle and clock changes. */
    public void subscribeContestState(long contestId, Consumer<ContestStateDto> onState)
            throws JudgeApi.ApiException {
        subscribe(StompDestinations.contestState(contestId), ContestStateDto.class, onState);
    }

    /** FR-07: a new announcement for this contest. */
    public void subscribeAnnouncements(long contestId, Consumer<AnnouncementDto> onAnnouncement)
            throws JudgeApi.ApiException {
        subscribe(StompDestinations.contestAnnouncements(contestId),
                AnnouncementDto.class, onAnnouncement);
    }

    /**
     * The clarification board moved. The frame carries only a timestamp, so the
     * payload type is a Map and the callback takes nothing — each side re-reads
     * the view it is allowed to see.
     */
    public void subscribeClarifications(long contestId, Runnable onChanged)
            throws JudgeApi.ApiException {
        subscribe(StompDestinations.contestClarifications(contestId),
                java.util.Map.class, ignored -> onChanged.run());
    }

    /** FR-07 sibling: material list changed — uploaded, or deleted. */
    public void subscribeMaterials(long contestId, Runnable onChanged)
            throws JudgeApi.ApiException {
        subscribe(StompDestinations.contestMaterials(contestId),
                java.util.Map.class, ignored -> onChanged.run());
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

    /**
     * Drops the session without stopping the underlying client, so the next
     * connect() dials afresh — and, crucially, re-reads the server address.
     * close() cannot be used for this: it stops the WebSocketStompClient, and a
     * stopped client cannot be reconnected. Needed when the user points the
     * app at a different server mid-session.
     */
    public synchronized void disconnect() {
        StompSession s = session;
        session = null;
        if (s != null && s.isConnected()) {
            try {
                s.disconnect();
            } catch (RuntimeException ignored) {
                // Already gone; nulling the reference is what matters.
            }
        }
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
