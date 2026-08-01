package com.labjudge.server.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labjudge.common.dto.ProblemPackageResultDto;
import com.labjudge.server.entity.Problem;
import com.labjudge.server.entity.TestCase;
import com.labjudge.server.repo.ProblemRepository;
import com.labjudge.server.repo.TestCaseRepository;

/**
 * FR-05: import a problem package ZIP.
 *
 * Expected layout (SRS §6.1 glossary):
 * <pre>
 *   config.json              { "code": "A", "title": "...", "timeLimitMs": 2000,
 *                              "memoryLimitKb": 262144 }
 *   statement/statement.html
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
    private static final long MAX_SINGLE_FILE = 32L * 1024 * 1024;           // 32 MiB

    private static final int MIN_TIME_LIMIT_MS = 100;
    private static final int MAX_TIME_LIMIT_MS = 30_000;
    private static final int MIN_MEMORY_KB = 1024;            // 1 MiB
    private static final int MAX_MEMORY_KB = 1024 * 1024;     // 1 GiB

    private final ProblemRepository problems;
    private final TestCaseRepository testCases;
    private final ContestService contestService;
    private final ObjectMapper json = new ObjectMapper();

    public ProblemPackageService(ProblemRepository problems,
                                 TestCaseRepository testCases,
                                 ContestService contestService) {
        this.problems = problems;
        this.testCases = testCases;
        this.contestService = contestService;
    }

    @Transactional
    public ProblemPackageResultDto importPackage(byte[] zipBytes) {
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

        String code = text(config, "code");
        String title = text(config, "title");
        int timeLimitMs = config.path("timeLimitMs").asInt(2000);
        int memoryLimitKb = config.path("memoryLimitKb").asInt(262144);

        if (code == null || code.isBlank()) {
            errors.add("config.json: \"code\" is required (e.g. \"A\")");
        } else if (code.length() > 8) {
            errors.add("config.json: \"code\" must be at most 8 characters");
        }
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

        // --- statement ---
        String statementHtml = findStatement(files, errors);

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

        // --- uniqueness within the contest ---
        long contestId = contestService.requireCurrent().getId();
        if (code != null && !code.isBlank()
                && problems.findByContestIdOrderByOrderingAscCodeAsc(contestId).stream()
                        .anyMatch(p -> p.getCode().equalsIgnoreCase(code.trim()))) {
            errors.add("Problem code \"" + code.trim()
                    + "\" already exists in this contest — delete it first or use another code");
        }

        if (!errors.isEmpty()) {
            return ProblemPackageResultDto.rejected(errors);
        }

        // --- persist (only now that everything validated) ---
        Problem problem = new Problem();
        problem.setContestId(contestId);
        problem.setCode(code.trim());
        problem.setTitle(title.trim());
        problem.setStatementHtml(statementHtml);
        problem.setTimeLimitMs(timeLimitMs);
        problem.setMemoryLimitKb(memoryLimitKb);
        problem.setOrdering(problems.findByContestIdOrderByOrderingAscCodeAsc(contestId).size() + 1);
        problems.save(problem);

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

    // ------------------------------------------------------------------

    /**
     * Reads all entries into memory, guarding against zip-slip and zip bombs.
     * Nothing is written to disk, so a hostile archive cannot escape anywhere.
     */
    private Map<String, byte[]> readZip(byte[] zipBytes, List<String> errors) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        long total = 0;
        int count = 0;

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (++count > MAX_ENTRIES) {
                    errors.add("Package has too many entries (limit " + MAX_ENTRIES + ")");
                    return out;
                }
                String name = entry.getName().replace('\\', '/');

                // Zip-slip: an entry like ../../etc/passwd must never be honoured.
                // We keep everything in memory, but a normalised name is still
                // required so nothing downstream can be tricked by the path.
                if (name.startsWith("/") || name.contains("../")) {
                    errors.add("Unsafe path in package: " + entry.getName());
                    return out;
                }
                if (entry.isDirectory()) {
                    continue;
                }
                if (entry.getSize() > MAX_SINGLE_FILE) {
                    errors.add("File too large in package: " + name);
                    return out;
                }

                byte[] data = readCapped(zis, MAX_SINGLE_FILE);
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

    private static byte[] readCapped(InputStream in, long cap) throws IOException {
        byte[] buf = in.readNBytes((int) Math.min(cap, Integer.MAX_VALUE));
        return buf;
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
        boolean sawPdf = false;
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
            } else if (path.endsWith(".pdf")) {
                sawPdf = true;
            }
        }
        if (found == null) {
            if (sawPdf) {
                errors.add("statement/ contains only a PDF — PDF statements are not supported yet; "
                        + "export it as HTML (see STATUS.md known gaps)");
            } else {
                errors.add("No statement found — statement/ must contain an .html, .txt or .md file");
            }
        }
        return found;
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }
}
