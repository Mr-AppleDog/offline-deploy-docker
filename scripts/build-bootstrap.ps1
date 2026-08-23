[CmdletBinding()]
param(
    [Parameter(Mandatory = $false)]
    [ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+$')]
    [string]$AppVersion = '1.1.1',

    [Parameter(Mandatory = $false)]
    [ValidateSet('linux/amd64')]
    [string]$Platform = 'linux/amd64',

    [Parameter(Mandatory = $false)]
    [string]$PackageRevision = '',

    [Parameter(Mandatory = $true)]
    [string]$DockerMediaRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$ProjectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$DistRoot = Join-Path $ProjectRoot 'dist'
if ($PackageRevision -and $PackageRevision -notmatch '^r[1-9][0-9]*$') {
    throw 'PackageRevision 必须为空或使用 r2、r3 这类格式。'
}
$RevisionSuffix = if ($PackageRevision) { "-$PackageRevision" } else { '' }
$KitName = "kunlun-bootstrap-$AppVersion$RevisionSuffix-linux-amd64"
$KitRoot = Join-Path $DistRoot $KitName
$ArchivePath = "$KitRoot.tar.gz"
$ArchiveChecksumPath = "$ArchivePath.sha256"
$DockerMediaRoot = [IO.Path]::GetFullPath($DockerMediaRoot)

function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Description,

        [Parameter(Mandatory = $true)]
        [scriptblock]$Command
    )

    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "$Description 失败，退出码：$LASTEXITCODE"
    }
}

function Get-EmbeddedValue {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Text,

        [Parameter(Mandatory = $true)]
        [string]$Key
    )

    $Pattern = '(?m)^' + [regex]::Escape($Key) + ':\s*&[^ ]+\s+"([^"]+)"$'
    $Match = [regex]::Match($Text, $Pattern)
    if (-not $Match.Success) {
        throw "Compose 缺少或无法解析：$Key"
    }
    return $Match.Groups[1].Value
}

function Write-AsciiFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [Parameter(Mandatory = $true)]
        [string[]]$Lines
    )

    [IO.File]::WriteAllText(
        $Path,
        (($Lines -join "`n") + "`n"),
        [Text.Encoding]::ASCII
    )
}

function Test-ChecksumRecords {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Root,

        [Parameter(Mandatory = $true)]
        [string]$ChecksumFile
    )

    $Records = Get-Content -LiteralPath $ChecksumFile -Encoding UTF8
    foreach ($Line in $Records) {
        if ($Line -notmatch '^([0-9a-f]{64})  (.+)$') {
            throw "SHA256SUMS 格式错误：$Line"
        }

        $ExpectedHash = $Matches[1]
        $RelativePath = $Matches[2]
        $FilePath = Join-Path $Root $RelativePath.Replace('/', '\')
        if (-not (Test-Path -LiteralPath $FilePath -PathType Leaf)) {
            throw "校验文件不存在：$RelativePath"
        }

        $ActualHash = (Get-FileHash -LiteralPath $FilePath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($ActualHash -ne $ExpectedHash) {
            throw "文件校验失败：$RelativePath"
        }
    }
    return $Records.Count
}

foreach ($OutputPath in @($KitRoot, $ArchivePath, $ArchiveChecksumPath)) {
    if (Test-Path -LiteralPath $OutputPath) {
        throw "输出已经存在，禁止覆盖：$OutputPath"
    }
}

foreach ($RequiredCommand in @('docker', 'tar')) {
    if (-not (Get-Command $RequiredCommand -ErrorAction SilentlyContinue)) {
        throw "缺少命令：$RequiredCommand"
    }
}

Invoke-Native 'Docker daemon 检查' { docker info *> $null }
Invoke-Native 'Docker Compose 检查' { docker compose version *> $null }

$MiddlewareComposeSource = Join-Path $ProjectRoot 'deploy\compose.middleware.yml'
$AppComposeSource = Join-Path $ProjectRoot 'deploy\compose.app.yml'
$MiddlewareText = [IO.File]::ReadAllText($MiddlewareComposeSource)
$AppText = [IO.File]::ReadAllText($AppComposeSource)

foreach ($Compose in @(
    @{ Path = $MiddlewareComposeSource; Text = $MiddlewareText },
    @{ Path = $AppComposeSource; Text = $AppText }
)) {
    if ($Compose.Text.Contains('${')) {
        throw "$($Compose.Path) 仍依赖运行时变量。"
    }
}

foreach ($Key in @(
    'x-kunlun-mysql-user',
    'x-kunlun-mysql-password',
    'x-kunlun-redis-password',
    'x-kunlun-rabbitmq-user',
    'x-kunlun-rabbitmq-password',
    'x-kunlun-minio-user',
    'x-kunlun-minio-secret'
)) {
    $MiddlewareValue = Get-EmbeddedValue -Text $MiddlewareText -Key $Key
    $AppValue = Get-EmbeddedValue -Text $AppText -Key $Key
    if ($MiddlewareValue -ne $AppValue) {
        throw "两份 Compose 的 $Key 不一致。"
    }
}

foreach ($PasswordKey in @('x-kunlun-mysql-password', 'x-kunlun-redis-password', 'x-kunlun-rabbitmq-password', 'x-kunlun-minio-secret')) {
    $PasswordValue = Get-EmbeddedValue -Text $MiddlewareText -Key $PasswordKey
    if ($PasswordValue.Length -lt 12) {
        throw "$PasswordKey 长度不能少于 12 个字符。"
    }
}
Remove-Variable PasswordKey, PasswordValue, MiddlewareValue, AppValue -ErrorAction SilentlyContinue

if (-not $AppText.Contains("offline-demo-backend:$AppVersion")) {
    throw "compose.app.yml 未引用 backend:$AppVersion"
}
if (-not $AppText.Contains("offline-demo-frontend:$AppVersion")) {
    throw "compose.app.yml 未引用 frontend:$AppVersion"
}

Invoke-Native 'middleware Compose 解析' {
    docker compose -f $MiddlewareComposeSource config --quiet
}
Invoke-Native 'app Compose 解析' {
    docker compose -f $AppComposeSource config --quiet
}

$RequiredMedia = @(
    'docker-29.7.0.tgz',
    'docker-compose-linux-x86_64',
    'docker-compose-linux-x86_64.sha256'
)
foreach ($MediaName in $RequiredMedia) {
    $MediaPath = Join-Path $DockerMediaRoot $MediaName
    if (-not (Test-Path -LiteralPath $MediaPath -PathType Leaf)) {
        throw "缺少 Docker 离线介质：$MediaPath"
    }
}

$ComposeBinary = Join-Path $DockerMediaRoot 'docker-compose-linux-x86_64'
$ComposeChecksum = Join-Path $DockerMediaRoot 'docker-compose-linux-x86_64.sha256'
$ComposeChecksumLine = (Get-Content -LiteralPath $ComposeChecksum -Encoding ASCII | Select-Object -First 1)
if ($ComposeChecksumLine -notmatch '^([0-9a-fA-F]{64})\s+\*?docker-compose-linux-x86_64$') {
    throw 'Compose 发布方 SHA256 文件格式错误。'
}
$ComposeExpectedHash = $Matches[1].ToLowerInvariant()
$ComposeActualHash = (Get-FileHash -LiteralPath $ComposeBinary -Algorithm SHA256).Hash.ToLowerInvariant()
if ($ComposeActualHash -ne $ComposeExpectedHash) {
    throw 'Compose 二进制发布方 SHA256 校验失败。'
}

$ImageRecords = @(
    [ordered]@{ Image='mysql:8.4.11'; Tar='middleware/mysql/image/mysql-8.4.11-linux-amd64.tar' },
    [ordered]@{ Image='redis:8.2.8'; Tar='middleware/redis/image/redis-8.2.8-linux-amd64.tar' },
    [ordered]@{ Image='rabbitmq:4.3.4-management'; Tar='middleware/rabbitmq/image/rabbitmq-4.3.4-management-linux-amd64.tar' },
    [ordered]@{ Image='minio/minio:RELEASE.2025-07-18T21-56-31Z'; Tar='middleware/minio/image/minio-RELEASE.2025-07-18T21-56-31Z-linux-amd64.tar' },
    [ordered]@{ Image="offline-demo-backend:$AppVersion"; Tar="application/images/$AppVersion/offline-demo-backend-$AppVersion-linux-amd64.tar" },
    [ordered]@{ Image="offline-demo-frontend:$AppVersion"; Tar="application/images/$AppVersion/offline-demo-frontend-$AppVersion-linux-amd64.tar" }
)

foreach ($Record in $ImageRecords) {
    $Identity = docker image inspect --format '{{.Id}}|{{.Os}}/{{.Architecture}}' $Record.Image
    if ($LASTEXITCODE -ne 0) {
        throw "镜像不存在：$($Record.Image)"
    }
    if ($Identity -notmatch '^sha256:[0-9a-f]{64}\|linux/amd64$') {
        throw "镜像平台或 ID 异常：$($Record.Image) -> $Identity"
    }
    $Record.Identity = $Identity
}

$Directories = @(
    'docker/install',
    'middleware/mysql/image',
    'middleware/redis/image',
    'middleware/rabbitmq/image',
    'middleware/minio/image',
    "application/images/$AppVersion",
    'database/init',
    "database/migrations/$AppVersion",
    'scripts',
    'docs'
)

foreach ($RelativeDirectory in $Directories) {
    New-Item -ItemType Directory -Force (Join-Path $KitRoot $RelativeDirectory.Replace('/', '\')) | Out-Null
}

Copy-Item -LiteralPath $MiddlewareComposeSource -Destination (Join-Path $KitRoot 'middleware\compose.middleware.yml')
Copy-Item -LiteralPath $AppComposeSource -Destination (Join-Path $KitRoot 'application\compose.app.yml')
Copy-Item -LiteralPath (Join-Path $ProjectRoot 'deploy\docker\daemon.json') -Destination (Join-Path $KitRoot 'docker\install\daemon.json')
Copy-Item -LiteralPath (Join-Path $ProjectRoot 'deploy\systemd\docker.service') -Destination (Join-Path $KitRoot 'docker\install\docker.service')

foreach ($MediaName in $RequiredMedia) {
    Copy-Item -LiteralPath (Join-Path $DockerMediaRoot $MediaName) -Destination (Join-Path $KitRoot 'docker\install')
}

Copy-Item -Path (Join-Path $ProjectRoot 'deploy\scripts\*.sh') -Destination (Join-Path $KitRoot 'scripts')
Copy-Item -Path (Join-Path $ProjectRoot 'docs\*.md') -Destination (Join-Path $KitRoot 'docs')
Copy-Item -LiteralPath (Join-Path $ProjectRoot 'README.md') -Destination $KitRoot
Copy-Item -LiteralPath (Join-Path $ProjectRoot '部署手册.md') -Destination $KitRoot

$DockerTar = Join-Path $KitRoot 'docker\install\docker-29.7.0.tgz'
$DockerTarHash = (Get-FileHash -LiteralPath $DockerTar -Algorithm SHA256).Hash.ToLowerInvariant()
Write-AsciiFile -Path "$DockerTar.sha256" -Lines @("$DockerTarHash  docker-29.7.0.tgz")

foreach ($Record in $ImageRecords) {
    $TarPath = Join-Path $KitRoot $Record.Tar.Replace('/', '\')
    Invoke-Native "导出镜像 $($Record.Image)" {
        docker save --output $TarPath $Record.Image
    }
    if (-not (Test-Path -LiteralPath $TarPath -PathType Leaf)) {
        throw "镜像 tar 未生成：$TarPath"
    }

    $TarHash = (Get-FileHash -LiteralPath $TarPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $Record.TarHash = $TarHash
    Write-AsciiFile -Path "$TarPath.sha256" -Lines @("$TarHash  $([IO.Path]::GetFileName($TarPath))")
}

$ManifestLines = @(
    'PACKAGE_TYPE=bootstrap',
    "APP_VERSION=$AppVersion",
    "PACKAGE_REVISION=$PackageRevision",
    "TARGET_PLATFORM=$Platform",
    'MIDDLEWARE_POLICY=fixed',
    'MYSQL_IMAGE=mysql:8.4.11',
    'REDIS_IMAGE=redis:8.2.8',
    'RABBITMQ_IMAGE=rabbitmq:4.3.4-management',
    'MINIO_IMAGE=minio/minio:RELEASE.2025-07-18T21-56-31Z',
    "BACKEND_IMAGE=offline-demo-backend:$AppVersion",
    "FRONTEND_IMAGE=offline-demo-frontend:$AppVersion",
    'DB_MIGRATION_REQUIRED=false',
    'DB_MIGRATION_TOOL=none',
    'CREDENTIAL_MODE=embedded-compose',
    'KUNLUN_ROOT=/opt/Kunlun'
)
Write-AsciiFile -Path (Join-Path $KitRoot 'manifest.env') -Lines $ManifestLines

$ImageLines = foreach ($Record in $ImageRecords) {
    "$($Record.Image)|$($Record.Identity)|$($Record.Tar)|$($Record.TarHash)"
}
Write-AsciiFile -Path (Join-Path $KitRoot 'images.txt') -Lines $ImageLines

$ChecksumPath = Join-Path $KitRoot 'SHA256SUMS'
$ChecksumLines = Get-ChildItem -LiteralPath $KitRoot -File -Recurse |
    Where-Object { $_.Name -ne 'SHA256SUMS' } |
    Sort-Object FullName |
    ForEach-Object {
        $Hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        $RelativePath = $_.FullName.Substring($KitRoot.Length + 1).Replace('\', '/')
        "$Hash  $RelativePath"
    }

[IO.File]::WriteAllText(
    $ChecksumPath,
    (($ChecksumLines -join "`n") + "`n"),
    [Text.UTF8Encoding]::new($false)
)

$VerifiedCount = Test-ChecksumRecords -Root $KitRoot -ChecksumFile $ChecksumPath

Invoke-Native '生成外层压缩包' {
    tar -czf $ArchivePath -C (Split-Path $KitRoot) (Split-Path $KitRoot -Leaf)
}
if (-not (Test-Path -LiteralPath $ArchivePath -PathType Leaf)) {
    throw '最终压缩包未生成。'
}

Invoke-Native '完整读取最终压缩包' {
    tar -tzf $ArchivePath *> $null
}

$ArchiveHash = (Get-FileHash -LiteralPath $ArchivePath -Algorithm SHA256).Hash.ToLowerInvariant()
Write-AsciiFile -Path $ArchiveChecksumPath -Lines @("$ArchiveHash  $([IO.Path]::GetFileName($ArchivePath))")

$VerifyRoot = Join-Path $DistRoot ('.verify-' + [guid]::NewGuid().ToString('N'))
$VerifyRootFull = [IO.Path]::GetFullPath($VerifyRoot)
$DistRootFull = [IO.Path]::GetFullPath($DistRoot).TrimEnd('\') + '\'
if (-not $VerifyRootFull.StartsWith($DistRootFull, [StringComparison]::OrdinalIgnoreCase) -or
    -not ([IO.Path]::GetFileName($VerifyRootFull)).StartsWith('.verify-', [StringComparison]::Ordinal)) {
    throw "临时复验目录越界：$VerifyRootFull"
}

New-Item -ItemType Directory -Path $VerifyRootFull | Out-Null
try {
    Invoke-Native '解包复验' {
        tar -xzf $ArchivePath -C $VerifyRootFull
    }
    $ExtractedRoot = Join-Path $VerifyRootFull $KitName
    $ExtractedChecksum = Join-Path $ExtractedRoot 'SHA256SUMS'
    $ExtractedVerifiedCount = Test-ChecksumRecords -Root $ExtractedRoot -ChecksumFile $ExtractedChecksum
    if ($ExtractedVerifiedCount -ne $VerifiedCount) {
        throw "解包复验数量不一致：$ExtractedVerifiedCount/$VerifiedCount"
    }
}
finally {
    if (Test-Path -LiteralPath $VerifyRootFull) {
        Remove-Item -LiteralPath $VerifyRootFull -Recurse -Force
    }
}

$ArchiveInfo = Get-Item -LiteralPath $ArchivePath
[pscustomobject]@{
    KitRoot = $KitRoot
    Archive = $ArchivePath
    ArchiveBytes = $ArchiveInfo.Length
    ArchiveSHA256 = $ArchiveHash
    PackageFilesVerified = $VerifiedCount
    ImageCount = $ImageRecords.Count
}
