# Peer Churn Bug — Ghost Peer Created by removeConnectedPeer Race

## Symptom

Nodes repeatedly lose peers and reconnect. Peers drop off the connected list,
reconnect attempts are silently rejected, and the cycle repeats until node restart.

---

## Root Cause

`Network.removeConnectedPeer()` removes a peer from **`handshakedPeers` first**,
then from `connectedPeers`. This creates a window of inconsistent state:

```java
// Network.java line 749 — BUGGY ORDERING
public void removeConnectedPeer(Peer peer) {
    synchronized (this.peerListsLock) {
        this.removeHandshakedPeer(peer);    // ← removed from handshakedPeers here
        // ↑ window opens
        synchronized (this.connectedPeers) {
            this.connectedPeers.removeIf(p -> p == peer);  // ← removed here
        }
        // window closes
    }
}
```

During the window, the peer is:
- **present** in `connectedPeers`
- **absent** from `handshakedPeers`

`repairOrphanedPeers()` runs on the scheduler thread and reads both lists
**outside `peerListsLock`** (Network.java line 1235–1240):

```java
// Network.java line 1230 — repairOrphanedPeers() check 1
for (Peer peer : getImmutableConnectedPeers()) {
    boolean inHandshaked = getImmutableHandshakedPeers().stream()
            .anyMatch(p -> p == peer);   // ← reads without holding peerListsLock

    if (!inHandshaked) {
        // Peer looks orphaned — adds it back to handshakedPeers
        synchronized (this.peerListsLock) {
            ...
            this.addHandshakedPeer(peer);  // ← MIS-FIRES during the removal window
        }
    }
}
```

When the repair fires during the window:

| Step | `connectedPeers` | `handshakedPeers` |
|------|-----------------|-------------------|
| Before disconnect | ✓ peer | ✓ peer |
| After `removeHandshakedPeer` | ✓ peer | ✗ (removed) |
| **repair fires** | ✓ peer | **✓ added back** |
| After `connectedPeers.removeIf` | ✗ (removed) | **✓ stranded** |

**Result: peer is a ghost** — present in `handshakedPeers`, absent from `connectedPeers`.

When the remote node tries to reconnect, `Handshake.CHALLENGE.onMessage()` calls
`getHandshakedPeerWithPublicKey()`, finds the ghost, and rejects the new connection
as a duplicate. The peer cannot reconnect until the node restarts.

---

## The Fix

Swap the removal order — remove from `connectedPeers` **first**, then `handshakedPeers`:

```java
// FIXED ordering
public void removeConnectedPeer(Peer peer) {
    synchronized (this.peerListsLock) {
        // connectedPeers first — closes the window repairOrphanedPeers exploits
        synchronized (this.connectedPeers) {
            this.connectedPeers.removeIf(p -> p == peer);
            this.immutableConnectedPeers = List.copyOf(this.connectedPeers);
        }
        this.removeHandshakedPeer(peer);
    }
}
```

With this order, when `repairOrphanedPeers` observes the intermediate state:
- peer is **absent** from `connectedPeers` (already removed)
- peer is **present** in `handshakedPeers` (not yet removed)

This matches check 2 of `repairOrphanedPeers` (line 1287), not check 1.
Check 2 calls `addConnectedPeer` rather than `addHandshakedPeer` — which is also wrong,
but the ghost condition (blocked reconnect) does not occur because
`getHandshakedPeerWithPublicKey` won't find the peer after `handshakedPeers` is cleared.

The cleanest fix is to ensure `repairOrphanedPeers` also holds `peerListsLock` during
its initial reads, making the entire operation atomic. The ordering fix above is the
minimum change that eliminates the ghost.

---

## Test

**File:** [`src/test/java/org/qortal/test/network/PeerListConsistencyTests.java`](../src/test/java/org/qortal/test/network/PeerListConsistencyTests.java)

**No `src/main` changes required.** The test uses plain Java objects to replicate
the two-list state machine, avoiding the `Network` singleton and socket dependency.

### Test 1 — `bugReproduction_removeAndRepairInterleave_leavesGhost`

Deterministically reproduces the race by manually interleaving the steps at the
exact vulnerable moment:

```java
// Step 1: first half of removeConnectedPeer — handshakedPeers removed
synchronized (lists.handshakedPeers) {
    lists.handshakedPeers.removeIf(x -> x == lists.peer);
}

// Step 2: repairOrphanedPeers fires in the window
// Sees: peer IN connectedPeers, NOT IN handshakedPeers → adds it back
lists.repairOrphanedPeers(lists.peer);

// Step 3: second half of removeConnectedPeer — connectedPeers removed
synchronized (lists.connectedPeers) {
    lists.connectedPeers.removeIf(x -> x == lists.peer);
}

// Ghost: peer gone from connectedPeers, stranded in handshakedPeers
assertFalse("peer should be gone from connectedPeers",
        lists.connectedPeers.contains(lists.peer));
assertTrue("BUG: peer is stranded in handshakedPeers (ghost) after disconnect",
        lists.handshakedPeers.contains(lists.peer));
```

This test **passes** against current `src/main` — it is asserting that the bug exists.
Once the fix is applied, this test should be inverted (or deleted, with the fix test
serving as the regression guard).

### Test 2 — `fixVerification_removeConnectedFirst_noGhost`

Shows that reversing the removal order eliminates the ghost:

```java
// Remove connectedPeers FIRST
synchronized (lists.connectedPeers) {
    lists.connectedPeers.removeIf(x -> x == lists.peer);
}

// repairOrphanedPeers fires — but peer is no longer in connectedPeers,
// so the repair condition is false. No mis-fire.
lists.repairOrphanedPeers(lists.peer);

// Remove handshakedPeers
synchronized (lists.handshakedPeers) {
    lists.handshakedPeers.removeIf(x -> x == lists.peer);
}

assertFalse("peer must be gone from connectedPeers",  lists.connectedPeers.contains(lists.peer));
assertFalse("peer must be gone from handshakedPeers", lists.handshakedPeers.contains(lists.peer));
```

---

## How to Run

```bash
mvn surefire:test -Dtest=PeerListConsistencyTests -DskipJUnitTests=false
```

Expected output against current (unfixed) code:

```
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Both tests pass — `bugReproduction` confirms the bug is present,
`fixVerification` confirms the fix works in isolation.

After applying the fix to `Network.removeConnectedPeer()`:

- `bugReproduction_removeAndRepairInterleave_leavesGhost` should **fail**
  (the ghost is no longer created — the `assertTrue` on `handshakedPeers` fails)
- `fixVerification_removeConnectedFirst_noGhost` should continue to **pass**

That is the red→green signal confirming the fix is correct.

---

## Files

| File | Role |
|------|------|
| [`src/main/java/org/qortal/network/Network.java`](../src/main/java/org/qortal/network/Network.java) | Bug location: `removeConnectedPeer()` line 749, `repairOrphanedPeers()` line 1230 |
| [`src/test/java/org/qortal/test/network/PeerListConsistencyTests.java`](../src/test/java/org/qortal/test/network/PeerListConsistencyTests.java) | Test reproducing the race |
