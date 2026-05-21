# deployKit

deployKit은 로컬 PC에서 Git, SVN, 일반 폴더 프로젝트의 변경 파일을 분석하고 배포 패키지 ZIP을 생성하는 Windows 데스크톱 배포 도구입니다.

운영 서버 API에 의존해 패키지를 생성하지 않고, 설치된 PC 안에서 Spring Boot 로컬 서버와 React 화면이 함께 동작합니다. 운영 웹사이트는 설치 파일 다운로드와 최신 버전 안내 용도로만 사용합니다.

## 주요 기능

- Git 커밋, SVN 리비전, 일반 로컬 파일 변경 이력 기반 배포 대상 조회
- 날짜, 버전, 파일 단위 선택 후 배포 ZIP 생성
- 동일 파일이 여러 버전에 포함될 때 중복 파일 버전 선택 지원
- Java 프로젝트의 `.java` 변경 파일을 `.class`로 컴파일해 패키징
- 프로젝트 경로, 배포 서버 경로, 로컬 프로젝트 경로, JDK 경로 등록/수정
- 경로 목록 JSON 다운로드 및 가져오기
- 다크모드, 앱 업데이트 알림, GitHub/Email 문의 링크
- Windows 설치 파일 생성 및 같은 앱으로 업데이트 설치

## 기술 스택

### Backend

- Kotlin 1.9.25
- Spring Boot 3.4.4
- Spring Web MVC
- Jackson Kotlin
- JGit 6.7
- SVNKit 1.10
- Java 17

### Frontend

- React 19
- TypeScript 4.9
- Create React App
- Bootstrap 5
- SweetAlert2
- react-day-picker
- Axios

### Desktop / Packaging

- JavaFX WebView
- jpackage
- WiX Toolset, Windows EXE 인스톨러 생성 시 필요
- Gradle Kotlin DSL

## 동작 구조

```text
DeployKit.exe
  └─ Spring Boot desktop profile
      ├─ 127.0.0.1:{random port} 로컬 서버 실행
      ├─ React 정적 파일 제공
      ├─ JavaFX WebView 데스크톱 창 표시
      ├─ Git/SVN/Local 변경 파일 분석
      └─ ZIP 패키지 생성 후 다운로드 응답
```

데스크톱 앱은 `application-desktop.yml` 기준으로 실행되며 로컬 포트는 기본적으로 자동 할당됩니다.

일반 서버 실행 시에는 `application.yml` 기준으로 `9090` 포트를 사용합니다.

## 저장 데이터

DB는 사용하지 않습니다. 사용자별 로컬 파일로 관리합니다.

| 항목 | 기본 위치 |
| --- | --- |
| 등록 경로 | `%USERPROFILE%\.deploy-project\sites.json` |
| 사용자 설정 | `%USERPROFILE%\.deploy-project\preferences.json` |
| 로그 | `%USERPROFILE%\.deploy-project\logs\deploy-project.log` |
| 추출 임시 작업 | 실행 위치의 `GitInfoJarFile` 또는 `%USERPROFILE%\.deploy-project\runtime\GitInfoJarFile` |

추출 임시 작업 디렉터리는 다운로드 완료 후 정리되며, 오래 남은 작업 디렉터리는 24시간 기준으로 정리됩니다.

## 주요 API

| API | 설명 |
| --- | --- |
| `GET /api/sites` | 등록된 경로 목록 조회 |
| `GET /api/pathList` | 경로 관리 목록 조회 |
| `POST /api/savedPath` | 경로 등록 |
| `POST /api/updatePath` | 경로 수정 |
| `POST /api/deletePath` | 경로 삭제 처리 |
| `POST /api/select-directory` | OS 폴더 선택 창 호출 |
| `POST /api/git/versions` | Git/SVN/Local 기준 버전 목록 조회 |
| `POST /api/git/version-files` | 선택 버전의 변경 파일 조회 |
| `POST /api/git/extraction` | 선택 파일 기준 배포 ZIP 생성 |
| `GET /api/app/update-check` | 최신 앱 버전 확인 |
| `POST /api/app/open-update-installer` | 업데이트 다운로드 페이지 열기 |
| `GET /download/deploykit.exe` | 설치 파일 다운로드 |

## 빌드

### React 빌드

```powershell
.\gradlew.bat buildFrontend
```

React 결과물은 `src/main/front/build`에 생성되고, Spring Boot 정적 리소스로 복사됩니다.

### Spring Boot JAR 빌드

```powershell
.\gradlew.bat bootJar
```

### Windows 설치 파일 생성

```powershell
.\gradlew.bat desktopInstaller
```



생성 결과:

```text
build/download/DeployKit.exe
```

Windows EXE 인스톨러 생성을 위해서는 JDK 17의 `jpackage`와 WiX Toolset이 필요합니다.

## 배포 파일 제공

운영 다운로드 페이지는 `https://deploy.jinukl.dev` 기준으로 동작합니다.

관련 파일:

- `build/download/DeployKit.exe`
- `src/main/front/public/version.json`
- `/download/deploykit.exe`
- `/version.json`

앱 업데이트 확인은 `version.json`의 `version`, `installerUrl`, `message`, `releaseNotes` 값을 기준으로 동작합니다.

## 환경 변수 / 설정

| 이름 | 설명 |
| --- | --- |
| `DEPLOY_PROJECT_UI_MODE` | `APP` 또는 `DOWNLOAD` 화면 모드 |
| `DEPLOY_PROJECT_PORT` | desktop profile 로컬 서버 포트 |
| `DEPLOY_PROJECT_LATEST_VERSION_URL` | 최신 버전 manifest URL |
| `DEPLOY_PROJECT_INSTALLER_DOWNLOAD_URL` | 설치 파일 다운로드 URL |
| `DEPLOY_PROJECT_INSTALLER_PATH` | 직접 지정한 설치 파일 경로 |
| `DEPLOY_PROJECT_INSTALLER_DIR` | 설치 파일 검색 디렉터리 |
| `DEPLOY_PROJECT_INSTALLER_FILE_NAME` | 다운로드 시 노출할 파일명 |
| `DEPLOY_PROJECT_SITES_FILE` | 경로 저장 JSON 파일 위치 |
| `DEPLOY_PROJECT_LOG_FILE` | 로그 파일 위치 |

## 배포 패키지 생성 흐름

1. 사용자가 프로젝트 경로와 배포 서버 경로를 등록합니다.
2. Git, SVN, Local 중 프로젝트 형식을 자동 판별합니다.
3. 날짜 범위를 선택합니다.
4. Git/SVN 프로젝트는 버전 목록을 조회하고, Local 프로젝트는 수정일 기준 파일을 조회합니다.
5. 변경 파일을 선택합니다.
6. 동일 파일이 여러 버전에 있을 경우 사용할 버전을 지정합니다.
7. 선택한 파일을 배포 서버 경로 구조에 맞춰 모읍니다.
8. Java 파일은 필요한 경우 JDK 경로를 사용해 `.class` 산출물을 생성합니다.
9. 최종 산출물을 ZIP으로 묶어 다운로드합니다.

## 로그 확인

기본 로그 파일:

```text
%USERPROFILE%\.deploy-project\logs\deploy-project.log
```

패키지 추출 단계별 시간은 로그에서 다음 형태로 확인할 수 있습니다.

```text
Extraction phase started: ...
Extraction phase finished: ...
```

## 문의

- GitHub: https://github.com/Backjinuk/deployProject
- Email: backj123@naver.com
