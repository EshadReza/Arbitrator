package com.arbitrator.server.realtime;

import java.security.Principal;
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

/** Applies the same persisted contest grant to contest-topic subscriptions. */
@Component
public class ContestSubscriptionInterceptor implements ChannelInterceptor {

    private static final Pattern CONTEST_TOPIC = Pattern.compile(
            "^/topic/contest/(\\d+)/(?:state|leaderboard|announcements|clarifications|materials)$");

    private final ContestAccessService contestAccess;

    public ContestSubscriptionInterceptor(ContestAccessService contestAccess) {
        this.contestAccess = contestAccess;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
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
