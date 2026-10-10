#!/usr/bin/env pwsh
# =====================================================================
#  Week 1 acceptance: auth end-to-end verification
#
#  Usage:
#    1) start the service:
#         java -jar server-java/target/server-java-1.0.0-SNAPSHOT.jar
#    2) run:
#         pwsh -File scripts/verify-week1.ps1
#
#  Covers:
#    A. GET /health
#    B. GET /v3/api-docs
#    C. register -> login (access + refresh)
#    D. authorised call to /user/profile
#    E. unauthorised call -> 401
#    F. refresh rotates the token pair
#    G. reusing the old refresh token fails
#    H. logout invalidates the refresh token
#    J. miniapp mock login (same code -> same user)
# =====================================================================

param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$Username = "verify_$(Get-Random -Maximum 99999)",
    [string]$Password = "verify123456"
)

$ErrorActionPreference = "Stop"
$script:pass = 0
$script:fail = 0

function Write-Step([string]$name) {
    Write-Host ""
    Write-Host "--- $name " -ForegroundColor Cyan
}

function Assert-True([bool]$cond, [string]$desc, [string]$detail = "") {
    if ($cond) {
        $script:pass++
        Write-Host "  [PASS] $desc" -ForegroundColor Green
    } else {
        $script:fail++
        Write-Host "  [FAIL] $desc" -ForegroundColor Red
    }
    if ($detail) { Write-Host "         $detail" -ForegroundColor DarkGray }
}

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        $Body,
        [string]$Token
    )
    $headers = @{ "Content-Type" = "application/json; charset=utf-8" }
    if ($Token) { $headers["Authorization"] = "Bearer $Token" }

    $params = @{
        Uri        = "$BaseUrl$Path"
        Method     = $Method
        Headers    = $headers
        TimeoutSec = 30
    }
    if ($null -ne $Body) {
        $params["Body"] = ([System.Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 6 -Compress)))
    }

    try {
        # 注意：不能用 -SkipHttpErrorCheck（仅 PowerShell 7+ 支持），
        # 本机宿主是 Windows PowerShell 5.1，因此手动从异常里读回响应体，
        # 否则 401 的响应体会被吞掉、看起来像"没有统一返回体"。
        $resp = Invoke-WebRequest @params -UseBasicParsing -ErrorAction Stop
        $json = $null
        try { $json = $resp.Content | ConvertFrom-Json } catch { }
        return [pscustomobject]@{
            Status     = [int]$resp.StatusCode
            Json       = $json
            Headers    = @{ "X-Trace-Id" = [string]$resp.Headers["X-Trace-Id"] }
            RawContent = $resp.Content
        }
    } catch {
        $errorResponse = $_.Exception.Response
        if ($null -ne $errorResponse) {
            $content = ""
            try {
                $stream = $errorResponse.GetResponseStream()
                if ($null -ne $stream) {
                    $reader = New-Object System.IO.StreamReader($stream)
                    $content = $reader.ReadToEnd()
                    $reader.Dispose()
                }
            } catch { }
            $json = $null
            try { $json = $content | ConvertFrom-Json } catch { }
            $trace = ""
            try { $trace = [string]$errorResponse.Headers["X-Trace-Id"] } catch { }
            return [pscustomobject]@{
                Status     = [int]$errorResponse.StatusCode
                Json       = $json
                Headers    = @{ "X-Trace-Id" = $trace }
                RawContent = $content
            }
        }
        return [pscustomobject]@{
            Status     = -1
            Json       = $null
            Headers    = @{ "X-Trace-Id" = "" }
            RawContent = $_.Exception.Message
            Error      = $_.Exception.GetType().FullName + ": " + $_.Exception.Message
        }
    }
}

Write-Host "================================================================" -ForegroundColor White
Write-Host " Week 1 acceptance - auth end-to-end" -ForegroundColor White
Write-Host " target : $BaseUrl" -ForegroundColor White
Write-Host " account: $Username / $Password" -ForegroundColor White
Write-Host "================================================================" -ForegroundColor White

# ------------------------------------------------------------------ A
Write-Step "A. health check"
$health = Invoke-Api -Method GET -Path "/health"
if ($health.PSObject.Properties.Name -contains "Error") {
    Write-Host "  transport error: $($health.Error)" -ForegroundColor Yellow
}
Assert-True ($health.Status -eq 200) "GET /health returns 200" "actual: $($health.Status)"
Assert-True ($health.Json.code -eq 200) "body.code = 200" "data.status = $($health.Json.data.status)"
$traceHeader = $health.Headers["X-Trace-Id"]
Assert-True ([bool]$traceHeader) "response header X-Trace-Id present" "X-Trace-Id = $traceHeader"
Assert-True ($health.Json.traceId -eq $traceHeader) "body.traceId equals response header" "body = $($health.Json.traceId)"

# ------------------------------------------------------------------ B
Write-Step "B. api docs"
$apiDocs = Invoke-Api -Method GET -Path "/v3/api-docs"
Assert-True ($apiDocs.Status -eq 200) "GET /v3/api-docs returns 200" "actual: $($apiDocs.Status)"
Assert-True ($apiDocs.RawContent -match "/health") "docs contain /health"
Assert-True ($apiDocs.RawContent -match "/auth/login") "docs contain /auth/login"

# ------------------------------------------------------------------ C
Write-Step "C. register and login"

$reg = Invoke-Api -Method POST -Path "/auth/register" -Body @{
    username = $Username; password = $Password; nickname = "verify-user"; examType = "kaoyan"
}
$regOk = ($reg.Status -eq 200) -and (($reg.Json.code -eq 200) -or ($reg.Json.msg -match "registered"))
Assert-True $regOk "POST /auth/register" "code = $($reg.Json.code) msg = $($reg.Json.msg)"

$login = Invoke-Api -Method POST -Path "/auth/login" -Body @{ username = $Username; password = $Password }
Assert-True ($login.Json.code -eq 200) "POST /auth/login succeeds" "code = $($login.Json.code) msg = $($login.Json.msg)"
$accessToken = $login.Json.data.accessToken
$refreshToken = $login.Json.data.refreshToken
Assert-True ([bool]$accessToken) "accessToken returned" "length = $($accessToken.Length)"
Assert-True ([bool]$refreshToken) "refreshToken returned" "length = $($refreshToken.Length)"
Assert-True ($login.Json.data.expiresIn -eq 7200) "accessToken ttl is 2h" "expiresIn = $($login.Json.data.expiresIn)"
Assert-True ($login.Json.data.userInfo.username -eq $Username) "userInfo returned" "username = $($login.Json.data.userInfo.username)"

$badLogin = Invoke-Api -Method POST -Path "/auth/login" -Body @{ username = $Username; password = "wrong_password" }
Assert-True ($badLogin.Json.code -eq 400) "wrong password rejected" "code = $($badLogin.Json.code) msg = $($badLogin.Json.msg)"

# ------------------------------------------------------------------ D
Write-Step "D. authorised request"
$profile = Invoke-Api -Method GET -Path "/user/profile" -Token $accessToken
Assert-True ($profile.Status -eq 200 -and $profile.Json.code -eq 200) "GET /user/profile succeeds" "actual: $($profile.Status)"
Assert-True ($profile.Json.data.username -eq $Username) "returns the user behind the token" "username = $($profile.Json.data.username)"

# ------------------------------------------------------------------ E
Write-Step "E. unauthorised request"
$noToken = Invoke-Api -Method GET -Path "/user/profile"
Assert-True ($noToken.Status -eq 401) "no token -> HTTP 401" "actual: $($noToken.Status)"
Assert-True ($noToken.Json.code -eq 401) "body.code = 401" "msg = $($noToken.Json.msg)"

$fakeToken = Invoke-Api -Method GET -Path "/user/profile" -Token "eyJhbGciOiJIUzI1NiJ9.fake.signature"
Assert-True ($fakeToken.Status -eq 401) "forged token -> HTTP 401" "actual: $($fakeToken.Status)"

# ------------------------------------------------------------------ F
Write-Step "F. refresh rotates the pair"
$refresh = Invoke-Api -Method POST -Path "/auth/refresh" -Body @{ refreshToken = $refreshToken }
Assert-True ($refresh.Json.code -eq 200) "POST /auth/refresh succeeds" "code = $($refresh.Json.code) msg = $($refresh.Json.msg)"
$newAccessToken = $refresh.Json.data.accessToken
$newRefreshToken = $refresh.Json.data.refreshToken
Assert-True ([bool]$newAccessToken) "new accessToken returned"
Assert-True ($newRefreshToken -ne $refreshToken) "refreshToken rotated (new differs from old)"

$profile2 = Invoke-Api -Method GET -Path "/user/profile" -Token $newAccessToken
Assert-True ($profile2.Json.code -eq 200) "new accessToken works" "username = $($profile2.Json.data.username)"

# ------------------------------------------------------------------ G
Write-Step "G. reuse of the old refresh token must fail"
$reuse = Invoke-Api -Method POST -Path "/auth/refresh" -Body @{ refreshToken = $refreshToken }
Assert-True ($reuse.Json.code -eq 401) "old refreshToken rejected" "code = $($reuse.Json.code) msg = $($reuse.Json.msg)"

# ------------------------------------------------------------------ H
Write-Step "H. logout invalidates the refresh token"
$logout = Invoke-Api -Method POST -Path "/auth/logout" -Token $newAccessToken -Body @{ refreshToken = $newRefreshToken }
Assert-True ($logout.Json.code -eq 200) "POST /auth/logout succeeds" "msg = $($logout.Json.msg)"
$afterLogout = Invoke-Api -Method POST -Path "/auth/refresh" -Body @{ refreshToken = $newRefreshToken }
Assert-True ($afterLogout.Json.code -eq 401) "refreshToken dead after logout" "code = $($afterLogout.Json.code) msg = $($afterLogout.Json.msg)"

# ------------------------------------------------------------------ J
Write-Step "J. miniapp mock login"
$wxCode = "test_code_$Username"
$wxLogin = Invoke-Api -Method POST -Path "/auth/wx-login" -Body @{ code = $wxCode; nickname = "verify-wx" }
Assert-True ($wxLogin.Json.code -eq 200) "POST /auth/wx-login succeeds" "code = $($wxLogin.Json.code) msg = $($wxLogin.Json.msg)"
$wxId = $wxLogin.Json.data.userInfo.id
Assert-True ([bool]$wxLogin.Json.data.accessToken) "miniapp accessToken returned" "userId = $wxId"
$wxLoginAgain = Invoke-Api -Method POST -Path "/auth/wx-login" -Body @{ code = $wxCode }
Assert-True ($wxLoginAgain.Json.data.userInfo.id -eq $wxId) "same code maps to same user" "userId = $($wxLoginAgain.Json.data.userInfo.id)"

# ------------------------------------------------------------------ summary
Write-Host ""
Write-Host "================================================================" -ForegroundColor White
$total = $script:pass + $script:fail
if ($script:fail -eq 0) {
    Write-Host " RESULT: all passed ($script:pass/$total)" -ForegroundColor Green
} else {
    Write-Host " RESULT: $script:pass passed / $script:fail failed (total $total)" -ForegroundColor Red
}
Write-Host "================================================================" -ForegroundColor White

exit $script:fail
