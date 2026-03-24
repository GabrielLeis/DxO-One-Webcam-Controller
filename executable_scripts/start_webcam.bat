@echo off
title DXO One Webcam Server
echo Starting DXO One MJPEG Server...
echo.

cd /d "%~dp0"

:: Excecute the Fat JAR
java -jar dxo-one-webcam-server-1.0.1.jar

pause
