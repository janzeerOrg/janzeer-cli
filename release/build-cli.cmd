@echo off
rem Windows twin of build-cli.sh: run in the "x64 Native Tools Command Prompt" with GraalVM 17 (set GRAAL17 to its folder).
rem Produces build\release\jz-<ver>-windows-x64.zip, jz-<ver>.jar and their SHA256SUMS lines.
setlocal
cd /d %~dp0..
if "%GRAAL17%"=="" set GRAAL17=%USERPROFILE%\graalvm-17
set JAVA_HOME=%GRAAL17%
set GRAALVM_HOME=%GRAAL17%
set PATH=%GRAAL17%\bin;%PATH%
for /f "tokens=3 delims== " %%v in ('findstr /r "^    version = " build.gradle.kts') do set VER=%%~v
set VER=%VER:"=%
echo jz %VER% - windows-x64 (native image)
call gradlew.bat --no-daemon -q :jz-core:check :jz-app:fatJar :jz-app:nativeCompile -Pjanzeer.nativeXmx=4g || exit /b 1
set REL=build\release
set NAME=jz-%VER%-windows-x64
if not exist %REL% mkdir %REL%
if exist %REL%\%NAME% rmdir /s /q %REL%\%NAME%
mkdir %REL%\%NAME%
copy /y app\build\native\nativeCompile\jz.exe %REL%\%NAME%\ >nul
copy /y app\build\native\nativeCompile\*.dll %REL%\%NAME%\ >nul 2>nul
copy /y LICENSE %REL%\%NAME%\LICENSE.txt >nul
copy /y app\build\libs\jz-%VER%.jar %REL%\jz-%VER%.jar >nul
(echo version=%VER%& echo os=windows& echo arch=x64& echo built=%DATE% %TIME%) > %REL%\%NAME%\VERSION
(echo jz %VER% - command-line client for the Janzeer blockchain (windows-x64). jz.exe --help for every command.& echo Verify the download with the release SHA256SUMS and its minisign signature; key in the README. Apache-2.0.) > %REL%\%NAME%\README.txt
pushd %REL%\%NAME%
powershell -NoProfile -Command "Get-ChildItem -File | Where-Object { $_.Name -ne 'SHA256SUMS' } | ForEach-Object { (Get-FileHash $_ -Algorithm SHA256).Hash.ToLower() + '  ' + $_.Name } | Set-Content -Encoding ascii SHA256SUMS"
popd
pushd %REL%
if exist %NAME%.zip del %NAME%.zip
powershell -NoProfile -Command "Compress-Archive -Path '%NAME%' -DestinationPath '%NAME%.zip'"
powershell -NoProfile -Command "Get-ChildItem '%NAME%.zip','jz-%VER%.jar' | ForEach-Object { (Get-FileHash $_ -Algorithm SHA256).Hash.ToLower() + '  ' + $_.Name } | Add-Content -Encoding ascii SHA256SUMS"
popd
%REL%\%NAME%\jz.exe version || exit /b 1
echo done: %REL%\%NAME%.zip - add the SHA256SUMS lines to the Linux build's file before signing.
endlocal
