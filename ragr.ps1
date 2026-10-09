<#
.SYNOPSIS
    Runs spring-ai-ragr's applications as Docker containers, or hands them back to IntelliJ.

.DESCRIPTION
    Each application runs in exactly one of two modes at a time:

      intellij  launched from its IntelliJ run configuration
      docker    the ai_ragr-* container from the `apps` profile in compose.yaml

    Both publish the same host ports, so nothing else - Prometheus, Grafana, Swagger, the golden
    suite - needs to know which mode is in use. This script never starts or stops an IntelliJ
    instance: IntelliJ has no command line for its run configurations, and killing a process someone
    started in the IDE is not this script's call. It refuses instead, and says what to do.

    Application names: app (ragr-app), ingest (ragr-ingest), eval (ragr-eval); the full names work too.
    No names means all three.

    `stack` acts on the whole of compose.yaml - the infrastructure and the three applications - where
    `docker` acts on the applications only and leaves the infrastructure running.

.EXAMPLE
    ./ragr.ps1 stack up                  # infrastructure and all three applications
.EXAMPLE
    ./ragr.ps1 stack down                # stop and remove every container; docker-volume/ is kept
.EXAMPLE
    ./ragr.ps1 docker up                 # package on the host, build images, start, wait for healthy
.EXAMPLE
    ./ragr.ps1 docker restart ingest     # re-package and recreate ragr-ingest only
.EXAMPLE
    ./ragr.ps1 docker stop app
.EXAMPLE
    ./ragr.ps1 intellij ingest           # stop the container, then press Run in IntelliJ
.EXAMPLE
    ./ragr.ps1 status
.EXAMPLE
    ./ragr.ps1 logs ingest               # follow the container log; Ctrl+C stops following, not the app
#>
param(
    [Parameter(Position = 0)][string]$Command = 'help',
    [Parameter(Position = 1, ValueFromRemainingArguments = $true)][string[]]$Rest
)

# Not 'Stop': in Windows PowerShell 5.1 that turns anything a native command writes to stderr - and
# docker and Maven write progress there - into a terminating error. Exit codes are checked instead.
$ErrorActionPreference = 'Continue'
Set-Location -LiteralPath $PSScriptRoot

$AllApps = @(
    [pscustomobject]@{ Name = 'ragr-app';    Alias = 'app';    Ports = @(8080, 9095); Health = 9095; RunConfig = 'SpringAiApplication'; VmOptions = '-Xmx384m -XX:+UseSerialGC' }
    [pscustomobject]@{ Name = 'ragr-ingest'; Alias = 'ingest'; Ports = @(8081, 9097); Health = 9097; RunConfig = 'IngestApplication';   VmOptions = '-Xmx512m -XX:+UseSerialGC' }
    [pscustomobject]@{ Name = 'ragr-eval';   Alias = 'eval';   Ports = @(9096);       Health = 9096; RunConfig = 'EvalApplication';     VmOptions = '-Xmx256m -XX:+UseSerialGC' }
)

function Write-Step([string]$Text) { Write-Host "==> $Text" -ForegroundColor Cyan }

function Stop-WithError([string]$Text) {
    Write-Host "ragr: $Text" -ForegroundColor Red
    exit 1
}

function Resolve-Apps([string[]]$Names) {
    if (-not $Names -or $Names.Count -eq 0) { return $AllApps }
    foreach ($n in $Names) {
        $app = $AllApps | Where-Object { $_.Name -eq $n -or $_.Alias -eq $n }
        if (-not $app) { Stop-WithError "unknown application '$n' - use app, ingest or eval" }
        $app
    }
}

# The PID of a java process on the host listening on one of the application's ports - an IntelliJ
# instance. A container's published port is owned by Docker Desktop's own processes instead.
function Get-HostPid($App) {
    foreach ($port in $App.Ports) {
        $listeners = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue
        foreach ($l in $listeners) {
            $proc = Get-Process -Id $l.OwningProcess -ErrorAction SilentlyContinue
            if ($proc -and $proc.Name -in @('java', 'javaw')) { return $proc.Id }
        }
    }
    return $null
}

# "running|Up 5 minutes (healthy)", or $null when there is no container at all.
function Get-ContainerState($App) {
    $line = docker ps -a --filter "name=^ai_$($App.Name)$" --format '{{.State}}|{{.Status}}'
    if ($line) { return [string]$line }
    return $null
}

function Get-Health($App) {
    try {
        (Invoke-RestMethod -Uri "http://localhost:$($App.Health)/actuator/health" -TimeoutSec 3).status
    } catch {
        if ($_.Exception.Response) { 'DOWN' } else { '-' }
    }
}

function Assert-Docker {
    docker info --format '{{.ServerVersion}}' *> $null
    if ($LASTEXITCODE -ne 0) { Stop-WithError 'Docker is not running - start Docker Desktop first.' }
}

function Test-Ollama {
    try {
        Invoke-RestMethod -Uri 'http://localhost:11434/api/version' -TimeoutSec 3 | Out-Null
    } catch {
        Write-Host "ragr: warning - Ollama is not answering on localhost:11434; the applications start, but chat and ingestion fail until it is." -ForegroundColor Yellow
    }
}

function Assert-NotOnHost($Apps) {
    foreach ($app in $Apps) {
        $hostPid = Get-HostPid $app
        if ($hostPid) {
            Stop-WithError ("$($app.Name) is running on the host (PID $hostPid, IntelliJ mode). Stop it in " +
                "IntelliJ first, then re-run. Its ports are needed by the container.")
        }
    }
}

function Invoke-DockerUp($Apps, [switch]$Recreate) {
    Assert-Docker
    Assert-NotOnHost $Apps
    Test-Ollama

    $modules = ($Apps | ForEach-Object { $_.Name }) -join ','
    Write-Step "Packaging $modules on the host"
    & "$PSScriptRoot\mvnw.cmd" -q -pl $modules -am package -DskipTests
    if ($LASTEXITCODE -ne 0) { Stop-WithError 'Maven packaging failed - see the output above.' }

    # The whole infrastructure, as ragr-app's compose integration starts it on a cold IntelliJ run.
    # The applications' depends_on would start only the parts they cannot run without, leaving out
    # the observability stack.
    Write-Step 'Starting the infrastructure'
    docker compose up -d --wait
    if ($LASTEXITCODE -ne 0) { Stop-WithError 'The infrastructure did not come up healthy - see docker compose ps.' }

    $services = @($Apps | ForEach-Object { $_.Name })
    Write-Step "Building and starting $($services -join ', ') (returns once healthy)"
    $upArgs = @('compose', '--profile', 'apps', 'up', '-d', '--build', '--wait', '--wait-timeout', '240')
    if ($Recreate) { $upArgs += '--force-recreate' }
    docker @upArgs @services
    if ($LASTEXITCODE -ne 0) {
        foreach ($s in $services) {
            Write-Host "`n--- last log lines of $s ---" -ForegroundColor Yellow
            docker compose --profile apps logs --tail 40 $s
        }
        Stop-WithError 'Not every application came up healthy - log tails above.'
    }
    Show-Status $Apps
}

function Invoke-DockerStop($Apps) {
    Assert-Docker
    $services = @($Apps | ForEach-Object { $_.Name })
    Write-Step "Stopping $($services -join ', ')"
    docker compose --profile apps stop @services
    if ($LASTEXITCODE -ne 0) { Stop-WithError 'docker compose stop failed.' }
}

# The applications go first, while Kafka, Loki and the OTel collector are still up to take their last
# turns, logs and spans; a single compose stop would order only by depends_on, which does not cover
# the observability stack. The data lives in bind mounts under docker-volume/, which down never
# touches; no -v all the same, so nothing a later compose change puts in a volume is lost with it.
function Invoke-StackStop([switch]$Remove) {
    Assert-Docker
    $running = @($AllApps | Where-Object { (Get-ContainerState $_) -like 'running*' })
    if ($running.Count -gt 0) { Invoke-DockerStop $running }

    $verb = if ($Remove) { 'down' } else { 'stop' }
    Write-Step "Running docker compose $verb on the infrastructure"
    docker compose --profile apps $verb
    if ($LASTEXITCODE -ne 0) { Stop-WithError "docker compose $verb failed." }

    foreach ($app in $AllApps) {
        $hostPid = Get-HostPid $app
        if ($hostPid) {
            Write-Host ("ragr: warning - $($app.Name) is still running from IntelliJ (PID $hostPid) and has " +
                "lost its infrastructure; stop it in IntelliJ.") -ForegroundColor Yellow
        }
    }
}

function Invoke-ToIntelliJ($Apps) {
    Assert-Docker
    $running = @($Apps | Where-Object { (Get-ContainerState $_) -like 'running*' })
    if ($running.Count -gt 0) { Invoke-DockerStop $running }
    Write-Host ''
    foreach ($app in $Apps) {
        $hostPid = Get-HostPid $app
        if ($hostPid) {
            Write-Host "$($app.Name): already running from IntelliJ (PID $hostPid)."
        } else {
            Write-Host "$($app.Name): port(s) $($app.Ports -join ', ') free - run '$($app.RunConfig)' in IntelliJ (VM options $($app.VmOptions))."
        }
    }
}

function Show-Status($Apps) {
    $rows = foreach ($app in $Apps) {
        $state = Get-ContainerState $app
        $hostPid = Get-HostPid $app
        if ($state -like 'running*') {
            $mode = 'docker'; $detail = "ai_$($app.Name): $($state.Split('|')[1])"
        } elseif ($hostPid) {
            $mode = 'intellij'; $detail = "PID $hostPid"
        } else {
            $mode = 'stopped'; $detail = if ($state) { "ai_$($app.Name): $($state.Split('|')[1])" } else { '' }
        }
        $health = if ($mode -eq 'stopped') { '-' } else { Get-Health $app }
        [pscustomobject]@{ Application = $app.Name; Mode = $mode; Health = $health; Detail = $detail }
    }
    $rows | Format-Table -AutoSize | Out-Host

    try {
        $models = (Invoke-RestMethod -Uri 'http://localhost:11434/api/ps' -TimeoutSec 3).models | ForEach-Object { $_.name }
        $loaded = if ($models) { $models -join ', ' } else { 'none loaded' }
        Write-Host "Ollama: $loaded"
    } catch {
        Write-Host 'Ollama: not answering on localhost:11434'
    }
}

function Show-Usage {
    Write-Host @'
Usage:
  ./ragr.ps1 stack up                               the infrastructure and all three applications
  ./ragr.ps1 stack stop                             stop every container, applications first; keep them
  ./ragr.ps1 stack down                             stop and remove every container and the network; data in docker-volume/ is kept
  ./ragr.ps1 docker up      [app|ingest|eval ...]   package on the host, build, start, wait for healthy
  ./ragr.ps1 docker restart [app|ingest|eval ...]   the same, recreating the containers even if unchanged
  ./ragr.ps1 docker stop    [app|ingest|eval ...]   stop the containers (the infrastructure keeps running)
  ./ragr.ps1 intellij       [app|ingest|eval ...]   stop the containers so IntelliJ can take the ports
  ./ragr.ps1 status                                 which mode each application is in, health, Ollama models
  ./ragr.ps1 logs           <app|ingest|eval>       follow a container's log

No application names means all three.
'@
}

switch ($Command) {
    'docker' {
        if (-not $Rest -or $Rest.Count -eq 0) { Show-Usage; exit 1 }
        $action = $Rest[0]
        $apps = Resolve-Apps ($Rest | Select-Object -Skip 1)
        switch ($action) {
            'up'      { Invoke-DockerUp $apps }
            'restart' { Invoke-DockerUp $apps -Recreate }
            'stop'    { Invoke-DockerStop $apps }
            default   { Show-Usage; exit 1 }
        }
    }
    'stack' {
        if (-not $Rest -or $Rest.Count -ne 1) { Show-Usage; exit 1 }
        switch ($Rest[0]) {
            'up'    { Invoke-DockerUp $AllApps }
            'stop'  { Invoke-StackStop }
            'down'  { Invoke-StackStop -Remove }
            default { Show-Usage; exit 1 }
        }
    }
    'intellij' { Invoke-ToIntelliJ (Resolve-Apps $Rest) }
    'status'   { Show-Status $AllApps }
    'logs' {
        if (-not $Rest -or $Rest.Count -ne 1) { Stop-WithError 'logs takes exactly one application: app, ingest or eval' }
        $app = Resolve-Apps $Rest
        docker compose --profile apps logs -f --tail 200 $app.Name
    }
    default { Show-Usage; if ($Command -ne 'help') { exit 1 } }
}
