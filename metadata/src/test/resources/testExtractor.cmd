@echo off
setlocal

set "PROD_FILE=%~1"
set "MET_FILE=%~2"

for %%I in ("%PROD_FILE%") do set "PROD_DIR=%%~dpI"
for %%I in ("%MET_FILE%") do set "MET_DIR=%%~dpI"

if /I "%PROD_DIR%"=="%MET_DIR%" copy /Y "<TEST_SAMPLE_MET_PATH>" "%MET_FILE%" >nul
