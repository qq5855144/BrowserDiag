package com.browserdiag.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkLabTest {
    @Test
    fun wildcardAndRegexUrlPatternsMatch() {
        assertTrue(matchesNetworkRulePattern("*://api.example.com/*", "https://api.example.com/v1/users"))
        assertFalse(matchesNetworkRulePattern("*://api.example.com/*", "https://other.example.com/v1/users"))
        assertTrue(matchesNetworkRulePattern("regex:https://[^/]+/v[0-9]+/items", "https://api.example.com/v2/items"))
        assertFalse(matchesNetworkRulePattern("regex:[", "https://api.example.com/"))
    }

    @Test
    fun methodListIsCaseInsensitive() {
        assertTrue(matchesNetworkRuleMethod("GET, post | PATCH", "post"))
        assertTrue(matchesNetworkRuleMethod("*", "DELETE"))
        assertFalse(matchesNetworkRuleMethod("GET,POST", "PUT"))
    }

    @Test
    fun urlRewriteSupportsPlaceholdersAndRegexGroups() {
        assertEquals(
            "https://mock.example.com/v1/users?debug=1",
            rewriteNetworkUrl(
                "*://api.example.com/*",
                "https://api.example.com/v1/users?debug=1",
                "https://mock.example.com{path}?{query}"
            )
        )
        assertEquals(
            "https://api.example.com/v2/users",
            rewriteNetworkUrl(
                "regex:(https://api\\.example\\.com)/v1/(.*)",
                "https://api.example.com/v1/users",
                "${'$'}1/v2/${'$'}2"
            )
        )
    }

    @Test
    fun bodyReplacementSupportsLiteralAndRegex() {
        assertEquals("hello prod", replaceNetworkText("hello dev", "dev", "prod"))
        assertEquals(
            "id=[42] id=[7]",
            replaceNetworkText("id=42 id=7", "regex:id=([0-9]+)", "id=[${'$'}1]")
        )
    }

    @Test
    fun headerParserKeepsOnlyValidHeaderLines() {
        val headers = parseNetworkHeaders("X-Debug: true\nAuthorization: Bearer token\nBad Header: nope\nEmpty:")
        assertEquals("true", headers["X-Debug"])
        assertEquals("Bearer token", headers["Authorization"])
        assertEquals(2, headers.size)
    }

    @Test
    fun validationRejectsInvalidRegexAndRedirectMock() {
        assertTrue(
            networkRuleValidationError(
                NetworkRule("bad-regex", "bad", urlPattern = "regex:[", action = NetworkRuleAction.BLOCK)
            )?.contains("正则") == true
        )
        assertTrue(
            networkRuleValidationError(
                NetworkRule("redirect", "redirect", action = NetworkRuleAction.MOCK_RESPONSE, statusCode = 302)
            )?.contains("状态码") == true
        )
        assertNull(
            networkRuleValidationError(
                NetworkRule("ok", "ok", action = NetworkRuleAction.MOCK_RESPONSE, statusCode = 200)
            )
        )
    }
}
