import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { styles } from "../../styles/IntroGuideStyles";
import guideDownloadComplete from "../../assets/guide/guide-download-complete.png";
import guideDuplicateModal from "../../assets/guide/guide-duplicate-modal.png";
import guideMainDuplicates from "../../assets/guide/guide-main-duplicates.png";
import guideMainEmpty from "../../assets/guide/guide-main-empty.png";
import guideMainFiles from "../../assets/guide/guide-main-files.png";
import guidePatchMenu from "../../assets/guide/guide-patch-menu.png";
import guidePathRegister from "../../assets/guide/guide-path-register.png";
import guideResultFolder from "../../assets/guide/guide-result-folder.png";
import guideSiteSelect from "../../assets/guide/guide-site-select.png";
import guideTerminalRun from "../../assets/guide/guide-terminal-run.png";
import guideWebExtracting from "../../assets/guide/guide-web-extracting.png";

type FlowNode = {
    title: string;
    text: string;
};

type GuideImage = {
    src: string;
    caption: string;
};

type Slide = {
    eyebrow: string;
    title: string;
    summary: string;
    images: GuideImage[];
    nodes: FlowNode[];
    note: string;
};

type Props = {
    show: boolean;
    onClose: () => void;
};

type MotionDirection = "next" | "prev";

const INTRO_GUIDE_ANIMATION_CSS = `
@keyframes introGuideSlideNext {
    from { opacity: 0.25; transform: translateX(36px); }
    to { opacity: 1; transform: translateX(0); }
}

@keyframes introGuideSlidePrev {
    from { opacity: 0.25; transform: translateX(-36px); }
    to { opacity: 1; transform: translateX(0); }
}

@keyframes introGuidePagerPulse {
    0% { transform: translateY(0); }
    50% { transform: translateY(-2px); }
    100% { transform: translateY(0); }
}

.intro-guide-motion {
    animation-duration: 260ms;
    animation-fill-mode: both;
    animation-timing-function: cubic-bezier(0.22, 0.74, 0.22, 1);
    will-change: transform, opacity;
}

.intro-guide-motion-next {
    animation-name: introGuideSlideNext;
}

.intro-guide-motion-prev {
    animation-name: introGuideSlidePrev;
}

.intro-guide-dot-active {
    animation: introGuidePagerPulse 220ms ease-out;
}

@media (prefers-reduced-motion: reduce) {
    .intro-guide-motion,
    .intro-guide-dot-active {
        animation: none;
    }
}
`;

const slides: Slide[] = [
    {
        eyebrow: "1. 경로 준비",
        title: "배포 경로를 등록하거나 파일에서 가져옵니다",
        summary:
            "처음 실행하면 사이트별로 사용할 서버 ROOT 경로, 로컬 프로젝트 경로, JDK 경로를 등록합니다. 백업해 둔 JSON 파일이 있다면 경로 가져오기로 한 번에 등록할 수 있습니다.",
        images: [
            { src: guidePathRegister, caption: "경로 등록 모달" },
            { src: guideSiteSelect, caption: "배포 대상 선택" },
        ],
        nodes: [
            {
                title: "경로 등록",
                text: "서버 ROOT, 로컬 프로젝트, JDK 폴더를 저장합니다. Java 파일을 class 파일로 만들 프로젝트라면 JDK 경로가 필요합니다.",
            },
            {
                title: "경로 가져오기/다운로드",
                text: "경로 다운로드로 현재 목록을 JSON으로 백업하고, 경로 가져오기로 다른 PC 또는 재설치 환경에서 다시 등록합니다. 이미 있는 경로는 중복 등록되지 않습니다.",
            },
        ],
        note: "등록한 경로는 이 PC의 로컬 파일에 저장됩니다. 경로 등록 후 목록에서 패키지를 만들 대상을 선택합니다.",
    },
    {
        eyebrow: "2. 대상/날짜 선택",
        title: "배포 대상을 선택하고 조회 기간을 지정합니다",
        summary:
            "배포할 사이트를 선택하면 기본 화면으로 이동합니다. 여기서 변경 파일을 찾을 시작일과 종료일을 선택합니다.",
        images: [
            { src: guideSiteSelect, caption: "배포 대상 선택" },
            { src: guideMainEmpty, caption: "대상 선택 후 기본 화면 및 날짜 선택" },
        ],
        nodes: [
            {
                title: "배포 대상 선택",
                text: "패키지를 만들 사이트 또는 프로젝트를 선택합니다.",
            },
            {
                title: "대상 선택 후 기본 화면 및 날짜 선택",
                text: "선택한 사이트의 저장 경로와 작업 영역을 확인하고, 조회할 시작일과 종료일을 입력합니다.",
            },
        ],
        note: "Git/SVN을 쓰지 않는 LOCAL 프로젝트는 버전 선택 없이 파일 최종 수정 시간 기준으로 조회됩니다.",
    },
    {
        eyebrow: "3. 버전/파일 확정",
        title: "버전과 파일을 선택하고 중복 파일을 정리합니다",
        summary:
            "Git/SVN 프로젝트는 조회된 버전을 선택한 뒤 변경 파일을 불러옵니다. 같은 파일이 여러 버전에 있으면 중복 파일 버전 선택 모달에서 최종 버전을 확정합니다.",
        images: [
            { src: guideMainFiles, caption: "버전 선택 및 파일 조회, 선택" },
            { src: guideMainDuplicates, caption: "중복 파일 표시" },
            { src: guideDuplicateModal, caption: "중복 파일 버전 선택" },
        ],
        nodes: [
            {
                title: "버전 선택 및 파일 조회, 선택",
                text: "조회 기간에 포함된 commit 또는 revision을 선택하고, 배포 패키지에 포함할 파일을 체크합니다.",
            },
            {
                title: "중복 파일 표시",
                text: "같은 경로의 파일이 여러 버전에 포함되어 있으면 중복 대상으로 표시됩니다.",
            },
            {
                title: "중복 파일 버전 선택",
                text: "중복 파일이 있을 때만 열리며, 파일별로 사용할 commit 또는 revision을 최종 선택합니다.",
            },
        ],
        note: "중복 파일이 없으면 중복 선택 단계는 건너뛰고 바로 추출이 진행됩니다.",
    },
    {
        eyebrow: "4. 웹 추출 실행",
        title: "추출하기 버튼을 눌러 최종 패키지를 다운로드합니다",
        summary:
            "파일 선택이 끝나면 추출하기 버튼을 클릭합니다. 웹 화면에서 추출 진행 상태를 확인하고, 완료되면 deploy-package-windows.zip 파일을 다운로드합니다.",
        images: [
            { src: guideMainFiles, caption: "추출하기 버튼 클릭" },
            { src: guideWebExtracting, caption: "웹 화면 추출 진행" },
            { src: guideDownloadComplete, caption: "다운로드 완료" },
        ],
        nodes: [
            {
                title: "추출하기 버튼 클릭",
                text: "선택한 파일과 중복 버전 정보를 기준으로 추출을 시작합니다.",
            },
            {
                title: "웹 화면 추출 진행",
                text: "웹 화면에서 추출 요청이 처리되는 동안 진행 상태를 확인합니다.",
            },
            {
                title: "다운로드 완료",
                text: "deploy-package-windows.zip 다운로드가 끝나면 압축을 풀 준비를 합니다.",
            },
        ],
        note: "다운로드된 압축 파일 안에는 이미 최종 패키지 파일과 patch.sh가 들어 있습니다. 별도 실행 파일을 다시 실행하지 않습니다.",
    },
    {
        eyebrow: "5. 패키지 확인",
        title: "압축을 풀고 최종 패키지 구성을 확인합니다",
        summary:
            "다운로드한 deploy-package-windows.zip을 압축 해제하면 변경 파일과 patch.sh가 바로 보입니다. 이 폴더 전체가 서버로 옮길 최종 패키지입니다.",
        images: [
            { src: guideDownloadComplete, caption: "deploy-package-windows.zip 압축 풀기" },
            { src: guideResultFolder, caption: "패키지 파일 확인" },
        ],
        nodes: [
            {
                title: "deploy-package-windows.zip 압축 풀기",
                text: "다운로드한 압축 파일을 작업 폴더에 풀고, 생성된 파일 구성을 확인합니다.",
            },
            {
                title: "패키지 파일 확인",
                text: "압축 해제 폴더에서 변경 파일과 patch.sh가 들어 있는지 확인합니다.",
            },
        ],
        note: "압축 해제한 폴더 전체를 배포 서버로 옮기면 됩니다. 이후 서버에서 patch.sh를 실행해 백업과 배포를 진행합니다.",
    },
    {
        eyebrow: "6. 서버 배포",
        title: "서버에서 patch.sh를 실행해 백업과 배포를 진행합니다",
        summary:
            "생성된 결과 폴더를 서버에 올린 뒤 해당 폴더에서 bash patch.sh를 실행합니다. 메뉴에서 Backup, Deploy, Recover 중 필요한 작업을 선택합니다.",
        images: [
            { src: guideTerminalRun, caption: "서버에서 patch.sh 실행" },
            { src: guidePatchMenu, caption: "배포 스크립트 메뉴" },
        ],
        nodes: [
            {
                title: "서버에서 patch.sh 실행",
                text: "결과 폴더 전체를 서버로 복사한 뒤 해당 폴더에서 bash patch.sh를 실행합니다.",
            },
            {
                title: "배포 스크립트 메뉴",
                text: "배포 전 Backup을 먼저 수행하고, 필요 시 Deploy 또는 Recover를 선택합니다.",
            },
        ],
        note: "실행 권한이 없어도 ./patch.sh 대신 bash patch.sh로 실행하면 됩니다. Backup은 운영 파일을 timestamp 기준으로 여러 번 보관합니다.",
    },
];

const IntroGuideModal: React.FC<Props> = ({ show, onClose }) => {
    const [slideIndex, setSlideIndex] = useState(0);
    const [motionDirection, setMotionDirection] = useState<MotionDirection>("next");
    const pointerStartRef = useRef<{ x: number; y: number } | null>(null);
    const activeSlide = slides[slideIndex];
    const isFirst = slideIndex === 0;
    const isLast = slideIndex === slides.length - 1;

    const goToSlide = useCallback(
        (targetIndex: number) => {
            const nextIndex = Math.min(Math.max(targetIndex, 0), slides.length - 1);
            if (nextIndex === slideIndex) return;

            setMotionDirection(nextIndex > slideIndex ? "next" : "prev");
            setSlideIndex(nextIndex);
        },
        [slideIndex]
    );

    const guideCards = useMemo(
        () =>
            activeSlide.images.map((image, index) => {
                const node = activeSlide.nodes[index] ?? { title: image.caption, text: "" };

                return (
                    <figure key={`${activeSlide.eyebrow}-${image.caption}`} style={styles.mediaCard}>
                        <div style={styles.mediaImageWrap}>
                            <img src={image.src} alt={image.caption} style={styles.mediaImage} />
                        </div>
                        <figcaption style={styles.mediaCaption}>
                            <div style={styles.mediaCaptionHeader}>
                                <span style={styles.mediaStepBadge}>{index + 1}</span>
                                <span style={styles.mediaTitle}>{node.title}</span>
                            </div>
                            <p style={styles.mediaDescription}>{node.text}</p>
                        </figcaption>
                    </figure>
                );
            }),
        [activeSlide]
    );

    const handlePointerDown = (event: React.PointerEvent<HTMLDivElement>) => {
        pointerStartRef.current = { x: event.clientX, y: event.clientY };
    };

    const handlePointerUp = (event: React.PointerEvent<HTMLDivElement>) => {
        if (!pointerStartRef.current) return;

        const diffX = event.clientX - pointerStartRef.current.x;
        const diffY = event.clientY - pointerStartRef.current.y;
        pointerStartRef.current = null;

        if (Math.abs(diffX) < 70 || Math.abs(diffX) < Math.abs(diffY) * 1.25) return;

        goToSlide(diffX < 0 ? slideIndex + 1 : slideIndex - 1);
    };

    useEffect(() => {
        if (!show) return;

        const handleKeyDown = (event: KeyboardEvent) => {
            if (event.key === "Escape") onClose();
            if (event.key === "ArrowLeft") goToSlide(slideIndex - 1);
            if (event.key === "ArrowRight") goToSlide(slideIndex + 1);
        };

        document.addEventListener("keydown", handleKeyDown);
        return () => document.removeEventListener("keydown", handleKeyDown);
    }, [show, onClose, goToSlide, slideIndex]);

    useEffect(() => {
        if (!show) return;

        setMotionDirection("next");
        setSlideIndex(0);
    }, [show]);

    if (!show) return null;

    return (
        <div
            style={styles.overlay}
            role="presentation"
            onMouseDown={(event) => {
                if (event.target === event.currentTarget) onClose();
            }}
        >
            <style>{INTRO_GUIDE_ANIMATION_CSS}</style>
            <section role="dialog" aria-modal="true" aria-label="DeployProject 사용 안내" style={styles.dialog}>
                <header style={styles.header}>
                    <div style={styles.titleWrap}>
                        <p style={styles.eyebrow}>{activeSlide.eyebrow}</p>
                        <h2 style={styles.title}>{activeSlide.title}</h2>
                    </div>
                    <button type="button" aria-label="안내 닫기" style={styles.closeBtn} onClick={onClose}>
                        x
                    </button>
                </header>

                <div style={styles.body}>
                    <div
                        key={slideIndex}
                        className={`intro-guide-motion intro-guide-motion-${motionDirection}`}
                        style={styles.slideLayout}
                        onPointerDown={handlePointerDown}
                        onPointerUp={handlePointerUp}
                        onPointerCancel={() => {
                            pointerStartRef.current = null;
                        }}
                    >
                        <p style={styles.summary}>{activeSlide.summary}</p>
                        <div style={styles.mediaGrid}>{guideCards}</div>
                        <div style={styles.noteBox}>{activeSlide.note}</div>
                    </div>
                </div>

                <footer style={styles.footer}>
                    <div style={styles.dots} aria-label="안내 단계">
                        {slides.map((slide, index) => {
                            const isActive = index === slideIndex;

                            return (
                                <button
                                    key={slide.eyebrow}
                                    type="button"
                                    aria-current={isActive ? "step" : undefined}
                                    aria-label={`${index + 1}번 안내로 이동`}
                                    className={isActive ? "intro-guide-dot-active" : undefined}
                                    style={isActive ? styles.dotActive : styles.dot}
                                    onClick={() => goToSlide(index)}
                                />
                            );
                        })}
                    </div>

                    <div style={styles.buttonRow}>
                        <button type="button" style={styles.secondaryButton} onClick={onClose}>
                            닫기
                        </button>
                        <button
                            type="button"
                            style={isFirst ? styles.secondaryButtonDisabled : styles.secondaryButton}
                            disabled={isFirst}
                            onClick={() => goToSlide(slideIndex - 1)}
                        >
                            이전
                        </button>
                        <button
                            type="button"
                            style={styles.primaryButton}
                            onClick={() => {
                                if (isLast) {
                                    onClose();
                                    return;
                                }
                                goToSlide(slideIndex + 1);
                            }}
                        >
                            {isLast ? "시작하기" : "다음"}
                        </button>
                    </div>
                </footer>
            </section>
        </div>
    );
};

export default IntroGuideModal;
