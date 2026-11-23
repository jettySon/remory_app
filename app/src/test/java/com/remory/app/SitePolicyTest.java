package com.remory.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class SitePolicyTest {
    @Test public void keepsOwnPagesAndDefaultPorts() {
        assertTrue(SitePolicy.isSameOrigin("https://remory.example/", "https://remory.example:443/privacy/"));
        assertTrue(SitePolicy.isSameOrigin("http://10.0.2.2:5173/", "http://10.0.2.2:5173/api/v1/auth"));
    }
    @Test public void rejectsOriginLookalikesAndUnsafeSchemes() {
        for (String url : new String[]{"https://remory.example.evil.com/", "https://remory.example@evil.com/",
            "https://evil@remory.example/", "http://remory.example/", "https://remory.example:444/",
            "javascript:alert(1)", "file:///etc/passwd", "/privacy/", "::invalid"}) {
            assertFalse(url, SitePolicy.isSameOrigin("https://remory.example/", url));
        }
    }
}
