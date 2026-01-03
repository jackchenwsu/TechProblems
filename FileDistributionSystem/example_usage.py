#!/usr/bin/env python3
"""Example usage of the distributed file system."""

import os
import sys
import tempfile

# Add project root to path
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from client.dfs_client import DFSClient
from metadata_service.metadata_server import MetadataService
from chunk_server.chunk_server import ChunkServer


def create_test_file(path: str, size_mb: int = 1) -> str:
    """Create a test file with random data."""
    with open(path, 'wb') as f:
        # Write random-ish data
        for _ in range(size_mb):
            f.write(os.urandom(1024 * 1024))
    return path


def main():
    """Run example usage demonstration."""
    print("=" * 60)
    print("Distributed File System - Example Usage")
    print("=" * 60)

    # Create temporary directories for testing
    with tempfile.TemporaryDirectory() as temp_dir:
        metadata_dir = os.path.join(temp_dir, "metadata")
        chunk_dir = os.path.join(temp_dir, "chunks")
        test_files_dir = os.path.join(temp_dir, "test_files")

        os.makedirs(metadata_dir)
        os.makedirs(chunk_dir)
        os.makedirs(test_files_dir)

        # Initialize services
        print("\n[1] Initializing services...")

        # Start metadata service
        metadata_service = MetadataService(
            node_id="node-1",
            peers=[],
            db_path=metadata_dir,
        )
        metadata_service.start()
        print("    - Metadata service started")

        # Start chunk servers
        chunk_servers = []
        for i in range(3):
            server = ChunkServer(
                server_id=f"server-{i}",
                address=f"localhost:500{i}",
                data_dir=os.path.join(chunk_dir, f"server-{i}"),
                capacity=1024 * 1024 * 1024,  # 1 GB
            )
            server.start()
            chunk_servers.append(server)

            # Register with metadata service
            metadata_service.handle_heartbeat(
                server_id=f"server-{i}",
                server_info={
                    "address": f"localhost:500{i}",
                    "capacity": 1024 * 1024 * 1024,
                    "used": 0,
                    "zone": f"zone-{i % 2}",
                },
            )
        print(f"    - {len(chunk_servers)} chunk servers started")

        # Initialize client
        client = DFSClient(metadata_addresses=["localhost:9000"])
        client.set_metadata_service(metadata_service)
        for i, server in enumerate(chunk_servers):
            client.set_chunk_server(f"server-{i}", server)
        print("    - Client initialized")

        # ─────────────────────────────────────────────────────────
        # DIRECTORY OPERATIONS
        # ─────────────────────────────────────────────────────────
        print("\n[2] Directory Operations")

        # Create directories
        print("    Creating directories...")
        client.mkdir("/data")
        client.mkdir("/data/projects")
        client.mkdir("/data/backups")
        print("    - Created /data, /data/projects, /data/backups")

        # List directory
        print("\n    Listing /data:")
        files = client.ls("/data")
        for f in files:
            print(f"        {f.type:10} {f.size:10} {f.name}")

        # ─────────────────────────────────────────────────────────
        # FILE UPLOAD
        # ─────────────────────────────────────────────────────────
        print("\n[3] File Upload")

        # Create a test file
        test_file = os.path.join(test_files_dir, "test_file.bin")
        create_test_file(test_file, size_mb=2)
        print(f"    Created test file: {test_file} (2 MB)")

        # Upload file
        print("\n    Uploading file...")

        def progress_callback(percent):
            bar_width = 40
            filled = int(bar_width * percent / 100)
            bar = "=" * filled + "-" * (bar_width - filled)
            print(f"\r    [{bar}] {percent:.1f}%", end="", flush=True)

        client.put(test_file, "/data/projects/test_file.bin", progress_callback)
        print("\n    - Upload completed!")

        # List directory after upload
        print("\n    Listing /data/projects:")
        files = client.ls("/data/projects")
        for f in files:
            print(f"        {f.type:10} {f.size:10} {f.name}")

        # ─────────────────────────────────────────────────────────
        # FILE INFO
        # ─────────────────────────────────────────────────────────
        print("\n[4] File Information")

        # Check if file exists
        exists = client.exists("/data/projects/test_file.bin")
        print(f"    File exists: {exists}")

        # Get file info
        info = client.stat("/data/projects/test_file.bin")
        print(f"    File info:")
        print(f"        Name: {info.name}")
        print(f"        Type: {info.type}")
        print(f"        Size: {info.size} bytes")
        print(f"        Version: {info.version}")

        # ─────────────────────────────────────────────────────────
        # FILE DOWNLOAD
        # ─────────────────────────────────────────────────────────
        print("\n[5] File Download")

        download_path = os.path.join(test_files_dir, "downloaded_file.bin")
        print(f"    Downloading to: {download_path}")

        client.get("/data/projects/test_file.bin", download_path, progress_callback)
        print("\n    - Download completed!")

        # Verify downloaded file
        original_size = os.path.getsize(test_file)
        downloaded_size = os.path.getsize(download_path)
        print(f"    Original size: {original_size} bytes")
        print(f"    Downloaded size: {downloaded_size} bytes")
        print(f"    Match: {original_size == downloaded_size}")

        # ─────────────────────────────────────────────────────────
        # DELETE OPERATIONS
        # ─────────────────────────────────────────────────────────
        print("\n[6] Delete Operations")

        # Delete file
        client.rm("/data/projects/test_file.bin")
        print("    - Deleted /data/projects/test_file.bin")

        # List to confirm
        files = client.ls("/data/projects")
        print(f"    Files in /data/projects: {len(files)}")

        # Delete directory
        client.rmdir("/data/projects")
        print("    - Deleted /data/projects")

        # Delete recursively
        client.mkdir("/data/temp")
        client.mkdir("/data/temp/subdir")
        client.rmdir("/data/temp", recursive=True)
        print("    - Deleted /data/temp recursively")

        # ─────────────────────────────────────────────────────────
        # CLEANUP
        # ─────────────────────────────────────────────────────────
        print("\n[7] Cleanup")

        # Stop services
        metadata_service.stop()
        for server in chunk_servers:
            server.stop()
        print("    - All services stopped")

    print("\n" + "=" * 60)
    print("Example completed successfully!")
    print("=" * 60)


if __name__ == "__main__":
    main()
