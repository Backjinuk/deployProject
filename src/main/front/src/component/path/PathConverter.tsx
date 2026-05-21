import React, { useEffect, useRef, useState } from "react";
import { DayPicker } from "react-day-picker";
import { ko } from "react-day-picker/locale";
import "react-day-picker/style.css";
import Swal from "sweetalert2";

import { Site } from "../../api/sites";
import { localApi } from "../../api/http";
import { styles } from "../../styles/PathConverterStyles";

interface Props {
    site: Site;
}

export type RepoVersionOption = {
    value: string;
    label: string;
    committedAt: string;
};

export type DuplicateFileItem = {
    path: string;
    versions: RepoVersionOption[];
};

type RepoVersionListResponse = {
    vcsType: string;
    versions: RepoVersionOption[];
};

type RepoVersionFileListResponse = {
    vcsType: string;
    files: string[];
    duplicateFiles: DuplicateFileItem[];
};

type ActiveDatePicker = "start" | "end" | null;

const collectUniqueDuplicateVersions = (items: DuplicateFileItem[]): RepoVersionOption[] => {
    const seen = new Set<string>();
    const versions: RepoVersionOption[] = [];

    items.forEach((item) => {
        item.versions.forEach((version) => {
            if (seen.has(version.value)) return;
            seen.add(version.value);
            versions.push(version);
        });
    });

    return versions;
};

const toLocalDateText = (date: Date): string => {
    const y = date.getFullYear();
    const m = String(date.getMonth() + 1).padStart(2, "0");
    const d = String(date.getDate()).padStart(2, "0");
    return `${y}-${m}-${d}`;
};

const addDays = (date: Date, days: number): Date => {
    const next = new Date(date);
    next.setDate(next.getDate() + days);
    return next;
};

const normalizePath = (path: string): string => path.replace(/\\/g, "/").replace(/^\/+/, "").trim();

const vcsTypeLabels: Record<string, string> = {
    GIT: "Git",
    SVN: "SVN",
    LOCAL: "로컬(최종 수정 시간 기준)",
    UNKNOWN: "알 수 없음",
};

const PathConverter: React.FC<Props> = ({ site }) => {
    const [startDate, setStartDate] = useState<Date | null>(null);
    const [endDate, setEndDate] = useState<Date | null>(null);
    const [activeDatePicker, setActiveDatePicker] = useState<ActiveDatePicker>(null);
    const dateRangeRef = useRef<HTMLDivElement>(null);

    const [versionOptions, setVersionOptions] = useState<RepoVersionOption[]>([]);
    const [selectedVersions, setSelectedVersions] = useState<string[]>([]);
    const [isLoadingVersions, setIsLoadingVersions] = useState(false);

    const [versionFiles, setVersionFiles] = useState<string[]>([]);
    const [selectedFiles, setSelectedFiles] = useState<string[]>([]);
    const [isLoadingFiles, setIsLoadingFiles] = useState(false);

    const [duplicateFiles, setDuplicateFiles] = useState<DuplicateFileItem[]>([]);
    const [duplicateFileVersionMap, setDuplicateFileVersionMap] = useState<Record<string, string>>({});
    const [duplicateSearch, setDuplicateSearch] = useState("");
    const [bulkDuplicateVersion, setBulkDuplicateVersion] = useState("");
    const [isDuplicateModalOpen, setIsDuplicateModalOpen] = useState(false);

    const [vcsType, setVcsType] = useState("");
    const [isExtracting, setIsExtracting] = useState(false);

    const isLocalMode = vcsType === "LOCAL";
    const hasFileQuery = isLocalMode ? Boolean(startDate && endDate) : selectedVersions.length > 0;
    const displayVcsType = vcsTypeLabels[vcsType] ?? vcsType;

    const duplicatePathSet = new Set(duplicateFiles.map((item) => item.path));
    const selectedDuplicateFiles = duplicateFiles.filter((item) => selectedFiles.includes(item.path));
    const normalizedDuplicateSearch = duplicateSearch.trim().toLowerCase();
    const duplicateBulkVersionOptions = collectUniqueDuplicateVersions(selectedDuplicateFiles);
    const duplicateFilesByFilter = selectedDuplicateFiles.filter((item) => {
        if (normalizedDuplicateSearch && !item.path.toLowerCase().includes(normalizedDuplicateSearch)) return false;
        return true;
    });
    const unresolvedDuplicateCount = duplicateFilesByFilter.filter(
        (item) => !duplicateFileVersionMap[item.path]
    ).length;

    useEffect(() => {
        if (!activeDatePicker) return;

        const handleMouseDown = (event: MouseEvent) => {
            if (!dateRangeRef.current?.contains(event.target as Node)) {
                setActiveDatePicker(null);
            }
        };

        document.addEventListener("mousedown", handleMouseDown);
        return () => document.removeEventListener("mousedown", handleMouseDown);
    }, [activeDatePicker]);

    useEffect(() => {
        if (!site.localPath || !startDate || !endDate) {
            setVersionOptions([]);
            setSelectedVersions([]);
            setVersionFiles([]);
            setSelectedFiles([]);
            setDuplicateFiles([]);
            setDuplicateFileVersionMap({});
            setDuplicateSearch("");
            setBulkDuplicateVersion("");
            setIsDuplicateModalOpen(false);
            setVcsType("");
            setIsLoadingVersions(false);
            return;
        }

        let cancelled = false;
        setIsLoadingVersions(true);
        setSelectedVersions([]);
        setVersionFiles([]);
        setSelectedFiles([]);
        setDuplicateFiles([]);
        setDuplicateFileVersionMap({});
        setDuplicateSearch("");
        setBulkDuplicateVersion("");
        setIsDuplicateModalOpen(false);

        localApi
            .post<RepoVersionListResponse>("/api/git/versions", {
                localPath: site.localPath,
                since: toLocalDateText(startDate),
                until: toLocalDateText(endDate),
            })
            .then((res) => {
                if (cancelled) return;
                setVersionOptions(res.data.versions ?? []);
                setVcsType(res.data.vcsType ?? "");
            })
            .catch((err) => {
                if (cancelled) return;
                console.error("Failed to load versions:", err);
                setVersionOptions([]);
                setVcsType("");
                Swal.fire({
                    icon: "error",
                    title: "버전 조회 실패",
                    text: "저장소 경로와 권한을 확인해 주세요.",
                }).then();
            })
            .finally(() => {
                if (!cancelled) setIsLoadingVersions(false);
            });

        return () => {
            cancelled = true;
        };
    }, [site.localPath, startDate, endDate]);

    useEffect(() => {
        const shouldLoadLocalFiles = Boolean(site.localPath && startDate && endDate && isLocalMode);
        const shouldLoadVersionFiles = Boolean(site.localPath && !isLocalMode && selectedVersions.length > 0);

        if (!shouldLoadLocalFiles && !shouldLoadVersionFiles) {
            setVersionFiles([]);
            setSelectedFiles([]);
            setDuplicateFiles([]);
            setDuplicateFileVersionMap({});
            setDuplicateSearch("");
            setBulkDuplicateVersion("");
            setIsDuplicateModalOpen(false);
            setIsLoadingFiles(false);
            return;
        }

        let cancelled = false;
        setIsLoadingFiles(true);

        localApi
            .post<RepoVersionFileListResponse>("/api/git/version-files", {
                localPath: site.localPath,
                selectedVersions,
                since: startDate ? toLocalDateText(startDate) : null,
                until: endDate ? toLocalDateText(endDate) : null,
            })
            .then((res) => {
                if (cancelled) return;

                const files = (res.data.files ?? []).map(normalizePath);
                const duplicates = (res.data.duplicateFiles ?? []).map((item) => ({
                    path: normalizePath(item.path),
                    versions: item.versions ?? [],
                }));

                setVersionFiles(files);
                setSelectedFiles(files);
                setDuplicateFiles(duplicates);
                setDuplicateFileVersionMap((prev) => {
                    const next: Record<string, string> = {};
                    duplicates.forEach((item) => {
                        const available = item.versions.map((version) => version.value);
                        const prevValue = prev[item.path];
                        next[item.path] = available.includes(prevValue) ? prevValue : available[0] ?? "";
                    });
                    return next;
                });
                setBulkDuplicateVersion((prev) => {
                    const options = collectUniqueDuplicateVersions(duplicates);
                    if (options.length === 0) return "";
                    if (options.some((opt) => opt.value === prev)) return prev;
                    return options[0].value;
                });
                setVcsType((prev) => res.data.vcsType ?? prev);
            })
            .catch((err) => {
                if (cancelled) return;
                console.error("Failed to load changed files:", err);
                setVersionFiles([]);
                setSelectedFiles([]);
                setDuplicateFiles([]);
                setDuplicateFileVersionMap({});
                setDuplicateSearch("");
                setBulkDuplicateVersion("");
                setIsDuplicateModalOpen(false);
                Swal.fire({
                    icon: "error",
                    title: "파일 조회 실패",
                    text: "변경 파일 목록을 불러오지 못했습니다.",
                }).then();
            })
            .finally(() => {
                if (!cancelled) setIsLoadingFiles(false);
            });

        return () => {
            cancelled = true;
        };
    }, [site.localPath, selectedVersions, startDate, endDate, isLocalMode]);

    useEffect(() => {
        if (!isDuplicateModalOpen) return;

        const previousOverflow = document.body.style.overflow;
        const handleKeyDown = (event: KeyboardEvent) => {
            if (event.key === "Escape") {
                setIsDuplicateModalOpen(false);
            }
        };

        document.body.style.overflow = "hidden";
        window.addEventListener("keydown", handleKeyDown);

        return () => {
            document.body.style.overflow = previousOverflow;
            window.removeEventListener("keydown", handleKeyDown);
        };
    }, [isDuplicateModalOpen]);

    useEffect(() => {
        if (!isExtracting) return;

        const previousBodyOverflow = document.body.style.overflow;
        const previousHtmlOverflow = document.documentElement.style.overflow;

        document.body.style.overflow = "hidden";
        document.documentElement.style.overflow = "hidden";

        return () => {
            document.body.style.overflow = previousBodyOverflow;
            document.documentElement.style.overflow = previousHtmlOverflow;
        };
    }, [isExtracting]);

    useEffect(() => {
        if (isDuplicateModalOpen && selectedDuplicateFiles.length === 0) {
            setIsDuplicateModalOpen(false);
        }
    }, [isDuplicateModalOpen, selectedDuplicateFiles.length]);

    useEffect(() => {
        if (duplicateBulkVersionOptions.length === 0) {
            if (bulkDuplicateVersion) setBulkDuplicateVersion("");
            return;
        }

        if (!duplicateBulkVersionOptions.some((option) => option.value === bulkDuplicateVersion)) {
            setBulkDuplicateVersion(duplicateBulkVersionOptions[0].value);
        }
    }, [duplicateBulkVersionOptions, bulkDuplicateVersion]);

    const resolveTargetOs = (): "WINDOWS" | "MAC" | "LINUX" | null => {
        const ua = navigator.userAgent.toLowerCase();
        if (ua.includes("windows")) return "WINDOWS";
        if (ua.includes("mac")) return "MAC";
        if (ua.includes("linux")) return "LINUX";
        return null;
    };

    const requiresJvmCompilation = (): boolean =>
        selectedFiles.some((file) => file.endsWith(".java") || file.endsWith(".kt"));

    const confirmMissingJdkPath = async (): Promise<boolean> => {
        if (!requiresJvmCompilation() || site.jdkPath?.trim()) return true;

        const result = await Swal.fire({
            icon: "warning",
            title: "JDK 경로 미설정",
            text: "Java/Kotlin 소스가 포함되어 있어 class 생성이 실패할 수 있습니다. 계속 진행할까요?",
            showCancelButton: true,
            confirmButtonText: "계속 진행",
            cancelButtonText: "취소",
        });

        return result.isConfirmed;
    };

    const validateInputs = async (options?: { includeDuplicateVersions?: boolean }): Promise<boolean> => {
        const includeDuplicateVersions = options?.includeDuplicateVersions ?? true;

        if (!startDate || !endDate) {
            await Swal.fire({
                icon: "warning",
                title: "날짜 선택 필요",
                text: "시작일과 종료일을 먼저 선택해 주세요.",
            });
            return false;
        }

        if (isLoadingVersions || isLoadingFiles) {
            await Swal.fire({
                icon: "info",
                title: "조회 진행 중",
                text: "목록 조회가 끝난 뒤 다시 시도해 주세요.",
            });
            return false;
        }

        if (!isLocalMode && versionOptions.length === 0) {
            await Swal.fire({
                icon: "warning",
                title: "버전 없음",
                text: "선택한 날짜 범위에 해당하는 버전이 없습니다.",
            });
            return false;
        }

        if (!isLocalMode && selectedVersions.length === 0) {
            await Swal.fire({
                icon: "warning",
                title: "버전 선택 필요",
                text: "최소 1개 이상의 버전을 선택해 주세요.",
            });
            return false;
        }

        if (selectedFiles.length === 0) {
            await Swal.fire({
                icon: "warning",
                title: "파일 선택 필요",
                text: "최소 1개 이상의 파일을 선택해 주세요.",
            });
            return false;
        }

        if (includeDuplicateVersions) {
            const missingDuplicateVersion = selectedDuplicateFiles.find((item) => !duplicateFileVersionMap[item.path]);
            if (missingDuplicateVersion) {
                await Swal.fire({
                    icon: "warning",
                    title: "중복 파일 버전 선택 필요",
                    text: `중복 파일의 버전을 선택해 주세요: ${missingDuplicateVersion.path}`,
                });
                return false;
            }
        }

        return true;
    };

    const toggleVersion = (value: string) => {
        setSelectedVersions((prev) =>
            prev.includes(value) ? prev.filter((item) => item !== value) : [...prev, value]
        );
    };

    const toggleFile = (value: string) => {
        setSelectedFiles((prev) =>
            prev.includes(value) ? prev.filter((item) => item !== value) : [...prev, value]
        );
    };

    const applyBulkDuplicateVersion = () => {
        if (!bulkDuplicateVersion) return;

        setDuplicateFileVersionMap((prev) => {
            const next = { ...prev };
            duplicateFilesByFilter.forEach((item) => {
                const matched = item.versions.find((version) => version.value === bulkDuplicateVersion);
                if (matched) next[item.path] = matched.value;
            });
            return next;
        });
    };

    const applyRecommendedDuplicateVersion = () => {
        setDuplicateFileVersionMap((prev) => {
            const next = { ...prev };
            duplicateFilesByFilter.forEach((item) => {
                if (item.versions.length > 0) next[item.path] = item.versions[0].value;
            });
            return next;
        });
    };

    const performExtraction = async () => {
        const targetOs = resolveTargetOs();
        if (!targetOs) {
            await Swal.fire({
                icon: "error",
                title: "지원하지 않는 OS",
                text: "클라이언트 OS를 감지하지 못했습니다.",
            });
            return;
        }

        setIsExtracting(true);
        Swal.fire({
            title: "패키지 생성 중",
            text: isLocalMode
                ? "선택한 파일 기준으로 배포 패키지를 생성하고 있습니다."
                : "선택한 버전과 파일 기준으로 배포 패키지를 생성하고 있습니다.",
            allowOutsideClick: false,
            allowEscapeKey: false,
            customClass: {
                popup: "dp-extraction-alert",
            },
            didOpen: () => Swal.showLoading(),
        });

        try {
            const selectedDuplicateVersionMap = Object.fromEntries(
                selectedDuplicateFiles
                    .map((item) => [item.path, duplicateFileVersionMap[item.path]])
                    .filter((entry): entry is [string, string] => Boolean(entry[1]))
            );

            const response = await localApi.post(
                "/api/git/extraction",
                {
                    siteId: site.id,
                    since: startDate ? toLocalDateText(startDate) : null,
                    until: endDate ? toLocalDateText(endDate) : null,
                    localPath: site.localPath,
                    homePath: site.homePath,
                    jdkPath: site.jdkPath,
                    fileStatusType: isLocalMode ? "STATUS" : "DIFF",
                    targetOs,
                    selectedVersions,
                    selectedFiles: selectedFiles.map(normalizePath),
                    duplicateFileVersionMap: selectedDuplicateVersionMap,
                },
                { responseType: "blob", timeout: 1800000 }
            );

            const now = new Date().toISOString().replace(/[:.]/g, "-");
            const disposition = response.headers["content-disposition"];
            let filename = `deploy-package-${now}.zip`;
            if (disposition) {
                const match = disposition.match(/filename="(.+)"/);
                if (match?.[1]) filename = match[1];
            }

            const blob = new Blob([response.data], { type: response.headers["content-type"] });
            const url = URL.createObjectURL(blob);
            const anchor = document.createElement("a");
            anchor.href = url;
            anchor.download = filename;
            document.body.appendChild(anchor);
            anchor.click();
            URL.revokeObjectURL(url);
            anchor.remove();

            await Swal.fire({
                icon: "success",
                title: "다운로드 완료",
                text: filename,
                timer: 1800,
                showConfirmButton: false,
            });
        } catch (err) {
            console.error("Extraction error:", err);
            await Swal.fire({
                icon: "error",
                title: "다운로드 실패",
                text: "서버 로그와 입력 조건을 확인해 주세요.",
            });
        } finally {
            setIsExtracting(false);
        }
    };

    const closeDuplicateModal = () => {
        if (isExtracting) return;
        setIsDuplicateModalOpen(false);
    };

    const handleExtractClick = async () => {
        if (!(await validateInputs({ includeDuplicateVersions: false }))) return;
        if (!(await confirmMissingJdkPath())) return;

        if (selectedDuplicateFiles.length > 0) {
            setDuplicateSearch("");
            setIsDuplicateModalOpen(true);
            return;
        }

        await performExtraction();
    };

    const handleDuplicateConfirm = async () => {
        if (!(await validateInputs())) return;
        if (!(await confirmMissingJdkPath())) return;
        setIsDuplicateModalOpen(false);
        await performExtraction();
    };

    const selectDateRange = (from: Date | null, to: Date | null, closePicker = false) => {
        setStartDate(from);
        setEndDate(to);
        if (closePicker) setActiveDatePicker(null);
    };

    const selectRecentDays = (days: number) => {
        const today = new Date();
        const from = addDays(today, -(days - 1));
        selectDateRange(from, today, true);
    };

    const selectBoundaryDate = (date: Date | undefined) => {
        if (!date || !activeDatePicker) return;

        if (activeDatePicker === "start") {
            setStartDate(date);
            if (endDate && date > endDate) setEndDate(null);
            setActiveDatePicker("end");
            return;
        }

        if (startDate && date < startDate) {
            setStartDate(date);
            setEndDate(startDate);
        } else {
            setEndDate(date);
        }
        setActiveDatePicker(null);
    };

    const activePickerDate = activeDatePicker === "start" ? startDate : endDate;
    const activePickerDefaultMonth = activePickerDate ?? startDate ?? endDate ?? new Date();

    const checkListBoxStyle = isDuplicateModalOpen || isExtracting
        ? { ...styles.checkListBox, overflowY: "hidden" as const }
        : styles.checkListBox;

    return (
        <div style={styles.customCardSingle}>
            <div style={styles.mainCard}>
                <div style={styles.controlsRow}>
                    <div className="date-range-control" ref={dateRangeRef}>
                        <div className="date-range-fields">
                            <button
                                type="button"
                                className={`date-range-field${activeDatePicker === "start" ? " active" : ""}`}
                                aria-expanded={activeDatePicker === "start"}
                                onClick={() => setActiveDatePicker((prev) => (prev === "start" ? null : "start"))}
                            >
                                <span>시작일</span>
                                <strong>{startDate ? toLocalDateText(startDate) : "날짜 선택"}</strong>
                            </button>
                            <span className="date-range-field-separator">~</span>
                            <button
                                type="button"
                                className={`date-range-field${activeDatePicker === "end" ? " active" : ""}`}
                                aria-expanded={activeDatePicker === "end"}
                                onClick={() => setActiveDatePicker((prev) => (prev === "end" ? null : "end"))}
                            >
                                <span>종료일</span>
                                <strong>{endDate ? toLocalDateText(endDate) : "날짜 선택"}</strong>
                            </button>
                        </div>

                        {activeDatePicker && (
                            <div className="date-range-popover" role="dialog" aria-label={`${activeDatePicker === "start" ? "시작일" : "종료일"} 선택`}>
                                <div className="date-range-popover-title">
                                    {activeDatePicker === "start" ? "시작일 선택" : "종료일 선택"}
                                </div>
                                <DayPicker
                                    mode="single"
                                    locale={ko}
                                    weekStartsOn={1}
                                    defaultMonth={activePickerDefaultMonth}
                                    selected={activePickerDate ?? undefined}
                                    onSelect={selectBoundaryDate}
                                />
                                <div className="date-range-actions">
                                    <button type="button" onClick={() => selectRecentDays(1)}>
                                        오늘
                                    </button>
                                    <button type="button" onClick={() => selectRecentDays(7)}>
                                        최근 7일
                                    </button>
                                    <button type="button" onClick={() => selectRecentDays(30)}>
                                        최근 30일
                                    </button>
                                    <button type="button" onClick={() => selectDateRange(null, null)}>
                                        초기화
                                    </button>
                                </div>
                            </div>
                        )}
                    </div>
                </div>

                <div style={styles.contentLayout}>
                    <div
                        style={{
                            ...styles.selectionGrid,
                            gridTemplateColumns: isLocalMode ? "minmax(0, 1fr)" : "repeat(2, minmax(0, 1fr))",
                        }}
                    >
                        {!isLocalMode && (
                            <div style={styles.selectionPanel}>
                                <div style={styles.panelHeader}>
                                    <label style={styles.panelTitle}>버전 선택</label>
                                    <div style={styles.panelTools}>
                                        <button
                                            type="button"
                                            style={styles.toolBtn}
                                            onClick={() => setSelectedVersions(versionOptions.map((item) => item.value))}
                                            disabled={isLoadingVersions || versionOptions.length === 0}
                                        >
                                            전체 선택
                                        </button>
                                        <button
                                            type="button"
                                            style={styles.toolBtn}
                                            onClick={() => setSelectedVersions([])}
                                            disabled={isLoadingVersions || selectedVersions.length === 0}
                                        >
                                            전체 해제
                                        </button>
                                    </div>
                                </div>

                                <div style={checkListBoxStyle}>
                                    {isLoadingVersions && <div style={styles.emptyText}>버전 목록을 불러오는 중입니다.</div>}
                                    {!isLoadingVersions && versionOptions.length === 0 && (
                                        <div style={styles.emptyText}>선택한 날짜 범위에 해당하는 버전이 없습니다.</div>
                                    )}
                                    {!isLoadingVersions &&
                                        versionOptions.map((option) => (
                                            <label
                                                key={option.value}
                                                style={selectedVersions.includes(option.value) ? styles.checkRowActive : styles.checkRow}
                                            >
                                                <input
                                                    type="checkbox"
                                                    style={styles.checkInput}
                                                    checked={selectedVersions.includes(option.value)}
                                                    onChange={() => toggleVersion(option.value)}
                                                />
                                                <span style={styles.checkText}>{option.label}</span>
                                            </label>
                                        ))}
                                </div>
                            </div>
                        )}

                        <div style={styles.selectionPanel}>
                            <div style={styles.panelHeader}>
                                <label style={styles.panelTitle}>파일 선택</label>
                                <div style={styles.panelTools}>
                                    <button
                                        type="button"
                                        style={styles.toolBtn}
                                        onClick={() => setSelectedFiles(versionFiles)}
                                        disabled={isLoadingFiles || versionFiles.length === 0}
                                    >
                                        전체 선택
                                    </button>
                                    <button
                                        type="button"
                                        style={styles.toolBtn}
                                        onClick={() => setSelectedFiles([])}
                                        disabled={isLoadingFiles || selectedFiles.length === 0}
                                    >
                                        전체 해제
                                    </button>
                                </div>
                            </div>

                            <div style={checkListBoxStyle}>
                                {!isLocalMode && selectedVersions.length === 0 && (
                                    <div style={styles.emptyText}>버전을 선택하면 변경 파일을 표시합니다.</div>
                                )}
                                {hasFileQuery && isLoadingFiles && (
                                    <div style={styles.emptyText}>
                                        {isLocalMode
                                            ? "선택한 날짜 범위의 수정 파일을 불러오는 중입니다."
                                            : "변경 파일 목록을 불러오는 중입니다."}
                                    </div>
                                )}
                                {hasFileQuery && !isLoadingFiles && versionFiles.length === 0 && (
                                    <div style={styles.emptyText}>
                                        {isLocalMode
                                            ? "선택한 날짜 범위에 해당하는 파일이 없습니다."
                                            : "선택한 버전에 해당하는 변경 파일이 없습니다."}
                                    </div>
                                )}
                                {hasFileQuery &&
                                    !isLoadingFiles &&
                                    versionFiles.map((file) => (
                                        <label
                                            key={file}
                                            style={selectedFiles.includes(file) ? styles.checkRowActive : styles.checkRow}
                                        >
                                            <input
                                                type="checkbox"
                                                style={styles.checkInput}
                                                checked={selectedFiles.includes(file)}
                                                onChange={() => toggleFile(file)}
                                            />
                                            <span style={styles.checkText}>{file}</span>
                                            {duplicatePathSet.has(file) && <span style={styles.duplicateBadge}>중복</span>}
                                        </label>
                                    ))}
                            </div>
                        </div>
                    </div>
                </div>

                {vcsType && (
                    <div style={styles.versionTypeText}>
                        감지된 대상 유형: <strong>{displayVcsType}</strong>
                    </div>
                )}

                <button
                    onClick={handleExtractClick}
                    style={styles.extractBtn}
                    disabled={isExtracting || isLoadingVersions || isLoadingFiles}
                >
                    {isExtracting ? "생성 중..." : "패키지 추출"}
                </button>
            </div>

            {isDuplicateModalOpen && (
                <div style={styles.duplicateModalOverlay} onClick={closeDuplicateModal}>
                    <div style={styles.duplicateModalDialog} onClick={(event) => event.stopPropagation()}>
                        <div style={styles.panelHeader}>
                            <div>
                                <div style={styles.panelTitle}>중복 파일 버전 선택</div>
                                <div style={styles.panelSubTitle}>
                                    같은 파일 경로가 여러 버전에 포함되어 있어 사용할 버전을 선택해야 합니다.
                                </div>
                            </div>
                            <div style={styles.duplicateModalHeaderActions}>
                                <span style={styles.summaryChip}>총 {selectedDuplicateFiles.length}건</span>
                                <button type="button" style={styles.duplicateCloseBtn} onClick={closeDuplicateModal}>
                                    닫기
                                </button>
                            </div>
                        </div>

                        <div style={styles.duplicateToolbar}>
                            <input
                                type="text"
                                value={duplicateSearch}
                                onChange={(event) => setDuplicateSearch(event.target.value)}
                                placeholder="파일 경로 검색"
                                style={styles.duplicateSearchInput}
                            />
                            <select
                                style={styles.duplicateBulkSelect}
                                value={bulkDuplicateVersion}
                                onChange={(event) => setBulkDuplicateVersion(event.target.value)}
                                disabled={duplicateBulkVersionOptions.length === 0}
                            >
                                {duplicateBulkVersionOptions.length === 0 && <option value="">선택 가능한 버전 없음</option>}
                                {duplicateBulkVersionOptions.map((version) => (
                                    <option key={`bulk-${version.value}`} value={version.value}>
                                        {version.label}
                                    </option>
                                ))}
                            </select>

                            <div style={styles.duplicateButtonRow}>
                                <button
                                    type="button"
                                    style={{ ...styles.toolBtn, ...styles.duplicateToolbarButton }}
                                    onClick={applyBulkDuplicateVersion}
                                    disabled={duplicateFilesByFilter.length === 0 || !bulkDuplicateVersion}
                                >
                                    필터 적용
                                </button>
                                <button
                                    type="button"
                                    style={{ ...styles.toolBtn, ...styles.duplicateToolbarButton }}
                                    onClick={applyRecommendedDuplicateVersion}
                                    disabled={duplicateFilesByFilter.length === 0}
                                >
                                    추천 적용
                                </button>
                            </div>
                        </div>

                        <div style={styles.duplicateMetaText}>
                            총 {selectedDuplicateFiles.length}건 / 표시 {duplicateFilesByFilter.length}건
                            {unresolvedDuplicateCount > 0 && (
                                <span style={styles.duplicateUnresolved}> / 미선택 {unresolvedDuplicateCount}건</span>
                            )}
                        </div>

                        {selectedDuplicateFiles.length === 0 && (
                            <div style={styles.emptyText}>선택한 파일에는 중복 항목이 없습니다.</div>
                        )}

                        {selectedDuplicateFiles.length > 0 && duplicateFilesByFilter.length === 0 && (
                            <div style={styles.emptyText}>현재 검색 조건에 맞는 중복 파일이 없습니다.</div>
                        )}

                        {selectedDuplicateFiles.length > 0 && duplicateFilesByFilter.length > 0 && (
                            <div style={styles.duplicateListBox}>
                                {duplicateFilesByFilter.map((item, idx) => (
                                    <div key={item.path} style={styles.duplicateRow}>
                                        <div style={styles.duplicatePathWrap}>
                                            <span style={styles.duplicateIndex}>{idx + 1}</span>
                                            <span style={styles.duplicatePath}>{item.path}</span>
                                        </div>
                                        <select
                                            style={styles.duplicateSelect}
                                            value={duplicateFileVersionMap[item.path] ?? ""}
                                            onChange={(event) =>
                                                setDuplicateFileVersionMap((prev) => ({
                                                    ...prev,
                                                    [item.path]: event.target.value,
                                                }))
                                            }
                                        >
                                            {item.versions.length === 0 && <option value="">선택 가능한 버전 없음</option>}
                                            {item.versions.map((version) => (
                                                <option key={`${item.path}-${version.value}`} value={version.value}>
                                                    {version.label}
                                                </option>
                                            ))}
                                        </select>
                                    </div>
                                ))}
                            </div>
                        )}

                        <div style={styles.modalFooter}>
                            <button type="button" style={styles.modalSecondaryButton} onClick={closeDuplicateModal}>
                                취소
                            </button>
                            <button
                                type="button"
                                style={styles.modalPrimaryButton}
                                onClick={handleDuplicateConfirm}
                                disabled={isExtracting}
                            >
                                이 버전으로 추출
                            </button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
};

export default PathConverter;
