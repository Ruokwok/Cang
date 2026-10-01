@echo off
rem Cang compiler launcher: cang <namespace/Main[.cang]> [options]
set "CANG_JAVA=java"
if defined JAVA_HOME set "CANG_JAVA=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_HOME (
    where java >nul 2>nul || set "CANG_JAVA=D:\Program Files\.jdks\openjdk-17.0.2\bin\java.exe"
)
"%CANG_JAVA%" -cp "%~dp0target\classes" Cang %*
