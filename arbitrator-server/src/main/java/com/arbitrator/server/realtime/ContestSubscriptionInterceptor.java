/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.realtime;

import java.security.Principal;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import com.arbitrator.server.service.ContestAccessService;
import com.arbitrator.server.repo.UserRepository;
import com.arbitrator.server.security.ActiveSessionRegistry;
import com.arbitrator.server.security.JwtHandshakeInterceptor;

/** Applies the same persisted contest grant to contest-topic subscriptions. */
@Component
public class ContestSubscriptionInterceptor implements ChannelInterceptor {

    private static final Pattern CONTEST_TOPIC = Pattern.compile(
            "^/topic/contest/(\\d+)/(?:state|leaderboard|announcements|clarifications|materials)$");

    private final ContestAccessService contestAccess;
    private final ActiveSessionRegistry sessions;
    private final UserRepository users;

    public ContestSubscriptionInterceptor(ContestAccessService contestAccess,
                                         ActiveSessionRegistry sessions, UserRepository users) {
        this.contestAccess = contestAccess;
        this.sessions = sessions;
        this.users = users;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        if (accessor.getCommand() != null && accessor.getCommand() != StompCommand.DISCONNECT) {
            Principal principal = accessor.getUser();
            Map<String, Object> attributes = accessor.getSessionAttributes();
            Object sid = attributes == null ? null : attributes.get(JwtHandshakeInterceptor.ATTR_SESSION_ID);
            if (principal == null || !(sid instanceof String sessionId)
                    || !sessions.validateRole(principal.getName(), sessionId,
                            users.findByUsername(principal.getName()).map(user -> user.getRole()).orElse(null))) {
                throw new AccessDeniedException("Session permissions changed. Sign in again.");
            }
        }
        if (accessor.getCommand() != StompCommand.SUBSCRIBE) {
            return message;
        }
        String destination = accessor.getDestination();
        Matcher matcher = destination == null ? null : CONTEST_TOPIC.matcher(destination);
        if (matcher == null || !matcher.matches()) {
            return message;
        }
        Principal principal = accessor.getUser();
        if (principal == null) {
            throw new AccessDeniedException("Authentication is required");
        }
        contestAccess.requireAccess(Long.parseLong(matcher.group(1)), principal.getName());
        return message;
    }
}
