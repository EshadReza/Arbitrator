package com.arbitrator.server.config;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import com.arbitrator.common.enums.ContestState;
import com.arbitrator.server.entity.Contest;
import com.arbitrator.server.repo.ContestRepository;

/**
 * Puts every contest back to DRAFT when the server boots.
 *
 * The contest state lives in MySQL, so it survives a restart: a contest left
 * ACTIVE at shutdown came back ACTIVE with its clock still ticking, and the
 * first student to open the client walked straight into a running contest that
 * nobody had started. For a lab session that is wrong twice over — the timer
 * has been running against the wall clock while the server was down, and the
 * problems are released before the instructor is ready.
 *
 * So a freshly booted server holds nothing live (CLAUDE.md, "Contest
 * lifecycle"): the instructor opens the lobby and then starts, deliberately.
 *
 * ENDED contests are left alone — they are history, not something to reopen.
 * Nothing is deleted: only the state and the clock fields are cleared, and the
 * submissions of the previous run stay untouched until {@code start()} archives
 * them (see {@code ContestResetListener}).
 *
 * Set {@code arbitrator.contest.reset-on-boot=true} to reset a contest to DRAFT
 * on restart, ensuring no contest resumes unexpectedly.
 */
@Configuration
public class ContestBootReset {

    private static final Logger log = LoggerFactory.getLogger(ContestBootReset.class);

    /** Everything that counts as "live" and must not survive a restart. */
    private static final List<ContestState> LIVE = List.of(
            ContestState.LOBBY, ContestState.ACTIVE,
            ContestState.PAUSED, ContestState.FROZEN);

    /**
     * Runs before {@code DemoDataSeeder}'s ordering is relevant but after
     * Flyway, which Spring Boot always applies before any CommandLineRunner.
     */
    @Bean
    @Order(0)
    CommandLineRunner resetContestsOnBoot(
            ContestRepository contests,
            @Value("${arbitrator.contest.reset-on-boot:false}") boolean enabled) {
        return args -> {
            List<Contest> live = contests.findAll().stream()
                    .filter(c -> LIVE.contains(c.getState()))
                    .toList();
            if (live.isEmpty()) {
                return;
            }

            if (!enabled) {
                for (Contest c : live) {
                    if (c.endTime() != null && !Instant.now().isBefore(c.endTime())) {
                        log.info("Contest \"{}\" end time passed while server was offline — marking ENDED", c.getTitle());
                        c.setState(ContestState.ENDED);
                        contests.save(c);
                    } else {
                        log.info("Contest \"{}\" remains {} across server restart", c.getTitle(), c.getState());
                    }
                }
                return;
            }

            for (Contest c : live) {
                log.info("Boot reset: \"{}\" was {} — returning it to DRAFT",
                        c.getTitle(), c.getState());
                c.setState(ContestState.DRAFT);
                c.setStartTime(null);
                c.setPausedAt(null);
                c.setPausedMillis(0);
            }
            contests.saveAll(live);
            log.info("{} contest(s) reset to DRAFT on boot — open the lobby from /admin to run one",
                    live.size());
        };
    }
}
