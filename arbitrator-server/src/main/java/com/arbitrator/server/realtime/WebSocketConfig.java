package com.arbitrator.server.realtime;

import java.security.Principal;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import com.arbitrator.common.api.StompDestinations;
import com.arbitrator.server.security.JwtHandshakeInterceptor;

/**
 * STOMP over raw WebSocket (§3.3.4) — no SockJS, the client is JavaFX, not a
 * browser. The handshake interceptor validates the JWT and stashes the
 * username; the handshake handler below promotes it to the session Principal,
 * which is exactly what convertAndSendToUser(...) keys on (FR-15).
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtHandshakeInterceptor jwtHandshakeInterceptor;
    private final ContestSubscriptionInterceptor contestSubscriptionInterceptor;
    private final AuthenticatedWebSocketSessions authenticatedSockets;

    public WebSocketConfig(JwtHandshakeInterceptor jwtHandshakeInterceptor,
                           ContestSubscriptionInterceptor contestSubscriptionInterceptor,
                           AuthenticatedWebSocketSessions authenticatedSockets) {
        this.jwtHandshakeInterceptor = jwtHandshakeInterceptor;
        this.contestSubscriptionInterceptor = contestSubscriptionInterceptor;
        this.authenticatedSockets = authenticatedSockets;
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(contestSubscriptionInterceptor);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.addDecoratorFactory(handler -> new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                if (authenticatedSockets.register(session)) {
                    super.afterConnectionEstablished(session);
                }
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session,
                                              org.springframework.web.socket.CloseStatus closeStatus)
                    throws Exception {
                authenticatedSockets.unregister(session);
                super.afterConnectionClosed(session, closeStatus);
            }
        });
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint(StompDestinations.WS_ENDPOINT)
                .setAllowedOriginPatterns("*")     // closed LAN (D4); revisit with HTTPS
                .addInterceptors(jwtHandshakeInterceptor)
                .setHandshakeHandler(new DefaultHandshakeHandler() {
                    @Override
                    protected Principal determineUser(ServerHttpRequest request,
                                                      WebSocketHandler wsHandler,
                                                      Map<String, Object> attributes) {
                        String username =
                                (String) attributes.get(JwtHandshakeInterceptor.ATTR_USERNAME);
                        return username == null ? null : () -> username;
                    }
                });
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Heartbeats are what let either side notice the other has gone away.
        // Without them a client whose server vanished (or whose LAN switch
        // died — FMEA-03) keeps reporting "connected" forever and silently
        // stops receiving verdicts and standings. 10 s each way.
        registry.enableSimpleBroker(StompDestinations.TOPIC_PREFIX,
                        StompDestinations.QUEUE_PREFIX)
                .setHeartbeatValue(new long[] { 10_000, 10_000 })
                .setTaskScheduler(heartbeatScheduler());
        registry.setUserDestinationPrefix(StompDestinations.USER_PREFIX);
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Bean
    TaskScheduler heartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("stomp-heartbeat-");
        scheduler.initialize();
        return scheduler;
    }
}
