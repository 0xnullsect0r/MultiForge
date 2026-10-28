@echo off
REM Forge requires a configured set of both JVM and program arguments.
REM Add custom JVM arguments to the user_jvm_args.txt
REM Add custom program arguments {such as nogui} to this file in the next line before the %* or
REM  pass them to this script directly

REM MultiForge: Java 21 exactly. Mods built for NeoForge 1.21.1 cannot read class
REM files of a newer Java. JAVA_HOME wins over the java on PATH.
set "JAVA_BIN=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"
set "JAVA_MAJOR="
for /f "tokens=2 delims==" %%v in ('"%JAVA_BIN%" -XshowSettings:properties -version 2^>^&1 ^| findstr java.specification.version') do set "JAVA_MAJOR=%%v"
if defined JAVA_MAJOR set "JAVA_MAJOR=%JAVA_MAJOR: =%"
if not "%JAVA_MAJOR%"=="21" (
    echo MultiForge requires Java 21. Found: %JAVA_MAJOR% at %JAVA_BIN%
    echo Install a JDK 21 ^(for example Temurin 21^) and point JAVA_HOME at it.
    pause
    exit /b 1
)
"%JAVA_BIN%" @user_jvm_args.txt @libraries/@MAVEN_PATH@/win_args.txt %*
pause
