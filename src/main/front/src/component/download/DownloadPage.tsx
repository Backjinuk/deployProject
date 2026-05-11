import { useState } from "react";
import "./DownloadPage.css";
import { installerDownloadUrl } from "../../api/http";

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
            <section className="download-hero">
                <div className="download-copy">
                    <span className="download-badge">Desktop Installer</span>
                    <h1>DeployProject</h1>
                    <p className="download-lead">
                        로컬 PC의 Git, SVN, 파일 수정 정보를 읽어 배포 패키지와 서버 패치 스크립트를 생성하는 설치형 도구입니다.
                    </p>

                    <div className="download-actions">
                        <a className="download-primary" href={installerDownloadUrl} onClick={handleDownload}>
                            {downloadState === "checking" ? "파일 확인 중..." : "DeployProject.exe 다운로드"}
                        </a>
                        <span className="download-note">Windows 설치 파일</span>
                    </div>

                    {downloadMessage && <p className="download-message">{downloadMessage}</p>}
                </div>
            </section>

            <section className="download-steps" aria-label="DeployProject usage flow">
                <article>
                    <strong>1. 설치</strong>
                    <p>다운로드한 exe를 실행해 DeployProject를 설치합니다.</p>
                </article>
                <article>
                    <strong>2. 경로 등록</strong>
                    <p>로컬 프로젝트 경로, JDK 경로, 배포 서버 경로를 등록합니다.</p>
                </article>
                <article>
                    <strong>3. 패키지 생성</strong>
                    <p>버전 또는 수정일 기준으로 파일을 선택하고 배포 패키지를 생성합니다.</p>
                </article>
            </section>
        </main>
    );
};

export default DownloadPage;
