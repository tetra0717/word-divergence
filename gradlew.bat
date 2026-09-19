@echo off
setlocal
set VERSION=9.3.1
set BASE=%USERPROFILE%\.gradle-bootstrap
set DIST=%BASE%\gradle-%VERSION%
set ZIP=%BASE%\gradle-%VERSION%-bin.zip
if exist "%DIST%\bin\gradle.bat" goto run
if not exist "%BASE%" mkdir "%BASE%"
if not exist "%ZIP%" powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%VERSION%-bin.zip' -OutFile '%ZIP%'"
powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -LiteralPath '%ZIP%' -DestinationPath '%BASE%' -Force"
:run
call "%DIST%\bin\gradle.bat" %*
