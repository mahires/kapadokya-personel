\
    @echo off
    title Kapadokya Personel APK Build
    echo.
    echo ============================================
    echo  OZEL KAPADOKYA HASTANESI - ANDROID APK
    echo ============================================
    echo.

    where gradle >nul 2>nul
    if errorlevel 1 (
        echo HATA: gradle komutu bulunamadi.
        echo Projeyi Android Studio ile acmaniz daha kolaydir.
        echo.
        pause
        exit /b 1
    )

    gradle :app:assembleDebug --stacktrace

    if errorlevel 1 (
        echo.
        echo APK olusturma basarisiz.
        pause
        exit /b 1
    )

    echo.
    echo APK hazir:
    echo app\build\outputs\apk\debug\app-debug.apk
    echo.
    pause
