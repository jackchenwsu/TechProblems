"""RocksDB-backed storage for metadata."""

import json
import threading
from typing import List, Tuple, Optional, Iterator
from datetime import datetime

from common.models import Inode, Chunk, ChunkServer, UploadSession


class MetadataStorage:
    """RocksDB-backed storage for metadata.

    This is a simplified implementation using a dict as backing store.
    In production, replace with actual RocksDB.
    """

    def __init__(self, db_path: str):
        self.db_path = db_path
        self._data = {}  # In production: RocksDB instance
        self._lock = threading.RLock()
        self._next_inode_id = 2  # 1 is reserved for root

    def _get(self, key: str) -> Optional[str]:
        """Get value by key."""
        with self._lock:
            return self._data.get(key)

    def _put(self, key: str, value: str):
        """Put key-value pair."""
        with self._lock:
            self._data[key] = value

    def _delete(self, key: str):
        """Delete key."""
        with self._lock:
            self._data.pop(key, None)

    def _prefix_scan(self, prefix: str) -> Iterator[Tuple[str, str]]:
        """Scan all keys with given prefix."""
        with self._lock:
            for key, value in self._data.items():
                if key.startswith(prefix):
                    yield key, value

    def _serialize(self, obj) -> str:
        """Serialize object to JSON."""
        if hasattr(obj, '__dataclass_fields__'):
            data = {}
            for field_name in obj.__dataclass_fields__:
                value = getattr(obj, field_name)
                if isinstance(value, datetime):
                    value = value.isoformat()
                elif isinstance(value, list):
                    value = [self._serialize_value(v) for v in value]
                data[field_name] = value
            return json.dumps(data)
        return json.dumps(obj)

    def _serialize_value(self, value):
        """Serialize a single value."""
        if hasattr(value, '__dataclass_fields__'):
            data = {}
            for field_name in value.__dataclass_fields__:
                v = getattr(value, field_name)
                if isinstance(v, datetime):
                    v = v.isoformat()
                data[field_name] = v
            return data
        return value

    def _deserialize(self, data: str, cls):
        """Deserialize JSON to object."""
        obj_dict = json.loads(data)
        # Convert datetime strings back
        for field_name, field_type in cls.__dataclass_fields__.items():
            if field_name in obj_dict and 'datetime' in str(field_type.type):
                if obj_dict[field_name]:
                    obj_dict[field_name] = datetime.fromisoformat(obj_dict[field_name])
        return cls(**obj_dict)

    # ─────────────────────────────────────────────────────────────
    # INODE OPERATIONS
    # ─────────────────────────────────────────────────────────────

    def get_inode(self, inode_id: int) -> Optional[Inode]:
        """Get inode by ID."""
        key = f"inode:{inode_id}"
        data = self._get(key)
        if data:
            return self._deserialize(data, Inode)
        return None

    def put_inode(self, inode: Inode):
        """Store inode."""
        key = f"inode:{inode.inode_id}"
        self._put(key, self._serialize(inode))

    def delete_inode(self, inode_id: int):
        """Delete inode."""
        key = f"inode:{inode_id}"
        self._delete(key)

    def next_inode_id(self) -> int:
        """Get next available inode ID (atomic increment)."""
        with self._lock:
            inode_id = self._next_inode_id
            self._next_inode_id += 1
            return inode_id

    # ─────────────────────────────────────────────────────────────
    # DIRECTORY OPERATIONS
    # ─────────────────────────────────────────────────────────────

    def add_child(self, parent_id: int, child_name: str, child_id: int):
        """Add child to directory."""
        key = f"children:{parent_id}:{child_name}"
        self._put(key, str(child_id))

    def remove_child(self, parent_id: int, child_name: str):
        """Remove child from directory."""
        key = f"children:{parent_id}:{child_name}"
        self._delete(key)

    def get_child(self, parent_id: int, child_name: str) -> Optional[int]:
        """Get child inode ID by name."""
        key = f"children:{parent_id}:{child_name}"
        data = self._get(key)
        return int(data) if data else None

    def list_children(self, parent_id: int) -> List[Tuple[str, int]]:
        """List all children of a directory."""
        prefix = f"children:{parent_id}:"
        results = []
        for key, value in self._prefix_scan(prefix):
            child_name = key.split(":")[-1]
            child_id = int(value)
            results.append((child_name, child_id))
        return results

    # ─────────────────────────────────────────────────────────────
    # CHUNK OPERATIONS
    # ─────────────────────────────────────────────────────────────

    def put_chunk(self, chunk: Chunk):
        """Store chunk metadata."""
        key = f"chunk:{chunk.inode_id}:{chunk.version}:{chunk.chunk_index}"
        self._put(key, self._serialize(chunk))

    def get_chunks(self, inode_id: int, version: int) -> List[Chunk]:
        """Get all chunks for a file version."""
        prefix = f"chunk:{inode_id}:{version}:"
        chunks = []
        for key, value in self._prefix_scan(prefix):
            chunk_data = json.loads(value)
            chunk = Chunk(**chunk_data)
            chunks.append(chunk)
        return sorted(chunks, key=lambda c: c.chunk_index)

    def delete_chunks(self, inode_id: int, version: int):
        """Delete all chunks for a file version."""
        prefix = f"chunk:{inode_id}:{version}:"
        keys_to_delete = [key for key, _ in self._prefix_scan(prefix)]
        for key in keys_to_delete:
            self._delete(key)

    def scan_all_chunks(self) -> Iterator[Chunk]:
        """Scan all chunk records."""
        prefix = "chunk:"
        for key, value in self._prefix_scan(prefix):
            chunk_data = json.loads(value)
            yield Chunk(**chunk_data)

    # ─────────────────────────────────────────────────────────────
    # CHUNK REFERENCE COUNTING
    # ─────────────────────────────────────────────────────────────

    def increment_chunk_ref(self, chunk_id: str) -> int:
        """Increment reference count for a chunk."""
        key = f"chunk_ref:{chunk_id}"
        with self._lock:
            current = int(self._get(key) or 0)
            new_count = current + 1
            self._put(key, str(new_count))
            return new_count

    def decrement_chunk_ref(self, chunk_id: str) -> int:
        """Decrement reference count for a chunk."""
        key = f"chunk_ref:{chunk_id}"
        with self._lock:
            current = int(self._get(key) or 0)
            new_count = max(0, current - 1)
            self._put(key, str(new_count))
            return new_count

    def get_chunk_ref(self, chunk_id: str) -> int:
        """Get reference count for a chunk."""
        key = f"chunk_ref:{chunk_id}"
        data = self._get(key)
        return int(data) if data else 0

    # ─────────────────────────────────────────────────────────────
    # CHUNK SERVER REGISTRY
    # ─────────────────────────────────────────────────────────────

    def register_server(self, server: ChunkServer):
        """Register or update chunk server."""
        key = f"server:{server.server_id}"
        self._put(key, self._serialize(server))

    def get_server(self, server_id: str) -> Optional[ChunkServer]:
        """Get chunk server by ID."""
        key = f"server:{server_id}"
        data = self._get(key)
        if data:
            return self._deserialize(data, ChunkServer)
        return None

    def list_servers(self, status: str = None) -> List[ChunkServer]:
        """List all chunk servers, optionally filtered by status."""
        prefix = "server:"
        servers = []
        for key, value in self._prefix_scan(prefix):
            server = self._deserialize(value, ChunkServer)
            if status is None or server.status == status:
                servers.append(server)
        return servers

    # ─────────────────────────────────────────────────────────────
    # UPLOAD SESSION MANAGEMENT
    # ─────────────────────────────────────────────────────────────

    def put_upload_session(self, session: UploadSession):
        """Store upload session."""
        key = f"upload:{session.upload_id}"
        self._put(key, self._serialize(session))

    def get_upload_session(self, upload_id: str) -> Optional[UploadSession]:
        """Get upload session by ID."""
        key = f"upload:{upload_id}"
        data = self._get(key)
        if data:
            obj_dict = json.loads(data)
            # Reconstruct ChunkAllocation objects
            from common.models import ChunkAllocation
            obj_dict['chunks'] = [
                ChunkAllocation(**c) if isinstance(c, dict) else c
                for c in obj_dict.get('chunks', [])
            ]
            # Convert datetime strings
            for field in ['created_at', 'expires_at']:
                if obj_dict.get(field):
                    obj_dict[field] = datetime.fromisoformat(obj_dict[field])
            return UploadSession(**obj_dict)
        return None

    def delete_upload_session(self, upload_id: str):
        """Delete upload session."""
        key = f"upload:{upload_id}"
        self._delete(key)

    def list_expired_uploads(self, current_time: datetime) -> List[UploadSession]:
        """List all expired upload sessions."""
        prefix = "upload:"
        expired = []
        for key, value in self._prefix_scan(prefix):
            session = self.get_upload_session(key.split(":")[-1])
            if session and session.expires_at < current_time:
                expired.append(session)
        return expired
