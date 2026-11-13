@echo off
title Maven 仓库合并到 D:\ownerMaven
chcp 936 >nul
echo.
echo  [1/3] 将 C:\Users\fengyale\.m2\repository 合并到 D:\ownerMaven\mavenRepository
echo        (同版本文件自动跳过，只补充新依赖，不会覆盖已有内容)
robocopy "C:\Users\fengyale\.m2\repository" "D:\ownerMaven\mavenRepository" /E /R:1 /W:1 /NFL /NDL
if errorlevel 8 goto :fail
echo.
echo  [2/3] 合并成功，删除 C 盘副本释放空间...
rd /s /q "C:\Users\fengyale\.m2\repository"
echo.
echo  [3/3] 完成！今后所有项目共用 D:\ownerMaven\mavenRepository
echo        (已通过 C:\Users\fengyale\.m2\settings.xml 配置 localRepository 指向该目录)
echo.
pause
exit /b 0
:fail
echo.
echo  合并失败(robocopy 错误码 %errorlevel%)，C 盘副本已保留，请检查后重试
pause