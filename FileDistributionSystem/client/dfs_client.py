"""Client SDK for interacting with the distributed file system."""

import os
import json
import hashlib
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import List, Callable, Optional, Dict, Any

from common.constants import CHUNK_SIZE
from common.models import (
    FileInfo,
    UploadState,
    Chunk,
    ChunkAllocation,
    UploadSession,
    Inode,
    UploadChunkRequest,
    DownloadChunkRequest,
)


def sha256(data: bytes) -> str:
    """Compute SHA-256 hash of data."""
    return hashlib.sha256(data).hexdigest()


class DFSError(Exception):
    """Base exception for DFS client errors."""
    pass


class NotFoundError(DFSError):
    """Path not found."""
    pass


class DownloadError(DFSError):
    """Download failed."""
    pass


class UploadError(DFSError):
    """Upload failed."""
    pass


class ChecksumMismatchError(DFSError):
    """Checksum verification failed."""
    pass


class MetadataClient:
    """Client for communicating with metadata service.

    This is a stub implementation. In production, this would use gRPC or HTTP.
    """

    def __init__(self, addresses: List[str]):
        self.addresses = addresses
        self._metadata_service = None

    def set_metadata_service(self, service):
        """Set the metadata service instance (for testing)."""
        self._metadata_service = service

    def create_directory(self, path: str) -> Inode:
        """Create a directory."""
        if self._metadata_service:
            return self._metadata_service.create_directory(path)
        raise NotImplementedError("RPC not implemented")

    def list_directory(self, path: str) -> List[Inode]:
        """List directory contents."""
        if self._metadata_service:
            return self._metadata_service.list_directory(path)
        raise NotImplementedError("RPC not implemented")

    def delete(self, path: str) -> bool:
        """Delete file or directory."""
        if self._metadata_service:
            return self._metadata_service.delete(path)
        raise NotImplementedError("RPC not implemented")

    def delete_recursive(self, path: str) -> bool:
        """Delete directory recursively."""
        if self._metadata_service:
            return self._metadata_service.delete_recursive(path)
        raise NotImplementedError("RPC not implemented")

    def resolve_path(self, path: str) -> Optional[Inode]:
        """Resolve path to inode."""
        if self._metadata_service:
            return self._metadata_service.resolve_path(path)
        raise NotImplementedError("RPC not implemented")

    def init_upload(self, path: str, size: int) -> UploadSession:
        """Initialize file upload."""
        if self._metadata_service:
            return self._metadata_service.init_upload(path, size)
        raise NotImplementedError("RPC not implemented")

    def commit_upload(self, upload_id: str, checksums: List[str]) -> Inode:
        """Commit file upload."""
        if self._metadata_service:
            return self._metadata_service.commit_upload(upload_id, checksums)
        raise NotImplementedError("RPC not implemented")

    def abort_upload(self, upload_id: str):
        """Abort file upload."""
        if self._metadata_service:
            return self._metadata_service.abort_upload(upload_id)
        raise NotImplementedError("RPC not implemented")

    def get_upload_session(self, upload_id: str) -> Optional[UploadSession]:
        """Get upload session."""
        if self._metadata_service:
            return self._metadata_service.get_upload_session(upload_id)
        raise NotImplementedError("RPC not implemented")

    def get_file_metadata(self, path: str, version: int = None):
        """Get file metadata and chunks."""
        if self._metadata_service:
            return self._metadata_service.get_file_metadata(path, version)
        raise NotImplementedError("RPC not implemented")

    def get_server(self, server_id: str):
        """Get chunk server info."""
        if self._metadata_service:
            return self._metadata_service.get_server(server_id)
        raise NotImplementedError("RPC not implemented")


class ChunkServerClient:
    """Client for communicating with chunk servers.

    This is a stub implementation. In production, this would use gRPC or HTTP.
    """

    def __init__(self, address: str):
        self.address = address
        self._chunk_server = None

    def set_chunk_server(self, server):
        """Set the chunk server instance (for testing)."""
        self._chunk_server = server

    def upload_chunk(self, chunk_id: str, data: bytes,
                     checksum: str, replica_servers: List[str]):
        """Upload a chunk."""
        if self._chunk_server:
            request = UploadChunkRequest(
                chunk_id=chunk_id,
                data=data,
                checksum=checksum,
                replica_servers=replica_servers,
            )
            response = self._chunk_server.handle_upload(request)
            if not response.success:
                raise UploadError(response.error)
            return
        raise NotImplementedError("RPC not implemented")

    def download_chunk(self, chunk_id: str):
        """Download a chunk."""
        if self._chunk_server:
            request = DownloadChunkRequest(chunk_id=chunk_id)
            return self._chunk_server.handle_download(request)
        raise NotImplementedError("RPC not implemented")


class DFSClient:
    """Client SDK for interacting with the distributed file system."""

    def __init__(self, metadata_addresses: List[str]):
        self.metadata = MetadataClient(metadata_addresses)
        self._chunk_clients: Dict[str, ChunkServerClient] = {}

    def set_metadata_service(self, service):
        """Set metadata service for testing."""
        self.metadata.set_metadata_service(service)

    def _get_chunk_client(self, server_id: str) -> ChunkServerClient:
        """Get or create chunk server client."""
        if server_id not in self._chunk_clients:
            server_info = self.metadata.get_server(server_id)
            address = server_info.address if server_info else server_id
            self._chunk_clients[server_id] = ChunkServerClient(address)
        return self._chunk_clients[server_id]

    def set_chunk_server(self, server_id: str, server):
        """Set chunk server for testing."""
        if server_id not in self._chunk_clients:
            self._chunk_clients[server_id] = ChunkServerClient(server_id)
        self._chunk_clients[server_id].set_chunk_server(server)

    # ─────────────────────────────────────────────────────────────
    # DIRECTORY OPERATIONS
    # ─────────────────────────────────────────────────────────────

    def mkdir(self, path: str) -> bool:
        """Create a directory."""
        try:
            self.metadata.create_directory(path)
            return True
        except Exception:
            return False

    def ls(self, path: str) -> List[FileInfo]:
        """List directory contents."""
        inodes = self.metadata.list_directory(path)

        return [
            FileInfo(
                name=inode.name,
                type=inode.type,
                size=inode.size,
                modified=inode.modified_at,
                created=inode.created_at,
                owner=inode.owner,
                version=inode.version,
            )
            for inode in inodes
        ]

    def rmdir(self, path: str, recursive: bool = False) -> bool:
        """Remove a directory."""
        if recursive:
            return self.metadata.delete_recursive(path)
        else:
            return self.metadata.delete(path)

    # ─────────────────────────────────────────────────────────────
    # FILE UPLOAD
    # ─────────────────────────────────────────────────────────────

    def put(self, local_path: str, remote_path: str,
            progress_callback: Callable[[float], None] = None) -> bool:
        """Upload a file to the distributed file system."""
        file_size = os.path.getsize(local_path)

        # Step 1: Initialize upload
        session = self.metadata.init_upload(remote_path, file_size)

        try:
            # Step 2: Upload chunks
            checksums = []

            with open(local_path, 'rb') as f:
                for i, allocation in enumerate(session.chunks):
                    # Read chunk data
                    data = f.read(CHUNK_SIZE)
                    checksum = sha256(data)
                    checksums.append(checksum)

                    # Upload to primary server (which replicates to others)
                    primary_server = allocation.servers[0]
                    replica_servers = allocation.servers[1:]

                    client = self._get_chunk_client(primary_server)
                    client.upload_chunk(
                        chunk_id=allocation.chunk_id,
                        data=data,
                        checksum=checksum,
                        replica_servers=replica_servers,
                    )

                    # Progress callback
                    if progress_callback:
                        progress = (i + 1) / len(session.chunks) * 100
                        progress_callback(progress)

            # Step 3: Commit upload
            self.metadata.commit_upload(session.upload_id, checksums)

            return True

        except Exception as e:
            # Abort upload on failure
            self.metadata.abort_upload(session.upload_id)
            raise UploadError(f"Upload failed: {e}")

    def put_parallel(self, local_path: str, remote_path: str,
                     max_workers: int = 4,
                     progress_callback: Callable[[float], None] = None) -> bool:
        """Upload a file with parallel chunk uploads."""
        file_size = os.path.getsize(local_path)
        session = self.metadata.init_upload(remote_path, file_size)

        checksums = [None] * len(session.chunks)
        completed = [0]
        lock = __import__('threading').Lock()

        def upload_chunk(index: int, allocation: ChunkAllocation):
            # Read chunk data
            with open(local_path, 'rb') as f:
                f.seek(index * CHUNK_SIZE)
                data = f.read(CHUNK_SIZE)

            checksum = sha256(data)
            checksums[index] = checksum

            # Upload
            primary_server = allocation.servers[0]
            replica_servers = allocation.servers[1:]

            client = self._get_chunk_client(primary_server)
            client.upload_chunk(
                chunk_id=allocation.chunk_id,
                data=data,
                checksum=checksum,
                replica_servers=replica_servers,
            )

            # Progress callback
            with lock:
                completed[0] += 1
                if progress_callback:
                    progress = completed[0] / len(session.chunks) * 100
                    progress_callback(progress)

        try:
            # Upload chunks in parallel
            with ThreadPoolExecutor(max_workers=max_workers) as executor:
                futures = [
                    executor.submit(upload_chunk, i, alloc)
                    for i, alloc in enumerate(session.chunks)
                ]

                # Wait for all to complete
                for future in as_completed(futures):
                    future.result()  # Raises exception if failed

            # Commit
            self.metadata.commit_upload(session.upload_id, checksums)
            return True

        except Exception as e:
            self.metadata.abort_upload(session.upload_id)
            raise UploadError(f"Upload failed: {e}")

    # ─────────────────────────────────────────────────────────────
    # FILE DOWNLOAD
    # ─────────────────────────────────────────────────────────────

    def get(self, remote_path: str, local_path: str,
            progress_callback: Callable[[float], None] = None) -> bool:
        """Download a file from the distributed file system."""
        # Step 1: Get file metadata and chunk locations
        inode, chunks = self.metadata.get_file_metadata(remote_path)

        # Step 2: Download chunks
        with open(local_path, 'wb') as f:
            for i, chunk in enumerate(chunks):
                # Try each server until success
                data = None
                last_error = None

                for server_id in chunk.servers:
                    try:
                        client = self._get_chunk_client(server_id)
                        response = client.download_chunk(chunk.chunk_id)

                        # Verify checksum
                        if sha256(response.data) != chunk.checksum:
                            raise ChecksumMismatchError(
                                f"Checksum mismatch for chunk {chunk.chunk_id}"
                            )

                        data = response.data
                        break

                    except Exception as e:
                        last_error = e
                        continue

                if data is None:
                    raise DownloadError(
                        f"Failed to download chunk {i}: {last_error}"
                    )

                # Write to file
                f.write(data)

                # Progress callback
                if progress_callback:
                    progress = (i + 1) / len(chunks) * 100
                    progress_callback(progress)

        return True

    def get_parallel(self, remote_path: str, local_path: str,
                     max_workers: int = 4,
                     progress_callback: Callable[[float], None] = None) -> bool:
        """Download a file with parallel chunk downloads."""
        inode, chunks = self.metadata.get_file_metadata(remote_path)

        # Pre-allocate file
        with open(local_path, 'wb') as f:
            f.truncate(inode.size)

        chunk_data = [None] * len(chunks)
        completed = [0]
        lock = __import__('threading').Lock()

        def download_chunk(index: int, chunk: Chunk):
            for server_id in chunk.servers:
                try:
                    client = self._get_chunk_client(server_id)
                    response = client.download_chunk(chunk.chunk_id)

                    if sha256(response.data) != chunk.checksum:
                        continue

                    chunk_data[index] = response.data

                    # Progress callback
                    with lock:
                        completed[0] += 1
                        if progress_callback:
                            progress = completed[0] / len(chunks) * 100
                            progress_callback(progress)
                    return

                except Exception:
                    continue

            raise DownloadError(f"Failed to download chunk {index}")

        # Download in parallel
        with ThreadPoolExecutor(max_workers=max_workers) as executor:
            futures = [
                executor.submit(download_chunk, i, chunk)
                for i, chunk in enumerate(chunks)
            ]

            for future in as_completed(futures):
                future.result()

        # Write all chunks to file
        with open(local_path, 'r+b') as f:
            for i, data in enumerate(chunk_data):
                f.seek(i * CHUNK_SIZE)
                f.write(data)

        return True

    # ─────────────────────────────────────────────────────────────
    # FILE OPERATIONS
    # ─────────────────────────────────────────────────────────────

    def rm(self, path: str) -> bool:
        """Delete a file."""
        return self.metadata.delete(path)

    def exists(self, path: str) -> bool:
        """Check if path exists."""
        try:
            inode = self.metadata.resolve_path(path)
            return inode is not None
        except Exception:
            return False

    def stat(self, path: str) -> FileInfo:
        """Get file/directory info."""
        inode = self.metadata.resolve_path(path)
        if inode is None:
            raise NotFoundError(f"Not found: {path}")

        return FileInfo(
            name=inode.name,
            type=inode.type,
            size=inode.size,
            created=inode.created_at,
            modified=inode.modified_at,
            owner=inode.owner,
            version=inode.version,
        )

    # ─────────────────────────────────────────────────────────────
    # RESUMABLE UPLOAD
    # ─────────────────────────────────────────────────────────────

    def put_resumable(self, local_path: str, remote_path: str,
                      state_file: str = None,
                      progress_callback: Callable[[float], None] = None) -> bool:
        """Upload with resume capability."""
        # Load previous state if exists
        state = self._load_upload_state(state_file) if state_file else None

        if state and state.remote_path == remote_path:
            # Resume previous upload
            session = self.metadata.get_upload_session(state.upload_id)
            if session is None:
                # Session expired, start fresh
                state = None

        if state is None:
            # Start new upload
            file_size = os.path.getsize(local_path)
            session = self.metadata.init_upload(remote_path, file_size)
            state = UploadState(
                upload_id=session.upload_id,
                remote_path=remote_path,
                completed_chunks=[],
                checksums=[None] * len(session.chunks),
            )

        completed_chunks = set(state.completed_chunks)
        checksums = state.checksums

        try:
            with open(local_path, 'rb') as f:
                for i, allocation in enumerate(session.chunks):
                    if i in completed_chunks:
                        continue  # Already uploaded

                    # Read and upload chunk
                    f.seek(i * CHUNK_SIZE)
                    data = f.read(CHUNK_SIZE)
                    checksum = sha256(data)
                    checksums[i] = checksum

                    primary_server = allocation.servers[0]
                    client = self._get_chunk_client(primary_server)
                    client.upload_chunk(
                        chunk_id=allocation.chunk_id,
                        data=data,
                        checksum=checksum,
                        replica_servers=allocation.servers[1:],
                    )

                    # Update state
                    completed_chunks.add(i)
                    state.completed_chunks.append(i)
                    state.checksums = checksums

                    if state_file:
                        self._save_upload_state(state_file, state)

                    # Progress callback
                    if progress_callback:
                        progress = len(completed_chunks) / len(session.chunks) * 100
                        progress_callback(progress)

            # Commit
            self.metadata.commit_upload(session.upload_id, checksums)

            # Clean up state file
            if state_file and os.path.exists(state_file):
                os.remove(state_file)

            return True

        except Exception as e:
            # State is saved, can resume later
            raise UploadError(f"Upload failed (resumable): {e}")

    def _load_upload_state(self, state_file: str) -> Optional[UploadState]:
        """Load upload state from file."""
        if not os.path.exists(state_file):
            return None

        try:
            with open(state_file, 'r') as f:
                data = json.load(f)
            return UploadState(**data)
        except Exception:
            return None

    def _save_upload_state(self, state_file: str, state: UploadState):
        """Save upload state to file."""
        data = {
            'upload_id': state.upload_id,
            'remote_path': state.remote_path,
            'completed_chunks': state.completed_chunks,
            'checksums': state.checksums,
        }
        with open(state_file, 'w') as f:
            json.dump(data, f)
