/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.service;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import com.arbitrator.server.entity.Contest;
import com.arbitrator.server.repo.ContestRepository;

class ContestPasswordBoundaryTest {
    @ParameterizedTest
    @MethodSource("boundaryPasswords")
    void contestPasswordsRejectSuffixCollisionAtAsciiAndUnicodeBoundaries(String password) {
        AtomicInteger saves = new AtomicInteger();
        ContestService service = service(saves);
        Contest contest = service.create("Contest", 60, password);
        assertTrue(service.verifyPassword(contest, password));
        assertFalse(service.verifyPassword(contest, "incorrect"));
        assertFalse(service.verifyPassword(contest, null));
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.verifyPassword(contest, password + "X")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.create("Rejected", 60, password + "Y")).getStatusCode());
        assertEquals(1, saves.get());
    }

    static Stream<String> boundaryPasswords() {
        return Stream.of("a".repeat(72), "é".repeat(36), "😀".repeat(18));
    }

    @Test
    void optionalPasswordsRemainOptionalWithoutAllowingOverlongBlankInput() {
        ContestService service = service(new AtomicInteger());
        assertTrue(service.verifyPassword(service.create("Open", 60, null), null));
        assertFalse(service.create("Open", 60, " ".repeat(72)).hasPassword());
        assertThrows(ResponseStatusException.class, () -> service.create("Rejected", 60, " ".repeat(73)));
        assertThrows(ResponseStatusException.class,
                () -> service.verifyPassword(new Contest(), "x".repeat(73)));
    }

    @Test
    void attackControlConfirmsRawBcryptStillIgnoresSuffixAndUsesRandomSalt() {
        var encoder = new BCryptPasswordEncoder(4);
        String prefix = "a".repeat(72);
        String hash = encoder.encode(prefix + "X");
        assertTrue(encoder.matches(prefix + "Y", hash));
        assertNotEquals(hash, encoder.encode(prefix + "X"));
        // Service tests above prove these same suffix attempts cannot reach bcrypt.
    }

    private static ContestService service(AtomicInteger saves) {
        ContestRepository repository = (ContestRepository) Proxy.newProxyInstance(
                ContestRepository.class.getClassLoader(), new Class<?>[] {ContestRepository.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("save")) {
                        saves.incrementAndGet();
                        return args[0];
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        return new ContestService(repository, new BCryptPasswordEncoder(4));
    }
}
