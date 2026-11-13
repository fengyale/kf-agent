$env:JAVA_HOME = 'D:\Program Files\Java\jdk-21.0.11'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:SEARCH_API_KEY = [Environment]::GetEnvironmentVariable('SEARCH_API_KEY', 'User')
Write-Host "JAVA_HOME=$env:JAVA_HOME"
Write-Host "SEARCH_API_KEY set: $([bool]$env:SEARCH_API_KEY)"
& 'D:\Program Files\JetBrains\IntelliJ IDEA 2026.1.3\plugins\maven\lib\maven3\bin\mvn.cmd' -s settings.xml spring-boot:run
