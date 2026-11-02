# Downloads the small English Vosk model into the app's assets (about 40 MB).
$ErrorActionPreference = "Stop"
$model = "vosk-model-small-en-us-0.15"
$url = "https://alphacephei.com/vosk/models/$model.zip"
$root = Split-Path -Parent $PSScriptRoot
$dest = Join-Path $root "app\src\main\assets\model-en-us"
$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("vosk-" + [guid]::NewGuid())
New-Item -ItemType Directory -Force $tmp | Out-Null
try {
  Write-Host "Downloading $url"
  Invoke-WebRequest -Uri $url -OutFile "$tmp\model.zip" -UseBasicParsing
  Expand-Archive -Path "$tmp\model.zip" -DestinationPath $tmp -Force
  if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }
  New-Item -ItemType Directory -Force (Split-Path -Parent $dest) | Out-Null
  Move-Item "$tmp\$model" $dest
  # Vosk's StorageService compares this file to decide whether to re-copy the model on device.
  Set-Content -Path "$dest\uuid" -Value $model -Encoding ascii
  Write-Host "Model installed at $dest"
} finally {
  Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}
