<#
.SYNOPSIS
    wibecoding Qdrant collection init/migration (M7 schema: named dense + sparse)
.DESCRIPTION
    Creates the knowledge collection with M7 schema:
      - dense  : named dense vector (Cosine, dimension from param, default 1024 for BGE-M3)
      - sparse : named sparse vector (Qdrant native sparse_vectors, for BM25-style keyword recall)
    Idempotent: if collection exists, only prints status by default.
    Use -Recreate to drop and recreate (destroys data).
.PARAMETER QdrantUrl
    Qdrant HTTP endpoint, default http://localhost:6333
.PARAMETER Collection
    Collection name, default knowledge
.PARAMETER Dimension
    Dense vector dimension, default 1024 (BGE-M3)
.PARAMETER Recreate
    Switch: drop existing collection then recreate (irreversible, clears vectors)
.EXAMPLE
    .\scripts\qdrant-setup.ps1
    .\scripts\qdrant-setup.ps1 -Recreate
    .\scripts\qdrant-setup.ps1 -QdrantUrl http://localhost:6333 -Collection knowledge -Dimension 1024 -Recreate
#>
[CmdletBinding()]
param(
    [string]$QdrantUrl = "http://localhost:6333",
    [string]$Collection = "knowledge",
    [int]$Dimension = 1024,
    [switch]$Recreate
)

$ErrorActionPreference = "Stop"

function Invoke-Qdrant {
    param([string]$Method, [string]$Path, $Body)
    $uri = "$QdrantUrl$Path"
    if ($Body) {
        $json = $Body | ConvertTo-Json -Depth 10 -Compress
        Invoke-RestMethod -Method $Method -Uri $uri -ContentType "application/json" -Body $json
    } else {
        Invoke-RestMethod -Method $Method -Uri $uri
    }
}

Write-Host "=== Qdrant collection setup ===" -ForegroundColor Cyan
Write-Host "QdrantUrl : $QdrantUrl"
Write-Host "Collection: $Collection"
Write-Host "Dimension : $Dimension (dense)"
Write-Host "Recreate  : $([bool]$Recreate)"
Write-Host ""

# 1. health check
try {
    Invoke-Qdrant -Method GET -Path "/" | Out-Null
} catch {
    Write-Error "Cannot reach Qdrant at $QdrantUrl. Run 'docker compose up -d qdrant' first."
    exit 1
}

# 2. check existence
$exists = $false
try {
    $info = Invoke-Qdrant -Method GET -Path "/collections/$Collection"
    $exists = $true
    Write-Host "Collection exists: status=$($info.result.status) vectors=$($info.result.vectors_count)" -ForegroundColor Yellow
} catch {
    if ($_.Exception.Response.StatusCode.value__ -ne 404) { throw }
    Write-Host "Collection does not exist, will create"
}

# 3. recreate branch
if ($Recreate -and $exists) {
    Write-Host "Dropping collection $Collection ..." -ForegroundColor Yellow
    Invoke-Qdrant -Method DELETE -Path "/collections/$Collection" | Out-Null
    $exists = $false
    Write-Host "Dropped" -ForegroundColor Green
}

if ($exists) {
    Write-Host "Keeping existing collection (use -Recreate to rebuild)" -ForegroundColor Green
    return
}

# 4. create collection (M7 schema)
$body = @{
    vectors = @{
        dense = @{
            size = $Dimension
            distance = "Cosine"
        }
    }
    sparse_vectors = @{
        sparse = @{}
    }
}

Write-Host "Creating collection $Collection (dense ${Dimension}d Cosine + sparse)..."
Invoke-Qdrant -Method PUT -Path "/collections/$Collection" -Body $body | Out-Null
Write-Host "Created" -ForegroundColor Green

# 5. verify
$verify = Invoke-Qdrant -Method GET -Path "/collections/$Collection"
$vectorsCfg = $verify.result.config.params.vectors | ConvertTo-Json -Compress
$sparseCfg = $verify.result.config.params.sparse_vectors | ConvertTo-Json -Compress
Write-Host ""
Write-Host "=== Verify ===" -ForegroundColor Cyan
Write-Host "status        : $($verify.result.status)"
Write-Host "vectors_count : $($verify.result.vectors_count)"
Write-Host "config.vectors: $vectorsCfg"
Write-Host "config.sparse : $sparseCfg"
