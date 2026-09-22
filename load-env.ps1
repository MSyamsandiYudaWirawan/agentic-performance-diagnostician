# Loads .env from the repo root into this PowerShell session's environment.
# Usage:  .\load-env.ps1    (then run the mvn gate in the same window)
# Env: changes in PowerShell are process-wide, so they survive script exit —
# no dot-sourcing needed.
$envFile = Join-Path $PSScriptRoot ".env"
Get-Content $envFile | ForEach-Object {
    $line = $_.Trim()
    if ($line.Length -eq 0 -or $line.StartsWith('#')) { return }
    $eq = $line.IndexOf('=')
    if ($eq -lt 1) { return }
    $key = $line.Substring(0, $eq).Trim()
    Set-Item -Path "Env:$key" -Value $line.Substring($eq + 1).Trim()
}
Write-Host "[load-env] loaded $envFile (values not printed)"
