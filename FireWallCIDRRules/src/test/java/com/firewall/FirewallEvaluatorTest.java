package com.firewall;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FirewallEvaluatorTest {

    private FirewallEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new FirewallEvaluator();
    }

    @Nested
    @DisplayName("Basic Matching Tests")
    class BasicMatchingTests {

        @Test
        @DisplayName("Single ALLOW rule matches - should return ALLOW")
        void singleAllowRuleMatches() {
            List<FirewallRule> rules = List.of(
                new FirewallRule("ALLOW", "192.168.1.0/24")
            );
            String result = evaluator.evaluate(rules, "192.168.1.10");
            assertEquals("ALLOW", result);
        }

        @Test
        @DisplayName("Single DENY rule matches - should return DENY")
        void singleDenyRuleMatches() {
            List<FirewallRule> rules = List.of(
                new FirewallRule("DENY", "192.168.1.0/24")
            );
            String result = evaluator.evaluate(rules, "192.168.1.10");
            assertEquals("DENY", result);
        }

        @Test
        @DisplayName("Single rule no match - should return DENY (default)")
        void singleRuleNoMatch() {
            List<FirewallRule> rules = List.of(
                new FirewallRule("ALLOW", "192.168.1.0/24")
            );
            String result = evaluator.evaluate(rules, "10.0.0.1");
            assertEquals("DENY", result);
        }

        @Test
        @DisplayName("Empty rules list - should return DENY (default)")
        void emptyRulesList() {
            List<FirewallRule> rules = Collections.emptyList();
            String result = evaluator.evaluate(rules, "192.168.1.10");
            assertEquals("DENY", result);
        }
    }

    @Nested
    @DisplayName("First-Match-Wins Tests")
    class FirstMatchWinsTests {

        @Test
        @DisplayName("First rule wins - DENY before ALLOW")
        void firstRuleWinsDenyBeforeAllow() {
            List<FirewallRule> rules = List.of(
                new FirewallRule("DENY", "192.168.1.0/24"),
                new FirewallRule("ALLOW", "192.168.0.0/16")
            );
            String result = evaluator.evaluate(rules, "192.168.1.10");
            assertEquals("DENY", result);
        }

        @Test
        @DisplayName("First rule wins - ALLOW before DENY")
        void firstRuleWinsAllowBeforeDeny() {
            List<FirewallRule> rules = List.of(
                new FirewallRule("ALLOW", "192.168.1.0/24"),
                new FirewallRule("DENY", "192.168.0.0/16")
            );
            String result = evaluator.evaluate(rules, "192.168.1.10");
            assertEquals("ALLOW", result);
        }

        @Test
        @DisplayName("Skip non-matching rules, match later rule")
        void skipNonMatchingMatchLater() {
            List<FirewallRule> rules = List.of(
                new FirewallRule("DENY", "192.168.0.0/16"),
                new FirewallRule("ALLOW", "10.0.0.0/8")
            );
            String result = evaluator.evaluate(rules, "10.0.0.5");
            assertEquals("ALLOW", result);
        }
    }

    @Nested
    @DisplayName("CIDR Edge Cases")
    class CidrEdgeCases {

        @Test
        @DisplayName("/32 exact match - should match single IP")
        void cidr32ExactMatch() {
            List<FirewallRule> rules = List.of(
                new FirewallRule("ALLOW", "192.168.1.1/32")
            );
            String result = evaluator.evaluate(rules, "192.168.1.1");
            assertEquals("ALLOW", result);
        }

        @Test
        @DisplayName("/32 no match - different IP should not match")
        void cidr32NoMatch() {
            List<FirewallRule> rules = List.of(
                new FirewallRule("ALLOW", "192.168.1.1/32")
            );
            String result = evaluator.evaluate(rules, "192.168.1.2");
            assertEquals("DENY", result);
        }

        @Test
        @DisplayName("/0 matches everything - random IP")
        void cidr0MatchesEverythingRandomIp() {
            List<FirewallRule> rules = List.of(
                new FirewallRule("ALLOW", "0.0.0.0/0")
            );
            String result = evaluator.evaluate(rules, "8.8.8.8");
            assertEquals("ALLOW", result);
        }

        @Test
        @DisplayName("/0 matches everything - max IP")
        void cidr0MatchesEverythingMaxIp() {
            List<FirewallRule> rules = List.of(
                new FirewallRule("ALLOW", "0.0.0.0/0")
            );
            String result = evaluator.evaluate(rules, "255.255.255.255");
            assertEquals("ALLOW", result);
        }

        @Test
        @DisplayName("Boundary - first IP in range should match")
        void boundaryFirstIpInRange() {
            List<FirewallRule> rules = List.of(
                new FirewallRule("ALLOW", "192.168.1.0/24")
            );
            String result = evaluator.evaluate(rules, "192.168.1.0");
            assertEquals("ALLOW", result);
        }

        @Test
        @DisplayName("Boundary - last IP in range should match")
        void boundaryLastIpInRange() {
            List<FirewallRule> rules = List.of(
                new FirewallRule("ALLOW", "192.168.1.0/24")
            );
            String result = evaluator.evaluate(rules, "192.168.1.255");
            assertEquals("ALLOW", result);
        }

        @Test
        @DisplayName("Boundary - just outside range should not match")
        void boundaryJustOutsideRange() {
            List<FirewallRule> rules = List.of(
                new FirewallRule("ALLOW", "192.168.1.0/24")
            );
            String result = evaluator.evaluate(rules, "192.168.2.0");
            assertEquals("DENY", result);
        }
    }

    @Nested
    @DisplayName("User's Example")
    class UserExample {

        @Test
        @DisplayName("User's example: 255.0.0.10 with DENY 255.0.0.8/29 - should return DENY")
        void userProvidedExample() {
            List<FirewallRule> rules = List.of(
                new FirewallRule("DENY", "255.0.0.8/29"),
                new FirewallRule("ALLOW", "117.145.102.64/30")
            );
            String result = evaluator.evaluate(rules, "255.0.0.10");
            assertEquals("DENY", result);
        }
    }

    @Nested
    @DisplayName("Validation Tests - Invalid IP")
    class InvalidIpValidation {

        @Test
        @DisplayName("Null IP should throw IllegalArgumentException")
        void nullIp() {
            List<FirewallRule> rules = List.of(
                new FirewallRule("ALLOW", "192.168.1.0/24")
            );
            assertThrows(IllegalArgumentException.class, () ->
                evaluator.evaluate(rules, null)
            );
        }

        @Test
        @DisplayName("Empty IP should throw IllegalArgumentException")
        void emptyIp() {
            List<FirewallRule> rules = List.of(
                new FirewallRule("ALLOW", "192.168.1.0/24")
            );
            assertThrows(IllegalArgumentException.class, () ->
                evaluator.evaluate(rules, "")
            );
        }

        @Test
        @DisplayName("Too few octets should throw IllegalArgumentException")
        void tooFewOctets() {
            List<FirewallRule> rules = List.of(
                new FirewallRule("ALLOW", "192.168.1.0/24")
            );
            assertThrows(IllegalArgumentException.class, () ->
                evaluator.evaluate(rules, "192.168.1")
            );
        }

        @Test
        @DisplayName("Too many octets should throw IllegalArgumentException")
        void tooManyOctets() {
            List<FirewallRule> rules = List.of(
                new FirewallRule("ALLOW", "192.168.1.0/24")
            );
            assertThrows(IllegalArgumentException.class, () ->
                evaluator.evaluate(rules, "192.168.1.1.1")
            );
        }

        @Test
        @DisplayName("Octet > 255 should throw IllegalArgumentException")
        void octetGreaterThan255() {
            List<FirewallRule> rules = List.of(
                new FirewallRule("ALLOW", "192.168.1.0/24")
            );
            assertThrows(IllegalArgumentException.class, () ->
                evaluator.evaluate(rules, "192.168.1.256")
            );
        }

        @Test
        @DisplayName("Negative octet should throw IllegalArgumentException")
        void negativeOctet() {
            List<FirewallRule> rules = List.of(
                new FirewallRule("ALLOW", "192.168.1.0/24")
            );
            assertThrows(IllegalArgumentException.class, () ->
                evaluator.evaluate(rules, "192.168.1.-1")
            );
        }

        @Test
        @DisplayName("Non-numeric octet should throw IllegalArgumentException")
        void nonNumericOctet() {
            List<FirewallRule> rules = List.of(
                new FirewallRule("ALLOW", "192.168.1.0/24")
            );
            assertThrows(IllegalArgumentException.class, () ->
                evaluator.evaluate(rules, "192.168.1.abc")
            );
        }
    }

    @Nested
    @DisplayName("Validation Tests - Invalid CIDR")
    class InvalidCidrValidation {

        @Test
        @DisplayName("Missing prefix should throw IllegalArgumentException")
        void missingPrefix() {
            // Now throws at construction time due to fail-fast validation
            assertThrows(IllegalArgumentException.class, () ->
                new FirewallRule("ALLOW", "192.168.1.0")
            );
        }

        @Test
        @DisplayName("Prefix > 32 should throw IllegalArgumentException")
        void prefixGreaterThan32() {
            List<FirewallRule> rules = List.of(
                new FirewallRule("ALLOW", "192.168.1.0/33")
            );
            assertThrows(IllegalArgumentException.class, () ->
                evaluator.evaluate(rules, "192.168.1.10")
            );
        }

        @Test
        @DisplayName("Negative prefix should throw IllegalArgumentException")
        void negativePrefix() {
            List<FirewallRule> rules = List.of(
                new FirewallRule("ALLOW", "192.168.1.0/-1")
            );
            assertThrows(IllegalArgumentException.class, () ->
                evaluator.evaluate(rules, "192.168.1.10")
            );
        }

        @Test
        @DisplayName("Invalid IP in CIDR should throw IllegalArgumentException")
        void invalidIpInCidr() {
            List<FirewallRule> rules = List.of(
                new FirewallRule("ALLOW", "192.168.1.256/24")
            );
            assertThrows(IllegalArgumentException.class, () ->
                evaluator.evaluate(rules, "192.168.1.10")
            );
        }

        @Test
        @DisplayName("Non-numeric prefix should throw IllegalArgumentException")
        void nonNumericPrefix() {
            List<FirewallRule> rules = List.of(
                new FirewallRule("ALLOW", "192.168.1.0/abc")
            );
            assertThrows(IllegalArgumentException.class, () ->
                evaluator.evaluate(rules, "192.168.1.10")
            );
        }
    }

    @Nested
    @DisplayName("Validation Tests - Invalid Action")
    class InvalidActionValidation {

        @Test
        @DisplayName("Invalid action should throw IllegalArgumentException")
        void invalidAction() {
            // Now throws at construction time due to fail-fast validation
            assertThrows(IllegalArgumentException.class, () ->
                new FirewallRule("BLOCK", "192.168.1.0/24")
            );
        }

        @Test
        @DisplayName("Null action should throw IllegalArgumentException")
        void nullAction() {
            // Now throws at construction time due to fail-fast validation
            assertThrows(IllegalArgumentException.class, () ->
                new FirewallRule(null, "192.168.1.0/24")
            );
        }
    }

    @Nested
    @DisplayName("Case Sensitivity Tests")
    class CaseSensitivityTests {

        @Test
        @DisplayName("Lowercase action 'allow' should work and return 'ALLOW'")
        void lowercaseAllow() {
            List<FirewallRule> rules = List.of(
                new FirewallRule("allow", "192.168.1.0/24")
            );
            String result = evaluator.evaluate(rules, "192.168.1.10");
            assertEquals("ALLOW", result);
        }

        @Test
        @DisplayName("Lowercase action 'deny' should work and return 'DENY'")
        void lowercaseDeny() {
            List<FirewallRule> rules = List.of(
                new FirewallRule("deny", "192.168.1.0/24")
            );
            String result = evaluator.evaluate(rules, "192.168.1.10");
            assertEquals("DENY", result);
        }

        @Test
        @DisplayName("Mixed case action 'Allow' should work and return 'ALLOW'")
        void mixedCaseAllow() {
            List<FirewallRule> rules = List.of(
                new FirewallRule("Allow", "192.168.1.0/24")
            );
            String result = evaluator.evaluate(rules, "192.168.1.10");
            assertEquals("ALLOW", result);
        }

        @Test
        @DisplayName("Mixed case action 'Deny' should work and return 'DENY'")
        void mixedCaseDeny() {
            List<FirewallRule> rules = List.of(
                new FirewallRule("Deny", "192.168.1.0/24")
            );
            String result = evaluator.evaluate(rules, "192.168.1.10");
            assertEquals("DENY", result);
        }
    }

    @Nested
    @DisplayName("Leading Zeros Validation")
    class LeadingZerosValidation {

        @Test
        @DisplayName("Leading zeros in IP octet should throw IllegalArgumentException")
        void leadingZerosInIp() {
            List<FirewallRule> rules = List.of(
                new FirewallRule("ALLOW", "192.168.1.0/24")
            );
            assertThrows(IllegalArgumentException.class, () ->
                evaluator.evaluate(rules, "192.168.01.10")
            );
        }

        @Test
        @DisplayName("Leading zeros in CIDR IP should throw IllegalArgumentException")
        void leadingZerosInCidr() {
            List<FirewallRule> rules = List.of(
                new FirewallRule("ALLOW", "192.168.001.0/24")
            );
            assertThrows(IllegalArgumentException.class, () ->
                evaluator.evaluate(rules, "192.168.1.10")
            );
        }

        @Test
        @DisplayName("Single zero octet should be valid")
        void singleZeroOctet() {
            List<FirewallRule> rules = List.of(
                new FirewallRule("ALLOW", "0.0.0.0/0")
            );
            String result = evaluator.evaluate(rules, "192.168.1.10");
            assertEquals("ALLOW", result);
        }
    }

    @Nested
    @DisplayName("Whitespace Validation")
    class WhitespaceValidation {

        @Test
        @DisplayName("Leading whitespace in IP should throw IllegalArgumentException")
        void leadingWhitespaceInIp() {
            List<FirewallRule> rules = List.of(
                new FirewallRule("ALLOW", "192.168.1.0/24")
            );
            assertThrows(IllegalArgumentException.class, () ->
                evaluator.evaluate(rules, " 192.168.1.10")
            );
        }

        @Test
        @DisplayName("Trailing whitespace in IP should throw IllegalArgumentException")
        void trailingWhitespaceInIp() {
            List<FirewallRule> rules = List.of(
                new FirewallRule("ALLOW", "192.168.1.0/24")
            );
            assertThrows(IllegalArgumentException.class, () ->
                evaluator.evaluate(rules, "192.168.1.10 ")
            );
        }
    }

    @Nested
    @DisplayName("Null Rule Validation")
    class NullRuleValidation {

        @Test
        @DisplayName("Null rule in list should throw IllegalArgumentException")
        void nullRuleInList() {
            List<FirewallRule> rules = new java.util.ArrayList<>();
            rules.add(new FirewallRule("ALLOW", "192.168.1.0/24"));
            rules.add(null);
            rules.add(new FirewallRule("DENY", "10.0.0.0/8"));

            assertThrows(IllegalArgumentException.class, () ->
                evaluator.evaluate(rules, "10.0.0.5")
            );
        }
    }

    @Nested
    @DisplayName("Empty Octet Validation")
    class EmptyOctetValidation {

        @Test
        @DisplayName("Empty octet in IP should throw IllegalArgumentException")
        void emptyOctetInIp() {
            List<FirewallRule> rules = List.of(
                new FirewallRule("ALLOW", "192.168.1.0/24")
            );
            assertThrows(IllegalArgumentException.class, () ->
                evaluator.evaluate(rules, "192..1.10")
            );
        }
    }
}
