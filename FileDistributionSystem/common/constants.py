"""Constants for the distributed file system."""

# Chunk configuration
CHUNK_SIZE = 64 * 1024 * 1024  # 64 MB
REPLICATION_FACTOR = 3

# Timing configuration
HEARTBEAT_INTERVAL = 10  # seconds
ELECTION_TIMEOUT_MIN = 150  # ms
ELECTION_TIMEOUT_MAX = 300  # ms
SERVER_TIMEOUT = 30  # seconds before marking server offline

# Root inode
ROOT_INODE_ID = 1

# Grace period for garbage collection
GC_GRACE_PERIOD_HOURS = 24


class FileStatus:
    """File status enum."""
    UPLOADING = "UPLOADING"
    ACTIVE = "ACTIVE"
    DELETED = "DELETED"


class FileType:
    """File type enum."""
    FILE = "FILE"
    DIRECTORY = "DIRECTORY"


class NodeRole:
    """Raft node role enum."""
    LEADER = "LEADER"
    FOLLOWER = "FOLLOWER"
    CANDIDATE = "CANDIDATE"


class ServerStatus:
    """Chunk server status enum."""
    ONLINE = "ONLINE"
    OFFLINE = "OFFLINE"
    DRAINING = "DRAINING"


class UploadStatus:
    """Upload session status enum."""
    PENDING = "PENDING"
    IN_PROGRESS = "IN_PROGRESS"
    COMPLETED = "COMPLETED"
    ABORTED = "ABORTED"
