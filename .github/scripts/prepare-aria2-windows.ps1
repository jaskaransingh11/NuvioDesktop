$ErrorActionPreference = 'Stop'

# Official Windows release binary, checksum validated against the public
# Microsoft WinGet manifest. Binary is not committed to this repository.
$asset = 'aria2-1.37.0-win-64bit-build1'
$expectedSha256 = '67D015301EEF0B612191212D564C5BB0A14B5B9C4796B76454276A4D28D9B288'
$url = 'https://github.com/aria2/aria2/releases/download/release-1.37.0/' + $asset + '.zip'
$archive = Join-Path $env:RUNNER_TEMP ($asset + '.zip')
$unpack = Join-Path $env:RUNNER_TEMP 'jj-aria2-verified'
if (Test-Path -LiteralPath $unpack) {
    throw 'Unexpected existing extraction; refusing mixed package'
}
Invoke-WebRequest -Uri $url -OutFile $archive -UseBasicParsing -TimeoutSec 120
$actualSha256 = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash
if ($actualSha256 -ne $expectedSha256) {
    throw 'Aria2 release checksum differs from WinGet-published value'
}
Expand-Archive -LiteralPath $archive -DestinationPath $unpack -ErrorAction Stop
$source = Join-Path $unpack $asset
$exe = Join-Path $source 'aria2c.exe'
$copying = Join-Path $source 'COPYING'
$openssl = Join-Path $source 'LICENSE.OpenSSL'
foreach ($file in @($exe, $copying, $openssl)) {
    if (-not(Test-Path -LiteralPath $file)) {
        throw ('Verified release archive missing required file: '+$file)
    }
}
$target = 'composeApp/packageResources/windows'
$tools = Join-Path $target 'tools'
$licenses = Join-Path $target 'licenses/aria2'
New-Item -ItemType Directory -Path $tools -Force | Out-Null
New-Item -ItemType Directory -Path $licenses -Force | Out-Null
Copy-Item -LiteralPath $exe -Destination (Join-Path $tools 'aria2c.exe') -ErrorAction Stop
foreach ($name in @('COPYING','LICENSE.OpenSSL','AUTHORS','README.mingw')) {
    $src = Join-Path $source $name
    if (Test-Path -LiteralPath $src) {
        Copy-Item -LiteralPath $src -Destination (Join-Path $licenses $name) -ErrorAction Stop
    }
}
$notice = [string]::Join([Environment]::NewLine, [string[]]@(
 'Third-party downloader: aria2 1.37.0, distributed as a separate CLI executable.',
 'Copyright (C) 2006, 2019 Tatsuhiro Tsujikawa and contributors.',
 'GNU General Public License, version 2 or later; see COPYING and LICENSE.OpenSSL.',
 'Corresponding source: https://github.com/aria2/aria2/tree/release-1.37.0',
 'Official binary: https://github.com/aria2/aria2/releases/tag/release-1.37.0',
 'WinGet checksum: https://github.com/microsoft/winget-pkgs/blob/master/manifests/a/aria2/aria2/1.37.0/aria2.aria2.installer.yaml',
 'This file records provenance; distribution compliance requires independent review.'
))
[IO.File]::WriteAllText((Join-Path $licenses 'THIRD-PARTY-NOTICE.txt'), $notice, [Text.UTF8Encoding]::new($false))
$check = (Get-FileHash -LiteralPath (Join-Path $tools 'aria2c.exe') -Algorithm SHA256).Hash
if ($check -ne (Get-FileHash -LiteralPath $exe -Algorithm SHA256).Hash) {
    throw 'Copied aria2 executable differs from verified upstream release'
}
Write-Output 'Verified upstream aria2 1.37.0 hash, binary and license material for MSI.'