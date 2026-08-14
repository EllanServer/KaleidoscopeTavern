Add-Type -AssemblyName System.Drawing
$root = "E:\Documents\森罗物语"
Set-Location $root
$files = & git diff --name-only | Where-Object { $_ -match "\.png$" }
$results = @()
foreach ($f in $files) {
  $tmp = Join-Path $env:TEMP "head_png_check.png"
  & cmd /c "git show HEAD:$f > $tmp" 2>$null
  if (-not (Test-Path $tmp)) { $results += "$f : git show 失败"; continue }
  try {
    $a = New-Object System.Drawing.Bitmap($tmp)
    $b = New-Object System.Drawing.Bitmap((Join-Path $root ($f -replace "/", "\")))
    $same = ($a.Width -eq $b.Width -and $a.Height -eq $b.Height)
    if ($same) {
      for ($y=0; $y -lt $a.Height -and $same; $y++) {
        for ($x=0; $x -lt $a.Width -and $same; $x++) {
          if ($a.GetPixel($x,$y).ToArgb() -ne $b.GetPixel($x,$y).ToArgb()) { $same = $false }
        }
      }
    }
    $results += "$f : 尺寸=$($a.Width)x$($a.Height) RGBA一致=$same"
    $a.Dispose(); $b.Dispose()
  } catch {
    $results += "$f : 解码失败 - $($_.Exception.Message)"
  }
  Remove-Item $tmp -ErrorAction SilentlyContinue
}
$results -join "`n"