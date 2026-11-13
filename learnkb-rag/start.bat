@echo off
title 学习知识库问答 (learnkb-rag)
cd /d "%~dp0"

rem JDK 21
set JAVA_HOME=D:\Program Files\Java\jdk-21.0.11
set JAVA=%JAVA_HOME%\bin\java.exe
if not exist "%JAVA%" set JAVA=java

rem Ollama 就绪检查：已在运行直接跳过；未运行则拉起并轮询等待（最多约 40 秒）
curl -s -o nul --max-time 3 http://localhost:11434/api/tags
if not errorlevel 1 goto ready
echo [info] Ollama 未运行，正在启动并等待就绪...
start "" /min "d:\ollama\ollama.exe" serve
set /a tries=0

:waitloop
if %tries% geq 20 goto ready
timeout /t 2 /nobreak >nul
curl -s -o nul --max-time 3 http://localhost:11434/api/tags
if not errorlevel 1 goto ready
set /a tries+=1
goto waitloop

:ready
echo [info] 启动知识库服务，浏览器访问 http://localhost:8090
echo [info] 注意：修改 application.yml 后需关闭本窗口重新启动才生效
echo [info] 笔记有更新时，在页面上点击「建立/重建索引」
"%JAVA%" -jar target\learnkb-rag-1.0.0.jar
pause
