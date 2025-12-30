package com.firewall;

import java.util.List;

public class FirewallEvaluator {

    private static final String ALLOW = "ALLOW";
    private static final String DENY = "DENY";
    private static final long IPV4_MASK = 0xFFFFFFFFL;

    /**
     * Evaluates firewall rules against a target IP address.
     * Rules are checked in order; first matching rule wins.
     * If no rule matches, default action is DENY.
     *
     * @param rules List of firewall rules to evaluate
     * @param ip    Target IP address to check
     * @return "ALLOW" or "DENY"
     * @throws IllegalArgumentException if inputs are invalid
     */
    public String evaluate(List<FirewallRule> rules, String ip) {
        validateIp(ip);

        if (rules == null) {
            throw new IllegalArgumentException("Rules list cannot be null");
        }

        for (int i = 0; i < rules.size(); i++) {
            FirewallRule rule = rules.get(i);
            if (rule == null) {
                throw new IllegalArgumentException("Rule at index " + i + " cannot be null");
            }
            validateAction(rule.getAction());
            validateCidr(rule.getCidr());

            if (isIpInCidr(ip, rule.getCidr())) {
                return rule.getAction().toUpperCase();
            }
        }

        return DENY;
    }

    /**
     * Checks if an IP address falls within a CIDR block.
     * Uses bitwise AND with subnet mask to compare network portions.
     */
    private boolean isIpInCidr(String ip, String cidr) {
        String[] parts = cidr.split("/");
        String networkIp = parts[0];
        int prefixLength = Integer.parseInt(parts[1]);

        long ipLong = parseIpToLong(ip);
        long networkLong = parseIpToLong(networkIp);
        long mask = createSubnetMask(prefixLength);

        // Compare network portions: (IP & mask) == (network & mask)
        return (ipLong & mask) == (networkLong & mask);
    }

    /**
     * Creates a subnet mask from prefix length.
     *
     * Examples:
     *   /32 → 0xFFFFFFFF (exact match)
     *   /24 → 0xFFFFFF00 (256 IPs)
     *   /0  → 0x00000000 (all IPs)
     */
    private long createSubnetMask(int prefixLength) {
        if (prefixLength == 0) {
            // Special case: /0 matches all IPs
            return 0L;
        }
        // Shift 32 ones left, then mask to 32 bits
        return (IPV4_MASK << (32 - prefixLength)) & IPV4_MASK;
    }

    /**
     * Converts an IPv4 address string to a 32-bit number (as long).
     *
     * Example: "192.168.1.10"
     *   = (192 << 24) | (168 << 16) | (1 << 8) | 10
     *   = 3232235786
     */
    private long parseIpToLong(String ip) {
        String[] octets = ip.split("\\.");
        return ((long) Integer.parseInt(octets[0]) << 24)
             | ((long) Integer.parseInt(octets[1]) << 16)
             | ((long) Integer.parseInt(octets[2]) << 8)
             | (long) Integer.parseInt(octets[3]);
    }

    /**
     * Validates an IPv4 address format.
     * Must be 4 octets, each 0-255, no leading/trailing whitespace.
     */
    private void validateIp(String ip) {
        if (ip == null || ip.isEmpty()) {
            throw new IllegalArgumentException("IP address cannot be null or empty");
        }

        // Check for leading/trailing whitespace
        if (!ip.equals(ip.trim())) {
            throw new IllegalArgumentException(
                "IP address cannot have leading/trailing whitespace: '" + ip + "'"
            );
        }

        String[] octets = ip.split("\\.");
        if (octets.length != 4) {
            throw new IllegalArgumentException(
                "IP address must have exactly 4 octets: " + ip
            );
        }

        for (String octet : octets) {
            validateOctet(octet);
        }
    }

    /**
     * Validates a single octet value.
     * Must be numeric, in range 0-255, and no leading zeros (except "0" itself).
     */
    private void validateOctet(String octet) {
        if (octet.isEmpty()) {
            throw new IllegalArgumentException("IP octet cannot be empty");
        }

        // Reject leading zeros (e.g., "010" could be interpreted as octal)
        if (octet.length() > 1 && octet.charAt(0) == '0') {
            throw new IllegalArgumentException(
                "IP octet cannot have leading zeros: " + octet
            );
        }

        int value;
        try {
            value = Integer.parseInt(octet);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "IP octet must be numeric: " + octet
            );
        }

        if (value < 0 || value > 255) {
            throw new IllegalArgumentException(
                "IP octet must be 0-255: " + octet
            );
        }
    }

    /**
     * Validates a CIDR block format.
     * Must be valid IP + "/" + prefix (0-32).
     */
    private void validateCidr(String cidr) {
        if (cidr == null || cidr.isEmpty()) {
            throw new IllegalArgumentException("CIDR cannot be null or empty");
        }

        // Find the '/' separator that divides IP address from prefix length
        // Valid CIDR format: "192.168.1.0/24" where '/' separates network IP from prefix
        int slashIndex = cidr.indexOf('/');
        if (slashIndex == -1) {
            // Missing separator means invalid CIDR notation
            // e.g., "192.168.1.0" without "/24" is not valid CIDR
            throw new IllegalArgumentException(
                "CIDR must contain '/' separator: " + cidr
            );
        }

        String ipPart = cidr.substring(0, slashIndex);
        String prefixPart = cidr.substring(slashIndex + 1);

        // Validate IP portion
        validateIp(ipPart);

        // Validate prefix length
        int prefix;
        try {
            prefix = Integer.parseInt(prefixPart);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "CIDR prefix must be numeric: " + prefixPart
            );
        }

        if (prefix < 0 || prefix > 32) {
            throw new IllegalArgumentException(
                "CIDR prefix must be 0-32: " + prefix
            );
        }
    }

    /**
     * Validates an action value.
     * Must be "ALLOW" or "DENY" (case-insensitive).
     */
    private void validateAction(String action) {
        if (action == null) {
            throw new IllegalArgumentException("Action cannot be null");
        }

        String normalized = action.toUpperCase();
        if (!ALLOW.equals(normalized) && !DENY.equals(normalized)) {
            throw new IllegalArgumentException(
                "Action must be ALLOW or DENY: " + action
            );
        }
    }
}
