/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.client.app;

import com.arbitrator.client.net.FakeJudgeApi;
import com.arbitrator.client.net.HttpJudgeApi;
import com.arbitrator.client.net.JudgeApi;
import com.arbitrator.common.dto.ContestStateDto;
import com.arbitrator.common.dto.LoginResponse;

/**
 * Session state shared across controllers. One instance per app run,
 * reached via AppState.get() from FXML-instantiated controllers.
 */
public final class AppState {

    private static AppState instance;

    private final ServerConfig serverConfig;
    private final JudgeApi api;

    private LoginResponse session;
    private ContestStateDto contest;

    /** serverTime - localTime at the moment the contest state was fetched;
        the client NEVER trusts its own clock alone (FMEA-06). */
    private long clockOffsetMs;

    /** Theme preference for this session (Ctrl+D or the top-bar toggle). */
    private boolean darkMode;

    private AppState() {
        this.serverConfig = ServerConfig.load();
        this.api = Boolean.getBoolean("arbitrator.mock")
                ? new FakeJudgeApi()
                : new HttpJudgeApi(serverConfig);
    }

    public static synchronized AppState get() {
        if (instance == null) {
            instance = new AppState();
        }
        return instance;
    }

    public JudgeApi api() {
        return api;
    }

    public ServerConfig serverConfig() {
        return serverConfig;
    }

    public LoginResponse session() {
        return session;
    }

    public void setSession(LoginResponse session) {
        this.session = session;
    }

    public ContestStateDto contest() {
        return contest;
    }

    public void setContest(ContestStateDto contest) {
        this.contest = contest;
        this.clockOffsetMs = contest.serverTimeMs() - System.currentTimeMillis();
    }

    /** Best estimate of the authoritative server clock, right now. */
    public long serverNowMs() {
        return System.currentTimeMillis() + clockOffsetMs;
    }

    /** Sign-out drops the contest so a stale one can't leak into a new session. */
    public void setContestCleared() {
        this.contest = null;
        this.clockOffsetMs = 0;
    }

    public boolean darkMode() {
        return darkMode;
    }

    public void setDarkMode(boolean darkMode) {
        this.darkMode = darkMode;
    }

    public void shutdown() {
        api.close();
    }
}
