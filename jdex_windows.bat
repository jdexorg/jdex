@echo off
setlocal enabledelayedexpansion
rem Launcher for jdex (Windows). Edit jvmopt.txt to change JVM options (e.g. the max heap).

set "BASEDIR=%~dp0"

set "JAR=%BASEDIR%jdex.jar"
if not exist "%JAR%" set "JAR=%BASEDIR%app\build\libs\jdex.jar"
if not exist "%JAR%" (
  echo jdex.jar not found. Build it first: gradlew shadowJar
  exit /b 1
)

set "JVMOPT="
if exist "%BASEDIR%jvmopt.txt" (
  for /f "usebackq eol=# tokens=* delims=" %%L in ("%BASEDIR%jvmopt.txt") do set "JVMOPT=!JVMOPT! %%L"
)

if defined JAVA_HOME (
  set "JAVA=%JAVA_HOME%\bin\java.exe"
) else (
  set "JAVA=java"
)

"%JAVA%" %JVMOPT% -jar "%JAR%" %*
exit /b %ERRORLEVEL%
