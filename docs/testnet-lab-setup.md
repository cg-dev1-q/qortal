# Qortal Private Testnet / Lab Setup Guide

## Overview

Qortal has first-class testnet support built in. No code changes are needed — everything is driven
by config files. A private blockchain cluster is isolated from mainnet using three settings:
a custom genesis (`testchain.json`), `isTestNet: true`, and `fixedNetwork` (peer whitelist).

Works for both LAN clusters and geo-distributed VPS nodes. The only difference between the two
is the IP addresses in `fixedNetwork`.

---

## Existing Assets (use these, don't recreate)

| File | Purpose |
|------|---------|
| [`testnet/testchain.json`](../testnet/testchain.json) | Ready-to-use testnet genesis block |
| [`testnet/settings-test.json`](../testnet/settings-test.json) | Testnet settings template |
| [`testnet/start.sh`](../testnet/start.sh) | Node startup script |
| [`testnet/stop.sh`](../testnet/stop.sh) | Node shutdown script (API + SIGTERM fallback) |
| [`testnet/README.md`](../testnet/README.md) | Upstream testnet docs |
| [`docker-compose.internal.yml`](../docker-compose.internal.yml) | Internal Docker network setup |
| [`Dockerfile`](../Dockerfile) | Multi-stage Java 17 build |

---

## Network Ports

| Port | Mainnet | Testnet | Purpose |
|------|---------|---------|---------|
| P2P | 12392 | 62392 | Blockchain peer communication |
| API | 12391 | 62391 | REST API |
| QDN | 12394 | 62394 | Data network (arbitrary data) |
| Dev proxy | 12393 | 62393 | Dev proxy |

---

## Step 1 — Prepare the Genesis Block

Copy [`testnet/testchain.json`](../testnet/testchain.json) to each node directory.

**Only change needed:** set `genesisInfo.timestamp` to a future time (epoch milliseconds,
~5–15 minutes from when you plan to start all nodes). Get current epoch ms:

```bash
date +%s000
```

Add 5 minutes: `current_epoch_ms + 300000`

The existing `testchain.json` already contains:
- `ISSUE_ASSET` transactions for QORT, Legacy-QORA, QORT-from-QORA
- 11 `REWARD_SHARE` entries (minting keys ready to use — see Quick Start below)
- ~100 `ACCOUNT_FLAGS` entries (founder accounts)
- ~200 `ACCOUNT_LEVEL` entries (level-1 accounts for sponsorship)
- `GENESIS` transactions funding test accounts

**Quick-start minting key (already wired into testchain.json):**

```
minterPublicKey:      DwcUnhxjamqppgfXCLgbYRx8H9XFPUc2qYRy3CEvQWEw
recipient:            QbTDMss7NtRxxQaSqBZtSLSNdSYgvGaqFf
rewardSharePublicKey: CRvQXxFfUMfr4q3o1PcUZPA4aPCiubBsXkk47GzRo754
minting private key:  F48mYJycFgRdqtc58kiovwbcJgVukjzRE4qRRtRsK9ix
```

---

## Step 2 — Create Per-Node settings.json

Create one `settings.json` per node. Nodes on different machines can use the same ports.
Nodes on the same machine need unique `apiPort`, `listenPort`, `listenDataPort`, and `repositoryPath`.

### Minimum settings.json

```json
{
  "isTestNet": true,
  "singleNodeTestnet": false,
  "bootstrap": false,
  "blockchainConfig": "testchain.json",
  "repositoryPath": "db",
  "listenPort": 62392,
  "apiPort": 62391,
  "listenDataPort": 62394,
  "bindAddress": "0.0.0.0",
  "minBlockchainPeers": 1,
  "localAuthBypassEnabled": true,
  "apiRestricted": false,
  "maxPeerConnectionTime": 999999999,
  "recoveryModeTimeout": 0,
  "bitcoinNet": "TEST3",
  "litecoinNet": "TEST3",
  "dogecoinNet": "TEST3",
  "digibyteNet": "TEST3",
  "ravencoinNet": "TEST3",
  "fixedNetwork": [
    "<node1-ip>:62392",
    "<node2-ip>:62392",
    "<node3-ip>:62392"
  ]
}
```

`fixedNetwork` is the isolation lever — nodes will **only** connect to addresses on this list
and will never touch mainnet peers.

### For VPS / public IPs, add:

```json
"uPnPEnabled": false
```

### For a single machine (multiple instances), use distinct ports per node:

| Node | apiPort | listenPort | listenDataPort | repositoryPath |
|------|---------|------------|----------------|----------------|
| node1 | 62391 | 62392 | 62394 | db-node1 |
| node2 | 62401 | 62402 | 62404 | db-node2 |
| node3 | 62411 | 62412 | 62414 | db-node3 |

---

## Step 3 — Start the Nodes

Distribute the same `testchain.json` and node-specific `settings.json` to each machine.
Start each node before the genesis timestamp passes:

```bash
java -jar qortal.jar settings.json
```

Or use the provided script:

```bash
./testnet/start.sh
```

The script sets JVM args (`-Xss256m -XX:+UseSerialGC`), logs to `run.log`, saves PID to `run.pid`.

---

## Step 4 — Wire Up Peers and Minting

After all nodes start, on **each node**:

```bash
# Clear any stale peer entries
curl -X DELETE http://localhost:62391/peers/known

# Add your other nodes as peers
curl -X POST http://localhost:62391/peers -d "192.168.1.102"
curl -X POST http://localhost:62391/peers -d "192.168.1.103"
```

On **at least one** node, add a minting key:

```bash
# Uses the generic quick-start key pre-wired in testchain.json
curl -X POST http://localhost:62391/admin/mintingaccounts \
  -d "F48mYJycFgRdqtc58kiovwbcJgVukjzRE4qRRtRsK9ix"
```

> The README notes you should have **at least 2 separate minting keys on 2 separate nodes**
> for multi-node minting. The testchain.json includes 11 REWARD_SHARE entries for this purpose.

---

## Step 5 — Verify

After genesis timestamp passes, block 2 should mint within ~60 seconds.

```bash
# Block height — should increment after genesis
curl http://<node-ip>:62391/blocks/height

# Connected peers — should show your other nodes
curl http://<node-ip>:62391/peers/connected

# Known peers
curl http://<node-ip>:62391/peers/known
```

**Chain convergence test:** mint several blocks on one node, then `GET /blocks/height` across
all nodes — heights should match. Kill a node, mint more blocks, restart it, verify it syncs
to chain tip.

**Force sync a stuck node:**

```bash
curl -X POST http://localhost:62391/admin/forcesync "192.168.1.102:62392"
```

---

## Deployment Scenarios

### A — LAN Cluster (fastest to iterate)

- Same `testchain.json` on every machine
- `fixedNetwork` uses RFC-1918 IPs (`192.168.x.x`)
- Same ports on all nodes (each has its own machine/IP)
- No firewall changes needed on a flat LAN
- Block port 12392 outbound if you want to guarantee mainnet isolation

### B — VPS / Geo-distributed

- Same as LAN but `fixedNetwork` uses public IPs or hostnames
- Open port **62392** (P2P) inbound on each VPS firewall
- Keep port 62391 (API) restricted to localhost or a management IP
- Add `"uPnPEnabled": false`
- `testnet/start.sh` works unchanged on any Linux VPS

### C — Single Machine (development)

Add `"singleNodeTestnet": true` — one node mints all blocks, no second peer required.
Set `"minBlockchainPeers": 0`. Use distinct ports per instance (see table above).
Revert `singleNodeTestnet` before adding real peers.

### D — Docker (LAN)

`docker-compose.internal.yml` sets up an internal bridge network. Extend it with multiple
service blocks (node1, node2, node3), each with its own volume mount and port mapping.
Pass node-specific settings via `QORTAL_SETTINGS_FILE` env var.

Key Docker env vars:

| Variable | Default | Override to |
|----------|---------|------------|
| `QORTAL_API_PORT` | 12391 | 62391 |
| `QORTAL_P2P_PORT` | 12392 | 62392 |
| `QORTAL_QDN_PORT` | 12394 | 62394 |
| `QORTAL_SETTINGS_FILE` | /qortal/settings.json | your testnet settings |
| `QORTAL_JVM_MEMORY_ARGS` | -XX:MaxRAMPercentage=25 | tune as needed |

---

## Dealing With a Stuck Chain

If nodes went offline and no one has minted recently:

1. Start your nodes
2. Force one node to mint by temporarily setting `"minBlockchainPeers": 0` and restarting it
3. Once it has minted up to current time, `forcesync` the other nodes
4. Restore `minBlockchainPeers` to its normal value

Alternative (debugger method from upstream README):
- Set breakpoint on `Settings.getMinBlockchainPeers()` at runtime
- Change `this.minBlockchainPeers` to 0 in the debugger, continue
- Once caught up, remove breakpoint

---

## Network Isolation Checklist

- [ ] `"isTestNet": true` in every node's settings
- [ ] `"bootstrap": false` — no mainnet bootstrap download
- [ ] `"blockchainConfig": "testchain.json"` — custom genesis, not mainnet `blockchain.json`
- [ ] `"fixedNetwork": [...]` — only your node IPs
- [ ] `minBlockchainPeers: 1` (or 0 for single-node) — don't stall waiting for 3 peers
- [ ] Firewall: port 12392 (mainnet P2P) blocked outbound on test machines
- [ ] `genesisInfo.timestamp` in `testchain.json` updated to a future time before first start

---

## Key Source Files

| Purpose | Path |
|---------|------|
| Settings class | [`src/main/java/org/qortal/settings/Settings.java`](../src/main/java/org/qortal/settings/Settings.java) |
| Peer management | [`src/main/java/org/qortal/network/Network.java`](../src/main/java/org/qortal/network/Network.java) |
| Hardcoded initial peers | `Network.java` lines 85–93 (bypassed by `fixedNetwork`) |
| Genesis block loading | [`src/main/java/org/qortal/block/GenesisBlock.java`](../src/main/java/org/qortal/block/GenesisBlock.java) |
| Node startup | [`src/main/java/org/qortal/controller/Controller.java`](../src/main/java/org/qortal/controller/Controller.java) |
| Bootstrap apply | [`src/main/java/org/qortal/ApplyBootstrap.java`](../src/main/java/org/qortal/ApplyBootstrap.java) |
