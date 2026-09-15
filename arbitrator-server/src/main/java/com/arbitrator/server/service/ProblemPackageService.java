package com.arbitrator.server.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.arbitrator.common.dto.ProblemPackageResultDto;
import com.arbitrator.common.enums.CheckerType;
import com.arbitrator.server.entity.Contest;
import com.arbitrator.server.entity.Problem;
import com.arbitrator.server.entity.TestCase;
import com.arbitrator.server.judge.CheckerCompilationException;
import com.arbitrator.server.judge.CheckerRunner;
import com.arbitrator.server.repo.ProblemRepository;
import com.arbitrator.server.repo.TestCaseRepository;

/**
 * FR-05: import a problem package ZIP.
 *
 * Expected layout (SRS §6.1 glossary):
 * <pre>
 *   config.json              { "code": "A", "title": "...", "timeLimitMs": 2000,
 *                              "memoryLimitKb": 262144 }
 *   statement/statement.html
 *   checker/checker.cpp      (optional)
 *   tests/01.in  01.out  02.in  02.out ...
 * </pre>
 * A single wrapping top-level directory is tolerated, because "zip the folder"
 * is what people actually do.
 *
 * Validation is all-or-nothing (UC-09, FMEA-07): the package is either fully
 * imported or fully rejected with a list of reasons. A half-imported problem
 * discovered mid-contest would be worse than one that never loaded.
 */
@Service
public class ProblemPackageService {

    /** Zip-bomb guards. A real lab package is a few hundred KB. */
    private static final int MAX_ENTRIES = 5_000;
    private static final long MAX_TOTAL_UNCOMPRESSED = 256L * 1024 * 1024;   // 256 MiB
    static final long MAX_SINGLE_FILE = 32L * 1024 * 1024;                   // 32 MiB

    /** A statement is a few pages; anything larger is a mistake, not a problem. */
    private static final long MAX_STATEMENT_PDF_BYTES = 16L * 1024 * 1024;

    // Public: AdminProblemController.update() enforces the same range on an
    // in-place edit, which otherwise had no upper bound at all (see there).
    // A time/memory limit this service would reject on upload must be
    // rejected on edit too, or the bound is trivially bypassed by uploading
    // a valid package and then editing it into something the sandbox and
    // the shared judge pool were never sized for.
    public static final int MIN_TIME_LIMIT_MS = 100;
    public static final int MAX_TIME_LIMIT_MS = 30_000;
    public static final int MIN_MEMORY_KB = 1024;            // 1 MiB
    public static final int MAX_MEMORY_KB = 1024 * 1024;     // 1 GiB

    private final ProblemRepository problems;
    private final TestCaseRepository testCases;
    private final ContestService contestService;
    private final CheckerRunner checkerRunner;
    /** Only for the statement-PDF side table; everything else goes through JPA. */
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public ProblemPackageService(ProblemRepository problems,
                                 TestCaseRepository testCases,
                                 ContestService contestService,
                                 CheckerRunner checkerRunner,
                                 JdbcTemplate jdbc) {
        this.problems = problems;
        this.testCases = testCases;
        this.contestService = contestService;
        this.checkerRunner = checkerRunner;
        this.jdbc = jdbc;
    }

    @Transactional
    public ProblemPackageResultDto importPackage(byte[] zipBytes) {
        return importPackageForContest(zipBytes, null);
    }

    @Transactional
    public ProblemPackageResultDto importPackageForContest(byte[] zipBytes, Long targetContestId) {
        List<String> errors = new ArrayList<>();

        Map<String, byte[]> files;
        try {
            files = readZip(zipBytes, errors);
        } catch (IOException e) {
            return ProblemPackageResultDto.rejected(
                    List.of("Not a readable ZIP archive: " + e.getMessage()));
        }
        if (!errors.isEmpty()) {
            return ProblemPackageResultDto.rejected(errors);
        }
        files = stripSingleTopLevelDir(files);

        // --- config.json ---
        byte[] configBytes = files.get("config.json");
        if (configBytes == null) {
            return ProblemPackageResultDto.rejected(
                    List.of("config.json is missing from the package root"));
        }
        JsonNode config;
        try {
            config = json.readTree(configBytes);
        } catch (IOException e) {
            return ProblemPackageResultDto.rejected(
                    List.of("config.json is not valid JSON: " + e.getMessage()));
        }

        // "code" is deliberately not read here — a problem's code is always
        // its alphabetic position (A, B, C...) among the contest's problems,
        // assigned below and kept in sync by reorder(). A code in config.json
        // from an older package is simply ignored, not an error.
        String title = text(config, "title");
        int timeLimitMs = config.path("timeLimitMs").asInt(2000);
        int memoryLimitKb = config.path("memoryLimitKb").asInt(262144);

        if (title == null || title.isBlank()) {
            errors.add("config.json: \"title\" is required");
        }
        if (timeLimitMs < MIN_TIME_LIMIT_MS || timeLimitMs > MAX_TIME_LIMIT_MS) {
            errors.add("config.json: \"timeLimitMs\" must be between "
                    + MIN_TIME_LIMIT_MS + " and " + MAX_TIME_LIMIT_MS);
        }
        if (memoryLimitKb < MIN_MEMORY_KB || memoryLimitKb > MAX_MEMORY_KB) {
            errors.add("config.json: \"memoryLimitKb\" must be between "
                    + MIN_MEMORY_KB + " and " + MAX_MEMORY_KB);
        }

        // --- statement (HTML preferred; PDF supported since V59) ---
        String statementHtml = findStatement(files, errors);
        byte[] statementPdf = findStatementPdf(files);
        if (statementPdf != null && statementPdf.length > MAX_STATEMENT_PDF_BYTES) {
            errors.add("The PDF statement is larger than "
                    + (MAX_STATEMENT_PDF_BYTES / 1024 / 1024) + " MB");
        }

        // --- checker (optional custom checker, FR-14) ---
        CheckerType checkerType = CheckerType.EXACT;
        String checkerSource = null;
        byte[] checkerBytes = files.get("checker/checker.cpp");
        if (checkerBytes != null) {
            checkerType = CheckerType.CUSTOM;
            checkerSource = new String(checkerBytes, StandardCharsets.UTF_8);
            if (checkerRunner != null) {
                try {
                    checkerRunner.validateChecker(checkerSource);
                } catch (CheckerCompilationException e) {
                    errors.add("Checker compilation failed: " + e.getCompilerOutput());
                }
            }
        }

        // --- tests: every .in needs its .out (FMEA-07) ---
        Map<String, String> inputs = new TreeMap<>();
        Map<String, String> outputs = new TreeMap<>();
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            String path = e.getKey();
            if (!path.startsWith("tests/")) {
                continue;
            }
            String name = path.substring("tests/".length());
            String content = new String(e.getValue(), StandardCharsets.UTF_8);
            if (name.endsWith(".in")) {
                inputs.put(name.substring(0, name.length() - 3), content);
            } else if (name.endsWith(".out")) {
                outputs.put(name.substring(0, name.length() - 4), content);
            }
        }
        if (inputs.isEmpty()) {
            errors.add("No test cases found — tests/ must contain at least one .in/.out pair");
        }
        for (String base : inputs.keySet()) {
            if (!outputs.containsKey(base)) {
                errors.add("Test \"" + base + ".in\" has no matching \"" + base + ".out\"");
            }
        }
        for (String base : outputs.keySet()) {
            if (!inputs.containsKey(base)) {
                errors.add("Test \"" + base + ".out\" has no matching \"" + base + ".in\"");
            }
        }

        long contestId = (targetContestId != null) ? targetContestId : contestService.requireCurrent().getId();

        if (!errors.isEmpty()) {
            return ProblemPackageResultDto.rejected(errors);
        }

        // --- persist (only now that everything validated) ---
        int ordering = problems.findByContestIdOrderByOrderingAscCodeAsc(contestId).size() + 1;
        String code = codeForOrdering(ordering);

        Problem problem = new Problem();
        problem.setContestId(contestId);
        problem.setCode(code);
        problem.setTitle(title.trim());
        // HTML wins when a package ships both; a PDF-only package gets the
        // placeholder, because statement_html is NOT NULL.
        boolean usingPdf = statementHtml == null && statementPdf != null;
        problem.setStatementHtml(usingPdf
                ? pdfPlaceholder(code, title.trim()) : statementHtml);
        problem.setStatementIsPdf(usingPdf);
        problem.setTimeLimitMs(timeLimitMs);
        problem.setMemoryLimitKb(memoryLimitKb);
        problem.setOrdering(ordering);
        problem.setCheckerType(checkerType);
        problem.setCheckerSource(checkerSource);
        problems.save(problem);

        if (usingPdf) {
            // Side table, so Problem stays cheap to load everywhere else (V59).
            jdbc.update("INSERT INTO problem_statement_pdfs (problem_id, data) VALUES (?, ?)",
                    problem.getId(), statementPdf);
        }

        int idx = 1;
        for (Map.Entry<String, String> in : inputs.entrySet()) {
            TestCase tc = new TestCase();
            tc.setProblemId(problem.getId());
            tc.setIdx(idx++);                       // ascending, fail-fast order (BR-05)
            tc.setInputData(in.getValue());
            tc.setExpectedOutput(outputs.get(in.getKey()));
            testCases.save(tc);
        }

        return new ProblemPackageResultDto(true, problem.getId(), problem.getCode(),
                problem.getTitle(), inputs.size(), List.of());
    }

    /**
     * Resequences a contest's problems to the given order and re-letters every
     * one of them to match (A, B, C...), regardless of what code each had
     * before. That covers both a plain drag-to-reorder and the one-time
     * cleanup of codes from packages that still set "code" in config.json —
     * the moment an admin reorders once, everything falls in line.
     */
    @Transactional
    public List<Problem> reorder(long contestId, List<Long> orderedProblemIds) {
        List<Problem> current = problems.findByContestIdOrderByOrderingAscCodeAsc(contestId);
        Map<Long, Problem> byId = new HashMap<>();
        for (Problem p : current) {
            byId.put(p.getId(), p);
        }
        if (orderedProblemIds.size() != current.size() || !byId.keySet().containsAll(orderedProblemIds)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The reorder list must contain exactly this contest's problems, once each");
        }
        // Two passes, not one: problems.code is unique per (contest, code), so
        // assigning final letters directly can collide mid-transaction with
        // another row's CURRENT code that hasn't moved yet (e.g. problem 3
        // becoming "A" while problem 1 still IS "A"). A placeholder pass first
        // moves every row out of the A/B/C... namespace so the second pass can
        // never collide with anything still sitting on its old code.
        for (int i = 0; i < current.size(); i++) {
            Problem p = current.get(i);
            p.setCode("#" + i);
            problems.save(p);
        }
        problems.flush();

        List<Problem> updated = new ArrayList<>();
        int ordering = 1;
        for (Long id : orderedProblemIds) {
            Problem p = byId.get(id);
            p.setOrdering(ordering);
            p.setCode(codeForOrdering(ordering));
            problems.save(p);
            updated.add(p);
            ordering++;
        }
        return updated;
    }

    /** 1 -> "A", 2 -> "B", ..., 26 -> "Z", 27 -> "AA" — spreadsheet-column style. */
    static String codeForOrdering(int ordering) {
        StringBuilder sb = new StringBuilder();
        int n = ordering;
        while (n > 0) {
            n--;
            sb.insert(0, (char) ('A' + n % 26));
            n /= 26;
        }
        return sb.toString();
    }

    /**
     * Replaces just the PDF bytes behind an existing problem (FR-05 follow-up,
     * same spirit as the HTML {@link #importPackageForContest} edit path):
     * previously a corrected PDF meant deleting the problem and re-uploading
     * the whole package, destroying every submission against it. Test data,
     * limits and checker are untouched — only the statement bytes move.
     */
    @Transactional
    public void replaceStatementPdf(long problemId, byte[] pdfBytes) {
        Problem p = problems.findById(problemId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such problem"));
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No file uploaded");
        }
        if (pdfBytes.length > MAX_STATEMENT_PDF_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The PDF statement is larger than " + (MAX_STATEMENT_PDF_BYTES / 1024 / 1024) + " MB");
        }
        if (!looksLikePdf(pdfBytes)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That file does not look like a PDF (missing %PDF header)");
        }
        int rows = jdbc.update("UPDATE problem_statement_pdfs SET data = ? WHERE problem_id = ?",
                pdfBytes, problemId);
        if (rows == 0) {
            jdbc.update("INSERT INTO problem_statement_pdfs (problem_id, data) VALUES (?, ?)",
                    problemId, pdfBytes);
        }
        if (!p.isStatementIsPdf()) {
            p.setStatementIsPdf(true);
            problems.save(p);
        }
    }

    private static boolean looksLikePdf(byte[] bytes) {
        return bytes.length >= 4 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F';
    }

    /**
     * Clones a contest: a fresh DRAFT contest under a new title, with a copy
     * of every problem (statement, PDF bytes if any, limits, checker, and
     * test cases). Nothing else — no submissions, standings, marks,
     * announcements or clarifications, and no participants. Those belong to
     * one run of a contest; a clone is a new contest that happens to start
     * from the same problem set, the same way re-running an ended contest
     * used to work before that was replaced by cloning (see
     * {@link ContestService#openLobby}).
     *
     * Problem codes are copied as-is rather than re-lettered: the clone's
     * problems are inserted in the source's order into an otherwise-empty
     * contest, so the same codes are already unique there.
     */
    @Transactional
    public Contest cloneContest(long sourceContestId, String newTitle) {
        if (newTitle == null || newTitle.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the clone a title");
        }
        Contest source = contestService.require(sourceContestId);
        Contest clone = contestService.create(newTitle.trim(), source.getDurationMinutes());

        for (Problem p : problems.findByContestIdOrderByOrderingAscCodeAsc(sourceContestId)) {
            Problem copy = new Problem();
            copy.setContestId(clone.getId());
            copy.setCode(p.getCode());
            copy.setTitle(p.getTitle());
            copy.setStatementHtml(p.getStatementHtml());
            copy.setStatementIsPdf(p.isStatementIsPdf());
            copy.setTimeLimitMs(p.getTimeLimitMs());
            copy.setMemoryLimitKb(p.getMemoryLimitKb());
            copy.setOrdering(p.getOrdering());
            copy.setCheckerType(p.getCheckerType());
            copy.setCheckerSource(p.getCheckerSource());
            problems.save(copy);

            if (p.isStatementIsPdf()) {
                List<byte[]> pdf = jdbc.query(
                        "SELECT data FROM problem_statement_pdfs WHERE problem_id = ?",
                        (rs, i) -> rs.getBytes("data"), p.getId());
                if (!pdf.isEmpty()) {
                    jdbc.update("INSERT INTO problem_statement_pdfs (problem_id, data) VALUES (?, ?)",
                            copy.getId(), pdf.get(0));
                }
            }

            for (TestCase t : testCases.findByProblemIdOrderByIdxAsc(p.getId())) {
                TestCase tc = new TestCase();
                tc.setProblemId(copy.getId());
                tc.setIdx(t.getIdx());
                tc.setInputData(t.getInputData());
                tc.setExpectedOutput(t.getExpectedOutput());
                testCases.save(tc);
            }
        }
        return clone;
    }

    // ------------------------------------------------------------------

    /**
     * Reads all entries into memory, guarding against zip-slip and zip bombs.
     * Nothing is written to disk, so a hostile archive cannot escape anywhere.
     */
    private Map<String, byte[]> readZip(byte[] zipBytes, List<String> errors) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        Set<String> entryNames = new HashSet<>();
        long total = 0;
        int count = 0;

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (++count > MAX_ENTRIES) {
                    errors.add("Package has too many entries (limit " + MAX_ENTRIES + ")");
                    return out;
                }
                String name = canonicalEntryName(entry.getName());

                // Nothing is extracted to disk, but all downstream lookups must
                // see one unambiguous, traversal-free representation of a path.
                if (name == null || name.isEmpty()) {
                    errors.add("Unsafe path in package: " + entry.getName());
                    return out;
                }
                if (!entryNames.add(name)) {
                    errors.add("Duplicate path in package: " + name);
                    return out;
                }
                if (entry.isDirectory()) {
                    continue;
                }
                if (entry.getSize() > MAX_SINGLE_FILE) {
                    errors.add("File too large in package: " + name);
                    return out;
                }

                CappedRead read = readCapped(zis, MAX_SINGLE_FILE);
                if (read.exceeded()) {
                    errors.add("File too large in package: " + name);
                    return out;
                }
                byte[] data = read.data();
                total += data.length;
                if (total > MAX_TOTAL_UNCOMPRESSED) {
                    errors.add("Package expands to more than "
                            + (MAX_TOTAL_UNCOMPRESSED / 1024 / 1024) + " MB — refusing to import");
                    return out;
                }
                out.put(name, data);
            }
        }
        if (out.isEmpty()) {
            errors.add("The ZIP archive is empty");
        }
        return out;
    }

    /**
     * Reads through the limit and probes one extra byte. ZIP entries commonly
     * report an unknown size until their data descriptor has been consumed, so
     * trusting {@link ZipEntry#getSize()} alone is not a size boundary.
     */
    static CappedRead readCapped(InputStream in, long cap) throws IOException {
        if (cap < 0 || cap >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("cap must be between 0 and Integer.MAX_VALUE - 1");
        }
        byte[] data = in.readNBytes((int) cap + 1);
        return new CappedRead(data, data.length > cap);
    }

    static record CappedRead(byte[] data, boolean exceeded) {}

    /**
     * Canonical ZIP path used both for validation and map keys. Backslashes,
     * repeated separators, and dot segments cannot create aliases; parent
     * traversal and absolute paths are rejected rather than resolved.
     */
    private static String canonicalEntryName(String rawName) {
        String slashName = rawName.replace('\\', '/');
        if (slashName.startsWith("/")) {
            return null;
        }

        List<String> segments = new ArrayList<>();
        for (String segment : slashName.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment) || segment.indexOf('\0') >= 0) {
                return null;
            }
            segments.add(segment);
        }
        return String.join("/", segments);
    }

    /**
     * "Zip the folder" produces every path under one wrapper directory.
     * Detect that case and strip the prefix so both layouts work.
     */
    private static Map<String, byte[]> stripSingleTopLevelDir(Map<String, byte[]> files) {
        if (files.isEmpty() || files.containsKey("config.json")) {
            return files;
        }
        String prefix = null;
        for (String path : files.keySet()) {
            int slash = path.indexOf('/');
            if (slash < 0) {
                return files;                 // a root-level file that isn't config.json
            }
            String top = path.substring(0, slash + 1);
            if (prefix == null) {
                prefix = top;
            } else if (!prefix.equals(top)) {
                return files;                 // more than one top-level dir
            }
        }
        if (prefix == null) {
            return files;
        }
        Map<String, byte[]> stripped = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            stripped.put(e.getKey().substring(prefix.length()), e.getValue());
        }
        return stripped;
    }

    /**
     * Statements render in a JavaFX WebView, so HTML is the supported format.
     * Plain text and Markdown are wrapped in a &lt;pre&gt; block rather than
     * rejected. PDF is explicitly called out — the SRS allows it, but rendering
     * it needs file storage plus a PDF view the client doesn't have yet.
     */
    private static String findStatement(Map<String, byte[]> files, List<String> errors) {
        String found = null;
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            String path = e.getKey().toLowerCase();
            if (!path.startsWith("statement/")) {
                continue;
            }
            if (path.endsWith(".html") || path.endsWith(".htm")) {
                return new String(e.getValue(), StandardCharsets.UTF_8);
            }
            if (path.endsWith(".txt") || path.endsWith(".md")) {
                found = "<pre class=\"statement-text\">"
                        + escapeHtml(new String(e.getValue(), StandardCharsets.UTF_8))
                        + "</pre>";
            }
        }
        // A PDF is handled by the caller, which stores the bytes; reaching here
        // with neither means the package genuinely has no statement.
        if (found == null && findStatementPdf(files) == null) {
            errors.add("No statement found — statement/ must contain "
                    + "an .html, .txt, .md or .pdf file");
        }
        return found;
    }

    /**
     * The PDF statement, if the package ships one (FR-05).
     *
     * HTML still wins when both are present: it themes properly, reflows, and
     * is searchable, so a package offering both is offering a preference.
     */
    private static byte[] findStatementPdf(Map<String, byte[]> files) {
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            String path = e.getKey().toLowerCase();
            if (path.startsWith("statement/") && path.endsWith(".pdf")) {
                return e.getValue();
            }
        }
        return null;
    }

    /** A PDF statement needs something in the NOT NULL statement_html column. */
    private static String pdfPlaceholder(String code, String title) {
        return "<p class=\"statement-text\">The statement for " + escapeHtml(code)
                + ". " + escapeHtml(title) + " is a PDF.</p>";
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }
}
