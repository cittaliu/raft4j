package com.mcintyret.raft;

import com.mcintyret.raft.core.Server;
import com.mcintyret.raft.elect.RandomElectionTimeoutGenerator;
import com.mcintyret.raft.message.MessageDispatcher;
import com.mcintyret.raft.persist.InMemoryPersistentState;
import com.mcintyret.raft.rpc.RpcMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * User: tommcintyre
 * Date: 11/29/14
 */
public class Runner {

    public static void main(String[] args) {
        int size = 5;

        final List<Server> servers = new ArrayList<>(size);

        MessageDispatcher messageDispatcher = (recipientId, message) -> {
            servers.get(recipientId).messageReceived(message);
        };

        List<Integer> allIds = makeAllIds(size);

        for (int i = 0; i < size; i++) {
            List<Integer> peers = new ArrayList<>(allIds);
            peers.remove((Integer) i);
            servers.add(new Server(i, peers,
                new InMemoryPersistentState(),
                new RandomElectionTimeoutGenerator(100L, 200L),
                messageDispatcher));
        }

        // Start them all!
        servers.forEach(server -> new Thread(server::run, "Server: " + server.getMyId()).start());
    }

    private static List<Integer> makeAllIds(int size) {
        List<Integer> tmp = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            tmp.add(size);
        }
        return Collections.unmodifiableList(tmp);
    }

}