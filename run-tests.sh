#!/usr/bin/env bash
set -euo pipefail

# Remove stale folder-size cache so ArbitraryDataStorageManager recalculates from scratch.
# This file lives outside the test data directories and isn't cleaned up by @Before/@After.
rm -f qortal-backup/ArbitraryDataFolderSizeEstimate.dat

# Network tests (Bitcoin/Litecoin/Ravencoin/PirateChain) are excluded by default via
# excludeNetworkTests in pom.xml. Pass -DexcludeNetworkTests=nothing to run them.
mvn test -DskipJUnitTests=false "$@"
