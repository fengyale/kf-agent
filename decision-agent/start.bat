@echo off
title Decision Agent
cd /d "%~dp0"

REM API keys (new window required after setx):
REM   setx DEEPSEEK_API_KEY "your-key"    (LLM, primary)
REM   setx SEARCH_API_KEY "your-key"      (Doubao search)
REM   setx BOCHA_API_KEY "your-key"       (Bocha search)
if "%DEEPSEEK_API_KEY%"=="" (
    echo [WARN] DEEPSEEK_API_KEY not set. LLM calls may fail.
    echo        Use "setx DEEPSEEK_API_KEY your-key" then reopen a new window.
    echo.
)

set "JAVA_HOME=D:\Program Files\Java\jdk-21.0.11"
set "PATH=%JAVA_HOME%\bin;%PATH%"

echo JAVA_HOME: %JAVA_HOME%
echo Starting Decision Agent...
echo App URL: http://localhost:8089
echo Press Ctrl+C to stop.
echo.
call mvn -s settings.xml spring-boot:run
pause
