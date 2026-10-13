@echo off
rem ===================================================================
rem  本地 Android SDK 引导脚本（仅开发机使用，CI 不需要）
rem  作用：接受许可并安装 platform-35 / build-tools 35.0.0
rem  安装位置：<工程根>\.toolchain\android-sdk
rem ===================================================================
setlocal
set "ROOT=%~dp0.."
set "SDK=%ROOT%\.toolchain\android-sdk"

rem 如未设置 JAVA_HOME，请改成你自己的 JDK 17/21 路径
if "%JAVA_HOME%"=="" set "JAVA_HOME=C:\Program Files\Java\jdk-17"
set "PATH=%JAVA_HOME%\bin;%PATH%"

set "SM=%SDK%\cmdline-tools\latest\bin\sdkmanager.bat"

echo === SDK ROOT: %SDK% ===
if not exist "%SM%" (
    echo [ERROR] 未找到 sdkmanager: %SM%
    echo         请先解压 cmdline-tools 到 %SDK%\cmdline-tools\latest
    exit /b 1
)

echo === ACCEPT LICENSES ===
(for /l %%i in (1,1,40) do @echo y) | call "%SM%" --sdk_root=%SDK% --licenses

echo === INSTALL PACKAGES ===
call "%SM%" --sdk_root=%SDK% --install "platform-tools" "platforms;android-35" "build-tools;35.0.0"
echo === INSTALL EXIT=%ERRORLEVEL% ===

echo === SDK CONTENT ===
dir /b "%SDK%"
endlocal
