/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.ExecutorSubscribableChannel;

import com.arbitrator.server.entity.Announcement;
import com.arbitrator.server.entity.Contest;
import com.arbitrator.server.repo.AnnouncementRepository;

class AnnouncementServiceSecurityTest {

    @Test
    void announcementIsSanitizedBeforePersistenceAndBroadcastResponse() {
        AtomicReference<Announcement> saved = new AtomicReference<>();
        AnnouncementService service = fixture(saved, null);

        var dto = service.publish(1,
                "<img src=x onerror=\"alert(1)\"><p onclick=\"alert(2)\">n<sup>2</sup></p>");

        assertSafe(saved.get().getBody());
        assertSafe(dto.body());
    }

    @Test
    void legacyStoredAnnouncementsAreSanitizedOnRead() throws Exception {
        Announcement legacy = new Announcement();
        setId(legacy);
        legacy.setContestId(1L);
        legacy.setBody("<script>alert(1)</script><p>n<sup>2</sup></p>");

        var dto = fixture(new AtomicReference<>(), legacy).forContest(1).get(0);

        assertSafe(dto.body());
    }

    private static void assertSafe(String html) {
        assertFalse(html.contains("script"));
        assertFalse(html.contains("onerror"));
        assertFalse(html.contains("onclick"));
        assertFalse(html.contains("<img"));
        assertTrue(html.contains("n<sup>2</sup>"));
    }

    private static AnnouncementService fixture(AtomicReference<Announcement> saved,
                                                Announcement legacy) {
        AnnouncementRepository repo = (AnnouncementRepository) Proxy.newProxyInstance(
                AnnouncementRepository.class.getClassLoader(),
                new Class<?>[] {AnnouncementRepository.class}, (proxy, method, args) -> {
                    if (method.getName().equals("save")) {
                        Announcement a = (Announcement) args[0];
                        setId(a);
                        saved.set(a);
                        return a;
                    }
                    if (method.getName().equals("findByContestIdOrderByCreatedAtDesc")) {
                        return List.of(legacy);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        ContestService contests = new ContestService(null, null) {
            @Override public Contest require(long id) { return new Contest(); }
        };
        SimpMessagingTemplate messaging = new SimpMessagingTemplate(new ExecutorSubscribableChannel()) {
            @Override public void convertAndSend(String destination, Object payload) { }
        };
        return new AnnouncementService(repo, contests, messaging);
    }

    private static void setId(Announcement a) throws Exception {
        var field = Announcement.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(a, 1L);
    }
}
