@echo off
rem DocWorks - a small word processor
rem Copyright (C) 2026 Adam Kost
rem Licensed under the GNU General Public License version 3 (see LICENSE).
rem
rem Starts DocWorks after it has been built with:  mvn package
rem Usage: docworks.bat [file.doc ...]

setlocal
set "JAR=%~dp0target\docworks.jar"

if not exist "%JAR%" (
    echo Cannot find "%JAR%".
    echo Build the application first by running:  mvn package
    pause
    exit /b 1
)

where javaw >nul 2>nul
if errorlevel 1 (
    echo Java was not found on your PATH. DocWorks needs Java 11 or newer.
    echo Install a JDK or JRE, or add its bin folder to PATH, and try again.
    pause
    exit /b 1
)

rem javaw starts the app without leaving a console window open.
rem Files passed to this script are opened as documents.
start "DocWorks" javaw -jar "%JAR%" %*
endlocal
