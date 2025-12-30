# FireWallCIDRRules

A Java implementation of a firewall rule evaluator that checks IP addresses against CIDR-based firewall rules.

## Problem Statement

You are given a list of firewall rules. Each rule has two parts:

- An Action: either "ALLOW" or "DENY".
- A CIDR block: An IP range (like "192.168.1.0/24").
- You are also given a specific Target IP.

Your job is to check the rules and decide if this IP is allowed or denied.

## Rules for Matching

- Check the rules from top to bottom.
- The first rule that covers the IP address decides the result.
- If a rule matches, stop checking. Use that rule's action.
- If you go through all rules and none match, the default answer is DENY.
- Note: Older rules (higher up in the list) are more important than newer rules.

## How CIDR Works

CIDR is a way to write a group of IP addresses. It looks like this: IP_address/Prefix.

Example: 192.168.1.0/24

The /24 tells us the size of the network.
This specific example covers IPs from 192.168.1.0 to 192.168.1.255 (256 addresses).

### Common Range Sizes

| CIDR        | Description          | Number of IPs |
|-------------|----------------------|---------------|
| x.x.x.x/32  | A single specific IP | 1             |
| x.x.x.x/31  | 2 IPs                | 2             |
| x.x.x.x/30  | 4 IPs                | 4             |
| x.x.x.x/29  | 8 IPs                | 8             |
| x.x.x.x/24  | Class C Network      | 256           |

### Calculating a Range

Let's look at 255.0.0.8/29:

- /29 means we have 3 bits left for the host (32 - 29 = 3).
- 2^3 = 8, so this block holds 8 addresses.
- Starting at 255.0.0.8, the range goes up to 255.0.0.15.

### Example 1: Simple Match

**Input:**

```json
rules = [
    {"action": "DENY", "cidr": "255.0.0.8/29"},
    {"action": "ALLOW", "cidr": "117.145.102.64/30"}
]

ip = "255.0.0.10"
```

**Explanation:**

- Look at the first rule: "DENY", "255.0.0.8/29".
- This covers 255.0.0.8 through 255.0.0.15.
- Our IP is 255.0.0.10. It fits in this range.
- Match found. Stop and return "DENY".

**Output:** `"DENY"`

### Example 2: Multiple Rules

**Input:**

```json
rules = [
    {"action": "DENY", "cidr": "255.0.0.8/29"},
    {"action": "ALLOW", "cidr": "117.145.102.64/30"},
    {"action": "ALLOW", "cidr": "192.168.0.0/16"}
]

ip = "192.168.1.100"
```

**Explanation:**

- Rule 1: Does the IP fit in 255.0.0.8/29? No.
- Rule 2: Does the IP fit in 117.145.102.64/30? No.
- Rule 3: Does the IP fit in 192.168.0.0/16? Yes.
- Match found. Return "ALLOW".

**Output:** `"ALLOW"`

### Example 3: No Match (Default)

**Input:**

```json
rules = [
    {"action": "ALLOW", "cidr": "10.0.0.0/8"}
]

ip = "192.168.1.1"
```

**Explanation:**

- Rule 1: Does the IP fit? No.
- No more rules to check.
- Default Action: Return "DENY".

**Output:** `"DENY"`

---

## Overview

The `FirewallEvaluator` class evaluates whether a given IP address should be allowed or denied based on a list of firewall rules. Rules are processed in order, and the first matching rule wins. If no rule matches, the default action is DENY.

## FirewallEvaluator Methods

### Public Methods

#### `evaluate(List<FirewallRule> rules, String ip)`

The main entry point for firewall rule evaluation.

**Parameters:**
- `rules` - List of firewall rules to evaluate (processed in order)
- `ip` - Target IP address to check

**Returns:** `"ALLOW"` or `"DENY"`

**Behavior:**
1. Validates the input IP address
2. Iterates through rules in order
3. For each rule, validates the action and CIDR
4. Returns the action of the first matching rule
5. If no rule matches, returns `"DENY"` (default deny)

**Throws:** `IllegalArgumentException` if inputs are invalid (null rules list, null rule, invalid IP, invalid CIDR, or invalid action)

---

### Private Methods

#### `isIpInCidr(String ip, String cidr)`

Checks if an IP address falls within a CIDR block using bitwise operations.

**Algorithm:**
1. Parses the CIDR into network IP and prefix length (e.g., `192.168.1.0/24`)
2. Converts both IPs to 32-bit numbers
3. Creates a subnet mask from the prefix length
4. Compares network portions: `(IP & mask) == (network & mask)`

**Example:**
- IP: `192.168.1.50`, CIDR: `192.168.1.0/24`
- Mask for /24: `0xFFFFFF00`
- `192.168.1.50 & mask` = `192.168.1.0`
- `192.168.1.0 & mask` = `192.168.1.0`
- Result: **Match**

---

#### `createSubnetMask(int prefixLength)`

Creates a 32-bit subnet mask from a CIDR prefix length.

**Examples:**

| Prefix | Mask (hex)   | Mask (decimal)  | Addresses  |
|--------|--------------|-----------------|------------|
| /32    | `0xFFFFFFFF` | 255.255.255.255 | 1          |
| /24    | `0xFFFFFF00` | 255.255.255.0   | 256        |
| /16    | `0xFFFF0000` | 255.255.0.0     | 65,536     |
| /8     | `0xFF000000` | 255.0.0.0       | 16,777,216 |
| /0     | `0x00000000` | 0.0.0.0         | All IPs    |

**Implementation:** Uses bit shifting: `(0xFFFFFFFF << (32 - prefixLength)) & 0xFFFFFFFF`

Special case: `/0` returns `0L` to match all IP addresses.

---

#### `parseIpToLong(String ip)`

Converts an IPv4 address string (like `"192.168.1.10"`) into a 32-bit integer stored as a `long`.

**Why Convert to a Number?**

IP addresses are actually 32-bit numbers. The dotted-decimal format (`192.168.1.10`) is just human-readable notation. Converting to a number enables:
- Fast bitwise comparisons for CIDR matching
- Efficient subnet mask operations

**How It Works**

An IPv4 address has 4 octets (bytes), each 8 bits:

```
   192    .    168    .     1     .    10
   ───         ───         ───         ───
 8 bits     8 bits      8 bits      8 bits
  └────────────────────────────────────────┘
              32 bits total
```

Each octet is shifted to its correct position in the 32-bit number:

| Octet | Value | Shift    | Binary Position       |
|-------|-------|----------|-----------------------|
| 1st   | 192   | `<< 24`  | bits 31-24 (leftmost) |
| 2nd   | 168   | `<< 16`  | bits 23-16            |
| 3rd   | 1     | `<< 8`   | bits 15-8             |
| 4th   | 10    | none     | bits 7-0 (rightmost)  |

**Step-by-Step Example:** `192.168.1.10`

```
Step 1: Parse octets
   "192.168.1.10".split(".")  →  ["192", "168", "1", "10"]

Step 2: Shift each octet to its position

   192 << 24  =  11000000 00000000 00000000 00000000  = 3221225472
   168 << 16  =  00000000 10101000 00000000 00000000  =   11010048
     1 << 8   =  00000000 00000000 00000001 00000000  =        256
    10 << 0   =  00000000 00000000 00000000 00001010  =         10

Step 3: Combine with bitwise OR (|)

   11000000 10101000 00000001 00001010  =  3232235786
   └──192──┘└──168──┘└───1───┘└──10───┘
```

**Why Use `long` Instead of `int`?**

Java's `int` is signed (range: -2^31 to 2^31-1). IPs like `255.255.255.255` would be interpreted as `-1` with a signed int. Using `long` avoids sign issues and keeps the value positive.

**The Code:**

```java
private long parseIpToLong(String ip) {
    String[] octets = ip.split("\\.");
    return ((long) Integer.parseInt(octets[0]) << 24)
         | ((long) Integer.parseInt(octets[1]) << 16)
         | ((long) Integer.parseInt(octets[2]) << 8)
         | (long) Integer.parseInt(octets[3]);
}
```

---

#### `validateIp(String ip)`

Validates an IPv4 address format.

**Validation rules:**
- Cannot be null or empty
- Cannot have leading/trailing whitespace
- Must have exactly 4 octets separated by `.`
- Each octet must pass `validateOctet()` checks

---

#### `validateOctet(String octet)`

Validates a single octet value.

**Validation rules:**
- Cannot be empty
- Cannot have leading zeros (e.g., `010` is rejected to avoid octal interpretation)
- Must be numeric
- Must be in range 0-255

---

#### `validateCidr(String cidr)`

Validates a CIDR block format.

**Validation rules:**
- Cannot be null or empty
- Must contain `/` separator
- IP portion must pass `validateIp()` checks
- Prefix must be numeric
- Prefix must be in range 0-32

**Valid examples:** `192.168.1.0/24`, `10.0.0.0/8`, `0.0.0.0/0`

**Invalid examples:** `192.168.1.0` (no prefix), `192.168.1.0/33` (prefix > 32)

---

#### `validateAction(String action)`

Validates an action value.

**Validation rules:**
- Cannot be null
- Must be `"ALLOW"` or `"DENY"` (case-insensitive)

---

## Usage Example

```java
List<FirewallRule> rules = Arrays.asList(
    new FirewallRule("ALLOW", "192.168.1.0/24"),  // Allow internal network
    new FirewallRule("DENY", "0.0.0.0/0")          // Deny everything else
);

FirewallEvaluator evaluator = new FirewallEvaluator();
String result = evaluator.evaluate(rules, "192.168.1.50");  // Returns "ALLOW"
String result2 = evaluator.evaluate(rules, "8.8.8.8");      // Returns "DENY"
```

## Key Concepts

### CIDR Notation

CIDR (Classless Inter-Domain Routing) notation combines an IP address with a prefix length to define a range of IP addresses:
- `192.168.1.0/24` covers `192.168.1.0` to `192.168.1.255`
- `10.0.0.0/8` covers `10.0.0.0` to `10.255.255.255`
- `0.0.0.0/0` covers all IPv4 addresses

### First Match Wins

Rules are evaluated in order. The first rule whose CIDR contains the target IP determines the result. This allows for specific rules to override general ones:

```java
// Allow specific IP, deny the rest of the subnet
new FirewallRule("ALLOW", "192.168.1.100/32"),  // Exact match for .100
new FirewallRule("DENY", "192.168.1.0/24"),      // Deny rest of subnet
```