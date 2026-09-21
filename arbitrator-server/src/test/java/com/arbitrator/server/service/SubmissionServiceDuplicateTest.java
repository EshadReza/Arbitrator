/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.arbitrator.common.dto.SubmitRequest;
import com.arbitrator.common.enums.Language;
import com.arbitrator.server.judge.JudgeProperties;
import com.arbitrator.server.repo.SubmissionRepository;

class SubmissionServiceDuplicateTest {

    @Test
    void exactRepeatForTheSameUserAndProblemIsRejected() {
        SubmissionService service = service((userId, problemId) -> List.of("print('same')\n"));

        assertTrue(service.isDuplicate(41L, 7L, "print('same')\n"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("meaningfulWhitespaceChanges")
    void whitespaceChangesAreNewSubmissions(String description, String existing, String candidate) {
        SubmissionService service = service((userId, problemId) -> List.of(existing));

        assertFalse(service.isDuplicate(41L, 7L, candidate));
    }

    static Stream<Arguments> meaningfulWhitespaceChanges() {
        return Stream.of(
                Arguments.of("Python indentation",
                        "if ready:\n    print('yes')\nprint('done')\n",
                        "if ready:\n    print('yes')\n    print('done')\n"),
                Arguments.of("whitespace inside a string",
                        "print('a b')\n", "print('ab')\n"),
                Arguments.of("a token boundary",
                        "int value = 1;\n", "intvalue=1;\n"),
                Arguments.of("line-ending representation",
                        "first\r\nsecond\r\n", "first\nsecond\n"),
                Arguments.of("trailing whitespace",
                        "print('same')\n", "print('same') \n"));
    }

    @Test
    void identicalSourceForAnotherProblemRemainsAllowed() {
        SubmissionService service = service((userId, problemId) ->
                problemId == 7L ? List.of("shared template") : List.of());

        assertTrue(service.isDuplicate(41L, 7L, "shared template"));
        assertFalse(service.isDuplicate(41L, 8L, "shared template"));
    }

    @Test
    void oversizedUtf8SourceIsRejectedBeforeAnyDatabaseWork() {
        SubmissionService service = service((userId, problemId) -> List.of());
        String source = "😀".repeat(70_000); // 140k UTF-16 chars, 280k UTF-8 bytes

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.submit("alice", new SubmitRequest(1L, Language.CPP17, source), "127.0.0.1"));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, error.getStatusCode());
    }

    private static SubmissionService service(SourceLookup lookup) {
        SubmissionRepository submissions = fake(SubmissionRepository.class, (method, args) -> {
            if (method.equals("findSourceCodesByUserIdAndProblemIdAndActiveTrue")) {
                return lookup.find((Long) args[0], (Long) args[1]);
            }
            throw new UnsupportedOperationException(method);
        });
        return new SubmissionService(submissions, null, null, null, null, null,
                null, null, null, new JudgeProperties());
    }

    @SuppressWarnings("unchecked")
    private static <T> T fake(Class<T> iface, RepoHandler handler) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[] { iface },
                (proxy, method, args) -> handler.handle(method.getName(), args));
    }

    @FunctionalInterface
    private interface SourceLookup {
        List<String> find(long userId, long problemId);
    }

    @FunctionalInterface
    private interface RepoHandler {
        Object handle(String method, Object[] args);
    }
}
