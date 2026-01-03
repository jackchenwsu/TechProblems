"""Data models for the distributed file system."""

from dataclasses import dataclass, field
from datetime import datetime
from typing import List, Optional
import uuid


@dataclass
class Inode:
    """Represents a file or directory in the file system."""
    inode_id: int
    parent_id: int
    name: str
    type: str  # "FILE" or "DIRECTORY"
    size: int
    status: str
    version: int
    created_at: datetime
    modified_at: datetime
    owner: str
    permissions: int = 0o755

    def is_file(self) -> bool:
        return self.type == "FILE"

    def is_directory(self) -> bool:
        return self.type == "DIRECTORY"


@dataclass
class Chunk:
    """Represents a chunk of a file."""
    chunk_id: str  # UUID
    inode_id: int
    version: int
    chunk_index: int
    size: int
    checksum: str
    servers: List[str]  # server_ids holding this chunk


@dataclass
class ChunkServer:
    """Represents a chunk storage server."""
    server_id: str
    address: str
    capacity: int  # Total bytes
    used: int  # Used bytes
    status: str  # ONLINE, OFFLINE, DRAINING
    last_heartbeat: datetime
    zone: str = "default"

    @property
    def available(self) -> int:
        return self.capacity - self.used


@dataclass
class ChunkAllocation:
    """Allocation plan for a single chunk during upload."""
    chunk_index: int
    chunk_id: str
    servers: List[str]


@dataclass
class UploadSession:
    """Represents an in-progress file upload."""
    upload_id: str
    inode_id: int
    version: int
    chunks: List[ChunkAllocation]
    status: str
    created_at: datetime
    expires_at: datetime


@dataclass
class ChunkInfo:
    """Local chunk information on a chunk server."""
    chunk_id: str
    size: int
    checksum: str
    created_at: datetime


@dataclass
class ChunkGCEntry:
    """Entry in the chunk garbage collection queue."""
    chunk_id: str
    servers: List[str]
    delete_after: datetime


@dataclass
class FileInfo:
    """File information returned to clients."""
    name: str
    type: str
    size: int
    created: Optional[datetime] = None
    modified: Optional[datetime] = None
    owner: Optional[str] = None
    version: Optional[int] = None


@dataclass
class UploadState:
    """State for resumable uploads."""
    upload_id: str
    remote_path: str
    completed_chunks: List[int] = field(default_factory=list)
    checksums: List[Optional[str]] = field(default_factory=list)


@dataclass
class LogEntry:
    """Raft log entry."""
    term: int
    command: 'Command'
    index: int = 0


@dataclass
class Command:
    """Command to be replicated via Raft."""
    type: str
    inode: Optional[Inode] = None
    inode_id: Optional[int] = None
    parent_id: Optional[int] = None
    name: Optional[str] = None
    child_id: Optional[int] = None
    chunk: Optional[Chunk] = None


@dataclass
class VoteRequest:
    """Raft RequestVote RPC."""
    term: int
    candidate_id: str
    last_log_index: int
    last_log_term: int


@dataclass
class VoteResponse:
    """Raft RequestVote RPC response."""
    term: int
    vote_granted: bool


@dataclass
class AppendEntriesRequest:
    """Raft AppendEntries RPC."""
    term: int
    leader_id: str
    prev_log_index: int
    prev_log_term: int
    entries: List[LogEntry]
    leader_commit: int


@dataclass
class AppendEntriesResponse:
    """Raft AppendEntries RPC response."""
    term: int
    success: bool


@dataclass
class UploadChunkRequest:
    """Request to upload a chunk."""
    chunk_id: str
    data: bytes
    checksum: str
    replica_servers: List[str]


@dataclass
class UploadChunkResponse:
    """Response from chunk upload."""
    success: bool
    error: Optional[str] = None


@dataclass
class DownloadChunkRequest:
    """Request to download a chunk."""
    chunk_id: str


@dataclass
class DownloadChunkResponse:
    """Response from chunk download."""
    data: bytes
    checksum: str


def generate_uuid() -> str:
    """Generate a new UUID string."""
    return str(uuid.uuid4())
