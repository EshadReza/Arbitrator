/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring Boot serves a welcome index.html for "/" but not for static
 * subdirectories, so /admin and /admin/ would 404 and only the ugly
 * /admin/index.html would work. Instructors are told to open
 * http://localhost:8080/admin, so make that the URL that actually resolves.
 *
 * The redirect target still lives under /admin, so LoopbackAdminFilter
 * (decision D3) continues to gate it — this changes routing, not access.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/admin", "/admin/index.html");
        registry.addRedirectViewController("/admin/", "/admin/index.html");
    }
}
