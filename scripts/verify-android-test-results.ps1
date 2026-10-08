[CmdletBinding()]
param(
    [string]$Directory = (Join-Path $PSScriptRoot '../app/build/outputs/androidTest-results/connected/debug'),
    [int]$MinimumTests = 15
)
$ErrorActionPreference = 'Stop'
$reports = @(Get-ChildItem -LiteralPath $Directory -Filter 'TEST-*.xml' -File -Recurse)
if ($reports.Count -eq 0) { throw 'No Android test XML was produced. Check emulator and APK installation errors.' }
foreach ($report in $reports) {
    [xml]$xml = Get-Content -Raw -LiteralPath $report.FullName
    # AGP can emit a testsuites aggregate containing one suite per test class.
    $summary = $xml.SelectSingleNode('/testsuites | /testsuite')
    if ($null -eq $summary) { throw 'Android report contains no testsuite.' }
    if ([int]$summary.tests -lt $MinimumTests -or [int]$summary.failures -gt 0 -or
        [int]$summary.errors -gt 0 -or [int]$summary.skipped -gt 0) {
        throw "Android tests were incomplete or failed: $($report.Name), tests=$($summary.tests), failures=$($summary.failures), errors=$($summary.errors), skipped=$($summary.skipped)."
    }
    foreach ($suite in $xml.SelectNodes('//testsuite')) {
        if ([int]$suite.failures -gt 0 -or [int]$suite.errors -gt 0 -or [int]$suite.skipped -gt 0) {
            throw "An Android test class failed or skipped tests: $($suite.name)."
        }
    }
    if ($xml.SelectNodes('//testcase').Count -lt $MinimumTests) { throw 'The report contains too few actual test cases.' }
    Write-Output "Verified $($summary.tests) Android tests: $($report.Name)."
}
