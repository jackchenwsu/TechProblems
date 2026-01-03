# Distributed File System Design

A comprehensive design for a distributed file system that works across multiple computers, handling standard file operations with strong consistency guarantees.

## Table of Contents

- [Requirements](#requirements)
- [High-Level Architecture](#high-level-architecture)
- [Data Model](#data-model)
- [Consistency Mechanism](#consistency-mechanism)
- [File Operations Flow](#file-operations-flow)
- [Large File Handling (Chunking)](#large-file-handling-chunking)
- [File Immutability & Versioning](#file-immutability--versioning)
- [Scalability Strategy](#scalability-strategy)
- [Data Partitioning & Indexing](#data-partitioning--indexing)
- [High Availability](#high-availability)
- [CAP Trade-off Analysis](#cap-trade-off-analysis)

---

## Requirements

### Core Functional Requirements

**File Operations**
- Create directory - Make new folders at any path
- List directory - Show contents of a folder (files + subdirectories)
- Put file - Upload files to the system
- Get file - Download files from the system
- Delete - Remove files and directories

**Hierarchy Support**
- Nested directories (folders within folders)
- Recursive operations (e.g., delete a folder with contents)

### Non-Functional Requirements

| Requirement        | Description                                                                                 |
|--------------------|---------------------------------------------------------------------------------------------|
| Strong Consistency | Read-after-write guarantee. List must immediately reflect changes. No eventual consistency. |
| Large File Support | Efficient handling of big files (chunking, streaming)                                       |
| High Availability  | System remains operational during partial failures                                          |

### Key Design Tensions

| Trade-off                   | Consideration                                                                      |
|-----------------------------|------------------------------------------------------------------------------------|
| Consistency vs Availability | Strong consistency requirement limits availability during partitions (CAP theorem) |
| Consistency vs Performance  | Synchronous replication adds latency                                               |
| Large files vs Consistency  | Chunked uploads complicate atomic visibility                                       |

---

## High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              CLIENTS                                         │
│                    (SDK / CLI / Web Interface)                               │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           API GATEWAY / LOAD BALANCER                        │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                 ┌──────────────────┴──────────────────┐
                 ▼                                     ▼
┌────────────────────────────────┐    ┌────────────────────────────────────────┐
│      METADATA SERVICE          │    │         STORAGE SERVICE                │
│      (Control Plane)           │    │         (Data Plane)                   │
│                                │    │                                        │
│  ┌──────────────────────────┐  │    │  ┌────────────────────────────────┐   │
│  │   Metadata Leader        │  │    │  │     Chunk Server 1             │   │
│  │   (Raft/Paxos)           │  │    │  │     ┌─────┬─────┬─────┐       │   │
│  └──────────────────────────┘  │    │  │     │ C1  │ C4  │ C7  │       │   │
│             │                  │    │  │     └─────┴─────┴─────┘       │   │
│             ▼                  │    │  └────────────────────────────────┘   │
│  ┌──────────────────────────┐  │    │  ┌────────────────────────────────┐   │
│  │   Metadata Followers     │  │    │  │     Chunk Server 2             │   │
│  │   (Replicas)             │  │    │  │     ┌─────┬─────┬─────┐       │   │
│  └──────────────────────────┘  │    │  │     │ C2  │ C5  │ C1' │       │   │
│             │                  │    │  │     └─────┴─────┴─────┘       │   │
│             ▼                  │    │  └────────────────────────────────┘   │
│  ┌──────────────────────────┐  │    │  ┌────────────────────────────────┐   │
│  │   Metadata Store         │  │    │  │     Chunk Server 3             │   │
│  │   (Distributed DB)       │  │    │  │     ┌─────┬─────┬─────┐       │   │
│  └──────────────────────────┘  │    │  │     │ C3  │ C6  │ C4' │       │   │
└────────────────────────────────┘    │  │     └─────┴─────┴─────┘       │   │
                                      │  └────────────────────────────────┘   │
                                      └────────────────────────────────────────┘
```

### Core Components

**1. Client**
- SDK/CLI for applications to interact with the file system
- Handles chunking of large files before upload
- Caches metadata locally (with invalidation)

**2. API Gateway**
- Single entry point for all requests
- Authentication & rate limiting
- Routes requests to appropriate services

**3. Metadata Service (Control Plane)**

The brain of the system - manages all file/directory information.

| Responsibility           | Details                           |
|--------------------------|-----------------------------------|
| Namespace management     | Directory tree structure          |
| File → Chunk mapping     | Which chunks belong to which file |
| Chunk → Server mapping   | Where each chunk is stored        |
| Consistency coordination | Ensures strong consistency        |
| Locking                  | Prevents conflicting writes       |

Uses Raft consensus for leader election and replicated state machine.

**4. Storage Service (Data Plane)**

Stores actual file data as chunks.

| Responsibility     | Details                           |
|--------------------|-----------------------------------|
| Chunk storage      | Store/retrieve file chunks        |
| Replication        | Maintain N copies of each chunk   |
| Health reporting   | Report status to Metadata Service |
| Garbage collection | Clean up orphaned chunks          |

---

## Data Model

### Metadata Schema

```
┌─────────────────────────────────────────────────────────────────┐
│                         INODE TABLE                             │
├─────────────────────────────────────────────────────────────────┤
│  inode_id     │ BIGINT PRIMARY KEY (globally unique)           │
│  parent_id    │ BIGINT (reference to parent directory)         │
│  name         │ VARCHAR (file/directory name)                  │
│  type         │ ENUM (FILE, DIRECTORY)                         │
│  size         │ BIGINT (bytes, 0 for directories)              │
│  created_at   │ TIMESTAMP                                      │
│  modified_at  │ TIMESTAMP                                      │
│  owner        │ VARCHAR                                        │
│  permissions  │ INT (Unix-style permissions)                   │
│  version      │ BIGINT (for optimistic locking)                │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                         CHUNK TABLE                             │
├─────────────────────────────────────────────────────────────────┤
│  chunk_id     │ UUID PRIMARY KEY                               │
│  inode_id     │ BIGINT (FK to INODE)                           │
│  chunk_index  │ INT (0, 1, 2... order in file)                 │
│  size         │ INT (bytes in this chunk)                      │
│  checksum     │ VARCHAR (SHA-256)                              │
│  servers      │ ARRAY<server_id> (where replicas live)         │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                      CHUNK SERVER TABLE                         │
├─────────────────────────────────────────────────────────────────┤
│  server_id    │ UUID PRIMARY KEY                               │
│  address      │ VARCHAR (host:port)                            │
│  capacity     │ BIGINT (total bytes)                           │
│  used         │ BIGINT (used bytes)                            │
│  status       │ ENUM (ONLINE, OFFLINE, DRAINING)               │
│  last_heartbeat│ TIMESTAMP                                     │
└─────────────────────────────────────────────────────────────────┘
```

### File Chunking Strategy

```
Large File (e.g., 256 MB)
┌──────────────────────────────────────────────────────────────────┐
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
                              │
                              ▼  Split into fixed-size chunks (64 MB default)
        ┌─────────────┬─────────────┬─────────────┬─────────────┐
        │  Chunk 0    │  Chunk 1    │  Chunk 2    │  Chunk 3    │
        │   64 MB     │   64 MB     │   64 MB     │   64 MB     │
        └─────────────┴─────────────┴─────────────┴─────────────┘
              │             │             │             │
              ▼             ▼             ▼             ▼
         Replicated    Replicated    Replicated    Replicated
         to 3 servers  to 3 servers  to 3 servers  to 3 servers
```

**Chunk size: 64 MB (configurable)**
- Large enough to reduce metadata overhead
- Small enough for parallel transfers and efficient replication

---

## Consistency Mechanism

Since strong consistency is the top priority, we use a two-phase approach:

### Metadata Consistency: Raft Consensus

```
┌─────────────────────────────────────────────────────────────────┐
│                    METADATA RAFT CLUSTER                        │
│                                                                 │
│    ┌─────────┐       ┌─────────┐       ┌─────────┐             │
│    │ Leader  │◄─────►│Follower │◄─────►│Follower │             │
│    │  Node   │       │  Node   │       │  Node   │             │
│    └─────────┘       └─────────┘       └─────────┘             │
│         │                                                       │
│         ▼                                                       │
│    All writes go through leader                                 │
│    Reads can go to leader (linearizable) or followers (stale)  │
└─────────────────────────────────────────────────────────────────┘
```

- All metadata writes go through Raft leader
- Write is acknowledged only after majority replication
- Guarantees: linearizable reads and writes

### File Write Consistency: Write-Ahead + Atomic Commit

```
Client                Metadata Service           Chunk Servers
  │                         │                         │
  │  1. Create file         │                         │
  │  ───────────────────►   │                         │
  │                         │                         │
  │  2. Return chunk IDs    │                         │
  │     + server locations  │                         │
  │  ◄───────────────────   │                         │
  │                         │                         │
  │  3. Upload chunks (parallel)                      │
  │  ─────────────────────────────────────────────►   │
  │                         │                         │
  │  4. All chunks ACK      │                         │
  │  ◄─────────────────────────────────────────────   │
  │                         │                         │
  │  5. COMMIT file         │                         │
  │  ───────────────────►   │                         │
  │                         │                         │
  │  6. Metadata updated    │                         │
  │     (visible in LIST)   │                         │
  │  ◄───────────────────   │                         │
```

**Key Principle:** File is NOT visible until COMMIT succeeds

### Handling Concurrent Writes (No Last-Writer-Wins)

**Approach: Pessimistic Locking with Lease**

```
┌─────────────────────────────────────────────────────────────────┐
│                      WRITE LOCK FLOW                            │
├─────────────────────────────────────────────────────────────────┤
│  1. Client A requests lock on /path/file.txt                    │
│  2. Metadata Service grants lock with lease (e.g., 60 seconds)  │
│  3. Client B tries to write → BLOCKED (gets error or waits)    │
│  4. Client A completes write → releases lock                   │
│  5. Client B can now acquire lock                               │
└─────────────────────────────────────────────────────────────────┘
```

**Alternative: Optimistic Locking with Version**
1. Read file with version V1
2. Modify locally
3. Write with "expected_version = V1"
4. If current version != V1 → CONFLICT error (client must retry)

---

## File Operations Flow

### CREATE DIRECTORY

```
Client                    Metadata Service
  │                             │
  │  mkdir("/a/b/newdir")       │
  │  ───────────────────────►   │
  │                             │  1. Validate parent "/a/b" exists
  │                             │  2. Check permissions
  │                             │  3. Check "newdir" doesn't exist
  │                             │  4. Acquire lock on parent
  │                             │  5. Create inode via Raft
  │                             │  6. Release lock
  │  Success                    │
  │  ◄───────────────────────   │
```

### LIST DIRECTORY

```
Client                    Metadata Service
  │                             │
  │  ls("/a/b")                 │
  │  ───────────────────────►   │
  │                             │  1. Read from Raft leader
  │                             │     (ensures latest data)
  │  [file1, file2, dir1]       │
  │  ◄───────────────────────   │
```

### PUT FILE (Upload)

```
Client                 Metadata Service              Chunk Servers
  │                          │                             │
  │ 1. PUT /a/file.txt       │                             │
  │    (size=150MB)          │                             │
  │ ──────────────────────►  │                             │
  │                          │ 2. Allocate 3 chunk IDs     │
  │                          │    Select servers           │
  │ 3. chunk_plan returned   │                             │
  │ ◄──────────────────────  │                             │
  │                          │                             │
  │ 4. Upload chunk 0 ───────────────────────────────────► │ Server A,B,C
  │ 5. Upload chunk 1 ───────────────────────────────────► │ Server D,E,F
  │ 6. Upload chunk 2 ───────────────────────────────────► │ Server G,H,I
  │                          │                             │
  │ 7. All ACKs received     │                             │
  │ ◄────────────────────────────────────────────────────  │
  │                          │                             │
  │ 8. COMMIT request        │                             │
  │ ──────────────────────►  │                             │
  │                          │ 9. Mark file VISIBLE        │
  │                          │    (Raft commit)            │
  │ 10. Success              │                             │
  │ ◄──────────────────────  │                             │
```

### GET FILE (Download)

```
Client                 Metadata Service              Chunk Servers
  │                          │                             │
  │ 1. GET /a/file.txt       │                             │
  │ ──────────────────────►  │                             │
  │                          │                             │
  │ 2. Return chunk list     │                             │
  │    + server locations    │                             │
  │ ◄──────────────────────  │                             │
  │                          │                             │
  │ 3. Download chunk 0 ─────────────────────────────────► │ (parallel)
  │ 4. Download chunk 1 ─────────────────────────────────► │
  │ 5. Download chunk 2 ─────────────────────────────────► │
  │                          │                             │
  │ 6. Reassemble file       │                             │
  │    (verify checksums)    │                             │
```

### DELETE

```
Client                 Metadata Service              Chunk Servers
  │                          │                             │
  │ 1. DELETE /a/file.txt    │                             │
  │ ──────────────────────►  │                             │
  │                          │ 2. Acquire lock             │
  │                          │ 3. Mark as DELETED (Raft)   │
  │                          │ 4. Release lock             │
  │ Success                  │                             │
  │ ◄──────────────────────  │                             │
  │                          │                             │
  │                          │ 5. Async: Add chunks to     │
  │                          │    garbage collection queue │
  │                          │                             │
  │                          │ 6. Background GC ──────────►│ Delete chunks
```

### Recursive Directory Deletion

Deleting a directory with millions of files is challenging. A naive approach could take hours and leave the system in an inconsistent state.

**Solution: Lazy Deletion with Tombstone**

```
┌─────────────────────────────────────────────────────────────────┐
│                    LAZY DELETION FLOW                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Phase 1: INSTANT (User-facing)                                 │
│  ─────────────────────────────────                              │
│  1. Mark directory with DELETED tombstone                       │
│  2. Remove from parent's children list                          │
│  3. Return SUCCESS to client                                    │
│                                                                 │
│  User sees: Immediate deletion ✓                                │
│                                                                 │
│  Phase 2: ASYNC (Background)                                    │
│  ─────────────────────────────                                  │
│  4. Queue directory for garbage collection                      │
│  5. GC worker traverses and deletes children                    │
│  6. Chunks added to chunk GC queue                              │
│  7. Chunk servers delete actual data                            │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Key Benefits:**
- User sees instant deletion
- System remains consistent (deleted paths return "not found")
- Failure recovery is built-in
- No long-running transactions or locks

---

## Large File Handling (Chunking)

### Why Chunking?

| Without chunking                                    | With chunking                                    |
|-----------------------------------------------------|--------------------------------------------------|
| Can't load 100GB file into memory                  | Stream chunks without full file in memory        |
| Single connection bottleneck                        | Parallel uploads across multiple connections     |
| Must restart entire upload if connection drops      | Resume from last successful chunk                |
| Can't spread across multiple servers               | Distribute chunks across cluster                 |
| Must copy entire file for each replica              | Replicate individual chunks independently        |

### Chunk Size Selection

| Size            | Pros                                    | Cons                                        |
|-----------------|-----------------------------------------|---------------------------------------------|
| Small (1-4 MB)  | Fine-grained parallelism, fast recovery | High metadata overhead                      |
| Medium (64 MB)  | Balanced trade-off                      | Good for most workloads                     |
| Large (256 MB+) | Low metadata overhead                   | Slow recovery, wasted space for small files |

**Our Choice: 64 MB (Configurable)**

Why 64 MB?
1. **Metadata efficiency** - 1 TB file = 16,384 chunks (manageable)
2. **Network efficiency** - Large enough to saturate network connection
3. **Parallel benefit** - 1 GB file = 16 chunks = 16 parallel uploads
4. **Recovery cost** - Failed chunk = retry 64 MB (acceptable)

Reference: GFS uses 64 MB, HDFS uses 128 MB

### Chunk Replication Strategy

```
┌─────────────────────────────────────────────────────────────────┐
│                  CHUNK PLACEMENT STRATEGY                       │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   Goals:                                                        │
│   1. Spread replicas across failure domains (racks/zones)       │
│   2. Balance storage utilization                                │
│   3. Minimize network distance for reads                        │
│                                                                 │
│   ┌─────────────────────────────────────────────────────────┐  │
│   │                     Rack 1                              │  │
│   │   ┌─────────┐  ┌─────────┐  ┌─────────┐               │  │
│   │   │Server A │  │Server B │  │Server C │               │  │
│   │   │ C0[P]   │  │ C1[S]   │  │ C2[S]   │               │  │
│   │   │ C3[S]   │  │         │  │ C0[S]   │               │  │
│   │   └─────────┘  └─────────┘  └─────────┘               │  │
│   └─────────────────────────────────────────────────────────┘  │
│                                                                 │
│   ┌─────────────────────────────────────────────────────────┐  │
│   │                     Rack 2                              │  │
│   │   ┌─────────┐  ┌─────────┐  ┌─────────┐               │  │
│   │   │Server D │  │Server E │  │Server F │               │  │
│   │   │ C1[P]   │  │ C0[S]   │  │ C3[S]   │               │  │
│   │   │ C2[S]   │  │ C3[P]   │  │ C1[S]   │               │  │
│   │   └─────────┘  └─────────┘  └─────────┘               │  │
│   └─────────────────────────────────────────────────────────┘  │
│                                                                 │
│   [P] = Primary replica    [S] = Secondary replica              │
│                                                                 │
│   Each chunk has 3 replicas across different racks              │
│   Survives: any 2 server failures OR entire rack failure        │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Resumable Uploads

```
Client state (persisted locally):
{
  upload_id: "xyz",
  file_path: "/data/video.mp4",
  completed_chunks: [0, 1],  # Already uploaded
  pending_chunks: [2, 3]     # Still need to upload
}

Resume flow:
1. Client: ResumeUpload(upload_id="xyz")
2. Server: Returns status of each chunk
3. Client: Uploads only chunks 2 and 3
4. Client: CommitUpload

Server also tracks upload state (with expiration):
- Incomplete uploads expire after 24 hours
- Orphaned chunks garbage collected
```

### Small Files Optimization

**Problem:** 1 KB file in 64 MB chunk = wasted space

**Solution 1: Inline small files in metadata**
- Files < 64 KB stored directly in inode record
- No chunks created
- Fast read (single metadata lookup)

**Solution 2: Block packing**
- Multiple small files packed into single chunk
- More complex but better space efficiency

---

## File Immutability & Versioning

### Why Immutability?

| Problem with Mutable                                       | How Immutability Solves It                                    |
|------------------------------------------------------------|---------------------------------------------------------------|
| Partial writes - Crash mid-update leaves corrupted file    | New version atomic - old version intact until commit          |
| Reader sees torn data - Reading while write in progress    | Readers see complete old version OR complete new version      |
| Replication complexity - Must sync partial changes         | Chunks never change - replicate once, done forever            |
| Caching invalidation - When does cache become stale?       | Chunk content keyed by ID - same ID = same content forever    |
| Backup/snapshot complexity - Point-in-time state           | Just record version pointer - chunks already immutable        |
| Concurrent write conflicts - Two writers modify same chunk | Each creates new version - conflict resolved at version level |

### Version Tracking

```
┌─────────────────────────────────────────────────────────────────┐
│                    VERSION TRACKING                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  INODE TABLE (file identity - stable)                           │
│  ┌───────────────────────────────────────────────────────────┐ │
│  │ inode_id │ name      │ current_version │ created_at       │ │
│  ├───────────────────────────────────────────────────────────┤ │
│  │ 12345    │ doc.pdf   │ 3               │ 2024-01-01       │ │
│  └───────────────────────────────────────────────────────────┘ │
│                             │                                   │
│                             ▼                                   │
│  VERSION TABLE (each version is a snapshot)                     │
│  ┌───────────────────────────────────────────────────────────┐ │
│  │ inode_id │ version │ size     │ created_at  │ status      │ │
│  ├───────────────────────────────────────────────────────────┤ │
│  │ 12345    │ 1       │ 10485760 │ 2024-01-01  │ ARCHIVED    │ │
│  │ 12345    │ 2       │ 10485800 │ 2024-01-05  │ ARCHIVED    │ │
│  │ 12345    │ 3       │ 10486000 │ 2024-01-10  │ CURRENT     │ │
│  └───────────────────────────────────────────────────────────┘ │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Chunk Deduplication

```
Version 1: 4 chunks
┌─────────┬─────────┬─────────┬─────────┐
│ uuid-A  │ uuid-B  │ uuid-C  │ uuid-D  │
│ "hello" │ "world" │ "foo"   │ "bar"   │
└─────────┴─────────┴─────────┴─────────┘

Version 2: Only chunk 1 changed
┌─────────┬─────────┬─────────┬─────────┐
│ uuid-A  │ uuid-E  │ uuid-C  │ uuid-D  │
│ "hello" │ "EARTH" │ "foo"   │ "bar"   │
└─────────┴─────────┴─────────┴─────────┘
    ▲         │         ▲         ▲
    │         │         │         │
  REUSED   NEW CHUNK  REUSED    REUSED

Storage used: 5 unique chunks (not 8)
```

### Concurrent Read During Update

**Problem:** What happens when someone reads while another writes?

**Solution: Version Pinning**

- Reader gets a consistent version at read start
- Even if new version commits during download, reader gets pinned version
- Chunks are immutable, so they're safe to download anytime
- Old chunks retained while referenced (reference counting)

### Conflict Handling

**Optimistic Locking (default):**
- No locks during edit
- Check version at commit time
- Retry on conflict
- Good for: low contention, short edits

**Pessimistic Locking (optional):**
- Acquire lock before editing
- Other writers blocked/rejected
- Lock has lease timeout (prevent deadlock)
- Good for: high contention, long edits

---

## Scalability Strategy

### Metadata Scalability: Namespace Partitioning (Sharding)

**Dynamic Subtree Partitioning (Recommended)**

```
Initial:
/users/** ──────────────────────► Shard 1

After /users/alice becomes hot:
/users/alice/**    ─────────────► Shard 1a (new)
/users/[^alice]/** ─────────────► Shard 1b

Routing Table:
┌─────────────────────────────────────────────────────────┐
│ prefix              │ shard  │ priority │               │
├─────────────────────────────────────────────────────────┤
│ /users/alice/       │ 1a     │ 1        │ (most specific)│
│ /users/             │ 1b     │ 2        │               │
│ /projects/          │ 2      │ 2        │               │
│ /data/              │ 3      │ 2        │               │
└─────────────────────────────────────────────────────────┘

Route by longest prefix match
```

**Benefits:**
- LIST operations stay within single shard
- Directory tree locality preserved
- Recursive operations efficient

### Storage Scalability

```
Need more storage? Add more chunk servers

┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐
│Server 1│ │Server 2│ │Server 3│ │Server 4│ │Server 5│  ...
│ 10 TB  │ │ 10 TB  │ │ 10 TB  │ │ 10 TB  │ │ 10 TB  │
└────────┘ └────────┘ └────────┘ └────────┘ └────────┘

- New servers register with Metadata Service
- Receive new chunk allocations automatically
- Background rebalancing redistributes existing data
```

### Large File Handling Techniques

| Technique             | Benefit                                           |
|-----------------------|---------------------------------------------------|
| Parallel chunk upload | Utilize multiple servers' bandwidth               |
| Streaming             | Don't load entire file in memory                  |
| Resumable uploads     | Client tracks uploaded chunks, resumes on failure |
| Compression           | Optional per-chunk compression                    |

---

## Data Partitioning & Indexing

### Primary Indexes

| Index         | Key Pattern                        | Use Case           |
|---------------|------------------------------------|--------------------|
| Inode         | `i:{inode_id}`                     | Get file metadata  |
| Children      | `c:{parent_id}:{child_name}`       | List directory     |
| Path (cache)  | `p:{full_path}`                    | Fast path lookup   |
| Chunks        | `k:{inode_id}:{version}:{idx}`     | Download file      |
| Locations     | `l:{chunk_id}`                     | Find chunk servers |
| Server chunks | `s:{server_id}:{chunk_id}`         | Rebalancing        |

### Chunk Data Partitioning: Consistent Hashing

```
Hash ring with virtual nodes:

                  0°
                  │
          S1-v1 ──┼── S3-v2
                 ╱│╲
        S2-v2 ──╱ │ ╲── S1-v2
              ╱   │   ╲
    270° ────●────┼────●──── 90°
              ╲   │   ╱
        S3-v1 ──╲ │ ╱── S2-v1
                 ╲│╱
          S1-v3 ──┼── S2-v3
                  │
                180°

Chunk placement:
position = hash(chunk_id) % 360
primary = next server clockwise
replicas = next 2 distinct servers clockwise
```

**Benefits:**
- Adding server: only 1/N chunks move
- Even distribution with virtual nodes
- Deterministic placement (no central lookup)

### Cross-Shard Operations

Moving files across shards uses **Two-Phase Commit**:

**Phase 1: PREPARE**
1. Coordinator → Shard 1: "Prepare to delete inode X"
2. Coordinator → Shard 2: "Prepare to create inode X'"
3. Both shards: Acquire locks, validate, log intent
4. Both respond: PREPARED

**Phase 2: COMMIT**
5. Coordinator → Both: COMMIT
6. Shard 1: Remove from source
7. Shard 2: Add to destination
8. Both: Release locks, acknowledge

Note: Actual chunk data doesn't move! Only metadata changes shards.

---

## High Availability

### Raft-Based Consensus

```
5-Node Metadata Cluster (tolerates 2 failures)

      ┌─────────┐
      │ Node 1  │◄─────── LEADER
      │ (Zone A)│         Handles all writes
      └────┬────┘         Can serve reads
           │
    ┌──────┴──────┐
    │   Raft      │
    │ Replication │
    └──────┬──────┘
           │
 ┌─────────┼─────────┬─────────┬─────────┐
 ▼         ▼         ▼         ▼         ▼
┌─────┐  ┌─────┐  ┌─────┐  ┌─────┐  ┌─────┐
│ N1  │  │ N2  │  │ N3  │  │ N4  │  │ N5  │
│LEADR│  │FOLWR│  │FOLWR│  │FOLWR│  │FOLWR│
│ZoneA│  │ZoneA│  │ZoneB│  │ZoneB│  │ZoneC│
└─────┘  └─────┘  └─────┘  └─────┘  └─────┘

✓ Automatic leader election
✓ No external coordination needed
✓ Strong consistency guaranteed
✓ Survives any 2 node failures
```

### Failure Scenarios

**Leader Failure:**
- Writes blocked for ~150-500ms during election
- Reads continue (from followers with ReadIndex)
- No data loss (committed data on majority)
- Automatic, no manual intervention

**Minority Partition:**
- Majority side continues operating
- Minority side unavailable (cannot elect leader)
- When partition heals, minority rejoins and catches up

**Zone Failure:**
- 5 nodes across 3 zones can survive any single zone failure
- Can also survive any 2 individual node failures

### Split-Brain Prevention

Raft's solution using **MAJORITY REQUIREMENT**:
1. Leader must get majority vote to become leader
2. Writes must replicate to majority before commit
3. Only one node can have majority at any time (mathematical impossibility to have two)
4. Leader lease: old leader stops serving when it can't reach majority

**Result:** Split-brain is IMPOSSIBLE with Raft

### Embedded Raft vs External Coordinator

| Use External (ZooKeeper/etcd) when:            | Use Embedded Raft when:                        |
|------------------------------------------------|------------------------------------------------|
| Multiple services need coordination            | Minimizing operational complexity              |
| Already running ZK/etcd for other systems      | Single system needs consensus                  |
| Need complex coordination primitives           | Using a database with built-in Raft            |
| Team lacks expertise to implement Raft         | Want tight integration between consensus/storage |

---

## CAP Trade-off Analysis

### Our Choice: CP (Consistency + Partition Tolerance)

**We Sacrificed: Availability**

```
┌─────────────┐              ┌─────────────┐
│             │              │             │
│  Consistency│  ✓ CHOSEN    │ Partition   │  ✓ CHOSEN
│             │              │ Tolerance   │
└─────────────┘              └─────────────┘

              ┌─────────────┐
              │             │
              │ Availability│  ✗ SACRIFICED
              │             │
              └─────────────┘
```

### Why CP?

| Requirement                          | Why CP is necessary              |
|--------------------------------------|----------------------------------|
| "LIST must show changes immediately" | Cannot serve stale reads         |
| "No last-writer-wins"                | Cannot accept conflicting writes |
| File system semantics                | Users expect POSIX-like behavior |

### What "Sacrificing Availability" Means

During network partition:
- **Majority partition:** Can read and write (majority available)
- **Minority partition:** UNAVAILABLE (cannot reach majority)

### Real-World Impact

| Situation           | Impact                          | Mitigation               |
|---------------------|---------------------------------|--------------------------|
| Network partition   | Minority side unavailable       | Deploy across 3+ zones   |
| Leader election     | Brief unavailability (~seconds) | Fast election timeout    |
| Leader failure      | Writes blocked until new leader | Automatic failover       |
| Majority nodes down | Complete outage                 | Proper capacity planning |

### Maximizing Availability Within CP

1. **Deploy across 3+ availability zones** - Single zone failure doesn't lose majority
2. **Fast leader election** - Raft election timeout: 150-300ms
3. **Leader lease for reads** - Reads don't need round-trip during valid lease
4. **Follower reads with ReadIndex** - Distribute read load
5. **Client retry with backoff** - Transient failures handled gracefully
6. **Health-aware load balancing** - Route away from unhealthy nodes quickly

In practice: 99.9%+ availability achievable with CP (partitions are rare in well-designed networks)

---

## Database Selection

### Recommendation: etcd + Embedded RocksDB

**For Coordination (etcd):**
- Built on Raft - Strong consistency out of the box
- Battle-tested - Powers Kubernetes at massive scale
- Watch support - Real-time notifications for changes
- Lease mechanism - Natural fit for distributed locks

**For Metadata Storage (RocksDB):**
- LSM-tree - Excellent write throughput
- Embedded - No network hop, low latency
- Prefix iteration - Efficient directory listing
- Snapshots - Consistent reads during compaction
- Proven - Used by CockroachDB, TiKV, many others

### Scale Recommendations

| Scale           | Recommendation                                 |
|-----------------|------------------------------------------------|
| < 100M files    | etcd + RocksDB (simpler ops, proven)           |
| 100M - 1B files | TiKV (auto-sharding, distributed transactions) |
| > 1B files      | FoundationDB or custom sharded solution        |

---

## Summary: Key Design Decisions

| Aspect               | Decision                                        | Rationale                                          |
|----------------------|-------------------------------------------------|----------------------------------------------------|
| Separation           | Control plane (metadata) vs Data plane (chunks) | Independent scaling, different consistency needs   |
| Metadata consistency | Raft consensus                                  | Strong consistency guarantee                       |
| Chunk size           | 64 MB                                           | Balance between overhead and parallelism           |
| Replication          | 3 replicas per chunk                            | Survive 2 failures                                 |
| Write visibility     | Atomic commit after all chunks uploaded         | Ensures LIST always shows complete files           |
| Concurrent writes    | Pessimistic locking with lease                  | Prevents last-writer-wins                          |
| CAP choice           | CP                                              | Strong consistency > availability during partition |
| HA Strategy          | 5-node Raft cluster across 3 zones              | Automatic failover, no external coordinator        |
| Partitioning         | Dynamic subtree (by path prefix)                | Locality preserved, efficient directory ops        |
| Storage engine       | RocksDB with prefix-based keys                  | Fast prefix scans for directory listing            |

---

## Technology Choices

| Component          | Options                        |
|--------------------|--------------------------------|
| Metadata consensus | etcd, Raft (custom), ZooKeeper |
| Metadata storage   | RocksDB, LevelDB, PostgreSQL   |
| Chunk storage      | Local filesystem, object store |
| RPC framework      | gRPC, Thrift                   |
| Client             | Go, Python, Java SDK           |
