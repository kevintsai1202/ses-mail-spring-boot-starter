@echo off
rem 測試用的假 aws CLI：轉呼叫 fake-aws.ps1，見該檔說明
pwsh -NoProfile -NonInteractive -File "%~dp0fake-aws.ps1" %*
exit /b %ERRORLEVEL%
