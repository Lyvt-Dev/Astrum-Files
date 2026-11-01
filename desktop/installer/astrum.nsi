; Astrum Files Windows installer (NSIS). Build: makensis -DPAYLOAD=<dir with jre\, AstrumFiles.jar, astrum.ico> astrum.nsi
Unicode true
!include "MUI2.nsh"
Name "Astrum Files"
OutFile "${OUTFILE}"
InstallDir "$LOCALAPPDATA\Programs\Astrum Files"
InstallDirRegKey HKCU "Software\Astrum Files" "InstallDir"
RequestExecutionLevel user
SetCompressor /SOLID lzma
!define MUI_ICON "${PAYLOAD}\astrum.ico"
!define MUI_UNICON "${PAYLOAD}\astrum.ico"
!define MUI_FINISHPAGE_RUN
!define MUI_FINISHPAGE_RUN_TEXT "Astrum Files starten"
!define MUI_FINISHPAGE_RUN_FUNCTION LaunchApp
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "German"
!insertmacro MUI_LANGUAGE "English"
VIProductVersion "1.3.0.0"
VIAddVersionKey /LANG=0 "ProductName" "Astrum Files"
VIAddVersionKey /LANG=0 "FileVersion" "1.3.0"
VIAddVersionKey /LANG=0 "FileDescription" "Astrum Files Setup"

Section "Install"
  SetOutPath "$INSTDIR"
  File /r "${PAYLOAD}\*"
  WriteUninstaller "$INSTDIR\Uninstall.exe"
  CreateDirectory "$SMPROGRAMS\Astrum Files"
  CreateShortcut "$SMPROGRAMS\Astrum Files\Astrum Files.lnk" "$INSTDIR\jre\bin\javaw.exe" '-jar "$INSTDIR\AstrumFiles.jar"' "$INSTDIR\astrum.ico"
  CreateShortcut "$DESKTOP\Astrum Files.lnk" "$INSTDIR\jre\bin\javaw.exe" '-jar "$INSTDIR\AstrumFiles.jar"' "$INSTDIR\astrum.ico"
  WriteRegStr HKCU "Software\Astrum Files" "InstallDir" "$INSTDIR"
  !define UK "Software\Microsoft\Windows\CurrentVersion\Uninstall\AstrumFiles"
  WriteRegStr HKCU "${UK}" "DisplayName" "Astrum Files"
  WriteRegStr HKCU "${UK}" "DisplayVersion" "1.3.0"
  WriteRegStr HKCU "${UK}" "Publisher" "Astrum"
  WriteRegStr HKCU "${UK}" "DisplayIcon" "$INSTDIR\astrum.ico"
  WriteRegStr HKCU "${UK}" "UninstallString" '"$INSTDIR\Uninstall.exe"'
  WriteRegDWORD HKCU "${UK}" "NoModify" 1
SectionEnd

Section "Uninstall"
  Delete "$DESKTOP\Astrum Files.lnk"
  RMDir /r "$SMPROGRAMS\Astrum Files"
  RMDir /r "$INSTDIR"
  DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AstrumFiles"
  DeleteRegKey HKCU "Software\Astrum Files"
SectionEnd

Function LaunchApp
  Exec '"$INSTDIR\jre\bin\javaw.exe" -jar "$INSTDIR\AstrumFiles.jar"'
FunctionEnd
