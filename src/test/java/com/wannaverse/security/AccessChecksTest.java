package com.wannaverse.security;

import static org.junit.jupiter.api.Assertions.*;

import com.github.dockerjava.api.model.Container;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

class AccessChecksTest {

    private static Container container(String id, String name) throws Exception {
        Container c = new Container();
        set(c, "id", id);
        set(c, "names", new String[] {"/" + name});
        return c;
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = Container.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void containersResolveTheWayDockerDoes() throws Exception {
        Container web = container("abc123" + "0".repeat(58), "web");
        // A container whose name looks like the start of the other one's ID.
        Container tricky = container("def456" + "0".repeat(58), "abc123");
        List<Container> all = List.of(web, tricky);

        // An exact name wins over an ID prefix, as in Docker.
        assertSame(tricky, PermissionInterceptor.resolveContainer(all, "abc123"));
        assertSame(web, PermissionInterceptor.resolveContainer(all, "web"));
        assertSame(web, PermissionInterceptor.resolveContainer(all, web.getId()));
        assertSame(tricky, PermissionInterceptor.resolveContainer(all, "def4"));
        assertNull(PermissionInterceptor.resolveContainer(all, "nothing"));
        // Ambiguous prefixes resolve to nothing.
        Container other = container("def999" + "0".repeat(58), "db");
        assertNull(PermissionInterceptor.resolveContainer(List.of(tricky, other), "def"));
    }

    @Test
    void wrongPasswordsFromOneAddressDoNotLockOthersOut() {
        LoginRateLimiter limiter = new LoginRateLimiter();
        for (int i = 0; i < 10; i++) limiter.recordFailure("10.0.0.1", "admin");
        assertTrue(limiter.retryAfterMs("10.0.0.1", "admin") > 0);
        assertEquals(0, limiter.retryAfterMs("10.0.0.2", "admin"));
    }
}
