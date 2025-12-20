# build.ps1 - Script de build do SonicSphere
Write-Host "🚀 Iniciando build do SonicSphere..." -ForegroundColor Green

# Configurar Java Home
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
Write-Host "✅ JDK configurado: $env:JAVA_HOME" -ForegroundColor Yellow

# Build
./gradlew clean assembleDebug --no-daemon

Write-Host "🎉 Build finalizado!" -ForegroundColor Green