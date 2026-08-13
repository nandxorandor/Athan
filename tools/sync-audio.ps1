# Mirrors audio/<Folder>/*.mp3 into app/src/main/assets/athan/<slug>/ and writes
# an index of durations. Re-run this after adding recordings, then rebuild —
# the app enumerates whatever is in assets, so no Kotlin needs editing.
#
#   powershell -ExecutionPolicy Bypass -File tools\sync-audio.ps1

$ErrorActionPreference = 'Stop'

$root   = Split-Path -Parent $PSScriptRoot
$src    = Join-Path $root 'audio'
$dst    = Join-Path $root 'app\src\main\assets\athan'
$ffmpeg = 'C:\ffmpeg-8.1.1-essentials_build\bin\ffmpeg.exe'
$ffprobe= 'C:\ffmpeg-8.1.1-essentials_build\bin\ffprobe.exe'

# Anything above this gets re-encoded. Adhan is voice: past ~96k mono the extra
# bitrate is inaudible on a phone speaker and only inflates the APK. Files
# already below it are copied untouched — re-encoding cannot recover quality.
$maxKbps = 100
$targetKbps = 96

if (Test-Path $dst) { Remove-Item $dst -Recurse -Force }
New-Item -ItemType Directory -Path $dst -Force | Out-Null

$index = New-Object System.Collections.Generic.List[string]

Get-ChildItem $src -Directory | ForEach-Object {
    $folder = $_
    $slug = ($folder.Name -replace '(?i)\s*athan\s*', '' -replace '[^A-Za-z0-9]', '').ToLower()
    if ([string]::IsNullOrWhiteSpace($slug)) { return }

    $files = Get-ChildItem $folder.FullName -Filter *.mp3 | Sort-Object Name
    if ($files.Count -eq 0) { return }

    $outDir = Join-Path $dst $slug
    New-Item -ItemType Directory -Path $outDir -Force | Out-Null

    foreach ($f in $files) {
        $meta = & $ffprobe -v quiet -print_format json -show_format $f.FullName | ConvertFrom-Json
        $kbps = [math]::Round([double]$meta.format.bit_rate / 1000)
        $secs = [math]::Round([double]$meta.format.duration)
        $out  = Join-Path $outDir $f.Name

        if ($kbps -gt $maxKbps) {
            & $ffmpeg -v error -y -i $f.FullName -codec:a libmp3lame -b:a "${targetKbps}k" -ac 1 -ar 44100 $out
            Write-Host ("re-encoded {0}/{1}  {2}k -> {3}k" -f $slug, $f.Name, $kbps, $targetKbps)
        } else {
            Copy-Item $f.FullName $out -Force
        }
        $index.Add("$slug`t$($f.Name)`t$secs")
    }
}

# ASCII, not the default: a UTF-8 BOM would corrupt the first field on read.
$indexPath = Join-Path $dst 'index.tsv'
[System.IO.File]::WriteAllLines($indexPath, $index, [System.Text.Encoding]::ASCII)

$total = (Get-ChildItem $dst -Recurse -File | Measure-Object -Property Length -Sum).Sum
Write-Host ("`n{0} recordings, {1} MB total" -f $index.Count, [math]::Round($total/1MB,1))
