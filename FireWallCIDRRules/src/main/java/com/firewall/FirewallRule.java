package com.firewall;

/**
 * Represents a single firewall rule with an action and CIDR block.
 *
 * <p>Example usage:
 * <pre>
 *     FirewallRule rule = new FirewallRule("DENY", "192.168.1.0/24");
 * </pre>
 */
public class FirewallRule {

    /** The action to take: "ALLOW" or "DENY" (case-insensitive) */
    private final String action;

    /** The CIDR block defining the IP range (e.g., "192.168.1.0/24") */
    private final String cidr;

    /**
     * Creates a new firewall rule.
     *
     * @param action The action to take when this rule matches ("ALLOW" or "DENY")
     * @param cidr   The CIDR block defining the IP range (e.g., "10.0.0.0/8")
     * @throws IllegalArgumentException if action or cidr is null/empty, or action is invalid
     */
    public FirewallRule(String action, String cidr) {
        if (action == null || action.trim().isEmpty()) {
            throw new IllegalArgumentException("Action cannot be null or empty");
        }
        String normalizedAction = action.trim().toUpperCase();
        if (!normalizedAction.equals("ALLOW") && !normalizedAction.equals("DENY")) {
            throw new IllegalArgumentException("Action must be ALLOW or DENY: " + action);
        }
        if (cidr == null || cidr.trim().isEmpty()) {
            throw new IllegalArgumentException("CIDR cannot be null or empty");
        }
        if (!cidr.contains("/")) {
            throw new IllegalArgumentException("CIDR must contain '/' separator: " + cidr);
        }

        this.action = action;
        this.cidr = cidr;
    }

    /**
     * Returns the action for this rule.
     *
     * @return "ALLOW" or "DENY" (case may vary based on input)
     */
    public String getAction() {
        return action;
    }

    /**
     * Returns the CIDR block for this rule.
     *
     * @return The CIDR notation string (e.g., "192.168.1.0/24")
     */
    public String getCidr() {
        return cidr;
    }
}
