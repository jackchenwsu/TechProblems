"""Background service for cleaning up deleted files and orphaned chunks."""

import threading
import time
from datetime import datetime, timedelta
from queue import Queue, Empty, PriorityQueue
from typing import List, Set

from common.constants import (
    FileType,
    FileStatus,
    GC_GRACE_PERIOD_HOURS,
)
from common.models import ChunkGCEntry
from .storage import MetadataStorage


class GarbageCollector:
    """Background service for cleaning up deleted files and orphaned chunks."""

    def __init__(self, storage: MetadataStorage):
        self.storage = storage

        # Queues
        self.deletion_queue: PriorityQueue = PriorityQueue()
        self.chunk_gc_queue: Queue = Queue()

        # Running state
        self._running = False
        self._threads: List[threading.Thread] = []

    def start(self):
        """Start garbage collection background threads."""
        self._running = True

        self._threads = [
            threading.Thread(target=self._process_deletions, daemon=True),
            threading.Thread(target=self._process_chunk_gc, daemon=True),
            threading.Thread(target=self._scan_orphans, daemon=True),
        ]

        for thread in self._threads:
            thread.start()

    def stop(self):
        """Stop garbage collection."""
        self._running = False

    def queue_deletion(self, inode_id: int, priority: int = 0):
        """Queue an inode for deletion."""
        self.deletion_queue.put((priority, inode_id))

    def queue_subtree_deletion(self, inode_id: int):
        """Queue a subtree for deletion."""
        self.deletion_queue.put((0, inode_id))

    # ─────────────────────────────────────────────────────────────
    # DIRECTORY DELETION (LAZY)
    # ─────────────────────────────────────────────────────────────

    def _process_deletions(self):
        """Process queued directory deletions."""
        while self._running:
            try:
                _, inode_id = self.deletion_queue.get(timeout=1)
            except Empty:
                continue

            try:
                inode = self.storage.get_inode(inode_id)
                if inode is None:
                    continue

                if inode.type == FileType.DIRECTORY:
                    self._delete_directory_contents(inode_id)
                else:
                    self._delete_file_chunks(inode_id)

                # Delete the inode itself
                self.storage.delete_inode(inode_id)

            except Exception as e:
                print(f"Error processing deletion for inode {inode_id}: {e}")
                # Re-queue with lower priority
                self.deletion_queue.put((1, inode_id))

    def _delete_directory_contents(self, dir_inode_id: int):
        """Recursively delete directory contents."""
        batch_size = 1000

        while True:
            children = self.storage.list_children(dir_inode_id)[:batch_size]

            if not children:
                break

            for child_name, child_id in children:
                child = self.storage.get_inode(child_id)

                if child is None:
                    self.storage.remove_child(dir_inode_id, child_name)
                    continue

                if child.type == FileType.DIRECTORY:
                    # Queue subdirectory for deletion
                    self.deletion_queue.put((time.time(), child_id))
                else:
                    # Queue file chunks for deletion
                    self._delete_file_chunks(child_id)
                    self.storage.delete_inode(child_id)

                # Remove from parent
                self.storage.remove_child(dir_inode_id, child_name)

            # Yield to prevent starving other operations
            time.sleep(0.01)

    def _delete_file_chunks(self, inode_id: int):
        """Queue all chunks of a file for deletion."""
        inode = self.storage.get_inode(inode_id)
        if inode is None:
            return

        # Delete all versions
        for version in range(1, inode.version + 1):
            chunks = self.storage.get_chunks(inode_id, version)

            for chunk in chunks:
                # Decrement reference count
                ref_count = self.storage.decrement_chunk_ref(chunk.chunk_id)

                if ref_count <= 0:
                    # Queue for physical deletion
                    entry = ChunkGCEntry(
                        chunk_id=chunk.chunk_id,
                        servers=chunk.servers,
                        delete_after=datetime.now() + timedelta(hours=GC_GRACE_PERIOD_HOURS),
                    )
                    self.chunk_gc_queue.put(entry)

            # Delete chunk metadata
            self.storage.delete_chunks(inode_id, version)

    # ─────────────────────────────────────────────────────────────
    # CHUNK PHYSICAL DELETION
    # ─────────────────────────────────────────────────────────────

    def _process_chunk_gc(self):
        """Delete chunks from chunk servers after grace period."""
        pending: List[ChunkGCEntry] = []

        while self._running:
            # Check pending entries
            now = datetime.now()
            ready = [e for e in pending if e.delete_after <= now]
            pending = [e for e in pending if e.delete_after > now]

            # Process ready entries
            for entry in ready:
                self._delete_chunk_from_servers(entry)

            # Get new entries
            try:
                entry = self.chunk_gc_queue.get(timeout=1)
                pending.append(entry)
            except Empty:
                pass

    def _delete_chunk_from_servers(self, entry: ChunkGCEntry):
        """Delete chunk from all chunk servers."""
        for server_id in entry.servers:
            try:
                # In production, this would be an RPC call to the chunk server
                # client = self.get_chunk_server_client(server_id)
                # client.delete_chunk(entry.chunk_id)
                print(f"Would delete chunk {entry.chunk_id} from {server_id}")
            except Exception as e:
                print(f"Failed to delete chunk {entry.chunk_id} from {server_id}: {e}")
                # Will be cleaned up by orphan scanner

    # ─────────────────────────────────────────────────────────────
    # ORPHAN DETECTION
    # ─────────────────────────────────────────────────────────────

    def _scan_orphans(self):
        """Periodically scan for orphaned chunks."""
        while self._running:
            # Run every 24 hours
            time.sleep(60 * 60 * 24)

            if not self._running:
                break

            try:
                self._do_orphan_scan()
            except Exception as e:
                print(f"Orphan scan failed: {e}")

    def _do_orphan_scan(self):
        """Perform orphan chunk detection."""
        print("Starting orphan chunk scan")

        # Get all known chunk IDs from metadata
        known_chunks: Set[str] = set()
        for chunk in self.storage.scan_all_chunks():
            known_chunks.add(chunk.chunk_id)

        # In production, we would iterate over all chunk servers
        # and compare their chunks against known_chunks
        # For now, just log the count
        print(f"Found {len(known_chunks)} known chunks in metadata")

        # Check each chunk server
        for server in self.storage.list_servers():
            try:
                # In production:
                # client = self.get_chunk_server_client(server.server_id)
                # server_chunks = client.list_chunks()
                #
                # for chunk_id in server_chunks:
                #     if chunk_id not in known_chunks:
                #         print(f"Orphan chunk found: {chunk_id} on {server.server_id}")
                #         client.delete_chunk(chunk_id)
                pass

            except Exception as e:
                print(f"Failed to scan server {server.server_id}: {e}")

        print("Orphan chunk scan completed")


class ChunkReplicator:
    """Background service for maintaining chunk replication."""

    def __init__(self, storage: MetadataStorage, target_replication: int = 3):
        self.storage = storage
        self.target_replication = target_replication
        self._running = False

    def start(self):
        """Start replication monitor."""
        self._running = True
        threading.Thread(target=self._monitor_replication, daemon=True).start()

    def stop(self):
        """Stop replication monitor."""
        self._running = False

    def _monitor_replication(self):
        """Monitor and maintain chunk replication."""
        while self._running:
            try:
                self._check_under_replicated()
            except Exception as e:
                print(f"Replication check failed: {e}")

            # Check every 5 minutes
            time.sleep(300)

    def _check_under_replicated(self):
        """Find and repair under-replicated chunks."""
        for chunk in self.storage.scan_all_chunks():
            # Count healthy replicas
            healthy_count = 0
            for server_id in chunk.servers:
                server = self.storage.get_server(server_id)
                if server and server.status == "ONLINE":
                    healthy_count += 1

            if healthy_count < self.target_replication:
                print(f"Under-replicated chunk: {chunk.chunk_id} "
                      f"({healthy_count}/{self.target_replication})")
                # In production, trigger re-replication
                # self._replicate_chunk(chunk)

    def _replicate_chunk(self, chunk):
        """Replicate a chunk to additional servers."""
        # Find source server (one that has the chunk)
        source_server = None
        for server_id in chunk.servers:
            server = self.storage.get_server(server_id)
            if server and server.status == "ONLINE":
                source_server = server
                break

        if not source_server:
            print(f"No healthy source for chunk {chunk.chunk_id}")
            return

        # Find target server (one that doesn't have the chunk)
        all_servers = self.storage.list_servers(status="ONLINE")
        existing_servers = set(chunk.servers)

        for server in all_servers:
            if server.server_id not in existing_servers:
                # In production:
                # source_client = ChunkServerClient(source_server.address)
                # target_client = ChunkServerClient(server.address)
                # data = source_client.download_chunk(chunk.chunk_id)
                # target_client.upload_chunk(chunk.chunk_id, data)
                print(f"Would replicate {chunk.chunk_id} from "
                      f"{source_server.server_id} to {server.server_id}")
                break
