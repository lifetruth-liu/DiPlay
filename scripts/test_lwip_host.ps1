param(
    [string]$Cmake = 'cmake',
    [string]$Compiler = 'gcc',
    [string]$Make = 'mingw32-make',
    [string]$BuildDir = ''
)
$ErrorActionPreference = 'Stop'
$repository = Split-Path -Parent $PSScriptRoot
if (-not $BuildDir) { $BuildDir = Join-Path $repository 'build/lwip-host-windows' }
$cmakeCommand = (Get-Command $Cmake -ErrorAction Stop).Source
$ctestCommand = Join-Path (Split-Path -Parent $cmakeCommand) 'ctest.exe'
$compilerCommand = (Get-Command $Compiler -ErrorAction Stop).Source.Replace('\', '/')
$makeCommand = (Get-Command $Make -ErrorAction Stop).Source.Replace('\', '/')
function Invoke-Checked([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Executable failed with exit $LASTEXITCODE" }
}
Invoke-Checked $cmakeCommand @('--version')
Invoke-Checked $compilerCommand @('--version')
Write-Output 'Pinned lwIP: 2.2.1 / 77dcd25a72509eb83f72b033d219b1d40cd8eb95'
Invoke-Checked 'python' @((Join-Path $repository 'scripts/check_lwip_native.py'))
Invoke-Checked $cmakeCommand @('-S', (Join-Path $repository 'shared/src/main/jni/lwip_backend'), '-B', $BuildDir,
    '-G', 'MinGW Makefiles', "-DCMAKE_C_COMPILER=$compilerCommand", "-DCMAKE_MAKE_PROGRAM=$makeCommand")
Invoke-Checked $cmakeCommand @('--build', $BuildDir, '--parallel', '2')
Push-Location $BuildDir
try {
    $inventory = & $ctestCommand -N
    if ($LASTEXITCODE -ne 0 -or -not ($inventory -match 'Total Tests: [1-9][0-9]*')) { throw 'No native tests registered' }
    $inventory
    Invoke-Checked $ctestCommand @('--output-on-failure', '--output-log', 'ctest.log')
} finally { Pop-Location }
