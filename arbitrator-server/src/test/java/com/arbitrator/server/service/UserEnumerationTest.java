/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.service;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import com.arbitrator.common.dto.LoginRequest;
import com.arbitrator.server.entity.User;
import com.arbitrator.server.repo.UserRepository;

class UserEnumerationTest {
    @Test
    void bothFailuresPerformExactlyOneRealBcryptCheckAtTheConfiguredCost() {
        var encoder = new RecordingEncoder();
        var service = service(encoder);
        assertEquals(1, encoder.encodes.get(), "dummy hash is generated once at construction");
        for (String username : new String[] {"existing", "missing", "missing"}) {
            AtomicReference<String> admitted = new AtomicReference<>();
            var error = assertThrows(ResponseStatusException.class,
                    () -> service.login(new LoginRequest(username, null, "Wrong7!Password"), admitted::set));
            assertEquals(HttpStatus.UNAUTHORIZED, error.getStatusCode());
            assertEquals("Invalid credentials", error.getReason());
            assertEquals(username, admitted.get());
            assertEquals("04", encoder.lastHash.get().substring(4, 6));
        }
        assertEquals(3, encoder.matches.get());
        assertEquals(1, encoder.encodes.get(), "failed requests must not generate new dummy hashes");
    }

    @Test
    void matchingDummyHashCannotAuthenticateUnknownUserEvenWithForce() {
        var encoder = new RecordingEncoder();
        var service = service(encoder);
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> service.login(new LoginRequest("missing", null, encoder.dummyInput, null, true))).getStatusCode());
        assertEquals(1, encoder.matches.get());
    }

    @Test
    void throttleRejectionOccursBeforeEitherRealOrDummyPasswordCheck() {
        var encoder = new RecordingEncoder();
        var service = service(encoder);
        for (String username : new String[] {"existing", "missing"}) {
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, assertThrows(ResponseStatusException.class,
                    () -> service.login(new LoginRequest(username, null, "Wrong7!Password"), ignored -> {
                        throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
                    })).getStatusCode());
        }
        assertEquals(0, encoder.matches.get());
    }

    private static UserService service(RecordingEncoder encoder) {
        User existing = new User();
        existing.setUsername("existing");
        existing.setPasswordHash(new BCryptPasswordEncoder(4).encode("Orbit7!Lake"));
        UserRepository repo = (UserRepository) Proxy.newProxyInstance(UserRepository.class.getClassLoader(),
                new Class<?>[] {UserRepository.class}, (proxy, method, args) -> {
                    if (method.getName().equals("findByUsername")) {
                        return "existing".equals(args[0]) ? Optional.of(existing) : Optional.empty();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        // Any unintended session creation/token issuance fails the test through these null dependencies.
        return new UserService(repo, encoder, null, null, null, null);
    }

    private static class RecordingEncoder implements PasswordEncoder {
        final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(4);
        final AtomicInteger encodes = new AtomicInteger(), matches = new AtomicInteger();
        final AtomicReference<String> lastHash = new AtomicReference<>();
        String dummyInput;
        @Override public String encode(CharSequence input) {
            encodes.incrementAndGet();
            dummyInput = input.toString();
            return bcrypt.encode(input);
        }
        @Override public boolean matches(CharSequence input, String hash) {
            matches.incrementAndGet();
            lastHash.set(hash);
            return bcrypt.matches(input, hash);
        }
    }
}
