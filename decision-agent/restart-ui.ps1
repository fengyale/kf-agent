# restart-ui.ps1 - TRAE 沙箱外执行：同步前端资源到 target/classes 并重启 decision-agent
$ErrorActionPreference = 'Continue'
# 路径全部基于脚本自身位置，项目目录迁移后无需修改
$root = $PSScriptRoot
$log = Join-Path $root 'restart-ui.log'
Start-Transcript -Path $log -Force

# 1. 同步静态资源与模板（Java 类未改动，仅镜像前端文件）
robocopy (Join-Path $root 'src\main\resources\static') (Join-Path $root 'target\classes\static') /MIR /NFL /NDL /NJH /NP
robocopy (Join-Path $root 'src\main\resources\templates') (Join-Path $root 'target\classes\templates') /MIR /NFL /NDL /NJH /NP
Write-Host "robocopy done (static/templates synced)"

# 2. 注入 API key（TRAE 进程读不到 setx 的用户级变量，从注册表取）+ 显式 JDK 21（WMI 默认 PATH 会解析到 JDK 8）
$env:DEEPSEEK_API_KEY = [Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY', 'User')
$env:SEARCH_API_KEY = [Environment]::GetEnvironmentVariable('SEARCH_API_KEY', 'User')
$env:JAVA_HOME = 'D:\Program Files\Java\jdk-21.0.11'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
Write-Host ("keys injected: deepseek={0} search={1}; JAVA_HOME={2}" -f [bool]$env:DEEPSEEK_API_KEY, [bool]$env:SEARCH_API_KEY, $env:JAVA_HOME)

# 3. 启动服务（前台保持运行，由 WMI 进程托管；显式 -f 指定 pom + -s 项目 settings，与 start.bat 一致）
Write-Host "starting mvn spring-boot:run ..."
mvn -s (Join-Path $root 'settings.xml') -f (Join-Path $root 'pom.xml') spring-boot:run
Stop-Transcript
