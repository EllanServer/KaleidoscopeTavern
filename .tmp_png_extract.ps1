$root = (Get-Location).Path
$head = Join-Path $env:TEMP "png_head"
Remove-Item $head -Recurse -Force -ErrorAction SilentlyContinue
$files = & git diff --name-only | Where-Object { $_ -match "\.png$" }
foreach ($f in $files) {
  $dst = Join-Path $head ($f -replace "/", "\")
  New-Item -ItemType Directory -Force -Path (Split-Path $dst) | Out-Null
  & cmd /c "git show HEAD:$f > $dst" 2>$null
}
"extracted: " + $files.Count