$ErrorActionPreference = 'Continue'

# a) 8080 监听 PID 与启动时间
$conn = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($conn) {
    $proc = Get-Process -Id $conn.OwningProcess
    Write-Output ("LISTENER: PID={0} StartTime={1}" -f $conn.OwningProcess, $proc.StartTime)
} else {
    Write-Output "NO_LISTENER"
}

# b) GET / 状态码
$r1 = Invoke-WebRequest -Uri 'http://localhost:8080/' -UseBasicParsing
Write-Output ("GET / -> {0}" -f $r1.StatusCode)

# c) POST 登录拿 token
$body = '{"username":"admin","password":"admin123"}'
$r2 = Invoke-RestMethod -Uri 'http://localhost:8080/api/v1/auth/login' -Method Post -ContentType 'application/json' -Body $body
Write-Output ("LOGIN RESPONSE: " + ($r2 | ConvertTo-Json -Compress))
$token = $r2
if ($r2 -isnot [string]) { $token = $r2.token; if (-not $token) { $token = $r2.data.token } }
Write-Output ("TOKEN_HEAD: " + $token.Substring(0, [Math]::Min(20, $token.Length)) + '...')

# d) 带 token GET 库存日汇总（不带日期参数）
$headers = @{ Authorization = "Bearer $token" }
try {
    $r3 = Invoke-WebRequest -Uri 'http://localhost:8080/api/v1/workshops/integration/inventory/daily-summary' -Headers $headers -UseBasicParsing
    Write-Output ("DAILY-SUMMARY -> {0}" -f $r3.StatusCode)
    Write-Output ("BODY: " + $r3.Content.Substring(0, [Math]::Min(500, $r3.Content.Length)))
} catch {
    Write-Output ("DAILY-SUMMARY FAILED: " + $_.Exception.Message)
    if ($_.Exception.Response) {
        $sr = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
        Write-Output ("ERROR_BODY: " + $sr.ReadToEnd())
    }
}
