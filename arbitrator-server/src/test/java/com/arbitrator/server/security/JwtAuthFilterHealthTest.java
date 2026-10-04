package com.arbitrator.server.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import com.arbitrator.server.repo.UserRepository;

class JwtAuthFilterHealthTest {
    @Test
    void localHealthRoutesDoNotDependOnJwtOrDatabaseRoleLookup() throws Exception {
        JwtService jwt = mock(JwtService.class);
        ActiveSessionRegistry sessions = mock(ActiveSessionRegistry.class);
        UserRepository users = mock(UserRepository.class);
        JwtAuthFilter filter = new JwtAuthFilter(jwt, sessions, users);
        for (String path : new String[] { "/admin/health/live", "/admin/health/ready" }) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            request.addHeader("Authorization", "Bearer still-present-during-outage");
            AtomicBoolean reached = new AtomicBoolean();
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> reached.set(true));
            assertTrue(reached.get());
        }
        verifyNoInteractions(jwt, sessions, users);
        assertFalse(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/admin/operations")));
    }
}
