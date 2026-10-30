@echo off
rem 词汇塔纯逻辑专项测试（不依赖 Android 设备）
rem 用法：在 mobile\ 目录下运行  test\game\run.bat
rem 依赖：D:\AndroidDev\jdk17 + D:\AndroidDev\libs\json-20240303.jar（verify.py 同款）
setlocal
set JAVA=D:\AndroidDev\jdk17\bin
set JSON_JAR=D:\AndroidDev\libs\json-20240303.jar
cd /d %~dp0..\..

if not exist "%JSON_JAR%" (
    echo 缺少 %JSON_JAR% ，CI 上由 verify.py 自动下载到 /tmp，请先跑一次 python verify.py
    exit /b 1
)

echo ▶ 编译
"%JAVA%\javac.exe" -encoding UTF-8 -cp "%JSON_JAR%" -d build\gameout ^
  src\com\zsb\plan\GCard.java src\com\zsb\plan\GameData.java src\com\zsb\plan\GQuiz.java ^
  src\com\zsb\plan\GBattle.java src\com\zsb\plan\GStore.java ^
  test\game\android\content\Context.java test\game\android\content\SharedPreferences.java ^
  test\game\android\content\res\AssetManager.java test\game\android\util\Log.java ^
  test\game\GameSimTest.java
if errorlevel 1 exit /b 1

echo ▶ 运行
"%JAVA%\java.exe" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dzsb.assets=assets ^
  -cp "%JSON_JAR%;build\gameout" com.zsb.plan.GameSimTest
endlocal
