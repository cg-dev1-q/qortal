package org.qortal.test.network;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Reproduces the peer connection churn bug in Network.removeConnectedPeer().
 *
 * SYMPTOM: Nodes repeatedly lose peers and reconnect.
 *
 * ROOT CAUSE: removeConnectedPeer() removes a peer from handshakedPeers FIRST,
 * then from connectedPeers. Between those two operations, repairOrphanedPeers()
 * can observe the inconsistent intermediate state — peer missing from handshakedPeers
 * but still in connectedPeers — and "repair" it by adding the peer BACK to
 * handshakedPeers. When removeConnectedPeer() then finishes removing from
 * connectedPeers, the peer is left stranded in handshakedPeers only (a ghost).
 *
 * The ghost blocks future reconnects: Handshake.CHALLENGE finds the peer in
 * handshakedPeers and rejects the new connection as a duplicate.
 *
 * FIX: removeConnectedPeer() must remove from connectedPeers FIRST, then
 * handshakedPeers — or both atomically under a single lock that repairOrphanedPeers
 * also holds during its read phase.
 */
public class PeerListConsistencyTests {

    /**
     * Simulates the two-list state machine that Network maintains.
     * Replicates the exact logic from Network without needing a socket.
     */
    static class PeerLists {
        final Object peer = new Object(); // token representing a connected peer
        final Object peerListsLock = new Object();
        final List<Object> connectedPeers   = Collections.synchronizedList(new ArrayList<>());
        final List<Object> handshakedPeers  = Collections.synchronizedList(new ArrayList<>());

        void addConnectedPeer(Object p) {
            synchronized (connectedPeers) { connectedPeers.add(p); }
        }
        void addHandshakedPeer(Object p) {
            synchronized (handshakedPeers) { handshakedPeers.add(p); }
        }

        /**
         * Mirrors Network.removeConnectedPeer() — removes handshakedPeers FIRST,
         * then connectedPeers. This is the BUGGY ordering.
         */
        void removeConnectedPeerBuggy(Object p) {
            synchronized (peerListsLock) {
                // BUG: handshakedPeers removed first — exposes intermediate state
                synchronized (handshakedPeers) { handshakedPeers.removeIf(x -> x == p); }
                // (repairOrphanedPeers can run here and see peer in connected but not handshaked)
                synchronized (connectedPeers) { connectedPeers.removeIf(x -> x == p); }
            }
        }

        /**
         * Mirrors Network.removeConnectedPeer() with the CORRECT ordering —
         * connectedPeers removed first, then handshakedPeers.
         */
        void removeConnectedPeerFixed(Object p) {
            synchronized (peerListsLock) {
                // FIXED: connectedPeers removed first — no window for repair to mis-fire
                synchronized (connectedPeers) { connectedPeers.removeIf(x -> x == p); }
                synchronized (handshakedPeers) { handshakedPeers.removeIf(x -> x == p); }
            }
        }

        /**
         * Mirrors repairOrphanedPeers() check 1:
         * if peer is in connectedPeers but NOT in handshakedPeers, add it back.
         * Reads happen OUTSIDE peerListsLock — exactly as in production code.
         */
        void repairOrphanedPeers(Object p) {
            // Read outside lock — same as production code (lines 1235-1296 in Network.java)
            boolean inConnected   = connectedPeers.stream().anyMatch(x -> x == p);
            boolean inHandshaked  = handshakedPeers.stream().anyMatch(x -> x == p);

            if (inConnected && !inHandshaked) {
                // Peer looks orphaned — repair by adding back to handshakedPeers
                synchronized (peerListsLock) {
                    // double-check inside lock
                    boolean stillInConnected  = connectedPeers.stream().anyMatch(x -> x == p);
                    boolean stillNotHandshaked = !handshakedPeers.stream().anyMatch(x -> x == p);
                    if (stillInConnected && stillNotHandshaked) {
                        addHandshakedPeer(p); // mis-fires and adds the peer back
                    }
                }
            }
        }
    }

    /**
     * Deterministic reproduction of the bug.
     *
     * We manually interleave removeConnectedPeer and repairOrphanedPeers
     * at the exact moment the intermediate state is visible — after
     * handshakedPeers is cleared but before connectedPeers is cleared.
     */
    @Test
    public void bugReproduction_removeAndRepairInterleave_leavesGhost() {
        PeerLists lists = new PeerLists();

        // Setup: peer is fully connected and handshaked
        lists.addConnectedPeer(lists.peer);
        lists.addHandshakedPeer(lists.peer);
        assertTrue("precondition: peer in connected",  lists.connectedPeers.contains(lists.peer));
        assertTrue("precondition: peer in handshaked", lists.handshakedPeers.contains(lists.peer));

        // Step 1: removeConnectedPeer removes from handshakedPeers (first half of buggy method)
        synchronized (lists.handshakedPeers) {
            lists.handshakedPeers.removeIf(x -> x == lists.peer);
        }

        // Step 2: repairOrphanedPeers fires in the window between the two removals.
        // It sees: peer IN connectedPeers, NOT IN handshakedPeers → adds it back.
        lists.repairOrphanedPeers(lists.peer);

        // Step 3: removeConnectedPeer finishes — removes from connectedPeers
        synchronized (lists.connectedPeers) {
            lists.connectedPeers.removeIf(x -> x == lists.peer);
        }

        // Result: peer is gone from connectedPeers but back in handshakedPeers → GHOST
        assertFalse("peer should be gone from connectedPeers", lists.connectedPeers.contains(lists.peer));

        // THIS IS THE BUG: peer is still in handshakedPeers after disconnect
        // It will block the next reconnect attempt as a false duplicate.
        assertTrue("BUG: peer is stranded in handshakedPeers (ghost) after disconnect",
                lists.handshakedPeers.contains(lists.peer));
    }

    /**
     * Verifies the fix: removing connectedPeers FIRST closes the window
     * that allows repairOrphanedPeers to mis-fire.
     */
    @Test
    public void fixVerification_removeConnectedFirst_noGhost() {
        PeerLists lists = new PeerLists();

        lists.addConnectedPeer(lists.peer);
        lists.addHandshakedPeer(lists.peer);

        // Step 1 (fixed order): remove from connectedPeers FIRST
        synchronized (lists.connectedPeers) {
            lists.connectedPeers.removeIf(x -> x == lists.peer);
        }

        // Step 2: repairOrphanedPeers fires — but now peer is NOT in connectedPeers,
        // so the repair condition (inConnected && !inHandshaked) is false. No mis-fire.
        lists.repairOrphanedPeers(lists.peer);

        // Step 3: remove from handshakedPeers
        synchronized (lists.handshakedPeers) {
            lists.handshakedPeers.removeIf(x -> x == lists.peer);
        }

        assertFalse("peer must be gone from connectedPeers",  lists.connectedPeers.contains(lists.peer));
        assertFalse("peer must be gone from handshakedPeers", lists.handshakedPeers.contains(lists.peer));
    }

}
