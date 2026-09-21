/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.config;

import java.time.Instant;


import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.arbitrator.common.enums.ContestState;
import com.arbitrator.common.enums.Role;
import com.arbitrator.server.entity.Contest;
import com.arbitrator.server.entity.Problem;
import com.arbitrator.server.entity.TestCase;
import com.arbitrator.server.entity.User;
import com.arbitrator.server.repo.ContestRepository;
import com.arbitrator.server.repo.ProblemRepository;
import com.arbitrator.server.repo.TestCaseRepository;
import com.arbitrator.server.repo.UserRepository;

/**
 * Seeds a demo contest on FIRST boot only (empty users table), so the Bundle 1
 * definition-of-done — login, open problem A, submit, get AC — works with zero
 * manual SQL. Credentials: admin/admin123, alice/alice123. Bcrypt hashes are
 * computed at runtime, so no hash literals live in the repo.
 */
@Configuration
public class DemoDataSeeder {

    @Bean
    CommandLineRunner seedDemoData(UserRepository users,
                                   ContestRepository contests,
                                   ProblemRepository problems,
                                   TestCaseRepository testCases,
                                   PasswordEncoder encoder) {
        return args -> {
            if (users.count() > 0) {
                return;
            }

            User admin = new User();
            admin.setUsername("admin");
            admin.setDisplayName("Lab Instructor");
            admin.setPasswordHash(encoder.encode("admin123"));
            admin.setRole(Role.ADMIN);
            users.save(admin);

            User alice = new User();
            alice.setUsername("alice");
            alice.setDisplayName("Alice");
            alice.setPasswordHash(encoder.encode("alice123"));
            alice.setRole(Role.STUDENT);
            users.save(alice);

            Contest contest = new Contest();
            contest.setTitle("Lab Contest #1");
            // DRAFT, not ACTIVE: a freshly started server must not put a
            // contest live on its own. The instructor opens the lobby and
            // starts it explicitly.
            contest.setState(ContestState.DRAFT);
            contest.setStartTime(null);
            contest.setDurationMinutes(180);
            contests.save(contest);

            Problem p = new Problem();
            p.setContestId(contest.getId());
            p.setCode("A");
            p.setTitle("Two Sum");
            p.setTimeLimitMs(2000);
            p.setMemoryLimitKb(262144);
            p.setOrdering(1);
            p.setStatementHtml("""
                    <h1>A. Two Sum</h1>
                    <div class="limits">time limit: 2 s &nbsp; memory limit: 256 MB</div>
                    <p>Given two integers <i>a</i> and <i>b</i>
                    (&minus;10<sup>9</sup> &le; a, b &le; 10<sup>9</sup>), print their sum.</p>
                    <h2>Input</h2><p>A single line with two integers.</p>
                    <h2>Output</h2><p>One integer &mdash; the sum.</p>
                    <h2>Example</h2>
                    <pre class="sample">input
                    2 3
                    output
                    5</pre>
                    """);
            problems.save(p);

            int[][] cases = { {2, 3}, {-5, 5}, {1000000000, 1000000000},
                              {0, 0}, {-1000000000, -1000000000} };
            for (int i = 0; i < cases.length; i++) {
                TestCase tc = new TestCase();
                tc.setProblemId(p.getId());
                tc.setIdx(i + 1);
                tc.setInputData(cases[i][0] + " " + cases[i][1] + "\n");
                tc.setExpectedOutput((long) cases[i][0] + (long) cases[i][1] + "\n");
                testCases.save(tc);
            }
        };
    }
}
