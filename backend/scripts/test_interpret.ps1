# Exercises every tool against a running backend (Windows PowerShell).
# Usage: .\scripts\test_interpret.ps1 -BaseUrl http://localhost:8787 -Secret yoursecret
param(
  [string]$BaseUrl = "http://localhost:8787",
  [string]$Secret = $env:APP_SHARED_SECRET
)

Write-Host "== GET /health"
curl.exe -s "$BaseUrl/health"; Write-Host ""

$phrases = @(
  "Jarvis call king",
  "Jarvis message junior on WhatsApp hi",
  "text mom on my way",
  "set an alarm for 6:30 pm",
  "set a timer for 5 minutes",
  "open youtube",
  "search for best biryani near me",
  "play arijit singh",
  "what is the capital of France",
  "message"
)
$now = Get-Date -Format "yyyy-MM-dd HH:mm ddd"
foreach ($p in $phrases) {
  Write-Host "== $p"
  $body = @{ text = $p; contacts = @("King", "Junior", "Mom"); now = $now } | ConvertTo-Json -Compress
  $tmp = New-TemporaryFile
  Set-Content -Path $tmp -Value $body -Encoding ascii
  curl.exe -s -X POST "$BaseUrl/interpret" -H "Content-Type: application/json" -H "X-Jarvis-Secret: $Secret" --data-binary "@$tmp"
  Remove-Item $tmp
  Write-Host ""
}
