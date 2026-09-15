package com.arbitrator.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
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
    @MethodSource("unsafeStudentIds")
    void registrationRejectsStudentIdsThatCouldEscapeMarkupOrJavaScript(String username) {
        var fixture = fixture();

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> fixture.service.register(new LoginRequest(username, "Normal Name", "password1")));

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

        fixture.service.register(new LoginRequest("  S-24_01.test  ", "  Ada Lovelace  ", "password1"));

        assertEquals("S-24_01.test", fixture.checkedUsername.get());
        assertEquals("S-24_01.test", fixture.savedUser.get().getUsername());
        assertEquals("Ada Lovelace", fixture.savedUser.get().getDisplayName());
    }

    @Test
    void displayNameMayContainUnicodeAndMarkupCharactersBecauseUiRendersItAsText() {
        var fixture = fixture();
        String displayName = "<img src=x onerror='alert(1)'> রাহিম Ω";

        fixture.service.register(new LoginRequest("safe.student-1", displayName, "password1"));

        assertEquals(displayName, fixture.savedUser.get().getDisplayName());
    }

    @Test
    void registrationRejectsControlCharactersAndOverlongDisplayNames() {
        var fixture = fixture();

        ResponseStatusException control = assertThrows(ResponseStatusException.class,
                () -> fixture.service.register(new LoginRequest("student1", "Ada\nLovelace", "password1")));
        ResponseStatusException overlong = assertThrows(ResponseStatusException.class,
                () -> fixture.service.register(new LoginRequest("student2", "a".repeat(129), "password1")));

        assertEquals(HttpStatus.BAD_REQUEST, control.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, overlong.getStatusCode());
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
