package com.mcintyret.raft.core;

import com.mcintyret.raft.address.Address;
import com.mcintyret.raft.address.Addressable;
import com.mcintyret.raft.address.Peer;
import com.mcintyret.raft.elect.ElectionTimeoutGenerator;
import com.mcintyret.raft.message.MessageDispatcher;
import com.mcintyret.raft.message.MessageReceiver;
import com.mcintyret.raft.persist.PersistentState;
import com.mcintyret.raft.rpc.AppendEntriesRequest;
import com.mcintyret.raft.rpc.AppendEntriesResponse;
import com.mcintyret.raft.rpc.BaseRequest;
import com.mcintyret.raft.rpc.Header;
import com.mcintyret.raft.rpc.NewEntryRequest;
import com.mcintyret.raft.rpc.NewEntryResponse;
import com.mcintyret.raft.rpc.RaftRpcMessage;
import com.mcintyret.raft.rpc.RequestVoteRequest;
import com.mcintyret.raft.rpc.RequestVoteResponse;
import com.mcintyret.raft.rpc.RpcMessage;
import com.mcintyret.raft.rpc.RpcMessageVisitor;
import com.mcintyret.raft.state.Snapshot;
import com.mcintyret.raft.state.StateMachine;
import com.mcintyret.raft.util.Multiset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * User: tommcintyre
 * Date: 11/29/14
 */
public class Server implements RpcMessageVisitor, MessageReceiver<RpcMessage>, Addressable {

    private static final Logger LOG = LoggerFactory.getLogger(Server.class);

    // TODO: configurable?
    private static final long HEARTBEAT_TIMEOUT = 1000;

    private final Peer me;

    // Simplifying assumption for now: peers are immutable. A peer may disappear then reappear, but no NEW peers will
    // turn up.
    // TODO: remove this assumption!
    private final List<Peer> peers;

    private final int majoritySize;

    private final PersistentState persistentState;

    private final ElectionTimeoutGenerator electionTimeoutGenerator;

    private final StateMachine stateMachine;

    // All messages are processed in a single thread, simplifying the logic
    private final BlockingQueue<RpcMessage> messageQueue = new LinkedBlockingQueue<>();

    private final Multiset<Long> indicesAwaitingCommit = new Multiset<>();

    private Peer currentLeader;

    private long commitIndex;

    private long nextElectionTimeout;

    private long nextHeartbeat;

    private ServerRole currentRole;

    private Snapshot currentSnapshot;

    // candidate only
    private final Set<Peer> votes = new HashSet<>();

    // leader only
    private final long[] nextIndices;

    private final MessageDispatcher messageDispatcher;

    public Server(Peer me, List<Peer> peers,
                  PersistentState persistentState,
                  ElectionTimeoutGenerator electionTimeoutGenerator,
                  MessageDispatcher messageDispatcher,
                  StateMachine stateMachine) {
        // TODO: do I actually need this constraint? Isn't the point of election timeouts to deal with split votes?
        if (peers.size() % 2 != 0) {
            throw new IllegalArgumentException("Must be an even number of peers (and so an odd number of servers" +
                " including myself) so that a majority can be reached.");
        }
        this.me = me;
        this.peers = peers;
        this.persistentState = persistentState;
        this.electionTimeoutGenerator = electionTimeoutGenerator;
        this.stateMachine = stateMachine;
        this.messageDispatcher = messageDispatcher;

        resetElectionTimeout();
        majoritySize = 1 + (peers.size() / 2);
        nextIndices = new long[peers.size()];
    }

    private void resetHeartbeat() {
        this.nextHeartbeat = System.currentTimeMillis() + HEARTBEAT_TIMEOUT;
    }

    @Override
    public void messageReceived(RpcMessage message) {
        messageQueue.add(message);
    }

    public void run() {
        setRole(ServerRole.FOLLOWER, "start-up"); // at startup, every server is a follower
        while (true) {
            long timeout = (currentRole == ServerRole.LEADER ? nextHeartbeat :
                nextElectionTimeout) - System.currentTimeMillis();

            RpcMessage message;
            try {
                message = messageQueue.poll(timeout, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.interrupted(); // dealt with
                return;
            }

            if (message != null) {
                if (message instanceof RaftRpcMessage) {
                    checkTerm((RaftRpcMessage) message);
                }
                message.visit(this);

                updateStateMachine();
            } else {
                // timed out
                if (currentRole == ServerRole.LEADER) {
                    sendAppendEntriesRequests(false);
                } else {
                    startElection();
                }
            }
        }
    }

    private void updateStateMachine() {
        long lastAppliedIndex = stateMachine.getLastAppliedIndex();
        if (commitIndex > lastAppliedIndex) {
            List<LogEntry> toApply = persistentState.getLogEntriesBetween(lastAppliedIndex + 1, commitIndex + 1);
            stateMachine.applyAll(toApply);

            if (currentSnapshot != stateMachine.getLatestSnapshot()) {
                currentSnapshot = stateMachine.getLatestSnapshot();
                persistentState.deleteLogsUpToAndIncluding(currentSnapshot);
            }
        }
    }

    // If someone else's term > mine, then I should become a follower and update my term
    private void checkTerm(RaftRpcMessage message) {
        if (message.getTerm() > persistentState.getCurrentTerm()) {
            setRole(ServerRole.FOLLOWER, "found another server with a higher term");
            updateTerm(message.getTerm(), "to match higher term seen on other server");
        }
    }

    private void startElection() {
        setRole(ServerRole.CANDIDATE, "election timeout");
        votes.clear(); // reset

        // vote for myself
        votes.add(me);
        persistentState.setVotedFor(me);

        long newTerm = persistentState.getCurrentTerm() + 1;
        updateTerm(newTerm, "starting an election");

        // TODO: is this correct? Or should it be last COMMITTED log entry?
        LogEntry lastLogEntry = persistentState.getLastLogEntry();

        sendToAll(recipient -> {
            return new RequestVoteRequest(
                headerFor(recipient),
                newTerm,
                lastLogEntry.getIndex(),
                lastLogEntry.getTerm());

        });

        resetElectionTimeout();
    }

    private void updateTerm(long newTerm, String reason) {
        persistentState.setCurrentTerm(newTerm);
        LOG.info("Updating term: {}, reason: {}", newTerm, reason);
    }

    @Override
    public void onAppendEntriesRequest(AppendEntriesRequest aeReq) {
        currentLeader = (Peer) aeReq.getHeader().getSource();
        long currentTerm = persistentState.getCurrentTerm();

        boolean success = false;

        if (currentTerm <= aeReq.getTerm()) {

            resetElectionTimeout();
            // probably already was a Follower, but may have been a candidate
            setRole(ServerRole.FOLLOWER, "received AppendEntriesRequest from peer with higher term");

            LogEntry lastLogEntry = persistentState.getLastLogEntry();

            List<LogEntry> entries = aeReq.getEntries();
            if (lastLogEntry.getTerm() == aeReq.getPrevLogTerm() &&
                lastLogEntry.getIndex() == aeReq.getPrevLogIndex()) {

                // Success!
                // only bother touching the persistent state if there are actual new entries, ie not a heartbeat
                if (!entries.isEmpty()) {
                    persistentState.deleteConflictingAndAppend(entries);
                    lastLogEntry = entries.get(entries.size() - 1);
                }
                success = true;
            }


            if (aeReq.getLeaderCommit() > commitIndex) {
                commitIndex = Math.min(aeReq.getLeaderCommit(), lastLogEntry.getIndex());
            }
        }

        AppendEntriesResponse response = new AppendEntriesResponse(headerFor(aeReq), currentTerm, success);
        messageDispatcher.sendResponse(response);
    }

    private void resetElectionTimeout() {
        nextElectionTimeout = electionTimeoutGenerator.nextElectionTimeout();
    }

    @Override
    public void onAppendEntriesResponse(AppendEntriesResponse aeResp) {
        // This may be the case if this response told us that we should be a follower now
        if (currentRole != ServerRole.LEADER) {
            return;
        }

        AppendEntriesRequest request = aeResp.getRequest();
        if (request == null) {
//            throw new IllegalStateException("Could not find AppendEntriesRequest corresponding to response with id " + aeResp.getRequestId());
            return; // Presumably wasn't us who sent this request and we have received the response in error
        }

        int peerIndex = getIndexForPeer((Peer) aeResp.getHeader().getSource());
        if (aeResp.isSuccess()) {
            List<LogEntry> entries = request.getEntries();
            if (!entries.isEmpty()) {
                // not a heartbeat - actually update state
                nextIndices[peerIndex] = entries.get(entries.size() - 1).getIndex() + 1;

                // This response could meant that a majority of servers have seen that log entry - they have been committed.
                entries.forEach(entry -> {
                    long index = entry.getIndex();
                    if (index > commitIndex && indicesAwaitingCommit.add(index) >= majoritySize) {
                        LOG.info("Log index={} has been persisted by the majority and is COMMITTED", index);
                        commitIndex = index;
                        indicesAwaitingCommit.clear(index); // clean-up - not interested any more
                    }
                });
            }
        } else {
            nextIndices[peerIndex]--; // TODO: smarter algorithm here?
        }
    }

    @Override
    public void onRequestVoteRequest(RequestVoteRequest rvReq) {
        long currentTerm = persistentState.getCurrentTerm();
        boolean voteGranted = false;

        if (rvReq.getTerm() >= currentTerm) {
            Peer votedFor = persistentState.getVotedFor();
            // if we haven't voted for someone else already this term...
            Peer candidate = (Peer) rvReq.getHeader().getSource();
            if ((votedFor == null || votedFor.equals(candidate))) {
                // ...and this candidate is at lease as up-to-date as we are
                LogEntry lastLogEntry = persistentState.getLastLogEntry();
                if (rvReq.getLastLogTerm() > lastLogEntry.getTerm() ||
                    (rvReq.getLastLogTerm() == lastLogEntry.getTerm() && rvReq.getLastLogIndex() >= lastLogEntry.getIndex())) {

                    LOG.info("Voting for candidate {}", candidate);
                    voteGranted = true;
                    persistentState.setVotedFor(candidate);
                }
            }
        }

        RequestVoteResponse response = new RequestVoteResponse(headerFor(rvReq), currentTerm, voteGranted);
        messageDispatcher.sendResponse(response);
    }


    @Override
    public void onRequestVoteResponse(RequestVoteResponse rvResp) {
        if (currentRole == ServerRole.CANDIDATE && rvResp.isVoteGranted()) {
            votes.add((Peer) rvResp.getHeader().getSource());

            if (votes.size() >= majoritySize) {

                // I've won! (as least as far as I'm concerned...
                becomeLeader();
            }
        }
    }

    private void becomeLeader() {
        setRole(ServerRole.LEADER, "won the election");
        currentLeader = me;

        // TODO: Again, should this be the last COMMITTED index?
        LogEntry lastLogEntry = persistentState.getLastLogEntry();
        Arrays.fill(nextIndices, lastLogEntry.getIndex() + 1);

        sendAppendEntriesRequests(true);
    }

    @Override
    public void onNewEntryRequest(NewEntryRequest neReq) {
        NewEntryResponse response;
        Header header = headerFor(neReq);
        Peer redirect = null;

        if (currentRole == ServerRole.LEADER) {
            LogEntry lastEntry = persistentState.getLastLogEntry();
            LogEntry newEntry = new LogEntry(persistentState.getCurrentTerm(), lastEntry.getIndex() + 1, neReq.getData());

            persistentState.appendLogEntry(newEntry);
            indicesAwaitingCommit.add(newEntry.getIndex()); // I, the leader, count as one of the replicas
            LOG.info("Applied new log entry, index={}", newEntry.getIndex());

        } else {
            // redirect to the leader
            LOG.info("Redirected new entry request to current leader, id={}", currentLeader);
            redirect = currentLeader;
        }
        response = new NewEntryResponse(header, redirect);

        messageDispatcher.sendResponse(response);
    }

    private void sendAppendEntriesRequests(boolean heartbeat) {
        if (currentRole != ServerRole.LEADER) {
            throw new IllegalStateException("Only the leader should send AppendEntriesRequests");
        }

        LogEntry lastLogEntry = persistentState.getLastLogEntry();

        peers.forEach(recipient -> {
            int peerIndex = getIndexForPeer(recipient);
            final long peerNextIndex = nextIndices[peerIndex];
            final long peerPreviousIndex = peerNextIndex - 1;

            IndexedAndTermed previousForPeer = (currentSnapshot != null && currentSnapshot.getIndex() == peerPreviousIndex) ?
                currentSnapshot :
                persistentState.getLogEntry(peerPreviousIndex);

            List<LogEntry> logsToSend;
            if (heartbeat) {
                logsToSend = Collections.emptyList();
            } else {
                // Everything from the last-seen to now. If this turns out to be empty this is essentially a heartbeat.

                // Go to this extra trouble so that we only hit PersistentState (ie Disk) when we need to
                long toIndex = lastLogEntry.getIndex() + 1;
                if (peerNextIndex == toIndex) {
                    logsToSend = Collections.emptyList();
                } else {
                    logsToSend = persistentState.getLogEntriesBetween(peerNextIndex, toIndex);
                }
            }

            if (logsToSend.isEmpty()) {
                LOG.debug("Sending heartbeat to peer id={}", recipient);
            } else {
                LOG.info("Sending AppendEntriesRequest with {} logs to peer id={}", logsToSend.size(), recipient);
            }

            AppendEntriesRequest request = new AppendEntriesRequest(
                headerFor(recipient),
                persistentState.getCurrentTerm(),
                previousForPeer.getIndex(),
                previousForPeer.getTerm(),
                logsToSend,
                commitIndex
            );

            messageDispatcher.sendRequest(request);
        });

        resetHeartbeat();
    }

    private void sendToAll(Function<Peer, BaseRequest> func) {
        peers.forEach(recipient -> messageDispatcher.sendRequest(func.apply(recipient)));
    }

    private int getIndexForPeer(Peer peer) {
        return peers.indexOf(peer);
    }

    private void setRole(ServerRole newRole, String reason) {
        if (currentRole != newRole) {
            LOG.info("Changing from role {} to role: {}, reason: {}", currentRole, newRole, reason);
            this.currentRole = newRole;
        }
    }

    @Override
    public Address getAddress() {
        return me;
    }
}
