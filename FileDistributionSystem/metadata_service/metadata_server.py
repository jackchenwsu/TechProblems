"""Main metadata service handling all file system operations."""

import threading
from datetime import datetime, timedelta
from typing import List, Tuple, Optional

from common.constants import (
    ROOT_INODE_ID,
    CHUNK_SIZE,
    REPLICATION_FACTOR,
    FileStatus,
    FileType,
    ServerStatus,
    UploadStatus,
)
from common.models import (
    Inode,
    Chunk,
    ChunkServer,
    ChunkAllocation,
    UploadSession,
    Command,
    generate_uuid,
)
from .storage import MetadataStorage
from .raft_node import RaftNode, NotLeaderError


class FileSystemError(Exception):
    """Base exception for file system errors."""
    pass


class NotFoundError(FileSystemError):
    """Path not found."""
    pass


class AlreadyExistsError(FileSystemError):
    """Path already exists."""
    pass


class NotADirectoryError(FileSystemError):
    """Path is not a directory."""
    pass


class NotAFileError(FileSystemError):
    """Path is not a file."""
    pass


class DirectoryNotEmptyError(FileSystemError):
    """Directory is not empty."""
    pass


class ParentNotFoundError(FileSystemError):
    """Parent directory not found."""
    pass


class UploadNotFoundError(FileSystemError):
    """Upload session not found."""
    pass


class InvalidUploadError(FileSystemError):
    """Invalid upload operation."""
    pass


class DistributedLockManager:
    """Simple distributed lock manager.

    In production, this would use the Raft log for distributed locks.
    """

    def __init__(self):
        self._locks = {}
        self._lock = threading.Lock()

    def lock(self, key: str):
        """Acquire a lock on the given key."""
        return _LockContext(self, key)

    def _acquire(self, key: str):
        with self._lock:
            if key not in self._locks:
                self._locks[key] = threading.Lock()
        self._locks[key].acquire()

    def _release(self, key: str):
        if key in self._locks:
            self._locks[key].release()


class _LockContext:
    def __init__(self, manager: DistributedLockManager, key: str):
        self.manager = manager
        self.key = key

    def __enter__(self):
        self.manager._acquire(self.key)
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.manager._release(self.key)
        return False


class MetadataService:
    """Main metadata service handling all file system operations."""

    def __init__(self, node_id: str, peers: List[str], db_path: str):
        self.storage = MetadataStorage(db_path)
        self.raft = RaftNode(node_id, peers, self.storage)
        self.locks = DistributedLockManager()

        # GC queue
        self._gc_queue = []
        self._gc_lock = threading.Lock()

        # Initialize root directory if needed
        if self.storage.get_inode(ROOT_INODE_ID) is None:
            self._init_root_directory()

    def start(self):
        """Start the metadata service."""
        self.raft.start()

    def stop(self):
        """Stop the metadata service."""
        self.raft.stop()

    def _init_root_directory(self):
        """Initialize the root directory."""
        root = Inode(
            inode_id=ROOT_INODE_ID,
            parent_id=ROOT_INODE_ID,
            name="/",
            type=FileType.DIRECTORY,
            size=0,
            status=FileStatus.ACTIVE,
            version=1,
            created_at=datetime.now(),
            modified_at=datetime.now(),
            owner="root",
        )
        self.storage.put_inode(root)

    # ─────────────────────────────────────────────────────────────
    # PATH RESOLUTION
    # ─────────────────────────────────────────────────────────────

    def resolve_path(self, path: str) -> Optional[Inode]:
        """Resolve a path to its inode."""
        if path == "/":
            return self.storage.get_inode(ROOT_INODE_ID)

        parts = path.strip("/").split("/")
        current_inode_id = ROOT_INODE_ID

        for part in parts:
            child_id = self.storage.get_child(current_inode_id, part)
            if child_id is None:
                return None

            inode = self.storage.get_inode(child_id)
            if inode is None or inode.status == FileStatus.DELETED:
                return None

            current_inode_id = child_id

        return self.storage.get_inode(current_inode_id)

    def _resolve_parent(self, path: str) -> Tuple[Optional[Inode], str]:
        """Get parent inode and child name from path."""
        parts = path.strip("/").split("/")
        child_name = parts[-1]
        parent_path = "/" + "/".join(parts[:-1]) if len(parts) > 1 else "/"

        parent_inode = self.resolve_path(parent_path)
        return parent_inode, child_name

    # ─────────────────────────────────────────────────────────────
    # DIRECTORY OPERATIONS
    # ─────────────────────────────────────────────────────────────

    def create_directory(self, path: str, owner: str = "default") -> Inode:
        """Create a new directory."""
        self.raft.ensure_leader()

        parent_inode, dir_name = self._resolve_parent(path)
        if parent_inode is None:
            raise ParentNotFoundError(f"Parent not found for: {path}")
        if parent_inode.type != FileType.DIRECTORY:
            raise NotADirectoryError(f"Parent is not a directory: {path}")

        # Check if already exists
        existing = self.storage.get_child(parent_inode.inode_id, dir_name)
        if existing is not None:
            raise AlreadyExistsError(f"Already exists: {path}")

        with self.locks.lock(f"dir:{parent_inode.inode_id}"):
            # Create new inode
            new_inode = Inode(
                inode_id=self.storage.next_inode_id(),
                parent_id=parent_inode.inode_id,
                name=dir_name,
                type=FileType.DIRECTORY,
                size=0,
                status=FileStatus.ACTIVE,
                version=1,
                created_at=datetime.now(),
                modified_at=datetime.now(),
                owner=owner,
            )

            # Replicate through Raft
            self.raft.propose(Command(type="CREATE_INODE", inode=new_inode))
            self.raft.propose(Command(
                type="ADD_CHILD",
                parent_id=parent_inode.inode_id,
                name=dir_name,
                child_id=new_inode.inode_id,
            ))

        return new_inode

    def list_directory(self, path: str) -> List[Inode]:
        """List contents of a directory."""
        # Use read index for linearizable read
        self.raft.read_index()

        inode = self.resolve_path(path)
        if inode is None:
            raise NotFoundError(f"Not found: {path}")
        if inode.type != FileType.DIRECTORY:
            raise NotADirectoryError(f"Not a directory: {path}")

        children = self.storage.list_children(inode.inode_id)

        result = []
        for child_name, child_id in children:
            child_inode = self.storage.get_inode(child_id)
            if child_inode and child_inode.status == FileStatus.ACTIVE:
                result.append(child_inode)

        return sorted(result, key=lambda x: x.name)

    # ─────────────────────────────────────────────────────────────
    # FILE UPLOAD
    # ─────────────────────────────────────────────────────────────

    def init_upload(self, path: str, size: int, owner: str = "default") -> UploadSession:
        """Initialize a file upload session."""
        self.raft.ensure_leader()

        parent_inode, file_name = self._resolve_parent(path)
        if parent_inode is None:
            raise ParentNotFoundError(f"Parent not found for: {path}")

        # Check if file exists (for update) or create new
        existing_id = self.storage.get_child(parent_inode.inode_id, file_name)

        with self.locks.lock(f"dir:{parent_inode.inode_id}"):
            if existing_id:
                # Update existing file - create new version
                existing = self.storage.get_inode(existing_id)
                inode_id = existing_id
                version = existing.version + 1
            else:
                # New file
                inode_id = self.storage.next_inode_id()
                version = 1

            # Calculate chunks
            num_chunks = max(1, (size + CHUNK_SIZE - 1) // CHUNK_SIZE)
            chunks = []

            for i in range(num_chunks):
                servers = self._select_chunk_servers(REPLICATION_FACTOR)

                chunk = ChunkAllocation(
                    chunk_index=i,
                    chunk_id=generate_uuid(),
                    servers=servers,
                )
                chunks.append(chunk)

            # Create inode in UPLOADING state
            inode = Inode(
                inode_id=inode_id,
                parent_id=parent_inode.inode_id,
                name=file_name,
                type=FileType.FILE,
                size=size,
                status=FileStatus.UPLOADING,
                version=version,
                created_at=datetime.now(),
                modified_at=datetime.now(),
                owner=owner,
            )

            self.raft.propose(Command(type="CREATE_INODE", inode=inode))

            if not existing_id:
                self.raft.propose(Command(
                    type="ADD_CHILD",
                    parent_id=parent_inode.inode_id,
                    name=file_name,
                    child_id=inode_id,
                ))

            # Create upload session
            session = UploadSession(
                upload_id=generate_uuid(),
                inode_id=inode_id,
                version=version,
                chunks=chunks,
                status=UploadStatus.PENDING,
                created_at=datetime.now(),
                expires_at=datetime.now() + timedelta(hours=24),
            )

            self.storage.put_upload_session(session)

            return session

    def _select_chunk_servers(self, count: int) -> List[str]:
        """Select chunk servers for replica placement."""
        servers = self.storage.list_servers(status=ServerStatus.ONLINE)

        if not servers:
            # Return placeholder servers for testing
            return [f"server-{i}" for i in range(count)]

        # Sort by available space
        servers.sort(key=lambda s: s.available, reverse=True)

        # Select from different zones if possible
        selected = []
        zones_used = set()

        for server in servers:
            if len(selected) >= count:
                break

            # Prefer servers in different zones
            if server.zone not in zones_used or len(selected) < count:
                selected.append(server.server_id)
                zones_used.add(server.zone)

        # Fill remaining with any available servers
        for server in servers:
            if len(selected) >= count:
                break
            if server.server_id not in selected:
                selected.append(server.server_id)

        return selected

    def commit_upload(self, upload_id: str, chunk_checksums: List[str]) -> Inode:
        """Commit an upload after all chunks are uploaded."""
        self.raft.ensure_leader()

        session = self.storage.get_upload_session(upload_id)
        if session is None:
            raise UploadNotFoundError(f"Upload not found: {upload_id}")

        # Verify all chunks
        if len(chunk_checksums) != len(session.chunks):
            raise InvalidUploadError("Chunk count mismatch")

        # Get the inode to calculate actual chunk sizes
        inode = self.storage.get_inode(session.inode_id)

        # Save chunk metadata
        for i, allocation in enumerate(session.chunks):
            # Calculate actual chunk size
            if i == len(session.chunks) - 1:
                # Last chunk may be smaller
                chunk_size = inode.size - (i * CHUNK_SIZE)
            else:
                chunk_size = CHUNK_SIZE

            chunk = Chunk(
                chunk_id=allocation.chunk_id,
                inode_id=session.inode_id,
                version=session.version,
                chunk_index=i,
                size=chunk_size,
                checksum=chunk_checksums[i],
                servers=allocation.servers,
            )

            self.raft.propose(Command(type="PUT_CHUNK", chunk=chunk))

        # Update inode status to ACTIVE
        inode.status = FileStatus.ACTIVE
        inode.modified_at = datetime.now()

        self.raft.propose(Command(type="CREATE_INODE", inode=inode))

        # Clean up session
        self.storage.delete_upload_session(upload_id)

        return inode

    def abort_upload(self, upload_id: str):
        """Abort an upload session."""
        session = self.storage.get_upload_session(upload_id)
        if session:
            # Delete incomplete inode if it was a new file
            inode = self.storage.get_inode(session.inode_id)
            if inode and inode.status == FileStatus.UPLOADING:
                if inode.version == 1:
                    # New file - remove completely
                    self.storage.delete_inode(session.inode_id)
                    self.storage.remove_child(inode.parent_id, inode.name)

            self.storage.delete_upload_session(upload_id)

    def get_upload_session(self, upload_id: str) -> Optional[UploadSession]:
        """Get an upload session by ID."""
        return self.storage.get_upload_session(upload_id)

    # ─────────────────────────────────────────────────────────────
    # FILE DOWNLOAD
    # ─────────────────────────────────────────────────────────────

    def get_file_metadata(self, path: str, version: int = None) -> Tuple[Inode, List[Chunk]]:
        """Get file metadata and chunk locations for download."""
        self.raft.read_index()

        inode = self.resolve_path(path)
        if inode is None:
            raise NotFoundError(f"Not found: {path}")
        if inode.type != FileType.FILE:
            raise NotAFileError(f"Not a file: {path}")
        if inode.status != FileStatus.ACTIVE:
            raise NotFoundError(f"Not found: {path}")

        # Use current version if not specified
        if version is None:
            version = inode.version

        chunks = self.storage.get_chunks(inode.inode_id, version)

        return inode, chunks

    # ─────────────────────────────────────────────────────────────
    # DELETE OPERATIONS
    # ─────────────────────────────────────────────────────────────

    def delete(self, path: str) -> bool:
        """Delete a file or directory."""
        self.raft.ensure_leader()

        parent_inode, name = self._resolve_parent(path)
        if parent_inode is None:
            raise ParentNotFoundError(f"Parent not found for: {path}")

        inode = self.resolve_path(path)
        if inode is None:
            raise NotFoundError(f"Not found: {path}")

        with self.locks.lock(f"dir:{parent_inode.inode_id}"):
            if inode.type == FileType.DIRECTORY:
                # Check if directory is empty
                children = self.storage.list_children(inode.inode_id)
                if children:
                    raise DirectoryNotEmptyError(f"Directory not empty: {path}")

            # Mark as deleted (lazy deletion)
            inode.status = FileStatus.DELETED
            inode.modified_at = datetime.now()

            self.raft.propose(Command(type="CREATE_INODE", inode=inode))

            # Remove from parent
            self.raft.propose(Command(
                type="REMOVE_CHILD",
                parent_id=parent_inode.inode_id,
                name=name,
            ))

            # Queue for garbage collection
            if inode.type == FileType.FILE:
                self._queue_for_gc(inode.inode_id)

        return True

    def delete_recursive(self, path: str) -> bool:
        """Delete directory and all contents (lazy)."""
        self.raft.ensure_leader()

        parent_inode, name = self._resolve_parent(path)
        inode = self.resolve_path(path)

        if inode is None:
            raise NotFoundError(f"Not found: {path}")

        with self.locks.lock(f"dir:{parent_inode.inode_id}"):
            # Mark root as deleted
            inode.status = FileStatus.DELETED
            self.raft.propose(Command(type="CREATE_INODE", inode=inode))

            # Remove from parent
            self.raft.propose(Command(
                type="REMOVE_CHILD",
                parent_id=parent_inode.inode_id,
                name=name,
            ))

            # Queue entire subtree for background GC
            self._queue_subtree_for_gc(inode.inode_id)

        return True

    def _queue_for_gc(self, inode_id: int):
        """Queue an inode for garbage collection."""
        with self._gc_lock:
            self._gc_queue.append(("file", inode_id))

    def _queue_subtree_for_gc(self, inode_id: int):
        """Queue a directory subtree for garbage collection."""
        with self._gc_lock:
            self._gc_queue.append(("subtree", inode_id))

    # ─────────────────────────────────────────────────────────────
    # CHUNK SERVER MANAGEMENT
    # ─────────────────────────────────────────────────────────────

    def handle_heartbeat(self, server_id: str, server_info: dict):
        """Handle heartbeat from chunk server."""
        server = self.storage.get_server(server_id)

        if server is None:
            # New server registration
            server = ChunkServer(
                server_id=server_id,
                address=server_info.get("address", ""),
                capacity=server_info.get("capacity", 0),
                used=server_info.get("used", 0),
                status=ServerStatus.ONLINE,
                last_heartbeat=datetime.now(),
                zone=server_info.get("zone", "default"),
            )
        else:
            # Update existing
            server.used = server_info.get("used", server.used)
            server.last_heartbeat = datetime.now()
            server.status = ServerStatus.ONLINE

        self.storage.register_server(server)

    def get_server(self, server_id: str) -> Optional[ChunkServer]:
        """Get chunk server info."""
        return self.storage.get_server(server_id)

    def list_servers(self, status: str = None) -> List[ChunkServer]:
        """List chunk servers."""
        return self.storage.list_servers(status)
