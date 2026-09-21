/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.security;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "arbitrator.api-rate-limit")
public class ApiRateLimitProperties {
    private int windowSeconds = 60, maxKeys = 20000, accountTotal = 1200, ipTotal = 12000;
    private Map<String, Integer> account = new LinkedHashMap<>(Map.of(
            "read", 600, "write", 120, "join", 10, "run", 12, "submit", 6, "clarification", 5));
    private Map<String, Integer> ip = new LinkedHashMap<>(Map.of(
            "read", 12000, "write", 2400, "registration", 30, "login", 600,
            "join", 240, "run", 240, "submit", 240, "clarification", 120));
    public int getWindowSeconds() { return windowSeconds; }
    public void setWindowSeconds(int value) { windowSeconds = value; }
    public int getMaxKeys() { return maxKeys; }
    public void setMaxKeys(int value) { maxKeys = value; }
    public int getAccountTotal() { return accountTotal; }
    public void setAccountTotal(int value) { accountTotal = value; }
    public int getIpTotal() { return ipTotal; }
    public void setIpTotal(int value) { ipTotal = value; }
    public Map<String, Integer> getAccount() { return account; }
    public void setAccount(Map<String, Integer> value) { account = value; }
    public Map<String, Integer> getIp() { return ip; }
    public void setIp(Map<String, Integer> value) { ip = value; }
}
