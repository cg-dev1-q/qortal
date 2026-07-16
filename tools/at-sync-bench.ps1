<#
.SYNOPSIS
    Benchmarks the AT fee/state persistence path (Block.processAtFeesAndStates) during a sync.

.DESCRIPTION
    Wipes the blockchain database to force a fresh sync, starts the node, waits until the
    node is synced (or a time budget expires), stops it cleanly, then parses the AT-METRICS
    lines from qortal.log into a markdown report.

    Requires a jar built from instrumented source (target/qortal-*.jar).

.PARAMETER Mode
    bootstrap : keep bootstrap enabled (default upstream behaviour). The node downloads a
                recent snapshot then block-syncs the gap to the chain tip. Those gap blocks
                are recent and AT-heavy, so this is the representative "catching up" case,
                but the sample is only as large as the gap.
    genesis   : disable bootstrap and sync from height 1. Exercises every block, but never
                reaches "synced" in a sane window, so it is time-bounded. Early blocks have
                few/no ATs, so expect empty windows before AT-heavy heights are reached.

.PARAMETER MaxMinutes
    Time budget. The run stops at whichever comes first: synced, or this many minutes.

.PARAMETER BlockInterval
    Blocks per AT-METRICS reporting window (-Dqortal.atMetrics.blockInterval).

.PARAMETER ParseOnly
    Skip the wipe/start/wait entirely and just re-parse an existing log into a report.
    Useful to re-cut the report from a previous run, or from a log copied off another node.

.EXAMPLE
    .\tools\at-sync-bench.ps1 -Mode bootstrap -MaxMinutes 45
    .\tools\at-sync-bench.ps1 -Mode genesis -MaxMinutes 120
    .\tools\at-sync-bench.ps1 -ParseOnly -LogFile qortal.log
#>
[CmdletBinding()]
param(
    [ValidateSet('bootstrap', 'genesis', 'orphan')]
    [string] $Mode = 'bootstrap',

    # orphan mode only: how many blocks to rewind before re-syncing them.
    [int] $OrphanBlocks = 2000,

    [int] $MaxMinutes = 60,

    # How long to wait for the API to bind before giving up. Generous by default: in
    # bootstrap mode the node downloads and extracts a multi-GB archive first.
    [int] $ApiTimeoutMinutes = 30,

    [int] $BlockInterval = 100,
    [int] $ApiPort = 12391,
    [string] $OutFile,
    [string] $LogFile,

    # Re-parse an existing log instead of running a sync
    [switch] $ParseOnly,

    # Skip the confirmation prompt before wiping the database
    [switch] $Force
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
if (-not $LogFile) { $LogFile = Join-Path $repoRoot 'qortal.log' }
if (-not $OutFile) {
    $OutFile = Join-Path $repoRoot "bench\at-bench-$Mode-$stamp.md"
}

function Write-Step { param([string] $Message) Write-Host "==> $Message" -ForegroundColor Cyan }
function Write-Warn { param([string] $Message) Write-Host "  ! $Message" -ForegroundColor Yellow }

# Any java process running our jar, whether or not this script started it.
function Get-QortalProcess {
    Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like '*qortal.jar*' -or $_.CommandLine -like '*qortal-*.jar*' }
}

function Get-QortalApiKey {
    $apiKeyPath = Join-Path $repoRoot 'apikey.txt'
    if (Test-Path $apiKeyPath) { return (Get-Content $apiKeyPath -Raw).Trim() }
    return $null
}

# /admin/stop needs an API key. The node would generate a random one at startup, but a known
# value keeps shutdown scriptable. Only created when absent, so a real key is never clobbered.
# Must be BOM-less: ApiKey.load() does new String(Files.readAllBytes(path)), so a BOM would
# silently become part of the key. Settings requires at least 8 characters.
function Initialize-QortalApiKey {
    $apiKeyPath = Join-Path $repoRoot 'apikey.txt'
    if (Test-Path $apiKeyPath) {
        Write-Host "  using existing apikey.txt"
        return
    }
    [System.IO.File]::WriteAllText($apiKeyPath, 'SUPER_SECRET_API_KEY', (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "  created apikey.txt with a test key"
}

# Ask the node to shut down, then kill whatever is left. A clean exit matters: it closes
# HSQLDB properly and lets the metrics shutdown hook flush its final partial window. A node
# left hung by a failed run will not answer /admin/stop, hence the kill fallback.
function Stop-QortalNode {
    param([int] $Port, [int] $GraceSec = 180)

    if (-not (Get-QortalProcess)) { return }

    $apiKey = Get-QortalApiKey
    if ($apiKey) {
        try {
            Invoke-RestMethod -Uri "http://localhost:$Port/admin/stop?apiKey=$apiKey" -TimeoutSec 20 | Out-Null
            Write-Host "  requested shutdown via /admin/stop"
        } catch {
            Write-Warn "/admin/stop failed: $($_.Exception.Message)"
        }
    } else {
        Write-Warn "no apikey.txt - cannot request a graceful shutdown"
    }

    $waited = 0
    while ((Get-QortalProcess) -and $waited -lt $GraceSec) { Start-Sleep -Seconds 2; $waited += 2 }

    $stubborn = Get-QortalProcess
    if ($stubborn) {
        Write-Warn "still running after ${waited}s - killing (final metrics window may be lost)"
        foreach ($p in $stubborn) { Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue }
        Start-Sleep -Seconds 2
    } else {
        Write-Host "  node exited cleanly after ${waited}s"
    }
}

# Report metadata; populated by the run phase, left as placeholders in -ParseOnly mode.
$useBootstrap = ($Mode -eq 'bootstrap')
$jar          = $null
$elapsed      = $null
$reachedSync  = $false
$startHeight  = $null
$lastHeight   = $null
$orphanedTo   = $null

function Invoke-BenchRun {
# --------------------------------------------------------------------------------------
# 1. Preconditions
# --------------------------------------------------------------------------------------

Write-Step "Checking preconditions"

$script:jar = Get-ChildItem -Path (Join-Path $repoRoot 'target') -Filter 'qortal-*.jar' -ErrorAction SilentlyContinue |
       Where-Object { $_.Name -notlike 'original-*' } |
       Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $script:jar) {
    throw "No target/qortal-*.jar found. Build first (mvn -DskipTests package)."
}

# Warn if the jar predates the instrumented sources - it would silently produce no metrics
$newestSource = Get-ChildItem -Path (Join-Path $repoRoot 'src\main\java') -Filter '*.java' -Recurse |
                Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($newestSource.LastWriteTime -gt $script:jar.LastWriteTime) {
    Write-Warn "$($script:jar.Name) is older than $($newestSource.Name) - rebuild or metrics may be stale/missing."
}

# A node holding the database open must go before we can wipe it. A node left hung by a
# previous failed run will not answer /admin/stop, so fall back to killing it.
$running = Get-QortalProcess
if ($running) {
    Write-Step "A Qortal node is already running (PID $($running.ProcessId -join ', '))"
    if (-not $Force) {
        $reply = Read-Host "Stop it and continue? (y/N)"
        if ($reply -ne 'y') { throw "Aborted by user - node left running, nothing deleted." }
    }
    Stop-QortalNode -Port $ApiPort -GraceSec 30
}

# --------------------------------------------------------------------------------------
# 2. Wipe the database
# --------------------------------------------------------------------------------------

$dbDirs = Get-ChildItem -Path $repoRoot -Directory -Filter 'db*' -ErrorAction SilentlyContinue

if ($Mode -eq 'orphan') {
    # Orphan mode measures re-processing of AT-heavy blocks against a full-size database,
    # which is the whole point - so the database must be kept, not wiped.
    if (-not $dbDirs) {
        throw "Mode 'orphan' needs an existing synced database, but no db* directory exists. Run -Mode bootstrap first."
    }
    Write-Step "Keeping existing database (orphan mode re-processes blocks against it)"
} elseif ($dbDirs) {
    $totalMb = [math]::Round((($dbDirs | ForEach-Object {
        (Get-ChildItem $_.FullName -Recurse -File -ErrorAction SilentlyContinue |
         Measure-Object -Property Length -Sum).Sum
    } | Measure-Object -Sum).Sum / 1MB), 1)

    Write-Step "About to delete the blockchain database"
    $dbDirs | ForEach-Object { Write-Host "      $($_.FullName)" }
    Write-Host "      total: $totalMb MB"

    if (-not $Force) {
        $reply = Read-Host "Delete these and force a full re-sync? (y/N)"
        if ($reply -ne 'y') { throw "Aborted by user - nothing deleted." }
    }

    foreach ($d in $dbDirs) { Remove-Item -Recurse -Force -Path $d.FullName }
    Write-Host "  deleted $($dbDirs.Count) database director$(if ($dbDirs.Count -eq 1) { 'y' } else { 'ies' })"
} else {
    Write-Step "No existing db* directory - already clean"
}

# Archive any previous log so the parse only sees this run
if (Test-Path $LogFile) {
    $archived = Join-Path $repoRoot "qortal.log.$stamp.bak"
    Move-Item -Path $LogFile -Destination $archived -Force
    Write-Host "  archived previous log -> $(Split-Path -Leaf $archived)"
}

# --------------------------------------------------------------------------------------
# 3. Settings for the chosen mode
# --------------------------------------------------------------------------------------

Write-Step "Configuring settings.json for mode '$Mode'"

$settingsPath = Join-Path $repoRoot 'settings.json'
if (Test-Path $settingsPath) {
    $backup = Join-Path $repoRoot "settings.json.$stamp.bak"
    Copy-Item -Path $settingsPath -Destination $backup -Force
    Write-Host "  backed up existing settings.json -> $(Split-Path -Leaf $backup)"
}

$settings = [ordered]@{
    bootstrap = $script:useBootstrap
    apiPort   = $ApiPort
}

# Must be BOM-less: Settings.java reads this with a plain FileReader, which does not strip
# a BOM, so JAXB fails with "Unexpected char 65279". Set-Content -Encoding utf8 emits a BOM
# on PowerShell 5.1, hence WriteAllText with an explicit no-BOM encoder.
$json = $settings | ConvertTo-Json -Depth 4
[System.IO.File]::WriteAllText($settingsPath, $json, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "  bootstrap = $($script:useBootstrap)"

Initialize-QortalApiKey

# --------------------------------------------------------------------------------------
# 4. Start the node
# --------------------------------------------------------------------------------------

Write-Step "Starting node (metrics window = $BlockInterval blocks)"

# start.sh convention: the runtime jar lives at the repo root as qortal.jar.
# The manifest's "Class-Path: . .." is what lets log4j2.properties in cwd be picked up.
Copy-Item -Path $script:jar.FullName -Destination (Join-Path $repoRoot 'qortal.jar') -Force

$javaArgs = @(
    '-Djava.net.preferIPv4Stack=false',
    '-XX:MaxRAMPercentage=50', '-XX:+UseG1GC', '-Xss1024k',
    "-Dqortal.atMetrics.blockInterval=$BlockInterval",
    '-jar', 'qortal.jar'
)

$proc = Start-Process -FilePath 'java' -ArgumentList $javaArgs -WorkingDirectory $repoRoot `
                      -RedirectStandardOutput (Join-Path $repoRoot 'run.log') `
                      -RedirectStandardError  (Join-Path $repoRoot 'run.err.log') `
                      -PassThru -NoNewWindow
Set-Content -Path (Join-Path $repoRoot 'run.pid') -Value $proc.Id
Write-Host "  java pid $($proc.Id), jar $($script:jar.Name)"

$runStart = Get-Date

# --------------------------------------------------------------------------------------
# 5. Poll until synced or out of time
# --------------------------------------------------------------------------------------

function Get-SyncState {
    param([int] $Port)
    try {
        $status = Invoke-RestMethod -Uri "http://localhost:$Port/admin/status" -TimeoutSec 10
        $last   = Invoke-RestMethod -Uri "http://localhost:$Port/blocks/last"  -TimeoutSec 10
        $nowMs  = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        return [pscustomobject]@{
            Ok            = $true
            Height        = [int] $status.height
            Synchronizing = [bool] $status.isSynchronizing
            SyncPercent   = $status.syncPercent
            Connections   = [int] $status.numberOfConnections
            TipAgeSec     = [int] [math]::Round(($nowMs - [double] $last.timestamp) / 1000)
        }
    } catch {
        return [pscustomobject]@{ Ok = $false }
    }
}

# A node that dies during startup (bad settings, port clash) can leave a non-daemon thread
# alive, so the process never exits and HasExited never fires. Without a guard the poll loop
# would spin "api not ready" for the whole budget.
#
# The guard cannot be a short timeout: in bootstrap mode the node downloads and extracts a
# multi-GB archive before the API binds, which took ~8 min here and logs nothing at all for
# ~5 of them - so neither a 5 min deadline nor a log-liveness check can tell "still working"
# from "hung". Key off a fatal startup error instead, and keep the deadline generous.
$fatalStartupPattern = 'ERROR\s+Settings:|Exception in thread "main"'
$script:apiEverUp = $false
$script:lastHeight = 0

# Poll until the node looks synced, or $Deadline passes. Returns $true if sync was reached.
# Called twice in orphan mode: once for the initial catch-up, once for the re-sync that is
# actually being measured.
function Wait-ForSync {
    param([datetime] $Deadline, [datetime] $ApiDeadline, [string] $Phase)

    $syncedStreak   = 0
    $requiredStreak = 3           # consecutive good polls before declaring synced

    while ((Get-Date) -lt $Deadline) {
        if ($proc.HasExited) {
            Write-Warn "Node exited early (code $($proc.ExitCode)) - see run.log / run.err.log"
            return $false
        }

        Start-Sleep -Seconds 15
        $s = Get-SyncState -Port $ApiPort
        if (-not $s.Ok) {
            if ($script:apiEverUp) {
                Write-Warn "API stopped responding - node may have died. See qortal.log."
                return $false
            }

            Write-Host "  api not ready yet (bootstrap download/extract can take ~10 min)..."

            $fatal = $null
            if (Test-Path $LogFile) {
                $fatal = Select-String -Path $LogFile -Pattern $fatalStartupPattern | Select-Object -Last 3
            }
            if ($fatal) {
                Write-Warn "Fatal startup error - aborting:"
                $fatal | ForEach-Object { Write-Host "      $($_.Line)" }
                return $false
            }

            if ((Get-Date) -gt $ApiDeadline) {
                Write-Warn "API did not come up within $ApiTimeoutMinutes min - giving up."
                return $false
            }
            continue
        }
        $script:apiEverUp = $true

        if ($null -eq $script:startHeight -and $s.Height -gt 0) { $script:startHeight = $s.Height }
        $script:lastHeight = $s.Height

        $pct = if ($null -ne $s.SyncPercent) { "$($s.SyncPercent)%" } else { 'n/a' }
        $elapsedMin = [int] ((Get-Date) - $runStart).TotalMinutes
        Write-Host ("  [{0,3}m] {1} height={2} sync={3} pct={4} peers={5} tipAge={6}s" -f `
                    $elapsedMin, $Phase, $s.Height, $s.Synchronizing, $pct, $s.Connections, $s.TipAgeSec)

        # Approximates Controller.isUpToDate(): recent chain tip plus enough peers.
        # isUpToDate() itself is not exposed over the API.
        $looksSynced = (-not $s.Synchronizing) -and ($s.Connections -ge 3) -and
                       ($s.TipAgeSec -ge 0) -and ($s.TipAgeSec -lt 600)

        if ($looksSynced) {
            $syncedStreak++
            if ($syncedStreak -ge $requiredStreak) { return $true }
        } else {
            $syncedStreak = 0
        }
    }
    return $false
}

# Rewind the chain so the node re-downloads and re-processes those blocks. BlockChain.orphan()
# uses tryLock on the blockchain lock, so this only works while not actively syncing - hence
# it is called after the initial sync settles.
#
# /admin/orphan does not return until every block is orphaned, and that is slow - measured at
# ~5.4 blocks/sec, so 2000 blocks needs ~6 min. The timeout must be generous: a client-side
# timeout does NOT stop the server, it just blinds us while it keeps working. Never retry at
# a shallower depth after a timeout either - by then the chain height has already dropped
# below the retry target, so the node answers 400 INVALID_HEIGHT and the failure looks real
# when the original orphan was in fact succeeding.
function Invoke-Orphan {
    param([int] $Port, [int] $FromHeight, [int] $Blocks)

    $target = $FromHeight - $Blocks
    if ($target -lt 1) {
        Write-Warn "orphan target height $target is invalid - skipping"
        return $null
    }

    # ~5.4 blocks/sec observed; allow roughly 3x headroom plus a floor.
    $timeoutSec = [int]($Blocks / 2) + 300
    Write-Host "  orphaning to $target (timeout ${timeoutSec}s; expect ~$([int]($Blocks / 5))s)"

    $apiKey = Get-QortalApiKey
    try {
        $r = Invoke-RestMethod -Uri "http://localhost:$Port/admin/orphan" -Method Post `
                               -Headers @{ 'X-API-KEY' = $apiKey } `
                               -ContentType 'text/plain' -Body "$target" -TimeoutSec $timeoutSec
        if ("$r".Trim() -eq 'true') {
            Write-Host "  orphaned $Blocks blocks: $FromHeight -> $target"
            return $target
        }
        # "false" means BlockChain.orphan() could not take the blockchain lock
        Write-Warn "orphan returned '$r' - blockchain lock busy, node was probably still syncing"
        return $null
    } catch {
        # The server keeps orphaning regardless. Check where the chain actually landed rather
        # than assuming failure.
        Write-Warn "orphan request failed client-side: $($_.Exception.Message)"
        $s = Get-SyncState -Port $Port
        if ($s.Ok -and $s.Height -lt $FromHeight) {
            Write-Warn "but the chain did rewind to $($s.Height) - measuring from there"
            return [int] $s.Height
        }
        return $null
    }
}

$deadline    = $runStart.AddMinutes($MaxMinutes)
$apiDeadline = $runStart.AddMinutes($ApiTimeoutMinutes)

Write-Step "Waiting for sync (budget: $MaxMinutes min). Ctrl-C aborts; node keeps running."
$script:reachedSync = Wait-ForSync -Deadline $deadline -ApiDeadline $apiDeadline -Phase 'sync'

if ($Mode -eq 'orphan' -and $OrphanBlocks -eq 0) {
    # -OrphanBlocks 0 means the database is already behind the tip (e.g. left rewound by a
    # previous run), so just measure it catching back up.
    Write-Step "OrphanBlocks=0 - skipping rewind, measuring the sync from the current height"
} elseif ($Mode -eq 'orphan') {
    if (-not $script:reachedSync) {
        Write-Warn "Never reached sync - skipping orphan step."
    } else {
        Write-Step "Orphaning $OrphanBlocks blocks from height $($script:lastHeight) to force re-processing"
        $script:orphanedTo = Invoke-Orphan -Port $ApiPort -FromHeight $script:lastHeight -Blocks $OrphanBlocks

        if ($null -ne $script:orphanedTo) {
            $script:startHeight = $script:orphanedTo
            Write-Step "Waiting for re-sync of the orphaned blocks (this is the measured phase)"
            $script:reachedSync = Wait-ForSync -Deadline $deadline -ApiDeadline $apiDeadline -Phase 're-sync'
        }
    }
}

$script:elapsed = (Get-Date) - $runStart

if ($script:reachedSync) {
    Write-Step ("Synced at height {0} after {1:n1} min" -f $script:lastHeight, $script:elapsed.TotalMinutes)
} else {
    Write-Step ("Stopped at height {0} after {1:n1} min (time budget reached)" -f $script:lastHeight, $script:elapsed.TotalMinutes)
}

# --------------------------------------------------------------------------------------
# 6. Stop the node cleanly (lets the shutdown hook flush the final partial window)
# --------------------------------------------------------------------------------------

Write-Step "Stopping node"

Stop-QortalNode -Port $ApiPort -GraceSec 180
Remove-Item -Path (Join-Path $repoRoot 'run.pid') -Force -ErrorAction SilentlyContinue
} # end Invoke-BenchRun

# --------------------------------------------------------------------------------------
# 7. Run (unless we are only re-parsing an existing log)
# --------------------------------------------------------------------------------------

if ($ParseOnly) {
    Write-Step "Parse-only: skipping wipe/start/wait"
} else {
    Invoke-BenchRun
}

# --------------------------------------------------------------------------------------
# 8. Parse AT-METRICS lines
# --------------------------------------------------------------------------------------

Write-Step "Parsing $((Split-Path -Leaf $LogFile))"

if (-not (Test-Path $LogFile)) { throw "Log not found: $LogFile - did the node start? See run.log." }

$windows = @()
foreach ($line in (Select-String -Path $LogFile -Pattern 'AT-METRICS' | ForEach-Object { $_.Line })) {
    $kv = @{}
    foreach ($m in [regex]::Matches($line, '(\w+)=([-\d.]+)')) {
        $kv[$m.Groups[1].Value] = [double] $m.Groups[2].Value
    }
    if ($kv.ContainsKey('ats') -and $kv['ats'] -gt 0) { $windows += [pscustomobject] $kv }
}

Write-Host "  found $($windows.Count) metrics window(s)"

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $OutFile) | Out-Null

$md = New-Object System.Text.StringBuilder
function Add-Line { param([string] $Text = '') [void] $md.AppendLine($Text) }

Add-Line "# AT persistence benchmark - $Mode"
Add-Line
Add-Line "Generated $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
Add-Line
Add-Line '## Run'
Add-Line
Add-Line '| | |'
Add-Line '|---|---|'
Add-Line "| Source log | ``$(Split-Path -Leaf $LogFile)`` |"
if ($ParseOnly) {
    Add-Line '| Mode | parse-only (re-parsed an existing log; run metadata unknown) |'
} else {
    Add-Line "| Mode | ``$Mode`` (bootstrap=$useBootstrap) |"
    Add-Line ("| Duration | {0:n1} min |" -f $elapsed.TotalMinutes)
    Add-Line "| Outcome | $(if ($reachedSync) { 'reached sync' } else { 'time budget reached' }) |"
    Add-Line "| Height | $startHeight -> $lastHeight |"
    if ($Mode -eq 'orphan') {
        if ($OrphanBlocks -eq 0) {
            Add-Line '| Orphaned to | not requested (`-OrphanBlocks 0`); measured the database catching up from where it already stood |'
        } elseif ($null -ne $orphanedTo) {
            Add-Line "| Orphaned to | $orphanedTo (re-processed against a full-size database) |"
        } else {
            Add-Line '| Orphaned to | **failed** - numbers below are a plain sync, not a re-sync |'
        }
    }
    Add-Line "| Jar | ``$($jar.Name)`` ($(Get-Date $jar.LastWriteTime -Format 'yyyy-MM-dd HH:mm')) |"
    Add-Line "| Metrics window | $BlockInterval blocks |"
}
Add-Line

if ($windows.Count -eq 0) {
    Add-Line '## No data'
    Add-Line
    Add-Line 'No `AT-METRICS` lines were logged. Likely causes:'
    Add-Line
    Add-Line '- The jar was built from un-instrumented source (rebuild: `mvn -DskipTests package`).'
    Add-Line '- No blocks containing ATs were processed (common early in a genesis sync, or if a'
    Add-Line '  bootstrap left almost no gap to gap-sync).'
    Add-Line '- The node was killed rather than shut down cleanly, losing the final partial window.'
    ($md.ToString()) | Set-Content -Path $OutFile -Encoding utf8
    Write-Warn "No AT-METRICS lines found - see $OutFile"
    return
}

# Reconstruct per-window totals so windows can be weighted by their AT count
$totBlocks = ($windows | Measure-Object -Property blocks -Sum).Sum
$totAts    = ($windows | Measure-Object -Property ats    -Sum).Sum

$sum = @{ fromAt = 0.0; modBal = 0.0; update = 0.0; saveStates = 0.0; saveStatesData = 0.0 }
foreach ($w in $windows) {
    $sum.fromAt         += $w.fromATAddressMs
    $sum.modBal         += $w.modifyBalanceMs
    $sum.update         += $w.atUpdateMs
    $sum.saveStates     += ($w.saveATStatesUs     * $w.ats / 1000.0)
    $sum.saveStatesData += ($w.saveATStatesDataUs * $w.ats / 1000.0)
}

# modifyBalance + fromATAddress + at.update are disjoint and together make up the loop body.
$totalMs     = $sum.fromAt + $sum.modBal + $sum.update
# at.update minus its two timed upserts leaves save(ATData) + flags-only state parsing.
$remainderMs = $sum.update - $sum.saveStates - $sum.saveStatesData

# $StepMs, not $TotalMs: PowerShell identifiers are case-insensitive, so a $TotalMs
# parameter would shadow the script-level $totalMs and make every share read 100%.
function Row {
    param([string] $Name, [double] $StepMs, [string] $Note = '')
    $perAt = if ($totAts -gt 0)  { $StepMs * 1000.0 / $totAts } else { 0 }
    $pct   = if ($totalMs -gt 0) { $StepMs / $totalMs * 100.0 } else { 0 }
    Add-Line ("| {0} | {1:n1} | {2:n1} | {3:n1}% | {4} |" -f $Name, $perAt, $StepMs, $pct, $Note)
}

Add-Line '## Where the time goes'
Add-Line
Add-Line "Across **$totBlocks blocks** / **$totAts AT states** ($([math]::Round($totAts / [double]$totBlocks, 1)) ATs per block)."
Add-Line
Add-Line '| Step | µs/AT | total ms | share | notes |'
Add-Line '|---|---:|---:|---:|---|'
Row 'modifyAssetBalance' $sum.modBal 'single UPDATE on AccountBalances'
Row 'fromATAddress'      $sum.fromAt 'AT row fetch; 0 once the code_bytes read is removed'
Row 'at.update **(total)**' $sum.update 'sum of the three rows below'
Add-Line ("| &nbsp;&nbsp;- save ATStates | {0:n1} | {1:n1} | {2:n1}% | metadata upsert |" -f `
    ($sum.saveStates * 1000.0 / $totAts), $sum.saveStates, ($sum.saveStates / $totalMs * 100.0))
Add-Line ("| &nbsp;&nbsp;- save ATStatesData | {0:n1} | {1:n1} | {2:n1}% | state_data BLOB upsert |" -f `
    ($sum.saveStatesData * 1000.0 / $totAts), $sum.saveStatesData, ($sum.saveStatesData / $totalMs * 100.0))
Add-Line ("| &nbsp;&nbsp;- AT row write + parse | {0:n1} | {1:n1} | {2:n1}% | save(ATData) upsert, or updateFlags once optimised |" -f `
    ($remainderMs * 1000.0 / $totAts), $remainderMs, ($remainderMs / $totalMs * 100.0))
Add-Line ("| **Total** | **{0:n1}** | **{1:n1}** | **100%** | per AT, per block |" -f `
    ($totalMs * 1000.0 / $totAts), $totalMs)
Add-Line
Add-Line ("Estimated AT-persistence cost per block: **{0:n1} ms**." -f ($totalMs / $totBlocks))
Add-Line

Add-Line '## Reading this'
Add-Line
Add-Line '- `fromATAddress` + `save(ATData)` are the two steps that touch the **immutable** AT'
Add-Line '  bytecode (`code_bytes`). Any share they hold is avoidable: the read only needs the'
Add-Line '  mutable flag columns, and the write rewrites bytecode that never changes after creation.'
Add-Line '- `save ATStatesData` is the real state BLOB. This cost is inherent, though the'
Add-Line '  `ON DUPLICATE KEY UPDATE` form forces an index probe even during a fresh sync where'
Add-Line '  every write is an insert.'
Add-Line '- Every step above is un-batched: one statement per AT per block. Sibling paths in'
Add-Line '  `Block.processBlock()` already batch (`modifyMintedBlockCounts`, `modifyAssetBalances`).'
Add-Line '- Caveat: `save(ATStateData)` is timed at every caller, not just the block loop, so the'
Add-Line '  two `save AT*` rows can be slightly inflated by non-sync callers.'
Add-Line '- Caveat: this measures **persistence only**. AT bytecode execution happens earlier in'
Add-Line '  `Block.executeATs()` and is not counted here.'
Add-Line '- Logging is *not* inflating these timings, despite `log4j2.properties` setting'
Add-Line '  `org.qortal.repository.hsqldb` to `debug`. The per-SQL logging in'
Add-Line '  `HSQLDBRepository.prepareStatement()` is gated by a `debugState` flag that defaults'
Add-Line '  to false and has no callers, so it never fires; a 79-block sync logged 1 DEBUG line'
Add-Line '  and no SQL. Timings would only be skewed if repository debug were enabled at runtime,'
Add-Line '  or if `slowQueryThreshold` were set in settings.json.'
Add-Line

Add-Line '## Windows'
Add-Line
Add-Line '| heights | blocks | ATs | ATs/blk | fromATAddress µs | modifyBalance µs | at.update µs | saveATStates µs | saveATStatesData µs |'
Add-Line '|---|---:|---:|---:|---:|---:|---:|---:|---:|'
foreach ($w in $windows) {
    Add-Line ("| {0}-{1} | {2} | {3} | {4:n1} | {5:n1} | {6:n1} | {7:n1} | {8:n1} | {9:n1} |" -f `
        [int] $w.heightFrom, [int] $w.heightTo, [int] $w.blocks, [int] $w.ats, $w.atsPerBlock,
        $w.fromATAddressUs, $w.modifyBalanceUs, $w.atUpdateUs, $w.saveATStatesUs, $w.saveATStatesDataUs)
}
Add-Line

($md.ToString()) | Set-Content -Path $OutFile -Encoding utf8

Write-Step "Report written"
Write-Host "  $OutFile"
