import { useState } from "react";
import "./DownloadPage.css";
import { appVersion, installerDownloadUrl } from "../../api/http";
import deployKitIcon from "../../assets/brand/deploykit-icon.png";
import appPreview from "../../assets/brand/deploykit-app-preview.png";

const DownloadPage = () => {
    const [downloadState, setDownloadState] = useState<"idle" | "checking">("idle");
    const [downloadMessage, setDownloadMessage] = useState("");

    const handleDownload = async (event: React.MouseEvent<HTMLAnchorElement>) => {
        event.preventDefault();
        if (downloadState === "checking") return;

        setDownloadState("checking");
        setDownloadMessage("");

        try {
            const response = await fetch(installerDownloadUrl, { method: "HEAD" });
            if (!response.ok) {
                throw new Error(`Installer check failed: ${response.status}`);
            }

            window.location.href = installerDownloadUrl;
        } catch (error) {
            console.error("installer download check failed", error);
            setDownloadMessage("설치 파일이 아직 서버에 준비되지 않았습니다. 관리자에게 다운로드 파일 경로를 확인해 주세요.");
        } finally {
            setDownloadState("idle");
        }
    };

    return (
        <main className="download-page">
            <header className="download-nav">
                <a className="download-nav-brand" href="/" aria-label="deployKit 홈">
                    <img src={deployKitIcon} alt="" aria-hidden="true" />
                    <span>deployKit</span>
                </a>
            </header>

            <section className="download-hero">
                <div className="download-hero-copy">
                    <h1>Deployment Packaging Toolkit</h1>
                    <p className="download-lead">
                        deployKit은 프로젝트 경로 등록부터 날짜와 버전 선택, ZIP 생성까지 로컬 PC에서 끝내는 배포 패키징 도구입니다.
                    </p>

                    <div className="download-actions">
                        <a className="download-primary" href={installerDownloadUrl} onClick={handleDownload}>
                            {downloadState === "checking" ? "파일 확인 중..." : "DeployKit.exe 다운로드"}
                        </a>
                        <span className="download-note">현재 배포 버전 v{appVersion}</span>
                    </div>

                    {downloadMessage && <p className="download-message">{downloadMessage}</p>}
                </div>

                <div className="download-window">
                    <div className="download-window-bar">
                        <div className="download-window-dots" aria-hidden="true">
                            <span />
                            <span />
                            <span />
                        </div>
                        <strong>DeployKit Desktop</strong>
                        <em>로컬에서 실행되는 배포 패키징 화면</em>
                    </div>
                    <img src={appPreview} alt="deployKit 배포 대상과 패키지 추출 화면" />
                </div>
            </section>

            <section className="download-overview" aria-label="deployKit 핵심 흐름">
                <div className="download-section-copy">
                    <span>Local workflow</span>
                    <h2>
                        로컬 환경에서 완성하는
                        <br />
                        배포 패키징 워크플로우
                    </h2>
                    <p>
                        Git · SVN 변경 파일 분석부터
                        <br />
                        배포 산출물 생성까지 지원합니다.
                    </p>
                </div>

                <div className="download-feature-grid">
                    <article>
                        <span>01</span>
                        <strong>경로 관리</strong>
                        <p>프로젝트 경로와 배포 경로를 등록하고 JSON 파일로 백업하거나 다시 가져올 수 있습니다.</p>
                    </article>
                    <article>
                        <span>02</span>
                        <strong>날짜와 버전 선택</strong>
                        <p>원하는 기간과 Git, SVN, 로컬 수정 시간 기준으로 필요한 변경 파일을 조회합니다.</p>
                    </article>
                    <article>
                        <span>03</span>
                        <strong>패키지 추출</strong>
                        <p>선택한 파일만 ZIP으로 내려받아 불필요한 배포 범위와 반복 작업을 줄입니다.</p>
                    </article>
                </div>
            </section>

            <footer className="download-contact">
                <span>문의나 개선 요청은 아래 채널로 남겨주세요.</span>
                <div>
                    <a href="https://github.com/Backjinuk/deployProject" target="_blank" rel="noreferrer">
                        GitHub
                    </a>
                    <a href="mailto:backj123@naver.com">backj123@naver.com</a>
                </div>
            </footer>
        </main>
    );
};

export default DownloadPage;
