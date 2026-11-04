@echo off
REM Forge requires a configured set of both JVM and program arguments.
REM Add custom JVM arguments to the user_jvm_args.txt
REM Add custom program arguments {such as nogui} to this file in the next line before the %* or
REM  pass them to this script directly

REM MultiForge: Java 21 exactly. Mods built for NeoForge 1.21.1 cannot read class
REM files of a newer Java. JAVA_HOME, when it points at a JDK, goes first on PATH.
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "PATH=%JAVA_HOME%\bin;%PATH%"
set "JAVA_MAJOR="
for /f "tokens=2 delims==" %%v in ('java -XshowSettings:properties -version 2^>^&1 ^| findstr java.specification.version') do set "JAVA_MAJOR=%%v"
if defined JAVA_MAJOR set "JAVA_MAJOR=%JAVA_MAJOR: =%"
if not "%JAVA_MAJOR%"=="21" (
    echo MultiForge requires Java 21. Found: %JAVA_MAJOR%
    echo Install a JDK 21 ^(for example Temurin 21^) and point JAVA_HOME at it.
    pause
    exit /b 1
)
REM NeoForged's server starter (server.jar) skips "@" and "REM" lines and launches the first
REM line whose first word is java: keep the launch line below the only such line.
java @user_jvm_args.txt @libraries/@MAVEN_PATH@/win_args.txt %*
pause
