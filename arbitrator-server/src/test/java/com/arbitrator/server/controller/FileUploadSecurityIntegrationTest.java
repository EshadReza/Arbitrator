/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.server.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;

import com.arbitrator.common.api.ApiPaths;
import com.arbitrator.common.dto.LoginRequest;
import com.arbitrator.common.dto.LoginResponse;
import com.arbitrator.common.dto.MaterialDto;
import com.arbitrator.server.entity.Contest;
import com.arbitrator.server.service.ContestService;

/** Live HTTP checks for upload/download content-type boundaries. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIf("com.arbitrator.server.controller.RoleEscalationIntegrationTest#mysqlIsReachable")
class FileUploadSecurityIntegrationTest {

    private static final Path MATERIAL_ROOT = createMaterialRoot();

    @DynamicPropertySource
    static void materialRoot(DynamicPropertyRegistry registry) {
        registry.add("arbitrator.materials.root", MATERIAL_ROOT::toString);
    }

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private ContestService contests;
    @Autowired private AdminContestController adminContests;

    private Contest contest;

    @AfterEach
    void cleanDatabase() {
        if (contest != null) {
            adminContests.delete(contest.getId());
            contest = null;
        }
    }

    @AfterAll
    static void cleanFiles() throws IOException {
        if (Files.exists(MATERIAL_ROOT)) {
            try (var paths = Files.walk(MATERIAL_ROOT)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @Test
    void uploaderMimeTypeCannotControlMaterialDownloadType() {
        contest = contests.create("upload-security-" + UUID.randomUUID(), 60);
        String token = loginAdmin().token();

        byte[] hostileHtml = "<script>window.uploadProbe=true</script>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteArrayResource resource = new ByteArrayResource(hostileHtml) {
            @Override
            public String getFilename() {
                return "lesson.html";
            }
        };
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.TEXT_HTML);
        LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new HttpEntity<>(resource, partHeaders));

        HttpHeaders uploadHeaders = authorized(token);
        uploadHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<MaterialDto> uploaded = rest.exchange(
                base() + "/api/admin/contests/" + contest.getId() + "/materials",
                HttpMethod.POST, new HttpEntity<>(form, uploadHeaders), MaterialDto.class);
        assertEquals(HttpStatus.OK, uploaded.getStatusCode());
        assertNotNull(uploaded.getBody());
        assertEquals(MediaType.TEXT_HTML_VALUE, uploaded.getBody().contentType(),
                "metadata may retain the reported type; the download boundary must ignore it");

        ResponseEntity<byte[]> downloaded = rest.exchange(
                base() + "/api/materials/" + uploaded.getBody().id() + "/download",
                HttpMethod.GET, new HttpEntity<>(authorized(token)), byte[].class);

        assertEquals(HttpStatus.OK, downloaded.getStatusCode());
        assertEquals(MediaType.APPLICATION_OCTET_STREAM, downloaded.getHeaders().getContentType());
        assertEquals("attachment", downloaded.getHeaders().getContentDisposition().getType());
        assertEquals("lesson.html", downloaded.getHeaders().getContentDisposition().getFilename());
        assertEquals("nosniff", downloaded.getHeaders().getFirst("X-Content-Type-Options"));
        assertEquals("DENY", downloaded.getHeaders().getFirst("X-Frame-Options"));
        assertEquals("no-referrer", downloaded.getHeaders().getFirst("Referrer-Policy"));
        assertEquals("camera=(), microphone=(), geolocation=(), payment=(), usb=()",
                downloaded.getHeaders().getFirst("Permissions-Policy"));
        assertTrue(downloaded.getHeaders().getFirst("Content-Security-Policy")
                .contains("frame-ancestors 'none'"));
        assertNull(downloaded.getHeaders().getFirst("Strict-Transport-Security"));
        assertEquals(new String(hostileHtml, java.nio.charset.StandardCharsets.UTF_8),
                new String(downloaded.getBody(), java.nio.charset.StandardCharsets.UTF_8));
    }

    private LoginResponse loginAdmin() {
        return rest.postForObject(base() + ApiPaths.AUTH_LOGIN,
                new LoginRequest("admin", null, "admin123", null, true), LoginResponse.class);
    }

    private static HttpHeaders authorized(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private String base() {
        return "http://127.0.0.1:" + port;
    }

    private static Path createMaterialRoot() {
        try {
            return Files.createTempDirectory("arbitrator-upload-security-");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
