"""Raft consensus implementation for metadata replication."""

import random
import threading
import time
from typing import List, Dict, Optional
from datetime import datetime

from common.constants import (
    NodeRole,
    ELECTION_TIMEOUT_MIN,
    ELECTION_TIMEOUT_MAX,
)
from common.models import (
    LogEntry,
    Command,
    VoteRequest,
    VoteResponse,
    AppendEntriesRequest,
    AppendEntriesResponse,
)


class NotLeaderError(Exception):
    """Raised when operation requires leader but node is not leader."""

    def __init__(self, leader_id: Optional[str] = None):
        self.leader_id = leader_id
        super().__init__(f"Not leader. Current leader: {leader_id}")


class RaftNode:
    """Raft consensus implementation for metadata replication."""

    def __init__(self, node_id: str, peers: List[str], storage):
        self.node_id = node_id
        self.peers = peers
        self.storage = storage

        # Persistent state (should be persisted to disk in production)
        self.current_term = 0
        self.voted_for: Optional[str] = None
        self.log: List[LogEntry] = []

        # Volatile state
        self.role = NodeRole.FOLLOWER
        self.leader_id: Optional[str] = None
        self.commit_index = -1
        self.last_applied = -1

        # Leader state (reinitialized after election)
        self.next_index: Dict[str, int] = {}
        self.match_index: Dict[str, int] = {}

        # Timing
        self.last_heartbeat = time.time()
        self.election_timeout = self._random_election_timeout()

        # Locks
        self._lock = threading.RLock()
        self._apply_lock = threading.Lock()

        # Start background threads
        self._running = True
        self._election_thread = threading.Thread(
            target=self._run_election_timer, daemon=True
        )
        self._heartbeat_thread = threading.Thread(
            target=self._run_heartbeat, daemon=True
        )

    def start(self):
        """Start the Raft node."""
        # If no peers, become leader immediately (single-node cluster)
        if not self.peers:
            self._become_leader()

        self._election_thread.start()
        self._heartbeat_thread.start()

    def stop(self):
        """Stop the Raft node."""
        self._running = False

    def _random_election_timeout(self) -> float:
        """Get random election timeout in seconds."""
        return random.uniform(
            ELECTION_TIMEOUT_MIN / 1000.0,
            ELECTION_TIMEOUT_MAX / 1000.0
        )

    # ─────────────────────────────────────────────────────────────
    # LEADER ELECTION
    # ─────────────────────────────────────────────────────────────

    def _run_election_timer(self):
        """Background thread to check election timeout."""
        while self._running:
            time.sleep(0.01)  # Check every 10ms

            with self._lock:
                if self.role == NodeRole.LEADER:
                    continue

                elapsed = time.time() - self.last_heartbeat
                if elapsed > self.election_timeout:
                    self._start_election()

    def _start_election(self):
        """Start a new election."""
        with self._lock:
            self.current_term += 1
            self.role = NodeRole.CANDIDATE
            self.voted_for = self.node_id
            self.election_timeout = self._random_election_timeout()

            votes_received = 1  # Vote for self

            # Get last log info
            last_log_index = len(self.log) - 1
            last_log_term = self.log[last_log_index].term if self.log else 0

            # Request votes from all peers
            for peer in self.peers:
                response = self._send_request_vote(
                    peer,
                    VoteRequest(
                        term=self.current_term,
                        candidate_id=self.node_id,
                        last_log_index=last_log_index,
                        last_log_term=last_log_term,
                    )
                )

                if response and response.vote_granted:
                    votes_received += 1

                # Check if discovered higher term
                if response and response.term > self.current_term:
                    self.current_term = response.term
                    self.role = NodeRole.FOLLOWER
                    self.voted_for = None
                    return

            # Check if won election
            majority = (len(self.peers) + 1) // 2 + 1
            if votes_received >= majority:
                self._become_leader()
            else:
                self.role = NodeRole.FOLLOWER

    def _send_request_vote(self, peer: str, request: VoteRequest) -> Optional[VoteResponse]:
        """Send RequestVote RPC to peer.

        In production, this would be an actual RPC call.
        """
        # Simulated RPC - in production, use gRPC or similar
        # For now, return a simulated response
        return VoteResponse(term=self.current_term, vote_granted=True)

    def handle_request_vote(self, request: VoteRequest) -> VoteResponse:
        """Handle incoming RequestVote RPC."""
        with self._lock:
            # Update term if needed
            if request.term > self.current_term:
                self.current_term = request.term
                self.role = NodeRole.FOLLOWER
                self.voted_for = None

            # Deny if term is old
            if request.term < self.current_term:
                return VoteResponse(term=self.current_term, vote_granted=False)

            # Check if already voted for someone else
            if self.voted_for is not None and self.voted_for != request.candidate_id:
                return VoteResponse(term=self.current_term, vote_granted=False)

            # Check if candidate's log is up-to-date
            my_last_index = len(self.log) - 1
            my_last_term = self.log[my_last_index].term if self.log else 0

            log_ok = (
                request.last_log_term > my_last_term or
                (request.last_log_term == my_last_term and
                 request.last_log_index >= my_last_index)
            )

            if log_ok:
                self.voted_for = request.candidate_id
                self.last_heartbeat = time.time()
                return VoteResponse(term=self.current_term, vote_granted=True)

            return VoteResponse(term=self.current_term, vote_granted=False)

    def _become_leader(self):
        """Transition to leader state."""
        self.role = NodeRole.LEADER
        self.leader_id = self.node_id

        # Initialize leader state
        for peer in self.peers:
            self.next_index[peer] = len(self.log)
            self.match_index[peer] = -1

        print(f"Node {self.node_id} became leader for term {self.current_term}")

    # ─────────────────────────────────────────────────────────────
    # LOG REPLICATION
    # ─────────────────────────────────────────────────────────────

    def _run_heartbeat(self):
        """Background thread to send heartbeats as leader."""
        while self._running:
            time.sleep(0.05)  # 50ms heartbeat interval

            with self._lock:
                if self.role != NodeRole.LEADER:
                    continue

                self._send_heartbeats()

    def _send_heartbeats(self):
        """Send heartbeats/AppendEntries to all peers."""
        for peer in self.peers:
            self._replicate_to_peer(peer)

    def propose(self, command: Command) -> bool:
        """Propose a command to be replicated.

        Only the leader can propose commands.
        """
        with self._lock:
            if self.role != NodeRole.LEADER:
                raise NotLeaderError(self.leader_id)

            # Append to local log
            entry = LogEntry(
                term=self.current_term,
                command=command,
                index=len(self.log)
            )
            self.log.append(entry)

            # Replicate to followers
            return self._replicate_to_majority()

    def _replicate_to_majority(self) -> bool:
        """Replicate current log to majority of nodes."""
        success_count = 1  # Self

        for peer in self.peers:
            if self._replicate_to_peer(peer):
                success_count += 1

        # For single-node cluster, we already have majority
        if not self.peers:
            new_commit = len(self.log) - 1
            if new_commit > self.commit_index:
                self.commit_index = new_commit
                self._apply_committed_entries()
            return True

        # Check if majority
        majority = (len(self.peers) + 1) // 2 + 1
        if success_count >= majority:
            # Update commit index
            new_commit = len(self.log) - 1
            if new_commit > self.commit_index:
                self.commit_index = new_commit
                self._apply_committed_entries()
            return True

        return False

    def _replicate_to_peer(self, peer: str) -> bool:
        """Send AppendEntries to a single peer."""
        prev_log_index = self.next_index.get(peer, 0) - 1
        prev_log_term = 0
        if prev_log_index >= 0 and prev_log_index < len(self.log):
            prev_log_term = self.log[prev_log_index].term

        entries = self.log[self.next_index.get(peer, 0):]

        response = self._send_append_entries(
            peer,
            AppendEntriesRequest(
                term=self.current_term,
                leader_id=self.node_id,
                prev_log_index=prev_log_index,
                prev_log_term=prev_log_term,
                entries=entries,
                leader_commit=self.commit_index,
            )
        )

        if response is None:
            return False

        if response.success:
            self.next_index[peer] = len(self.log)
            self.match_index[peer] = len(self.log) - 1
            return True
        else:
            # Decrement next_index and retry
            self.next_index[peer] = max(0, self.next_index.get(peer, 0) - 1)
            return False

    def _send_append_entries(self, peer: str, request: AppendEntriesRequest) -> Optional[AppendEntriesResponse]:
        """Send AppendEntries RPC to peer.

        In production, this would be an actual RPC call.
        """
        # Simulated RPC - in production, use gRPC or similar
        return AppendEntriesResponse(term=self.current_term, success=True)

    def handle_append_entries(self, request: AppendEntriesRequest) -> AppendEntriesResponse:
        """Handle incoming AppendEntries RPC."""
        with self._lock:
            self.last_heartbeat = time.time()

            # Update term if needed
            if request.term > self.current_term:
                self.current_term = request.term
                self.role = NodeRole.FOLLOWER
                self.voted_for = None

            # Reject if term is old
            if request.term < self.current_term:
                return AppendEntriesResponse(term=self.current_term, success=False)

            self.leader_id = request.leader_id
            self.role = NodeRole.FOLLOWER

            # Check if log matches at prev_log_index
            if request.prev_log_index >= 0:
                if request.prev_log_index >= len(self.log):
                    return AppendEntriesResponse(term=self.current_term, success=False)
                if self.log[request.prev_log_index].term != request.prev_log_term:
                    # Delete conflicting entries
                    self.log = self.log[:request.prev_log_index]
                    return AppendEntriesResponse(term=self.current_term, success=False)

            # Append new entries
            for i, entry in enumerate(request.entries):
                index = request.prev_log_index + 1 + i
                if index < len(self.log):
                    if self.log[index].term != entry.term:
                        self.log = self.log[:index]
                        self.log.append(entry)
                else:
                    self.log.append(entry)

            # Update commit index
            if request.leader_commit > self.commit_index:
                self.commit_index = min(request.leader_commit, len(self.log) - 1)
                self._apply_committed_entries()

            return AppendEntriesResponse(term=self.current_term, success=True)

    def _apply_committed_entries(self):
        """Apply committed log entries to state machine."""
        with self._apply_lock:
            while self.last_applied < self.commit_index:
                self.last_applied += 1
                if self.last_applied < len(self.log):
                    entry = self.log[self.last_applied]
                    self._apply_command(entry.command)

    def _apply_command(self, command: Command):
        """Apply a command to the metadata storage."""
        if command.type == "CREATE_INODE":
            self.storage.put_inode(command.inode)
        elif command.type == "DELETE_INODE":
            self.storage.delete_inode(command.inode_id)
        elif command.type == "ADD_CHILD":
            self.storage.add_child(command.parent_id, command.name, command.child_id)
        elif command.type == "REMOVE_CHILD":
            self.storage.remove_child(command.parent_id, command.name)
        elif command.type == "PUT_CHUNK":
            self.storage.put_chunk(command.chunk)

    # ─────────────────────────────────────────────────────────────
    # LINEARIZABLE READS
    # ─────────────────────────────────────────────────────────────

    def read_index(self) -> int:
        """Get read index for linearizable reads.

        This ensures the read sees all committed writes.
        """
        with self._lock:
            if self.role != NodeRole.LEADER:
                raise NotLeaderError(self.leader_id)

            read_index = self.commit_index

            # For single-node cluster, no need to confirm leadership
            if not self.peers:
                return max(0, read_index)

            # Confirm leadership with heartbeat round
            acks = 1  # Self
            for peer in self.peers:
                response = self._send_append_entries(
                    peer,
                    AppendEntriesRequest(
                        term=self.current_term,
                        leader_id=self.node_id,
                        prev_log_index=len(self.log) - 1,
                        prev_log_term=self.log[-1].term if self.log else 0,
                        entries=[],
                        leader_commit=self.commit_index,
                    )
                )
                if response and response.success:
                    acks += 1

            majority = (len(self.peers) + 1) // 2 + 1
            if acks < majority:
                raise NotLeaderError(None)

            # Wait for state machine to catch up
            while self.last_applied < read_index:
                time.sleep(0.001)

            return read_index

    def ensure_leader(self):
        """Ensure this node is the leader."""
        if self.role != NodeRole.LEADER:
            raise NotLeaderError(self.leader_id)

    def is_leader(self) -> bool:
        """Check if this node is the leader."""
        return self.role == NodeRole.LEADER

    def get_leader_id(self) -> Optional[str]:
        """Get the current leader ID."""
        return self.leader_id
