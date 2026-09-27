# native-tee.ps1 - run a command file and stream its output to BOTH stdout and a log file.
#
# Why this exists: cmd.exe has no `tee`. Without it, a long native-image run either
# shows nothing until it finishes (bad for an agent watching the build) or writes only
# to a file (nothing on the console). This gives real-time to both.
#
# ASCII-only on purpose: Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI and
# would garble non-ASCII text. The file MUST also keep CRLF line endings.
#
# Design notes:
#  - The command is passed as a *file* rather than an argument list, so the caller's
#    quoting (native-image args contain quotes, colons and backslashes) never has to
#    survive a cmd -> PowerShell round trip.
#  - Lines are emitted with the success stream (not Write-Host) so a parent process
#    capturing stdout still receives them.
#  - The log is opened with FileShare.ReadWrite. This matters: with the default share
#    mode another process (e.g. the agent tailing the log) gets
#    "the file is in use by another process" and cannot read it while the build runs --
#    which would defeat the whole point of real-time feedback. Verified empirically.

param(
    [Parameter(Mandatory = $true)][string]$CmdFile,
    [Parameter(Mandatory = $true)][string]$Log
)

$ErrorActionPreference = 'Continue'

$enc = New-Object System.Text.UTF8Encoding($false)
$fs = New-Object System.IO.FileStream(
    $Log,
    [System.IO.FileMode]::Append,
    [System.IO.FileAccess]::Write,
    [System.IO.FileShare]::ReadWrite)
$writer = New-Object System.IO.StreamWriter($fs, $enc)
$writer.AutoFlush = $true

try {
    & cmd.exe /c "`"$CmdFile`"" 2>&1 | ForEach-Object {
        $line = [string]$_
        $line                      # success stream -> real-time stdout
        $writer.WriteLine($line)   # real-time log, readable by others
    }
    $code = $LASTEXITCODE
} finally {
    $writer.Close()
    $fs.Dispose()
}

exit $code
