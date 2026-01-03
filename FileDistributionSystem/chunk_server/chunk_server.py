"""Server that stores actual file chunks."""

import os
import hashlib
import threading
import time
from datetime import datetime
from typing import Dict, List, Optional

from common.constants import HEARTBEAT_INTERVAL
from common.models import (
    ChunkInfo,
    UploadChunkRequest,
    UploadChunkResponse,
    DownloadChunkRequest,
    DownloadChunkResponse,
)


class ChunkError(Exception):
    """Base exception for chunk operations."""
    pass


class ChunkNotFoundError(ChunkError):
    """Chunk not found."""
    pass


class ChecksumMismatchError(ChunkError):
    """Checksum verification failed."""
    pass


class ChunkCorruptedError(ChunkError):
    """Chunk data is corrupted."""
    pass


def sha256(data: bytes) -> str:
    """Compute SHA-256 hash of data."""
    return hashlib.sha256(data).hexdigest()


class ChunkServer:
    """Server that stores actual file chunks."""

    def __init__(self, server_id: str, address: str, data_dir: str,
                 capacity: int, metadata_addresses: List[str] = None):
        self.server_id = server_id
        self.address = address
        self.data_dir = data_dir
        self.capacity = capacity
        self.metadata_addresses = metadata_addresses or []

        # Local chunk index
        self.chunks: Dict[str, ChunkInfo] = {}

        # Stats
        self.used = 0

        # Locks
        self._lock = threading.RLock()

        # Running state
        self._running = False
        self._threads: List[threading.Thread] = []

        # Ensure data directory exists
        os.makedirs(data_dir, exist_ok=True)

    def start(self):
        """Start the chunk server."""
        self._running = True

        # Scan existing chunks
        self._scan_local_chunks()

        # Start background threads
        self._threads = [
            threading.Thread(target=self._heartbeat_loop, daemon=True),
            threading.Thread(target=self._scrub_loop, daemon=True),
        ]

        for thread in self._threads:
            thread.start()

    def stop(self):
        """Stop the chunk server."""
        self._running = False

    # ─────────────────────────────────────────────────────────────
    # CHUNK STORAGE
    # ─────────────────────────────────────────────────────────────

    def _chunk_path(self, chunk_id: str) -> str:
        """Get file path for a chunk (sharded by prefix)."""
        # Use first 4 chars for directory sharding
        prefix = chunk_id[:4]
        return os.path.join(self.data_dir, prefix, chunk_id)

    def write_chunk(self, chunk_id: str, data: bytes, checksum: str) -> bool:
        """Write a chunk to local storage."""
        # Verify checksum
        computed_checksum = sha256(data)
        if computed_checksum != checksum:
            raise ChecksumMismatchError(
                f"Checksum mismatch for chunk {chunk_id}: "
                f"expected {checksum}, got {computed_checksum}"
            )

        # Write to disk
        path = self._chunk_path(chunk_id)
        os.makedirs(os.path.dirname(path), exist_ok=True)

        # Write to temp file first, then rename (atomic)
        temp_path = f"{path}.tmp"
        try:
            with open(temp_path, 'wb') as f:
                f.write(data)
            os.rename(temp_path, path)
        except Exception:
            # Clean up temp file on failure
            if os.path.exists(temp_path):
                os.remove(temp_path)
            raise

        # Update local index
        with self._lock:
            self.chunks[chunk_id] = ChunkInfo(
                chunk_id=chunk_id,
                size=len(data),
                checksum=checksum,
                created_at=datetime.now(),
            )
            self.used += len(data)

        return True

    def read_chunk(self, chunk_id: str) -> bytes:
        """Read a chunk from local storage."""
        path = self._chunk_path(chunk_id)

        if not os.path.exists(path):
            raise ChunkNotFoundError(f"Chunk not found: {chunk_id}")

        with open(path, 'rb') as f:
            data = f.read()

        # Verify checksum
        with self._lock:
            if chunk_id in self.chunks:
                expected = self.chunks[chunk_id].checksum
                actual = sha256(data)
                if actual != expected:
                    raise ChunkCorruptedError(
                        f"Chunk corrupted: {chunk_id}, "
                        f"expected {expected}, got {actual}"
                    )

        return data

    def delete_chunk(self, chunk_id: str) -> bool:
        """Delete a chunk from local storage."""
        path = self._chunk_path(chunk_id)

        with self._lock:
            if os.path.exists(path):
                size = os.path.getsize(path)
                os.remove(path)
                self.used -= size

            if chunk_id in self.chunks:
                del self.chunks[chunk_id]

        return True

    def has_chunk(self, chunk_id: str) -> bool:
        """Check if chunk exists locally."""
        with self._lock:
            return chunk_id in self.chunks

    def list_chunks(self) -> List[str]:
        """List all chunk IDs."""
        with self._lock:
            return list(self.chunks.keys())

    def get_chunk_info(self, chunk_id: str) -> Optional[ChunkInfo]:
        """Get chunk info."""
        with self._lock:
            return self.chunks.get(chunk_id)

    # ─────────────────────────────────────────────────────────────
    # REQUEST HANDLERS
    # ─────────────────────────────────────────────────────────────

    def handle_upload(self, request: UploadChunkRequest) -> UploadChunkResponse:
        """Handle chunk upload from client."""
        try:
            # Write locally first
            self.write_chunk(request.chunk_id, request.data, request.checksum)

            # Replicate to secondary servers (chain replication)
            if request.replica_servers:
                self._replicate_to_chain(
                    request.chunk_id,
                    request.data,
                    request.checksum,
                    request.replica_servers,
                )

            return UploadChunkResponse(success=True)

        except Exception as e:
            return UploadChunkResponse(success=False, error=str(e))

    def handle_download(self, request: DownloadChunkRequest) -> DownloadChunkResponse:
        """Handle chunk download request."""
        data = self.read_chunk(request.chunk_id)

        with self._lock:
            checksum = self.chunks[request.chunk_id].checksum

        return DownloadChunkResponse(data=data, checksum=checksum)

    def _replicate_to_chain(self, chunk_id: str, data: bytes,
                            checksum: str, replica_servers: List[str]):
        """Replicate chunk to chain of servers."""
        if not replica_servers:
            return

        next_server = replica_servers[0]
        remaining = replica_servers[1:]

        try:
            # In production, this would be an RPC call
            # client = ChunkServerClient(next_server)
            # client.upload_chunk(
            #     chunk_id=chunk_id,
            #     data=data,
            #     checksum=checksum,
            #     replica_servers=remaining,
            # )
            print(f"Would replicate chunk {chunk_id} to {next_server}")

        except Exception as e:
            # Log error, metadata service will handle re-replication
            print(f"Failed to replicate to {next_server}: {e}")

    # ─────────────────────────────────────────────────────────────
    # HEARTBEAT
    # ─────────────────────────────────────────────────────────────

    def _heartbeat_loop(self):
        """Background thread to send heartbeats to metadata service."""
        while self._running:
            try:
                self._send_heartbeat()
            except Exception as e:
                print(f"Heartbeat failed: {e}")

            time.sleep(HEARTBEAT_INTERVAL)

    def _send_heartbeat(self):
        """Send heartbeat to metadata service."""
        # In production, this would be an RPC call
        # for address in self.metadata_addresses:
        #     try:
        #         client = MetadataClient(address)
        #         client.heartbeat(
        #             server_id=self.server_id,
        #             address=self.address,
        #             capacity=self.capacity,
        #             used=self.used,
        #             chunk_count=len(self.chunks),
        #         )
        #         break
        #     except Exception:
        #         continue
        pass

    # ─────────────────────────────────────────────────────────────
    # DATA INTEGRITY (SCRUBBING)
    # ─────────────────────────────────────────────────────────────

    def _scrub_loop(self):
        """Background thread to verify chunk integrity."""
        while self._running:
            try:
                self._scrub_chunks()
            except Exception as e:
                print(f"Scrubbing failed: {e}")

            # Full scan every 24 hours
            time.sleep(60 * 60 * 24)

    def _scrub_chunks(self):
        """Verify integrity of all chunks."""
        with self._lock:
            chunk_ids = list(self.chunks.keys())

        for chunk_id in chunk_ids:
            if not self._running:
                break

            try:
                info = self.chunks.get(chunk_id)
                if info is None:
                    continue

                path = self._chunk_path(chunk_id)

                if not os.path.exists(path):
                    print(f"Missing chunk: {chunk_id}")
                    self._report_missing_chunk(chunk_id)
                    continue

                with open(path, 'rb') as f:
                    data = f.read()

                actual_checksum = sha256(data)

                if actual_checksum != info.checksum:
                    print(f"Corrupted chunk detected: {chunk_id}")
                    self._report_corrupted_chunk(chunk_id)

            except Exception as e:
                print(f"Error scrubbing chunk {chunk_id}: {e}")

            # Throttle scrubbing
            time.sleep(0.1)

    def _report_corrupted_chunk(self, chunk_id: str):
        """Report corrupted chunk to metadata service."""
        # In production, this would be an RPC call
        # self.metadata_service.report_chunk_issue(
        #     server_id=self.server_id,
        #     chunk_id=chunk_id,
        #     issue_type="CORRUPTED",
        # )

        # Delete corrupted chunk
        self.delete_chunk(chunk_id)

    def _report_missing_chunk(self, chunk_id: str):
        """Report missing chunk to metadata service."""
        # In production, this would be an RPC call
        # self.metadata_service.report_chunk_issue(
        #     server_id=self.server_id,
        #     chunk_id=chunk_id,
        #     issue_type="MISSING",
        # )

        # Remove from local index
        with self._lock:
            self.chunks.pop(chunk_id, None)

    # ─────────────────────────────────────────────────────────────
    # STARTUP & RECOVERY
    # ─────────────────────────────────────────────────────────────

    def _scan_local_chunks(self):
        """Scan local storage on startup."""
        self.chunks = {}
        self.used = 0

        if not os.path.exists(self.data_dir):
            return

        for root, dirs, files in os.walk(self.data_dir):
            for filename in files:
                if filename.endswith('.tmp'):
                    # Remove incomplete uploads
                    path = os.path.join(root, filename)
                    os.remove(path)
                    continue

                chunk_id = filename
                path = os.path.join(root, filename)

                try:
                    size = os.path.getsize(path)

                    # Compute checksum
                    with open(path, 'rb') as f:
                        checksum = sha256(f.read())

                    self.chunks[chunk_id] = ChunkInfo(
                        chunk_id=chunk_id,
                        size=size,
                        checksum=checksum,
                        created_at=datetime.fromtimestamp(os.path.getctime(path)),
                    )

                    self.used += size

                except Exception as e:
                    print(f"Error scanning chunk {chunk_id}: {e}")

        print(f"Scanned {len(self.chunks)} chunks, {self.used} bytes used")

    # ─────────────────────────────────────────────────────────────
    # STATS
    # ─────────────────────────────────────────────────────────────

    def get_stats(self) -> dict:
        """Get server statistics."""
        with self._lock:
            return {
                "server_id": self.server_id,
                "address": self.address,
                "capacity": self.capacity,
                "used": self.used,
                "available": self.capacity - self.used,
                "chunk_count": len(self.chunks),
            }
