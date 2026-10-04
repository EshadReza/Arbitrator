/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import com.arbitrator.common.dto.LoginRequest;
import com.arbitrator.server.entity.User;
import com.arbitrator.server.repo.UserRepository;
import com.arbitrator.server.security.ActiveSessionRegistry;
import com.arbitrator.server.security.JwtService;

class UserServiceTest {

    @ParameterizedTest
    @MethodSource("weakPasswords")
    void registrationRejectsWhitespaceCommonAndRepetitivePasswordsBeforePersistence(String password) {
        var fixture = fixture();
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> fixture.service.register(new LoginRequest("student1", "Student", password))).getStatusCode());
        assertEquals(null, fixture.checkedUsername.get());
        assertEquals(null, fixture.savedUser.get());
    }

    static Stream<String> weakPasswords() {
        return Stream.of("        ", "good pass9", " pass987", "pass987 ", "pass\t9876",
                "pass\n9876", "pass\u00a09876", "pass\u20039876", "pass\u00009876",
                "password", "PASSWORD123", "12345678", "qwertyuiop", "P@ssw0rd",
                "aaaaaaaa", "😀".repeat(8));
    }

    @Test
    void eightCharacterNonCommonPasswordStillWorks() {
        fixture().service.register(new LoginRequest("student1", "Student", "Oak7!Sky"));
    }

    @Test
    void existingWeakOrSpacedPasswordStillAuthenticatesWithoutRewritingHash() {
        for (String password : new String[] {"password", "old pass9"}) {
            var fixture = fixture();
            fixture.service.register(new LoginRequest("student1", "Student", "Oak7!Sky"));
            String legacyHash = new BCryptPasswordEncoder(4).encode(password);
            fixture.savedUser.get().setPasswordHash(legacyHash);
            fixture.service.login(new LoginRequest("student1", null, password, null, true));
            assertEquals(legacyHash, fixture.savedUser.get().getPasswordHash());
        }
    }

    @ParameterizedTest
    @MethodSource("boundaryPasswords")
    void accountPasswordsAcceptExactByteBoundaryAndRejectIgnoredSuffix(String password) {
        var fixture = fixture();
        fixture.service.register(new LoginRequest("student1", "Student", password));
        assertTrue(new BCryptPasswordEncoder(4).matches(password, fixture.savedUser.get().getPasswordHash()));
        fixture.service.login(new LoginRequest("student1", "Student", password, null, true));
        ResponseStatusException login = assertThrows(ResponseStatusException.class,
                () -> fixture.service.login(new LoginRequest("student1", "Student", password + "X")));
        assertEquals(HttpStatus.BAD_REQUEST, login.getStatusCode());
        var rejected = fixture();
        ResponseStatusException registration = assertThrows(ResponseStatusException.class,
                () -> rejected.service.register(new LoginRequest("student2", "Student", password + "Y")));
        assertEquals(HttpStatus.BAD_REQUEST, registration.getStatusCode());
        assertEquals(null, rejected.checkedUsername.get());
        assertEquals(null, rejected.savedUser.get());
        assertTrue(registration.getReason().contains("72 UTF-8 bytes"));
    }

    static Stream<String> boundaryPasswords() {
        return Stream.of("aB7!".repeat(18), "éΩ".repeat(18), "😀🚀".repeat(9));
    }

    @Test
    void nullLoginPasswordReturnsInvalidCredentialsWithoutHashing() {
        var fixture = fixture();
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> fixture.service.login(new LoginRequest("student1", "Student", null))).getStatusCode());
    }

    @ParameterizedTest
    @MethodSource("unsafeStudentIds")
    void registrationRejectsStudentIdsThatCouldEscapeMarkupOrJavaScript(String username) {
        var fixture = fixture();

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> fixture.service.register(new LoginRequest(username, "Normal Name", "Orbit7!Lake")));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertTrue(error.getReason().contains("Student ID"));
    }

    static Stream<String> unsafeStudentIds() {
        return Stream.of(
                "student' onclick='alert(1)",
                "<script>alert(1)</script>",
                "student id",
                "student/id",
                "-starts-with-punctuation",
                "a".repeat(65));
    }

    @Test
    void registrationNormalizesStudentIdBeforeUniquenessCheckAndStorage() {
        var fixture = fixture();

        fixture.service.register(new LoginRequest("  S-24_01.test  ", "  Ada Lovelace  ", "Orbit7!Lake"));

        assertEquals("S-24_01.test", fixture.checkedUsername.get());
        assertEquals("S-24_01.test", fixture.savedUser.get().getUsername());
        assertEquals("Ada Lovelace", fixture.savedUser.get().getDisplayName());
    }

    @Test
    void displayNameMayContainUnicodeAndMarkupCharactersBecauseUiRendersItAsText() {
        var fixture = fixture();
        String displayName = "<img src=x onerror='alert(1)'> রাহিম Ω";

        fixture.service.register(new LoginRequest("safe.student-1", displayName, "Orbit7!Lake"));

        assertEquals(displayName, fixture.savedUser.get().getDisplayName());
    }

    @Test
    void registrationRejectsControlCharactersAndOverlongDisplayNames() {
        var fixture = fixture();

        ResponseStatusException control = assertThrows(ResponseStatusException.class,
                () -> fixture.service.register(new LoginRequest("student1", "Ada\nLovelace", "Orbit7!Lake")));
        ResponseStatusException overlong = assertThrows(ResponseStatusException.class,
                () -> fixture.service.register(new LoginRequest("student2", "a".repeat(129), "Orbit7!Lake")));

        assertEquals(HttpStatus.BAD_REQUEST, control.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, overlong.getStatusCode());
    }

    @ParameterizedTest
    @MethodSource("deceptiveDisplayNames")
    void registrationRejectsDeceptiveUnicodeBeforePersistence(String displayName) {
        var fixture = fixture();

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> fixture.service.register(new LoginRequest("student1", displayName, "Orbit7!Lake")));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertEquals(null, fixture.checkedUsername.get());
        assertEquals(null, fixture.savedUser.get());
    }

    static Stream<String> deceptiveDisplayNames() {
        return Stream.of("Ada\u202Eadmin", "Ada\u2066admin", "Ada\u061Cadmin",
                "Ada\u200Badmin", "Ada\u2060admin", "Ada\uFEFFadmin",
                "Ada\u00ADadmin", "Ada\u2028admin", "Ada\uD800admin");
    }

    @Test
    void registrationNormalizesOrdinaryMultilingualDisplayNameWithoutRemovingJoiners() {
        var fixture = fixture();
        String decomposed = "  Cafe\u0301 রাহিম می\u200Cخواهم \uD83D\uDE80  ";

        fixture.service.register(new LoginRequest("student1", decomposed, "Orbit7!Lake"));

        assertEquals("Café রাহিম می\u200Cخواهم \uD83D\uDE80", fixture.savedUser.get().getDisplayName());
    }

    private static Fixture fixture() {
        AtomicReference<String> checkedUsername = new AtomicReference<>();
        AtomicReference<User> savedUser = new AtomicReference<>();
        UserRepository users = fake(UserRepository.class, (method, args) -> switch (method) {
            case "existsByUsername" -> {
                checkedUsername.set((String) args[0]);
                yield false;
            }
            case "save" -> {
                savedUser.set((User) args[0]);
                yield args[0];
            }
            case "findByUsername" -> Optional.ofNullable(savedUser.get())
                    .filter(user -> user.getUsername().equals(args[0]));
            default -> throw new UnsupportedOperationException(method);
        });
        JwtService jwt = new JwtService("test-secret-that-is-at-least-thirty-two-bytes-long", 12);
        UserService service = new UserService(users, new BCryptPasswordEncoder(4), jwt,
                new ActiveSessionRegistry(), null, null);
        return new Fixture(service, checkedUsername, savedUser);
    }

    @SuppressWarnings("unchecked")
    private static <T> T fake(Class<T> iface, RepoHandler handler) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[] { iface },
                (proxy, method, args) -> handler.handle(method.getName(), args));
    }

    private record Fixture(UserService service, AtomicReference<String> checkedUsername,
                           AtomicReference<User> savedUser) {}

    @FunctionalInterface
    private interface RepoHandler {
        Object handle(String method, Object[] args);
    }
}
