import React, { forwardRef, useEffect, useState } from "react";
import axios from "axios";
import DatePicker from "react-datepicker";
import { ko } from "date-fns/locale";
import "react-datepicker/dist/react-datepicker.css";
import Swal from "sweetalert2";

import { Site } from "../../api/sites";
import { styles } from "../../styles/PathConverterStyles";

interface Props {
    site: Site;
}

type RepoVersionOption = {
    value: string;
    label: string;
    committedAt: string;
};

type DuplicateFileItem = {
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

const CustomInput = forwardRef<HTMLInputElement, { value?: string; onClick?: () => void }>(
    ({ value, onClick }, ref) => (
        <input
            ref={ref}
            value={value}
            onClick={onClick}
            readOnly
            placeholder="날짜 선택"
            style={styles.dateInput}
        />
    )
);
CustomInput.displayName = "CustomInput";

const toLocalDateText = (date: Date): string => {
    const y = date.getFullYear();
    const m = String(date.getMonth() + 1).padStart(2, "0");
    const d = String(date.getDate()).padStart(2, "0");
    return `${y}-${m}-${d}`;
};

const normalizePath = (path: string): string => path.replace(/\\/g, "/").replace(/^\/+/, "").trim();

const PathConverter: React.FC<Props> = ({ site }) => {
    const [startDate, setStartDate] = useState<Date | null>(null);
    const [endDate, setEndDate] = useState<Date | null>(null);

    const [versionOptions, setVersionOptions] = useState<RepoVersionOption[]>([]);
    const [selectedVersions, setSelectedVersions] = useState<string[]>([]);
    const [isLoadingVersions, setIsLoadingVersions] = useState(false);

    const [versionFiles, setVersionFiles] = useState<string[]>([]);
    const [selectedFiles, setSelectedFiles] = useState<string[]>([]);
    const [isLoadingFiles, setIsLoadingFiles] = useState(false);

    const [duplicateFiles, setDuplicateFiles] = useState<DuplicateFileItem[]>([]);
    const [duplicateFileVersionMap, setDuplicateFileVersionMap] = useState<Record<string, string>>({});
    const [duplicateOnlySelected, setDuplicateOnlySelected] = useState(true);
    const [duplicateSearch, setDuplicateSearch] = useState("");
    const [bulkDuplicateVersion, setBulkDuplicateVersion] = useState("");

    const [vcsType, setVcsType] = useState("");
    const [isExtracting, setIsExtracting] = useState(false);

    const showDuplicatePanel = selectedVersions.length >= 2;
    const duplicatePathSet = new Set(duplicateFiles.map((item) => item.path));
    const normalizedDuplicateSearch = duplicateSearch.trim().toLowerCase();
    const duplicateBulkVersionOptions = collectUniqueDuplicateVersions(duplicateFiles);

    const duplicateFilesByFilter = duplicateFiles.filter((item) => {
        if (duplicateOnlySelected && !selectedFiles.includes(item.path)) return false;
        if (normalizedDuplicateSearch && !item.path.toLowerCase().includes(normalizedDuplicateSearch)) return false;
        return true;
    });

    const unresolvedDuplicateCount = duplicateFilesByFilter.filter(
        (item) => !duplicateFileVersionMap[item.path]
    ).length;

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

        axios
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
                    text: "저장소 경로 또는 권한을 확인해 주세요.",
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
        if (!site.localPath || selectedVersions.length === 0) {
            setVersionFiles([]);
            setSelectedFiles([]);
            setDuplicateFiles([]);
            setDuplicateFileVersionMap({});
            setDuplicateSearch("");
            setBulkDuplicateVersion("");
            setIsLoadingFiles(false);
            return;
        }

        let cancelled = false;
        setIsLoadingFiles(true);

        axios
            .post<RepoVersionFileListResponse>("/api/git/version-files", {
                localPath: site.localPath,
                selectedVersions,
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
                Swal.fire({
                    icon: "error",
                    title: "파일 조회 실패",
                    text: "선택한 버전의 변경 파일을 읽지 못했습니다.",
                }).then();
            })
            .finally(() => {
                if (!cancelled) setIsLoadingFiles(false);
            });

        return () => {
            cancelled = true;
        };
    }, [site.localPath, selectedVersions]);

    const resolveTargetOs = (): "WINDOWS" | "MAC" | "LINUX" | null => {
        const ua = navigator.userAgent.toLowerCase();
        if (ua.includes("windows")) return "WINDOWS";
        if (ua.includes("mac")) return "MAC";
        if (ua.includes("linux")) return "LINUX";
        return null;
    };

    const validateInputs = async (): Promise<boolean> => {
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

        if (versionOptions.length === 0) {
            await Swal.fire({
                icon: "warning",
                title: "버전 없음",
                text: "선택한 날짜 범위에 버전이 없습니다.",
            });
            return false;
        }

        if (selectedVersions.length === 0) {
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

        const selectedDuplicateFiles = duplicateFiles.filter((item) => selectedFiles.includes(item.path));
        const missingDuplicateVersion = selectedDuplicateFiles.find((item) => !duplicateFileVersionMap[item.path]);
        if (missingDuplicateVersion) {
            await Swal.fire({
                icon: "warning",
                title: "중복 파일 버전 선택 필요",
                text: `중복 파일의 버전을 선택해 주세요: ${missingDuplicateVersion.path}`,
            });
            return false;
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

    const extraction = async () => {
        if (!(await validateInputs())) return;

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
            text: "선택한 버전/파일 기준으로 패키지를 생성하고 있습니다.",
            allowOutsideClick: false,
            didOpen: () => Swal.showLoading(),
        });

        try {
            const selectedDuplicateVersionMap = Object.fromEntries(
                duplicateFiles
                    .filter((item) => selectedFiles.includes(item.path))
                    .map((item) => [item.path, duplicateFileVersionMap[item.path]])
                    .filter((entry): entry is [string, string] => Boolean(entry[1]))
            );

            const response = await axios.post(
                "/api/git/extraction",
                {
                    siteId: site.id,
                    since: startDate ? toLocalDateText(startDate) : null,
                    until: endDate ? toLocalDateText(endDate) : null,
                    localPath: site.localPath,
                    homePath: site.homePath,
                    fileStatusType: "DIFF",
                    targetOs,
                    selectedVersions,
                    selectedFiles: selectedFiles.map(normalizePath),
                    duplicateFileVersionMap: selectedDuplicateVersionMap,
                },
                { responseType: "blob", timeout: 600000 }
            );

            const now = new Date().toISOString().replace(/[:.]/g, "-");
            const disposition = response.headers["content-disposition"];
            let filename = `deploy-bundle-${now}.zip`;
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

    return (
        <div style={styles.customCard}>
            <div style={styles.controlsRow}>
                <DatePicker
                    locale={ko}
                    selected={startDate}
                    onChange={(date) => setStartDate(date)}
                    dateFormat="yyyy-MM-dd"
                    customInput={<CustomInput />}
                />
                <span style={styles.separator}>~</span>
                <DatePicker
                    locale={ko}
                    selected={endDate}
                    onChange={(date) => setEndDate(date)}
                    dateFormat="yyyy-MM-dd"
                    customInput={<CustomInput />}
                />
            </div>

            <div style={styles.contentLayout}>
                <div style={styles.leftLayout}>
                    <div style={styles.selectionGrid}>
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

                            <div style={styles.checkListBox}>
                                {isLoadingVersions && <div style={styles.emptyText}>버전 목록을 불러오는 중입니다.</div>}
                                {!isLoadingVersions && versionOptions.length === 0 && (
                                    <div style={styles.emptyText}>선택한 날짜 범위에 버전이 없습니다.</div>
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

                            <div style={styles.checkListBox}>
                                {selectedVersions.length === 0 && (
                                    <div style={styles.emptyText}>버전을 선택하면 변경 파일이 표시됩니다.</div>
                                )}
                                {selectedVersions.length > 0 && isLoadingFiles && (
                                    <div style={styles.emptyText}>변경 파일 목록을 불러오는 중입니다.</div>
                                )}
                                {selectedVersions.length > 0 && !isLoadingFiles && versionFiles.length === 0 && (
                                    <div style={styles.emptyText}>선택한 버전에 변경 파일이 없습니다.</div>
                                )}
                                {selectedVersions.length > 0 &&
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

                {showDuplicatePanel && (
                    <div style={styles.rightLayout}>
                        <div style={styles.duplicatePanel}>
                            <div style={styles.panelHeader}>
                                <div>
                                    <div style={styles.panelTitle}>중복 파일 버전 선택</div>
                                    <div style={styles.panelSubTitle}>
                                        동일 파일이 여러 버전에 있을 때 기준 버전을 정합니다.
                                    </div>
                                </div>
                                <span style={styles.summaryChip}>총 {duplicateFiles.length}건</span>
                            </div>

                            {duplicateFiles.length === 0 && (
                                <div style={styles.emptyText}>선택한 버전에 중복 파일이 없습니다.</div>
                            )}

                            {duplicateFiles.length > 0 && (
                                <>
                                    <div style={styles.duplicateToolbar}>
                                        <label style={styles.duplicateFilterCheck}>
                                            <input
                                                type="checkbox"
                                                checked={duplicateOnlySelected}
                                                onChange={(event) => setDuplicateOnlySelected(event.target.checked)}
                                            />
                                            <span>선택 파일만 표시</span>
                                        </label>
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
                                        >
                                            {duplicateBulkVersionOptions.map((version) => (
                                                <option key={`bulk-${version.value}`} value={version.value}>
                                                    {version.label}
                                                </option>
                                            ))}
                                        </select>
                                        <button
                                            type="button"
                                            style={styles.toolBtn}
                                            onClick={applyBulkDuplicateVersion}
                                            disabled={duplicateFilesByFilter.length === 0 || !bulkDuplicateVersion}
                                        >
                                            필터 대상 적용
                                        </button>
                                        <button
                                            type="button"
                                            style={styles.toolBtn}
                                            onClick={applyRecommendedDuplicateVersion}
                                            disabled={duplicateFilesByFilter.length === 0}
                                        >
                                            추천값 적용
                                        </button>
                                    </div>

                                    <div style={styles.duplicateMetaText}>
                                        표시 {duplicateFilesByFilter.length}건
                                        {unresolvedDuplicateCount > 0 && (
                                            <span style={styles.duplicateUnresolved}> / 미선택 {unresolvedDuplicateCount}건</span>
                                        )}
                                    </div>

                                    {duplicateFilesByFilter.length === 0 && (
                                        <div style={styles.emptyText}>현재 필터에 맞는 중복 파일이 없습니다.</div>
                                    )}

                                    {duplicateFilesByFilter.length > 0 && (
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
                                </>
                            )}
                        </div>
                    </div>
                )}
            </div>

            {vcsType && (
                <div style={styles.versionTypeText}>
                    감지된 저장소 유형: <strong>{vcsType}</strong>
                </div>
            )}

            <button
                onClick={extraction}
                style={styles.extractBtn}
                disabled={isExtracting || isLoadingVersions || isLoadingFiles}
            >
                {isExtracting ? "생성 중..." : "패키지 추출"}
            </button>
        </div>
    );
};

export default PathConverter;
