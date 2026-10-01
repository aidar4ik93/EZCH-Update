[CmdletBinding()]
param(
    [string]$Directory = (Join-Path $PSScriptRoot '../app/build/outputs/androidTest-results/connected/debug'),
    [int]$MinimumTests = 17
)
$ErrorActionPreference = 'Stop'
$reports = @(Get-ChildItem -LiteralPath $Directory -Filter 'TEST-*.xml' -File)
if ($reports.Count -eq 0) { throw 'No Android test XML was produced. Check emulator and APK installation errors.' }
foreach ($report in $reports) {
    [xml]$xml = Get-Content -Raw -LiteralPath $report.FullName
    $summary = $xml.DocumentElement
    if ([int]$summary.tests -lt $MinimumTests -or [int]$summary.failures -gt 0 -or
        [int]$summary.errors -gt 0 -or [int]$summary.skipped -gt 0) {
        throw "Android tests were incomplete or failed: $($report.Name), tests=$($summary.tests), failures=$($summary.failures), errors=$($summary.errors), skipped=$($summary.skipped)."
    }
    Write-Output "Verified $($summary.tests) Android tests: $($report.Name)."
}
