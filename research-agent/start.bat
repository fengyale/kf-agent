@echo off
title AI Research Agent
cd /d "%~dp0"

REM Search key is set via: setx SEARCH_API_KEY "your-key" (new window required)
if "%SEARCH_API_KEY%"=="" (
    echo [WARN] SEARCH_API_KEY not set. Search may not work.
    echo        Use "setx SEARCH_API_KEY your-key" then reopen a new window.
    echo.
    pause
)

set "JAVA_HOME=D:\Program Files\Java\jdk-21.0.11"
set "PATH=%JAVA_HOME%\bin;%PATH%"

echo JAVA_HOME: %JAVA_HOME%
echo Starting AI Research Agent...
echo App URL: http://localhost:8088
echo Press Ctrl+C to stop.
echo.
call mvn spring-boot:run
pause
