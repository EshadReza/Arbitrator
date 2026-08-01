package com.labjudge.server.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import com.labjudge.common.api.StompDestinations;
import com.labjudge.common.dto.VerdictEventDto;

/**
 * FR-15: verdict pushed to the submitting client's private queue.
 * The client subscribed to /user/queue/verdicts; the broker resolves that to
 * this user's session via the handshake Principal.
 */
@Component
public class VerdictPublisher {

    private static final Logger log = LoggerFactory.getLogger(VerdictPublisher.class);

    private final SimpMessagingTemplate template;

    public VerdictPublisher(SimpMessagingTemplate template) {
        this.template = template;
    }

    public void publishVerdict(String username, VerdictEventDto event) {
        template.convertAndSendToUser(username, StompDestinations.QUEUE_VERDICTS, event);
        log.debug("Pushed verdict {} for submission {} to {}",
                event.verdict(), event.submissionId(), username);
        // If the client is disconnected the message is simply dropped; the
        // client re-fetches /api/submissions/mine on reconnect (UC-05 exception).
    }
}
