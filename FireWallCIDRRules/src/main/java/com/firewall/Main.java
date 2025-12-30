package com.firewall;

import java.util.ArrayList;
import java.util.List;

public class Main {

    public static void main(String[] args) {
        if (args.length == 0) {
            runDemo();
            System.out.println("\n--- Usage ---");
            printUsage();
            return;
        }

        if (args.length < 3) {
            System.err.println("Error: Insufficient arguments (need IP + at least one rule)");
            printUsage();
            System.exit(1);
        }

        // Check for complete action/cidr pairs (args after IP must be even count)
        int ruleArgs = args.length - 1;
        if (ruleArgs % 2 != 0) {
            System.err.println("Error: Incomplete rule. Each rule needs both ACTION and CIDR.");
            System.err.println("       Got " + ruleArgs + " arguments after IP (expected even number).");
            printUsage();
            System.exit(1);
        }

        String ip = args[0];
        List<FirewallRule> rules = parseRulesFromArgs(args);

        FirewallEvaluator evaluator = new FirewallEvaluator();
        try {
            String result = evaluator.evaluate(rules, ip);
            System.out.println(result);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void runDemo() {
        System.out.println("=== Firewall CIDR Rules Demo ===\n");

        FirewallEvaluator evaluator = new FirewallEvaluator();

        // User's example
        List<FirewallRule> rules = List.of(
            new FirewallRule("DENY", "255.0.0.8/29"),
            new FirewallRule("ALLOW", "117.145.102.64/30")
        );
        String ip = "255.0.0.10";

        System.out.println("Rules:");
        for (int i = 0; i < rules.size(); i++) {
            FirewallRule r = rules.get(i);
            System.out.printf("  %d. %s %s%n", i + 1, r.getAction(), r.getCidr());
        }
        System.out.println("\nTarget IP: " + ip);
        System.out.println("Result: " + evaluator.evaluate(rules, ip));

        // Additional examples
        System.out.println("\n--- Additional Examples ---\n");

        // Example 2: First match wins
        rules = List.of(
            new FirewallRule("ALLOW", "192.168.1.0/24"),
            new FirewallRule("DENY", "192.168.0.0/16")
        );
        ip = "192.168.1.50";
        System.out.println("First-match-wins:");
        System.out.println("  Rules: ALLOW 192.168.1.0/24, DENY 192.168.0.0/16");
        System.out.println("  IP: " + ip);
        System.out.println("  Result: " + evaluator.evaluate(rules, ip) + " (first rule matches)\n");

        // Example 3: No match defaults to DENY
        rules = List.of(
            new FirewallRule("ALLOW", "10.0.0.0/8")
        );
        ip = "192.168.1.1";
        System.out.println("No match (default DENY):");
        System.out.println("  Rules: ALLOW 10.0.0.0/8");
        System.out.println("  IP: " + ip);
        System.out.println("  Result: " + evaluator.evaluate(rules, ip) + " (no rule matches)\n");

        // Example 4: /0 matches everything
        rules = List.of(
            new FirewallRule("ALLOW", "0.0.0.0/0")
        );
        ip = "8.8.8.8";
        System.out.println("Match all (/0):");
        System.out.println("  Rules: ALLOW 0.0.0.0/0");
        System.out.println("  IP: " + ip);
        System.out.println("  Result: " + evaluator.evaluate(rules, ip) + " (/0 matches any IP)");
    }

    private static List<FirewallRule> parseRulesFromArgs(String[] args) {
        List<FirewallRule> rules = new ArrayList<>();

        // Format: <ip> <action1> <cidr1> [<action2> <cidr2> ...]
        for (int i = 1; i < args.length; i += 2) {
            String action = args[i];
            String cidr = args[i + 1];
            rules.add(new FirewallRule(action, cidr));
        }

        return rules;
    }

    private static void printUsage() {
        System.out.println("Usage: java -jar firewall-cidr-rules.jar <ip> <action1> <cidr1> [<action2> <cidr2> ...]");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java -jar firewall-cidr-rules.jar 192.168.1.10 ALLOW 192.168.1.0/24");
        System.out.println("  java -jar firewall-cidr-rules.jar 255.0.0.10 DENY 255.0.0.8/29 ALLOW 117.145.102.64/30");
        System.out.println();
        System.out.println("Run without arguments to see demo.");
    }
}
