import React, { ChangeEvent, useCallback, useEffect, useRef, useState } from "react";
import "bootstrap/dist/css/bootstrap.min.css";
import Swal from "sweetalert2";

import { Site } from "../../api/sites";
import { appVersion, serverApi } from "../../api/http";
import SiteSelector from "./StieSelector";
import PathConverter from "./PathConverter";
import PathUpdateModal from "../pathModal/PathUpdateModal";
import PathAddModal from "../pathModal/PathAddModal";
import IntroGuideModal from "../guide/IntroGuideModal";
import { styles } from "../../styles/ConverterStyles";
import deployKitIcon from "../../assets/brand/deploykit-icon.png";
import { checkForAppUpdate, openAppUpdateInstaller, UpdateCheckResult } from "../../api/updateCheck";

const INTRO_GUIDE_STORAGE_KEY = "deployProjectIntroGuideSeen";
const UPDATE_DISMISSED_VERSION_STORAGE_KEY = "deployKitUpdateDismissedVersion";
const SUPPORT_GITHUB_URL = "https://github.com/Backjinuk/deployProject";
const SUPPORT_EMAIL = "backj123@naver.com";

type PathBackupSite = Pick<Site, "id" | "text" | "homePath" | "localPath" | "jdkPath" | "useYn">;
type ImportedPathSite = Pick<Site, "text" | "homePath" | "localPath"> & Pick<Partial<Site>, "jdkPath">;
type PathIdentitySite = Pick<Site, "text" | "homePath" | "localPath">;
type ThemeMode = "light" | "dark";

type ConverterMainProps = {
    themeMode: ThemeMode;
    onToggleThemeMode: () => void;
};

const timestampForFileName = () => {
    const now = new Date();
    const pad = (value: number) => value.toString().padStart(2, "0");

    return [
        now.getFullYear(),
        pad(now.getMonth() + 1),
        pad(now.getDate()),
        "-",
        pad(now.getHours()),
        pad(now.getMinutes()),
        pad(now.getSeconds()),
    ].join("");
};

const toPathBackupSite = (site: Site): PathBackupSite => ({
    id: site.id,
    text: site.text,
    homePath: site.homePath,
    localPath: site.localPath,
    jdkPath: site.jdkPath,
    useYn: site.useYn,
});

const isObject = (value: unknown): value is Record<string, unknown> =>
    typeof value === "object" && value !== null;

const stringValue = (value: unknown) => (typeof value === "string" ? value.trim() : "");

const normalizePathPart = (value: string) =>
    value.trim().replace(/\\/g, "/").replace(/\/+$/, "").toLowerCase();

const pathIdentity = (site: PathIdentitySite) =>
    JSON.stringify([
        normalizePathPart(site.text),
        normalizePathPart(site.homePath),
        normalizePathPart(site.localPath),
    ]);

const uniquePathSites = <T extends PathIdentitySite>(pathList: T[]): T[] => {
    const seen = new Set<string>();
    return pathList.filter((site) => {
        const key = pathIdentity(site);
        if (seen.has(key)) return false;
        seen.add(key);
        return true;
    });
};

const extractPathEntries = (value: unknown): unknown[] => {
    if (Array.isArray(value)) return value;
    if (isObject(value) && Array.isArray(value.sites)) return value.sites;
    throw new Error("Invalid path backup file.");
};

const parseImportedPaths = (value: unknown): ImportedPathSite[] => {
    return extractPathEntries(value).reduce<ImportedPathSite[]>((sites, entry) => {
        if (!isObject(entry)) return sites;
        if (stringValue(entry.useYn).toUpperCase() === "N") return sites;

        const text = stringValue(entry.text);
        const homePath = stringValue(entry.homePath);
        const localPath = stringValue(entry.localPath);
        const jdkPath = stringValue(entry.jdkPath);

        if (!text || !homePath || !localPath) return sites;

        sites.push({
            text,
            homePath,
            localPath,
            jdkPath: jdkPath || undefined,
        });
        return sites;
    }, []);
};

const ConverterMain: React.FC<ConverterMainProps> = ({ themeMode, onToggleThemeMode }) => {
    const [sites, setSites] = useState<Site[]>([]);
    const [selected, setSelected] = useState<Site | null>(null);
    const [showPathUpdateModal, setShowPathUpdateModal] = useState<boolean>(false);
    const [showPathAddModal, setShowPathAddModal] = useState<boolean>(false);
    const [showIntroGuideModal, setShowIntroGuideModal] = useState<boolean>(false);
    const [updateInfo, setUpdateInfo] = useState<UpdateCheckResult | null>(null);
    const [showUpdateBanner, setShowUpdateBanner] = useState(false);
    const [isPathMenuOpen, setIsPathMenuOpen] = useState(false);
    const [isContactMenuOpen, setIsContactMenuOpen] = useState(false);
    const pathFileInputRef = useRef<HTMLInputElement>(null);
    const pathMenuRef = useRef<HTMLDivElement>(null);
    const contactMenuRef = useRef<HTMLDivElement>(null);

    const fetchSites = useCallback(() => {
        serverApi
            .get<Site[]>("/api/sites")
            .then((res) => {
                setSites(res.data);
                setSelected((prev) => res.data.find((site) => site.id === prev?.id) ?? res.data[0] ?? null);
            })
            .catch((err) => console.error("site list load failed:", err));
    }, []);

    useEffect(() => {
        fetchSites();
    }, [fetchSites]);

    useEffect(() => {
        if (localStorage.getItem(INTRO_GUIDE_STORAGE_KEY)) return;
        setShowIntroGuideModal(true);
    }, []);

    useEffect(() => {
        if (!isPathMenuOpen) return;

        const handleMouseDown = (event: MouseEvent) => {
            if (!pathMenuRef.current?.contains(event.target as Node)) {
                setIsPathMenuOpen(false);
            }
        };

        document.addEventListener("mousedown", handleMouseDown);
        return () => document.removeEventListener("mousedown", handleMouseDown);
    }, [isPathMenuOpen]);

    useEffect(() => {
        if (!isContactMenuOpen) return;

        const handleMouseDown = (event: MouseEvent) => {
            if (!contactMenuRef.current?.contains(event.target as Node)) {
                setIsContactMenuOpen(false);
            }
        };

        document.addEventListener("mousedown", handleMouseDown);
        return () => document.removeEventListener("mousedown", handleMouseDown);
    }, [isContactMenuOpen]);

    useEffect(() => {
        let cancelled = false;

        checkForAppUpdate().then((result) => {
            if (cancelled) return;
            setUpdateInfo(result);
            if (result) {
                setShowUpdateBanner(
                    localStorage.getItem(UPDATE_DISMISSED_VERSION_STORAGE_KEY) !== result.latestVersion
                );
            }
        });

        return () => {
            cancelled = true;
        };
    }, []);

    const closeIntroGuide = () => {
        localStorage.setItem(INTRO_GUIDE_STORAGE_KEY, "Y");
        setShowIntroGuideModal(false);
    };

    const handlePathUpdateClose = () => {
        setShowPathUpdateModal(false);
        fetchSites();
    };

    const handlePathAddClose = () => {
        setShowPathAddModal(false);
        fetchSites();
    };

    const handleUpdateClick = async () => {
        if (!updateInfo?.installerUrl) return;

        try {
            await openAppUpdateInstaller(updateInfo.installerUrl);
            await Swal.fire({
                icon: "info",
                title: "다운로드 페이지를 열었습니다.",
                text: "브라우저에서 새 deployKit 설치 파일을 내려받아 실행해 주세요.",
                timer: 1800,
                showConfirmButton: false,
            });
        } catch (error) {
            console.info("open update installer through desktop API failed:", error);
            window.location.href = updateInfo.installerUrl;
        }
    };

    const dismissUpdateBanner = () => {
        if (updateInfo?.latestVersion) {
            localStorage.setItem(UPDATE_DISMISSED_VERSION_STORAGE_KEY, updateInfo.latestVersion);
        }
        setShowUpdateBanner(false);
    };

    const downloadPaths = async () => {
        try {
            const response = await serverApi.get<Site[]>("/api/pathList");
            const pathList = response.data;
            setSites(pathList);
            setSelected((prev) => pathList.find((site) => site.id === prev?.id) ?? pathList[0] ?? null);

            if (pathList.length === 0) {
                await Swal.fire({
                    icon: "info",
                    title: "다운로드할 경로가 없습니다.",
                    text: "먼저 경로를 등록해 주세요.",
                });
                return;
            }

            const backup = {
                app: "DeployProject",
                schemaVersion: 1,
                exportedAt: new Date().toISOString(),
                sites: pathList.map(toPathBackupSite),
            };
            const blob = new Blob([JSON.stringify(backup, null, 2)], {
                type: "application/json;charset=utf-8",
            });
            const url = URL.createObjectURL(blob);
            const anchor = document.createElement("a");
            anchor.href = url;
            anchor.download = `deploy-project-paths-${timestampForFileName()}.json`;
            document.body.appendChild(anchor);
            anchor.click();
            anchor.remove();
            URL.revokeObjectURL(url);
        } catch (error) {
            console.error("path download failed", error);
            await Swal.fire({
                icon: "error",
                title: "경로 다운로드 실패",
                text: "저장된 경로 목록을 불러오지 못했습니다.",
            });
        }
    };

    const importPaths = async (event: ChangeEvent<HTMLInputElement>) => {
        const file = event.target.files?.[0];
        event.target.value = "";
        if (!file) return;

        try {
            const importedSites = uniquePathSites(parseImportedPaths(JSON.parse(await file.text())));

            if (importedSites.length === 0) {
                await Swal.fire({
                    icon: "warning",
                    title: "등록할 경로가 없습니다.",
                    text: "파일에 사이트 이름, 배포 서버 경로, 로컬 프로젝트 경로가 포함되어 있는지 확인해 주세요.",
                });
                return;
            }

            const currentResponse = await serverApi.get<Site[]>("/api/pathList");
            const existingKeys = new Set(currentResponse.data.map(pathIdentity));
            const sitesToImport = importedSites.filter((site) => !existingKeys.has(pathIdentity(site)));
            const skippedCount = importedSites.length - sitesToImport.length;

            if (sitesToImport.length === 0) {
                await Swal.fire({
                    icon: "info",
                    title: "추가할 새 경로가 없습니다.",
                    text: `${skippedCount}개의 경로가 이미 등록되어 있습니다.`,
                });
                return;
            }

            const result = await Swal.fire({
                icon: "question",
                title: "경로를 가져올까요?",
                text:
                    skippedCount > 0
                        ? `${sitesToImport.length}개의 경로를 추가하고, 중복 ${skippedCount}개는 제외합니다.`
                        : `${sitesToImport.length}개의 경로를 현재 목록에 추가합니다.`,
                showCancelButton: true,
                confirmButtonText: "가져오기",
                cancelButtonText: "취소",
            });

            if (!result.isConfirmed) return;

            for (const site of sitesToImport) {
                await serverApi.post("/api/savedPath", site);
            }

            fetchSites();
            await Swal.fire({
                position: "center",
                icon: "success",
                title:
                    skippedCount > 0
                        ? `경로 ${sitesToImport.length}개를 가져왔습니다.`
                        : "경로를 가져왔습니다.",
                timer: 1200,
                showConfirmButton: false,
            });
        } catch (error) {
            console.error("path import failed", error);
            await Swal.fire({
                icon: "error",
                title: "경로 가져오기 실패",
                text: "JSON 파일 형식이 올바른지 확인해 주세요.",
            });
        }
    };

    return (
        <div style={styles.wrapper}>
            <div style={styles.container}>
                <IntroGuideModal show={showIntroGuideModal} onClose={closeIntroGuide} />
                <PathUpdateModal show={showPathUpdateModal} onPath={handlePathUpdateClose} />
                <PathAddModal show={showPathAddModal} onPathAdd={handlePathAddClose} />

                <div style={styles.converterHeader}>
                    <div className="brand-lockup">
                        <img className="brand-logo-image" src={deployKitIcon} alt="" aria-hidden="true" />
                        <div className="brand-copy">
                            <h1 style={styles.converterHeaderTitle}>deployKit</h1>
                            <p className="brand-subtitle">Build. Configure. Deploy.</p>
                        </div>
                    </div>
                    <div style={styles.headerActions}>
                        <button
                            type="button"
                            className="theme-toggle"
                            aria-pressed={themeMode === "dark"}
                            onClick={onToggleThemeMode}
                        >
                            <span className="theme-toggle-track" aria-hidden="true">
                                <span className="theme-toggle-thumb" />
                            </span>
                            <span>{themeMode === "dark" ? "다크" : "라이트"}</span>
                        </button>
                        {updateInfo ? (
                            <button className="update-pill" type="button" onClick={handleUpdateClick}>
                                새 버전 {updateInfo.latestVersion}
                            </button>
                        ) : (
                            <span className="version-pill">v{appVersion}</span>
                        )}
                        <button className="action-btn secondary" onClick={() => setShowIntroGuideModal(true)}>
                            사용 안내
                        </button>
                        <div className="header-menu" ref={pathMenuRef}>
                            <button
                                type="button"
                                className="header-menu-button"
                                aria-label="경로 작업 메뉴"
                                aria-expanded={isPathMenuOpen}
                                title="경로 작업"
                                onClick={() => setIsPathMenuOpen((prev) => !prev)}
                            >
                                <span className="header-menu-icon" aria-hidden="true">
                                    <span />
                                    <span />
                                    <span />
                                </span>
                            </button>
                            {isPathMenuOpen && (
                                <div className="header-menu-panel" role="menu">
                                    <button
                                        type="button"
                                        className="header-menu-item"
                                        role="menuitem"
                                        onClick={() => {
                                            setIsPathMenuOpen(false);
                                            setShowPathAddModal(true);
                                        }}
                                    >
                                        <strong>경로 등록</strong>
                                        <small>새 배포 경로 추가</small>
                                    </button>
                                    <button
                                        type="button"
                                        className="header-menu-item"
                                        role="menuitem"
                                        onClick={() => {
                                            setIsPathMenuOpen(false);
                                            setShowPathUpdateModal(true);
                                        }}
                                    >
                                        <strong>경로 관리</strong>
                                        <small>수정 및 삭제</small>
                                    </button>
                                    <button
                                        type="button"
                                        className="header-menu-item"
                                        role="menuitem"
                                        onClick={() => {
                                            setIsPathMenuOpen(false);
                                            downloadPaths();
                                        }}
                                    >
                                        <strong>경로 다운로드</strong>
                                        <small>JSON 백업 파일 저장</small>
                                    </button>
                                    <button
                                        type="button"
                                        className="header-menu-item"
                                        role="menuitem"
                                        onClick={() => {
                                            setIsPathMenuOpen(false);
                                            pathFileInputRef.current?.click();
                                        }}
                                    >
                                        <strong>경로 가져오기</strong>
                                        <small>백업 JSON 불러오기</small>
                                    </button>
                                </div>
                            )}
                        </div>
                        <input
                            ref={pathFileInputRef}
                            type="file"
                            accept="application/json,.json"
                            style={{ display: "none" }}
                            onChange={importPaths}
                        />
                    </div>
                </div>

                {updateInfo && showUpdateBanner && (
                    <section className="update-banner" aria-label="deployKit update notification">
                        <div className="update-banner-copy">
                            <span className="update-banner-badge">업데이트 가능</span>
                            <div>
                                <strong>운영 서버에 새 버전이 준비되었습니다.</strong>
                                <p>
                                    현재 v{updateInfo.currentVersion}에서 v{updateInfo.latestVersion}로 업데이트할 수 있습니다.
                                    {updateInfo.message ? ` ${updateInfo.message}` : ""}
                                </p>
                            </div>
                        </div>
                        <div className="update-banner-actions">
                            <button className="update-banner-primary" type="button" onClick={handleUpdateClick}>
                                새 버전 받기
                            </button>
                            <button className="update-banner-secondary" type="button" onClick={dismissUpdateBanner}>
                                나중에
                            </button>
                        </div>
                    </section>
                )}

                <div style={styles.customCardSite}>
                    <SiteSelector
                        sites={sites}
                        selectedId={selected?.id ?? null}
                        onSelect={(id) => {
                            const found = sites.find((site) => site.id === id) ?? null;
                            setSelected(found);
                        }}
                    />
                </div>

                {selected && (
                    <div style={styles.customCardPath}>
                        <PathConverter site={selected} />
                    </div>
                )}

                <footer className="app-contact-footer">
                    <div className="app-contact-meta">
                        <span>v{appVersion}</span>
                        <span aria-hidden="true">·</span>
                        <div className="app-contact-menu" ref={contactMenuRef}>
                            <button
                                type="button"
                                className="app-contact-trigger"
                                aria-expanded={isContactMenuOpen}
                                onClick={() => setIsContactMenuOpen((prev) => !prev)}
                            >
                                문의하기
                            </button>
                            {isContactMenuOpen && (
                                <div className="app-contact-panel" role="menu">
                                    <a
                                        className="app-contact-link"
                                        href={SUPPORT_GITHUB_URL}
                                        target="_blank"
                                        rel="noreferrer"
                                        role="menuitem"
                                    >
                                        GitHub
                                    </a>
                                    <a
                                        className="app-contact-link"
                                        href={`mailto:${SUPPORT_EMAIL}`}
                                        role="menuitem"
                                    >
                                        Email
                                    </a>
                                </div>
                            )}
                        </div>
                    </div>
                </footer>
            </div>
        </div>
    );
};

export default ConverterMain;
